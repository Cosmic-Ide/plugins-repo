package org.cosmicide.plugins.elixir

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

class ElixirPlugin : CosmicPlugin {
    override val setupActions = listOf(
        PluginSetupAction(
            id = "org.cosmicide.plugins.elixir.installToolchain",
            label = "Install Elixir and Expert",
            command = ELIXIR_AND_EXPERT_INSTALL_COMMAND,
            description = "Install Elixir and the official Expert language server."
        )
    )

    override fun activate(context: PluginContext) {
        val commandService = context.services.require(IdeServices.COMMAND_EXECUTION)
        val processService = context.services.require(IdeServices.TOOL_PROCESS)
        val owner = context.descriptor.id

        listOf(
            context.extensions.register(
                point = EditorExtensionPoints.LSP_SERVER_PROVIDER,
                extension = ExpertLanguageServerProvider(processService, context.logger),
                ownerPluginId = owner,
                priority = 350
            ),
            context.extensions.register(
                point = ProjectExtensionPoints.TYPE_PROVIDER,
                extension = ElixirProjectTypeProvider,
                ownerPluginId = owner,
                priority = 350
            ),
            context.extensions.register(
                point = ProjectExtensionPoints.CREATION_PROVIDER,
                extension = ElixirProjectCreationProvider(commandService),
                ownerPluginId = owner,
                priority = 350
            ),
            context.extensions.register(
                point = ProjectExtensionPoints.COMMAND_PROVIDER,
                extension = ElixirProjectCommandProvider,
                ownerPluginId = owner,
                priority = 350
            )
        ).forEach(context::registerDisposable)

        context.logger.info("Elixir language, Mix project, and Expert support registered")
    }
}

private class ExpertLanguageServerProvider(
    private val processes: ToolProcessService,
    private val logger: PluginLogger
) : LspServerProvider {
    override val id = "org.cosmicide.plugins.elixir.expert"
    override val displayName = "Elixir language support"
    override val description = "Elixir editing powered by Expert"
    override val priority = 350

    override fun supports(request: LspServerRequest): Boolean {
        return request.extension.lowercase() in ELIXIR_FILE_EXTENSIONS
    }

    override fun createDefinition(request: LspServerRequest): LspServerDefinition {
        val extension = request.extension.lowercase()
        return LspServerDefinition(
            id = "$id.$extension",
            fileExtensions = ELIXIR_FILE_EXTENSIONS,
            displayName = "Expert",
            connectionFactory = {
                ExpertLanguageServerConnection(processes, it, logger)
            },
            textMateGrammarLink = when (extension) {
                "heex" -> HEEX_TEXTMATE_GRAMMAR
                "eex" -> EEX_TEXTMATE_GRAMMAR
                else -> ELIXIR_TEXTMATE_GRAMMAR
            },
            enableInlayHints = true,
            enableSignatureHelp = true,
            initializationTimeoutMillis = 120_000
        )
    }
}

private class ExpertLanguageServerConnection(
    private val processes: ToolProcessService,
    private val request: LspServerRequest,
    private val logger: PluginLogger
) : LspServerConnection {
    @Volatile
    private var process: Process? = null

    @Synchronized
    override fun start() {
        check(process == null) { "Expert connection has already started" }
        process = processes.start(
            CommandRequest(
                command = "expert",
                arguments = listOf("--stdio"),
                workingDirectory = request.project.root,
                environment = mapOf(
                    "COSMIC_PROJECT_ROOT" to request.project.root.absolutePath
                )
            ),
            redirectErrorStream = false
        ).also { started ->
            drainStderr(started.errorStream)
            logger.info("Expert started for ${request.project.name}")
        }
    }

    override val outputStream: OutputStream
        get() = checkNotNull(process) { "Expert has not started" }.outputStream

    override val inputStream: InputStream
        get() = checkNotNull(process) { "Expert has not started" }.inputStream

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
                logger.warn("Expert stderr logger stopped", it)
            }
        }.apply {
            name = "Expert-Language-Server-Stderr"
            isDaemon = true
            start()
        }
    }
}

private object ElixirProjectTypeProvider : ProjectTypeProvider {
    override val id = "org.cosmicide.plugins.elixir.projectType"
    override val displayName = "Elixir projects"
    override val description = "Recognizes Mix projects"
    override val languageName = "Elixir"
    override val fileExtension = "ex"

    override fun supports(projectRoot: File): Boolean {
        return projectRoot.resolve("mix.exs").isFile
    }
}

private class ElixirProjectCreationProvider(
    private val commands: CommandExecutionService
) : ProjectCreationProvider {
    override val id = "org.cosmicide.plugins.elixir.createProject"
    override val displayName = "New Elixir project"
    override val description = "Create a Mix project"
    override val actionLabel = "Create"

    override val fields = listOf(
        PluginFormField(
            id = "name",
            label = "Project name",
            placeholder = "hello_elixir",
            required = true
        ),
        PluginFormField(
            id = "kind",
            label = "Project kind",
            type = PluginFormFieldType.CHOICE,
            defaultValue = "standard",
            options = listOf(
                PluginFormOption("standard", "Standard"),
                PluginFormOption("supervision", "Supervision tree"),
                PluginFormOption("umbrella", "Umbrella")
            )
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

        val kind = request.values["kind"].orEmpty().ifBlank { "standard" }
        require(kind in setOf("standard", "supervision", "umbrella")) {
            "Unsupported Elixir project kind"
        }

        val projectsRoot = request.projectsDirectory.canonicalFile
        val root = projectsRoot.resolve(name).canonicalFile
        require(root.parentFile == projectsRoot) { "Invalid project name" }
        require(!root.exists()) { "A project with this name already exists" }

        reporter.report(OperationUpdate("Creating Mix project…\n"))
        val result = commands.execute(
            CommandRequest(
                command = "mix",
                arguments = buildList {
                    add("new")
                    add(root.absolutePath)
                    when (kind) {
                        "supervision" -> add("--sup")
                        "umbrella" -> add("--umbrella")
                    }
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
            error(
                result.output.lineSequence().lastOrNull { it.isNotBlank() }
                    ?: "mix new failed with exit code ${result.exitCode}"
            )
        }

        return ProjectCreationResult(
            project = ElixirProjectTypeProvider.project(root),
            message = "Elixir project created successfully"
        )
    }
}

private object ElixirProjectCommandProvider : ProjectCommandProvider {
    override val id = "org.cosmicide.plugins.elixir.commands"
    override val displayName = "Mix commands"
    override val description = "Compile, run, test, format, and maintain Mix projects"

    override fun commands(project: Project): List<ProjectCommand> {
        if (!ElixirProjectTypeProvider.supports(project.root)) return emptyList()
        val mixFile = project.root.resolve("mix.exs")
        val isPhoenix = runCatching { ":phoenix" in mixFile.readText() }.getOrDefault(false)

        return listOf(
            command(
                name = "compile",
                description = "Compile the Mix project",
                kind = ProjectCommandKind.BUILD
            ),
            command(
                name = if (isPhoenix) "phx.server" else "run",
                command = if (isPhoenix) "mix phx.server" else "iex -S mix",
                label = if (isPhoenix) "Run Phoenix server" else "Open IEx with project",
                description = if (isPhoenix) {
                    "Start the Phoenix development server"
                } else {
                    "Start an interactive Elixir shell with the project loaded"
                },
                kind = ProjectCommandKind.RUN
            ),
            command(
                name = "test",
                description = "Run the project tests"
            ),
            command(
                name = "format",
                description = "Format the project source"
            ),
            ProjectCommand(
                id = "$id.dependencies",
                label = "Dependencies",
                children = listOf(
                    command(
                        name = "deps.get",
                        label = "Get",
                        description = "Fetch project dependencies",
                        kind = ProjectCommandKind.SYNC
                    ),
                    command(
                        name = "deps.update",
                        command = "mix deps.update --all",
                        label = "Update all",
                        description = "Update all dependencies"
                    ),
                    command(
                        name = "deps.tree",
                        label = "Show tree",
                        description = "Print the dependency tree"
                    ),
                    command(
                        name = "deps.clean",
                        command = "mix deps.clean --all",
                        label = "Clean all",
                        description = "Remove all dependency build artifacts"
                    ),
                    command(
                        name = "deps.unlock",
                        command = "mix deps.unlock --all",
                        label = "Unlock all",
                        description = "Remove all dependency locks"
                    )
                )
            ),
            ProjectCommand(
                id = "$id.quality",
                label = "Quality",
                children = listOf(
                    command(
                        name = "format.check",
                        command = "mix format --check-formatted",
                        label = "Check formatting",
                        description = "Fail if source files are not formatted"
                    ),
                    command(
                        name = "compile.warnings",
                        command = "mix compile --warnings-as-errors",
                        label = "Compile with warnings as errors",
                        description = "Compile and fail on warnings",
                        kind = ProjectCommandKind.BUILD
                    ),
                    command(
                        name = "test.cover",
                        command = "mix test --cover",
                        label = "Test with coverage",
                        description = "Run tests and report coverage"
                    )
                )
            ),
            ProjectCommand(
                id = "$id.maintenance",
                label = "Maintenance",
                children = listOf(
                    command(
                        name = "clean",
                        description = "Delete generated project files"
                    ),
                    command(
                        name = "do.clean",
                        command = "mix do clean, deps.clean --all",
                        label = "Clean project and dependencies",
                        description = "Clean project and dependency build artifacts"
                    ),
                    command(
                        name = "hex.info",
                        label = "Hex information",
                        description = "Show Hex and environment information"
                    )
                )
            )
        )
    }

    private fun command(
        name: String,
        command: String = "mix $name",
        label: String = "Mix $name",
        description: String,
        kind: ProjectCommandKind = ProjectCommandKind.OTHER
    ) = ProjectCommand(
        id = "$id.$name",
        label = label,
        command = command,
        description = description,
        kind = kind
    )
}

private val ELIXIR_FILE_EXTENSIONS = setOf("ex", "exs", "eex", "heex")
private val ELIXIR_AND_EXPERT_INSTALL_COMMAND = """
    set -e
    pacman -S --needed elixir curl
    mix local.hex --force
    mix local.rebar --force
    case "${'$'}(uname -m)" in
      aarch64|arm64) expert_arch=arm64 ;;
      x86_64|amd64) expert_arch=amd64 ;;
      *) echo "Unsupported Expert architecture: ${'$'}(uname -m)" >&2; exit 1 ;;
    esac
    expert_asset="expert_linux_${'$'}expert_arch"
    expert_tmp="${'$'}(mktemp -d)"
    trap 'rm -rf "${'$'}expert_tmp"' EXIT
    curl -fsSL "https://github.com/expert-lsp/expert/releases/latest/download/${'$'}expert_asset" -o "${'$'}expert_tmp/${'$'}expert_asset"
    curl -fsSL "https://github.com/expert-lsp/expert/releases/latest/download/expert_checksums.txt" -o "${'$'}expert_tmp/expert_checksums.txt"
    (cd "${'$'}expert_tmp" && sha256sum --check --ignore-missing expert_checksums.txt)
    install -m 755 "${'$'}expert_tmp/${'$'}expert_asset" /usr/local/bin/expert
""".trimIndent()
private const val ELIXIR_TEXTMATE_GRAMMAR =
    "https://raw.githubusercontent.com/elixir-lsp/vscode-elixir-ls/master/syntaxes/elixir.json"
private const val EEX_TEXTMATE_GRAMMAR =
    "https://raw.githubusercontent.com/elixir-lsp/vscode-elixir-ls/master/syntaxes/eex.json"
private const val HEEX_TEXTMATE_GRAMMAR =
    "https://raw.githubusercontent.com/elixir-lsp/vscode-elixir-ls/master/syntaxes/html-eex.json"
