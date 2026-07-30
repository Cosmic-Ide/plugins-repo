package org.cosmicide.plugins.scala

import org.cosmicide.editor.EditorExtensionPoints
import org.cosmicide.editor.LspServerConnection
import org.cosmicide.editor.LspServerDefinition
import org.cosmicide.editor.LspServerProvider
import org.cosmicide.editor.LspServerRequest
import org.cosmicide.plugin.api.CosmicPlugin
import org.cosmicide.plugin.api.PluginContext
import org.cosmicide.plugin.api.PluginLogger
import org.cosmicide.plugin.api.PluginSetupAction
import org.cosmicide.project.CommandRequest
import org.cosmicide.project.IdeServices
import org.cosmicide.project.OperationReporter
import org.cosmicide.project.OperationUpdate
import org.cosmicide.project.PluginFormField
import org.cosmicide.project.PluginFormFieldType
import org.cosmicide.project.PluginFormOption
import org.cosmicide.project.Project
import org.cosmicide.project.ProjectCommand
import org.cosmicide.project.ProjectCommandKind
import org.cosmicide.project.ProjectCommandProvider
import org.cosmicide.project.ProjectCreationProvider
import org.cosmicide.project.ProjectCreationRequest
import org.cosmicide.project.ProjectCreationResult
import org.cosmicide.project.ProjectExtensionPoints
import org.cosmicide.project.ProjectTask
import org.cosmicide.project.ProjectTaskProvider
import org.cosmicide.project.ProjectTypeProvider
import org.cosmicide.project.ToolProcessService
import java.io.File
import java.io.InputStream
import java.io.OutputStream

class ScalaPlugin : CosmicPlugin {
    override val setupActions = listOf(
        PluginSetupAction(
            id = "org.cosmicide.plugins.scala.installTools",
            label = "Install Scala tools",
            command = SCALA_INSTALL_COMMAND,
            description = "Install Metals, sbt, and Scala CLI with Coursier."
        )
    )

    override fun activate(context: PluginContext) {
        val processes = context.services.require(IdeServices.TOOL_PROCESS)
        val owner = context.descriptor.id

        listOf(
            context.extensions.register(
                point = EditorExtensionPoints.LSP_SERVER_PROVIDER,
                extension = MetalsServerProvider(processes, context.logger),
                ownerPluginId = owner,
                priority = 350
            ),
            context.extensions.register(
                point = ProjectExtensionPoints.TYPE_PROVIDER,
                extension = ScalaProjectTypeProvider,
                ownerPluginId = owner,
                priority = 350
            ),
            context.extensions.register(
                point = ProjectExtensionPoints.CREATION_PROVIDER,
                extension = ScalaProjectCreationProvider,
                ownerPluginId = owner,
                priority = 350
            ),
            context.extensions.register(
                point = ProjectExtensionPoints.COMMAND_PROVIDER,
                extension = ScalaProjectCommandProvider,
                ownerPluginId = owner,
                priority = 350
            ),
            context.extensions.register(
                point = ProjectExtensionPoints.TASK_PROVIDER,
                extension = ScalaProjectTaskProvider,
                ownerPluginId = owner,
                priority = 350
            )
        ).forEach(context::registerDisposable)

        context.logger.info("Scala language, project, and Metals support registered")
    }
}

private class MetalsServerProvider(
    private val processes: ToolProcessService,
    private val logger: PluginLogger
) : LspServerProvider {
    override val id = "org.cosmicide.plugins.scala.metals"
    override val displayName = "Scala language support"
    override val description = "Scala editing powered by Metals"
    override val priority = 350

    override fun supports(request: LspServerRequest): Boolean =
        request.extension.lowercase() in SCALA_FILE_EXTENSIONS

    override fun createDefinition(request: LspServerRequest) = LspServerDefinition(
        id = id,
        fileExtensions = SCALA_FILE_EXTENSIONS,
        displayName = "Metals",
        connectionFactory = { MetalsServerConnection(processes, it, logger) },
        textMateGrammarLink = SCALA_TEXTMATE_GRAMMAR,
        enableInlayHints = true,
        enableSignatureHelp = true,
        initializationTimeoutMillis = 120_000
    )
}

private class MetalsServerConnection(
    private val processes: ToolProcessService,
    private val request: LspServerRequest,
    private val logger: PluginLogger
) : LspServerConnection {
    @Volatile
    private var process: Process? = null

    @Synchronized
    override fun start() {
        check(process == null) { "Metals connection has already started" }
        process = processes.start(
            CommandRequest(
                command = "metals",
                workingDirectory = request.project.root,
                environment = mapOf("COSMIC_PROJECT_ROOT" to request.project.root.absolutePath)
            ),
            redirectErrorStream = false
        ).also { started ->
            drainStderr(started.errorStream)
            logger.info("Metals started for ${request.project.name}")
        }
    }

    override val outputStream: OutputStream
        get() = checkNotNull(process) { "Metals has not started" }.outputStream

    override val inputStream: InputStream
        get() = checkNotNull(process) { "Metals has not started" }.inputStream

    override val isClosed: Boolean
        get() = process?.isAlive != true

    @Synchronized
    override fun close() {
        val running = process ?: return
        process = null
        runCatching { running.outputStream.close() }
        runCatching { running.inputStream.close() }
        runCatching { running.errorStream.close() }
        if (running.isAlive) running.destroy()
    }

    private fun drainStderr(stderr: InputStream) {
        Thread {
            runCatching {
                stderr.bufferedReader().useLines { lines ->
                    lines.forEach(logger::debug)
                }
            }.onFailure {
                logger.warn("Metals stderr logger stopped", it)
            }
        }.apply {
            name = "Metals-Stderr"
            isDaemon = true
            start()
        }
    }
}

private object ScalaProjectTypeProvider : ProjectTypeProvider {
    override val id = "org.cosmicide.plugins.scala.projectType"
    override val displayName = "Scala projects"
    override val description = "Recognizes Scala build files and source layouts"
    override val languageName = "Scala"
    override val fileExtension = "scala"

    override fun supports(projectRoot: File): Boolean = scalaProjectMarkers(projectRoot).any()
}

private object ScalaProjectCreationProvider : ProjectCreationProvider {
    override val id = "org.cosmicide.plugins.scala.createProject"
    override val displayName = "New Scala project"
    override val description = "Create an sbt-based Scala 3 application or library"
    override val actionLabel = "Create"

    override val fields = listOf(
        PluginFormField(
            id = "name",
            label = "Project name",
            placeholder = "hello-scala",
            required = true
        ),
        PluginFormField(
            id = "package_name",
            label = "Package",
            defaultValue = "com.example",
            placeholder = "com.example",
            required = true
        ),
        PluginFormField(
            id = "kind",
            label = "Project type",
            type = PluginFormFieldType.CHOICE,
            defaultValue = "application",
            options = listOf(
                PluginFormOption("application", "Application"),
                PluginFormOption("library", "Library")
            )
        )
    )

    override suspend fun create(
        request: ProjectCreationRequest,
        reporter: OperationReporter
    ): ProjectCreationResult {
        val name = request.values["name"].orEmpty().trim()
        require(name.matches(PROJECT_NAME)) {
            "Project name may contain letters, numbers, dots, underscores, and hyphens"
        }
        val packageName = request.values["package_name"].orEmpty().trim()
        require(packageName.matches(SCALA_PACKAGE)) {
            "Package must be a dot-separated Scala identifier"
        }
        val kind = request.values["kind"].orEmpty().ifBlank { "application" }
        require(kind == "application" || kind == "library") {
            "Unsupported Scala project type"
        }

        val projectsRoot = request.projectsDirectory.canonicalFile
        val root = projectsRoot.resolve(name).canonicalFile
        require(root.parentFile == projectsRoot) { "Invalid project name" }
        require(!root.exists()) { "A project with this name already exists" }

        reporter.report(OperationUpdate("Creating Scala sbt project…\n"))
        try {
            check(root.mkdirs()) { "Could not create the project directory" }
            root.resolve("project").mkdirs()
            root.resolve("project/build.properties").writeText("sbt.version=$SBT_VERSION\n")
            root.resolve("build.sbt").writeText(scalaBuildSbt(name, packageName))
            root.resolve(".gitignore").writeText(
                ".bloop/\n.bsp/\n.metals/\n.scala-build/\ntarget/\nproject/target/\n"
            )
            root.writeScalaSource(packageName, kind)
        } catch (failure: Throwable) {
            if (root.exists()) root.deleteRecursively()
            throw failure
        }

        return ProjectCreationResult(
            project = ScalaProjectTypeProvider.project(root),
            message = "Scala project created successfully"
        )
    }
}

private object ScalaProjectCommandProvider : ProjectCommandProvider {
    override val id = "org.cosmicide.plugins.scala.commands"
    override val displayName = "Scala commands"
    override val description = "Build, run, test, and maintain Scala projects"

    override fun commands(project: Project): List<ProjectCommand> {
        if (!ScalaProjectTypeProvider.supports(project.root)) return emptyList()
        val tool = scalaBuildTool(project.root)

        return buildList {
            add(tool.command("resolve", "Resolve dependencies", ProjectCommandKind.SYNC))
            add(tool.command("build", "Build", ProjectCommandKind.BUILD))
            if (hasRunnableScalaSource(project.root)) {
                add(tool.command("run", "Run", ProjectCommandKind.RUN))
            }
            add(tool.command("test", "Test"))
            add(tool.command("clean", "Clean"))
        }
    }

    private fun ScalaBuildTool.command(
        operation: String,
        label: String,
        kind: ProjectCommandKind = ProjectCommandKind.OTHER
    ) = ProjectCommand(
        id = "${this@ScalaProjectCommandProvider.id}.${name.lowercase()}.$operation",
        label = label,
        command = command(operation),
        description = "$label with $displayName",
        kind = kind
    )
}

private object ScalaProjectTaskProvider : ProjectTaskProvider {
    override val id = "org.cosmicide.plugins.scala.tasks"
    override val displayName = "Scala tasks"
    override val description = "Tasks for the detected Scala build tool"

    override fun supports(project: Project): Boolean =
        ScalaProjectTypeProvider.supports(project.root)

    override suspend fun tasks(project: Project): List<ProjectTask> {
        val tool = scalaBuildTool(project.root)
        return tool.operations
            .filter { (operation, _) ->
                operation != "run" || hasRunnableScalaSource(project.root)
            }
            .map { (operation, label) ->
                ProjectTask(
                    id = "$id.${tool.name.lowercase()}.$operation",
                    label = label,
                    command = tool.command(operation),
                    description = "$label with ${tool.displayName}",
                    group = tool.displayName
                )
            }
    }
}

internal enum class ScalaBuildTool(
    val displayName: String,
    val operations: List<Pair<String, String>>
) {
    SBT(
        "sbt",
        listOf(
            "resolve" to "Update dependencies",
            "build" to "Compile",
            "run" to "Run",
            "test" to "Test",
            "clean" to "Clean",
            "console" to "Scala console",
            "dependencyTree" to "Dependency tree",
            "reload" to "Reload build"
        )
    ),
    GRADLE(
        "Gradle",
        listOf(
            "resolve" to "Resolve dependencies",
            "build" to "Build",
            "run" to "Run",
            "test" to "Test",
            "clean" to "Clean"
        )
    ),
    MAVEN(
        "Maven",
        listOf(
            "resolve" to "Resolve dependencies",
            "build" to "Package",
            "run" to "Run",
            "test" to "Test",
            "clean" to "Clean"
        )
    ),
    MILL(
        "Mill",
        listOf(
            "resolve" to "Resolve dependencies",
            "build" to "Compile",
            "run" to "Run",
            "test" to "Test",
            "clean" to "Clean"
        )
    ),
    SCALA_CLI(
        "Scala CLI",
        listOf(
            "resolve" to "Resolve dependencies",
            "build" to "Compile",
            "run" to "Run",
            "test" to "Test",
            "clean" to "Clean",
            "console" to "Scala console",
            "setupIde" to "Set up IDE"
        )
    );

    fun command(operation: String): String = when (this) {
        SBT -> when (operation) {
            "resolve" -> "sbt update"
            "build" -> "sbt compile"
            "run" -> "sbt run"
            "test" -> "sbt test"
            "clean" -> "sbt clean"
            "console" -> "sbt console"
            "dependencyTree" -> "sbt dependencyTree"
            "reload" -> "sbt reload"
            else -> error("Unsupported sbt operation: $operation")
        }
        GRADLE -> "./gradlew ${if (operation == "resolve") "dependencies" else operation}"
        MAVEN -> when (operation) {
            "resolve" -> "mvn dependency:go-offline"
            "build" -> "mvn package"
            "run" -> "mvn scala:run"
            else -> "mvn $operation"
        }
        MILL -> when (operation) {
            "resolve" -> "mill __.ivyDepsTree"
            "build" -> "mill __.compile"
            "run" -> "mill __.run"
            "test" -> "mill __.test"
            "clean" -> "mill clean"
            else -> error("Unsupported Mill operation: $operation")
        }
        SCALA_CLI -> when (operation) {
            "resolve" -> "scala-cli compile ."
            "build" -> "scala-cli compile ."
            "run" -> "scala-cli run ."
            "test" -> "scala-cli test ."
            "clean" -> "scala-cli clean ."
            "console" -> "scala-cli repl ."
            "setupIde" -> "scala-cli setup-ide ."
            else -> error("Unsupported Scala CLI operation: $operation")
        }
    }
}

internal fun scalaBuildTool(root: File): ScalaBuildTool = when {
    root.resolve("build.sbt").isFile -> ScalaBuildTool.SBT
    root.resolve("gradlew").isFile -> ScalaBuildTool.GRADLE
    root.resolve("pom.xml").isFile -> ScalaBuildTool.MAVEN
    root.resolve("mill").isFile ||
        root.resolve("build.sc").isFile ||
        root.resolve("build.mill").isFile -> ScalaBuildTool.MILL
    else -> ScalaBuildTool.SCALA_CLI
}

internal fun scalaBuildSbt(name: String, organization: String): String = """
    ThisBuild / organization := "$organization"
    ThisBuild / scalaVersion := "$SCALA_VERSION"

    lazy val root = project
      .in(file("."))
      .settings(
        name := "$name"
      )
""".trimIndent() + "\n"

private fun scalaProjectMarkers(root: File): Sequence<File> = sequence {
    SCALA_BUILD_MARKERS.map(root::resolve).filter(File::isFile).forEach { yield(it) }
    listOf("src/main/scala", "app/src/main/scala")
        .map(root::resolve)
        .filter(File::isDirectory)
        .forEach { yield(it) }
    root.listFiles().orEmpty()
        .filter { it.isFile && it.extension.lowercase() in setOf("scala", "sc") }
        .forEach { yield(it) }
}

private fun hasRunnableScalaSource(root: File): Boolean =
    root.walkTopDown()
        .onEnter { it == root || it.name !in SCAN_EXCLUDED_DIRECTORIES }
        .filter { it.isFile && it.extension.lowercase() in setOf("scala", "sc") }
        .any { source ->
            val text = runCatching(source::readText).getOrDefault("")
            SCALA_MAIN_REGEX.containsMatchIn(text)
        }

private fun File.writeScalaSource(packageName: String, kind: String) {
    val sourceDirectory = resolve("src/main/scala/${packageName.replace('.', '/')}")
    check(sourceDirectory.mkdirs()) { "Could not create Scala source directory" }
    if (kind == "application") {
        sourceDirectory.resolve("Main.scala").writeText(
            """
                package $packageName

                @main def main(): Unit =
                  println("Hello from Scala!")
            """.trimIndent() + "\n"
        )
    } else {
        sourceDirectory.resolve("Library.scala").writeText(
            """
                package $packageName

                object Library:
                  def greeting: String = "Hello from Scala!"
            """.trimIndent() + "\n"
        )
    }
}

private const val SCALA_VERSION = "3.3.8"
private const val SBT_VERSION = "1.12.11"
private const val COURSIER_URL =
    "https://github.com/coursier/coursier/releases/download/v2.1.25-M26/cs-aarch64-pc-linux.gz"
private const val SCALA_TEXTMATE_GRAMMAR =
    "https://raw.githubusercontent.com/scala/vscode-scala-syntax/main/syntaxes/Scala.tmLanguage.json"
private val SCALA_FILE_EXTENSIONS = setOf("scala", "sc", "sbt", "mill")
private val SCALA_BUILD_MARKERS = setOf(
    "build.sbt",
    "build.sc",
    "build.mill",
    "mill",
    "project.scala",
    "scala-cli.yaml"
)
private val SCAN_EXCLUDED_DIRECTORIES = setOf(
    ".bloop",
    ".git",
    ".gradle",
    ".metals",
    ".scala-build",
    "build",
    "target"
)
private val PROJECT_NAME = Regex("[A-Za-z0-9][A-Za-z0-9._-]*")
private val SCALA_PACKAGE =
    Regex("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)*")
private val SCALA_MAIN_REGEX = Regex(
    """(?m)(?:@\s*main\b|def\s+main\s*\(|extends\s+App\b)"""
)
private val SCALA_INSTALL_COMMAND = """
    set -euo pipefail
    install_dir="${'$'}HOME/.scala/bin"
    cs="${'$'}APP_FILES_DIR/usr/bin/cs"
    legacy_cs="${'$'}APP_FILES_DIR/coursier/cs"
    mkdir -p "${'$'}install_dir" "${'$'}APP_FILES_DIR/usr/bin"
    if [ ! -x "${'$'}cs" ] && [ -x "${'$'}legacy_cs" ]; then
      echo "Moving Coursier to /usr/bin..."
      mv "${'$'}legacy_cs" "${'$'}cs"
    fi
    if [ ! -x "${'$'}cs" ]; then
      echo "Downloading Coursier..."
      curl -fL "$COURSIER_URL" | unpigz -c > "${'$'}cs.tmp"
      chmod +x "${'$'}cs.tmp"
      mv "${'$'}cs.tmp" "${'$'}cs"
    fi
    echo "Installing Metals, sbt, and Scala CLI..."
    "${'$'}cs" install --install-dir "${'$'}install_dir" metals sbt scala-cli
    test -x "${'$'}install_dir/metals"
    test -x "${'$'}install_dir/sbt"
    test -x "${'$'}install_dir/scala-cli"
    echo "Scala tools installed."
""".trimIndent()
