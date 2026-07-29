package org.cosmicide.plugins.gleam

import org.cosmicide.editor.EditorExtensionPoints
import org.cosmicide.editor.LspServerConnection
import org.cosmicide.editor.LspServerDefinition
import org.cosmicide.editor.LspServerProvider
import org.cosmicide.editor.LspServerRequest
import org.cosmicide.plugin.api.CosmicPlugin
import org.cosmicide.plugin.api.PluginContext
import org.cosmicide.plugin.api.PluginLogger
import org.cosmicide.plugin.api.PluginSetupAction
import org.cosmicide.project.CommandExecutionService
import org.cosmicide.project.CommandRequest
import org.cosmicide.project.IdeServices
import org.cosmicide.project.OperationMessageKind
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
import org.cosmicide.project.ProjectTypeProvider
import org.cosmicide.project.ToolProcessService
import java.io.File
import java.io.InputStream
import java.io.OutputStream

class GleamPlugin : CosmicPlugin {
    override val setupActions = listOf(
        PluginSetupAction(
            id = "org.cosmicide.plugins.gleam.installToolchain",
            label = "Install Gleam",
            command = GLEAM_INSTALL_COMMAND,
            description = "Install the Gleam compiler and language server."
        )
    )

    override fun activate(context: PluginContext) {
        val commandService = context.services.require(IdeServices.COMMAND_EXECUTION)
        val processService = context.services.require(IdeServices.TOOL_PROCESS)
        val owner = context.descriptor.id

        listOf(
            context.extensions.register(
                point = EditorExtensionPoints.LSP_SERVER_PROVIDER,
                extension = GleamAnalyzerProvider(processService, context.logger),
                ownerPluginId = owner,
                priority = 350
            ),
            context.extensions.register(
                point = ProjectExtensionPoints.TYPE_PROVIDER,
                extension = GleamProjectTypeProvider,
                ownerPluginId = owner,
                priority = 350
            ),
            context.extensions.register(
                point = ProjectExtensionPoints.CREATION_PROVIDER,
                extension = GleamProjectCreationProvider(commandService),
                ownerPluginId = owner,
                priority = 350
            ),
            context.extensions.register(
                point = ProjectExtensionPoints.COMMAND_PROVIDER,
                extension = GleamProjectCommandProvider,
                ownerPluginId = owner,
                priority = 350
            )
        ).forEach(context::registerDisposable)

        context.logger.info("Gleam language and gleam project support registered")
    }
}

private class GleamAnalyzerProvider(
    private val processes: ToolProcessService,
    private val logger: PluginLogger
) : LspServerProvider {
    override val id = "org.cosmicide.plugins.gleam.lsp"
    override val displayName = "Gleam language support"
    override val description = "Gleam editing powered by gleam lsp"
    override val priority = 350

    override fun supports(request: LspServerRequest): Boolean {
        return request.extension.equals("gleam", ignoreCase = true)
    }

    override fun createDefinition(request: LspServerRequest): LspServerDefinition {
        return LspServerDefinition(
            id = id,
            fileExtensions = setOf("gleam"),
            displayName = "gleam lsp",
            connectionFactory = {
                GleamAnalyzerConnection(processes, it, logger)
            },
            textMateGrammarLink = GLEAM_TEXTMATE_GRAMMAR,
            enableInlayHints = true,
            enableSignatureHelp = true,
            initializationTimeoutMillis = 120_000
        )
    }
}

private class GleamAnalyzerConnection(
    private val processes: ToolProcessService,
    private val request: LspServerRequest,
    private val logger: PluginLogger
) : LspServerConnection {
    @Volatile
    private var process: Process? = null

    @Synchronized
    override fun start() {
        check(process == null) { "gleam lsp connection has already started" }
        process = processes.start(
            CommandRequest(
                command = "gleam",
                arguments = listOf("lsp"),
                workingDirectory = request.project.root,
                environment = mapOf(
                    "COSMIC_PROJECT_ROOT" to request.project.root.absolutePath
                )
            ),
            redirectErrorStream = false
        ).also { started ->
            drainStderr(started.errorStream)
            logger.info("gleam lsp started for ${request.project.name}")
        }
    }

    override val outputStream: OutputStream
        get() = checkNotNull(process) { "gleam lsp has not started" }.outputStream

    override val inputStream: InputStream
        get() = checkNotNull(process) { "gleam lsp has not started" }.inputStream

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
                logger.warn("gleam lsp stderr logger stopped", it)
            }
        }.apply {
            name = "Gleam-Analyzer-Stderr"
            isDaemon = true
            start()
        }
    }
}

private object GleamProjectTypeProvider : ProjectTypeProvider {
    override val id = "org.cosmicide.plugins.gleam.projectType"
    override val displayName = "Gleam projects"
    override val description = "Recognizes Gleam projects"
    override val languageName = "Gleam"
    override val fileExtension = "gleam"

    override fun supports(projectRoot: File): Boolean {
        return projectRoot.resolve("gleam.toml").isFile
    }
}

private class GleamProjectCreationProvider(
    private val commands: CommandExecutionService
) : ProjectCreationProvider {
    override val id = "org.cosmicide.plugins.gleam.createProject"
    override val displayName = "New Gleam project"
    override val description = "Create a Gleam project"
    override val actionLabel = "Create"

    override val fields = listOf(
        PluginFormField(
            id = "name",
            label = "Project name",
            placeholder = "hello_gleam",
            required = true
        ),
        PluginFormField(
            id = "template",
            label = "The template to use",
            type = PluginFormFieldType.CHOICE,
            defaultValue = "erlang",
            options = listOf(
                PluginFormOption("erlang", "Erlang"),
                PluginFormOption("javascript", "JavaScript")
            )
        ),
        PluginFormField(
            id = "skip_git",
            label = "Skip git initialization",
            type = PluginFormFieldType.BOOLEAN,
            defaultValue = "false"
        )
    )

    override suspend fun create(
        request: ProjectCreationRequest,
        reporter: OperationReporter
    ): ProjectCreationResult {
        val name = request.values["name"].orEmpty().trim()
        require(name.matches(Regex("[a-z][a-z0-9_]*"))) {
            "Project name must start with a lowercase letter and contain only lowercase letters, numbers, and underscores"
        }

        val projectsRoot = request.projectsDirectory.canonicalFile
        val root = projectsRoot.resolve(name).canonicalFile
        require(root.parentFile == projectsRoot) { "Invalid project name" }
        require(!root.exists()) { "A project with this name already exists" }

        val template = request.values["template"].orEmpty().ifBlank { "erlang" }
        require(template == "erlang" || template == "javascript") {
            "Unsupported Gleam project template"
        }
        val skipGit = request.values["skip_git"].toBoolean()

        reporter.report(OperationUpdate("Creating Gleam project…\n"))
        val result = commands.execute(
            CommandRequest(
                command = "gleam",
                arguments = buildList {
                    add("new")
                    add("--name")
                    add(name)
                    add("--template")
                    add(template)
                    if (skipGit) add("--skip-git")
                    add(root.absolutePath)
                },
                workingDirectory = projectsRoot
            )
        ) { output ->
            reporter.report(
                OperationUpdate(
                    message = output,
                    kind = OperationMessageKind.OUTPUT
                )
            )
        }

        if (!result.successful) {
            if (root.exists()) root.deleteRecursively()
            error(result.output.lineSequence().lastOrNull { it.isNotBlank() }
                ?: "Gleam new failed with exit code ${result.exitCode}")
        }

        return ProjectCreationResult(
            project = GleamProjectTypeProvider.project(root),
            message = "Gleam project created successfully"
        )
    }
}

private object GleamProjectCommandProvider : ProjectCommandProvider {
    override val id = "org.cosmicide.plugins.gleam.commands"
    override val displayName = "Gleam commands"
    override val description = "Build, run, maintain, document, and publish Gleam projects"

    override fun commands(project: Project): List<ProjectCommand> {
        if (!GleamProjectTypeProvider.supports(project.root)) return emptyList()

        return listOf(
            command(
                name = "check",
                description = "Type-check the project",
                kind = ProjectCommandKind.BUILD
            ),
            command(
                name = "build",
                description = "Compile the project",
                kind = ProjectCommandKind.BUILD
            ),
            command(
                name = "run",
                description = "Compile and run the project",
                kind = ProjectCommandKind.RUN
            ),
            command(
                name = "dev",
                description = "Run the project's development module"
            ),
            command(
                name = "test",
                description = "Run the project tests"
            ),
            command(
                name = "format",
                description = "Format all Gleam source files"
            ),
            ProjectCommand(
                id = "$id.dependencies",
                label = "Dependencies",
                children = listOf(
                    command(
                        name = "deps.update",
                        command = "gleam update",
                        label = "Update",
                        description = "Update dependencies to compatible versions"
                    ),
                    command(
                        name = "deps.outdated",
                        command = "gleam deps outdated",
                        label = "Show outdated",
                        description = "List dependencies with newer versions"
                    ),
                    command(
                        name = "deps.list",
                        command = "gleam deps list",
                        label = "List",
                        description = "List dependency names and versions"
                    ),
                    command(
                        name = "deps.tree",
                        command = "gleam deps tree",
                        label = "Show tree",
                        description = "Display the dependency tree"
                    )
                )
            ),
            ProjectCommand(
                id = "$id.docs",
                label = "Documentation",
                children = listOf(
                    command(
                        name = "docs.build",
                        command = "gleam docs build",
                        label = "Build",
                        description = "Build the package documentation"
                    ),
                    command(
                        name = "docs.publish",
                        command = "gleam docs publish",
                        label = "Publish",
                        description = "Publish documentation to HexDocs"
                    )
                )
            ),
            ProjectCommand(
                id = "$id.export",
                label = "Export",
                children = listOf(
                    exportCommand(
                        name = "escript",
                        label = "Escript",
                        description = "Build a single-file Erlang executable"
                    ),
                    exportCommand(
                        name = "erlang-shipment",
                        label = "Erlang shipment",
                        description = "Build a deployable Erlang shipment"
                    ),
                    exportCommand(
                        name = "hex-tarball",
                        label = "Hex tarball",
                        description = "Build the package's Hex tarball"
                    ),
                    exportCommand(
                        name = "javascript-prelude",
                        label = "JavaScript prelude",
                        description = "Print the JavaScript prelude module"
                    ),
                    exportCommand(
                        name = "typescript-prelude",
                        label = "TypeScript prelude",
                        description = "Print the TypeScript prelude module"
                    )
                )
            ),
            ProjectCommand(
                id = "$id.maintenance",
                label = "Maintenance",
                children = listOf(
                    command(
                        name = "clean",
                        description = "Delete the project's build artifacts"
                    ),
                    command(
                        name = "fix",
                        description = "Apply automatic source and configuration fixes"
                    )
                )
            ),
            ProjectCommand(
                id = "$id.package",
                label = "Package",
                children = listOf(
                    command(
                        name = "publish",
                        description = "Publish the package to Hex"
                    ),
                )
            )
        )
    }

    private fun command(
        name: String,
        command: String = "gleam $name",
        label: String = "Gleam $name",
        description: String,
        kind: ProjectCommandKind = ProjectCommandKind.OTHER
    ) = ProjectCommand(
        id = "$id.$name",
        label = label,
        command = command,
        description = description,
        kind = kind
    )

    private fun exportCommand(
        name: String,
        label: String,
        description: String
    ) = command(
        name = "export.$name",
        command = "gleam export $name",
        label = label,
        description = description
    )
}

private const val GLEAM_INSTALL_COMMAND = "pacman -S --needed gleam"
private const val GLEAM_TEXTMATE_GRAMMAR =
    "https://raw.githubusercontent.com/gleam-lang/vscode-gleam/refs/heads/main/syntaxes/gleam.tmLanguage.json"
