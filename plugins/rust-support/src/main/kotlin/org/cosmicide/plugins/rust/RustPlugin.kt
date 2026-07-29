package org.cosmicide.plugins.rust

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

class RustPlugin : CosmicPlugin {
    override val setupActions = listOf(
        PluginSetupAction(
            id = "org.cosmicide.plugins.rust.installToolchain",
            label = "Install Rust toolchain",
            command = RUST_INSTALL_COMMAND,
            description = "Install Rust, Cargo, GCC, and rust-analyzer."
        )
    )

    override fun activate(context: PluginContext) {
        val commandService = context.services.require(IdeServices.COMMAND_EXECUTION)
        val processService = context.services.require(IdeServices.TOOL_PROCESS)
        val owner = context.descriptor.id

        listOf(
            context.extensions.register(
                point = EditorExtensionPoints.LSP_SERVER_PROVIDER,
                extension = RustAnalyzerProvider(processService, context.logger),
                ownerPluginId = owner,
                priority = 350
            ),
            context.extensions.register(
                point = ProjectExtensionPoints.TYPE_PROVIDER,
                extension = RustProjectTypeProvider,
                ownerPluginId = owner,
                priority = 350
            ),
            context.extensions.register(
                point = ProjectExtensionPoints.CREATION_PROVIDER,
                extension = RustProjectCreationProvider(commandService),
                ownerPluginId = owner,
                priority = 350
            ),
            context.extensions.register(
                point = ProjectExtensionPoints.COMMAND_PROVIDER,
                extension = RustProjectCommandProvider,
                ownerPluginId = owner,
                priority = 350
            )
        ).forEach(context::registerDisposable)

        context.logger.info("Rust language and Cargo project support registered")
    }
}

private class RustAnalyzerProvider(
    private val processes: ToolProcessService,
    private val logger: PluginLogger
) : LspServerProvider {
    override val id = "org.cosmicide.plugins.rust.lsp"
    override val displayName = "Rust language support"
    override val description = "Rust editing powered by rust-analyzer"
    override val priority = 350

    override fun supports(request: LspServerRequest): Boolean {
        return request.extension.equals("rs", ignoreCase = true)
    }

    override fun createDefinition(request: LspServerRequest): LspServerDefinition {
        return LspServerDefinition(
            id = id,
            fileExtensions = setOf("rs"),
            displayName = "rust-analyzer",
            connectionFactory = {
                RustAnalyzerConnection(processes, it, logger)
            },
            textMateGrammarLink = RUST_TEXTMATE_GRAMMAR,
            initializationOptions = mapOf(
                "check" to mapOf("command" to "clippy")
            ),
            enableInlayHints = true,
            initializationTimeoutMillis = 120_000
        )
    }
}

private class RustAnalyzerConnection(
    private val processes: ToolProcessService,
    private val request: LspServerRequest,
    private val logger: PluginLogger
) : LspServerConnection {
    @Volatile
    private var process: Process? = null

    @Synchronized
    override fun start() {
        check(process == null) { "rust-analyzer connection has already started" }
        process = processes.start(
            CommandRequest(
                command = "rust-analyzer",
                workingDirectory = request.project.root,
                environment = mapOf(
                    "RUST_BACKTRACE" to "1",
                    "COSMIC_PROJECT_ROOT" to request.project.root.absolutePath
                )
            ),
            redirectErrorStream = false
        ).also { started ->
            drainStderr(started.errorStream)
            logger.info("rust-analyzer started for ${request.project.name}")
        }
    }

    override val outputStream: OutputStream
        get() = checkNotNull(process) { "rust-analyzer has not started" }.outputStream

    override val inputStream: InputStream
        get() = checkNotNull(process) { "rust-analyzer has not started" }.inputStream

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
                logger.warn("rust-analyzer stderr logger stopped", it)
            }
        }.apply {
            name = "Rust-Analyzer-Stderr"
            isDaemon = true
            start()
        }
    }
}

private object RustProjectTypeProvider : ProjectTypeProvider {
    override val id = "org.cosmicide.plugins.rust.projectType"
    override val displayName = "Rust projects"
    override val description = "Recognizes Cargo projects"
    override val languageName = "Rust"
    override val fileExtension = "rs"

    override fun supports(projectRoot: File): Boolean {
        return projectRoot.resolve("Cargo.toml").isFile
    }
}

private class RustProjectCreationProvider(
    private val commands: CommandExecutionService
) : ProjectCreationProvider {
    override val id = "org.cosmicide.plugins.rust.createProject"
    override val displayName = "New Rust project"
    override val description = "Create a Cargo binary or library project"
    override val actionLabel = "Create"

    override val fields = listOf(
        PluginFormField(
            id = "name",
            label = "Project name",
            placeholder = "hello-rust",
            required = true
        ),
        PluginFormField(
            id = "kind",
            label = "Project kind",
            type = PluginFormFieldType.CHOICE,
            defaultValue = "bin",
            options = listOf(
                PluginFormOption("bin", "Binary"),
                PluginFormOption("lib", "Library")
            )
        )
    )

    override suspend fun create(
        request: ProjectCreationRequest,
        reporter: OperationReporter
    ): ProjectCreationResult {
        val name = request.values["name"].orEmpty().trim()
        require(name.matches(Regex("[A-Za-z0-9][A-Za-z0-9_-]*"))) {
            "Project name may contain letters, numbers, underscores, and hyphens"
        }

        val projectsRoot = request.projectsDirectory.canonicalFile
        val root = projectsRoot.resolve(name).canonicalFile
        require(root.parentFile == projectsRoot) { "Invalid project name" }
        require(!root.exists()) { "A project with this name already exists" }

        val kind = request.values["kind"].orEmpty().ifBlank { "bin" }
        require(kind == "bin" || kind == "lib") { "Unsupported Cargo project kind" }

        reporter.report(OperationUpdate("Creating Cargo project…\n"))
        val result = commands.execute(
            CommandRequest(
                command = "cargo",
                arguments = listOf("new", "--name", name, "--$kind", root.absolutePath),
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
                ?: "cargo new failed with exit code ${result.exitCode}")
        }

        return ProjectCreationResult(
            project = RustProjectTypeProvider.project(root),
            message = "Rust project created successfully"
        )
    }
}

private object RustProjectCommandProvider : ProjectCommandProvider {
    override val id = "org.cosmicide.plugins.rust.commands"
    override val displayName = "Cargo commands"
    override val description = "Cargo fetch, check, build, run, and test commands"

    override fun commands(project: Project): List<ProjectCommand> {
        if (!RustProjectTypeProvider.supports(project.root)) return emptyList()

        return buildList {
            add(
                ProjectCommand(
                    id = "$id.fetch",
                    label = "Cargo fetch",
                    command = "cargo fetch",
                    description = "Download Cargo dependencies",
                    kind = ProjectCommandKind.SYNC
                )
            )
            add(
                ProjectCommand(
                    id = "$id.check",
                    label = "Cargo check",
                    command = "cargo check",
                    description = "Type-check the Rust project",
                    kind = ProjectCommandKind.BUILD
                )
            )
            add(
                ProjectCommand(
                    id = "$id.build",
                    label = "Cargo build",
                    command = "cargo build",
                    description = "Build the Rust project",
                    kind = ProjectCommandKind.BUILD
                )
            )
            if (project.root.resolve("src/main.rs").isFile) {
                add(
                    ProjectCommand(
                        id = "$id.run",
                        label = "Cargo run",
                        command = "cargo run",
                        description = "Build and run the Rust binary",
                        kind = ProjectCommandKind.RUN
                    )
                )
            }
            add(
                ProjectCommand(
                    id = "$id.test",
                    label = "Cargo test",
                    command = "cargo test",
                    description = "Run Rust tests",
                    kind = ProjectCommandKind.OTHER
                )
            )
        }
    }
}

private const val RUST_INSTALL_COMMAND = "pacman -S --needed rust rust-analyzer gcc"
private const val RUST_TEXTMATE_GRAMMAR =
    "https://raw.githubusercontent.com/microsoft/vscode/main/extensions/rust/syntaxes/rust.tmLanguage.json"
