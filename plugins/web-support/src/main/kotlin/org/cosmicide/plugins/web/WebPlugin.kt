package org.cosmicide.plugins.web

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
import org.cosmicide.project.CommandResult
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

class WebPlugin : CosmicPlugin {
    override val setupActions = listOf(
        PluginSetupAction(
            id = "org.cosmicide.plugins.web.installToolchain",
            label = "Install Web related packages",
            command = INSTALL_COMMAND,
            description = "Install NodeJS and the typescript language server."
        )
    )

    override fun activate(context: PluginContext) {
        val commandService = context.services.require(IdeServices.COMMAND_EXECUTION)
        val processService = context.services.require(IdeServices.TOOL_PROCESS)
        val owner = context.descriptor.id

        listOf(
            context.extensions.register(
                point = EditorExtensionPoints.LSP_SERVER_PROVIDER,
                extension = WebServerProvider(processService, context.logger),
                ownerPluginId = owner,
                priority = 350
            ),
            context.extensions.register(
                point = ProjectExtensionPoints.TYPE_PROVIDER,
                extension = WebProjectTypeProvider,
                ownerPluginId = owner,
                priority = 350
            ),
            context.extensions.register(
                point = ProjectExtensionPoints.CREATION_PROVIDER,
                extension = WebProjectCreationProvider(commandService),
                ownerPluginId = owner,
                priority = 350
            ),
            context.extensions.register(
                point = ProjectExtensionPoints.COMMAND_PROVIDER,
                extension = WebProjectCommandProvider,
                ownerPluginId = owner,
                priority = 350
            )
        ).forEach(context::registerDisposable)

        context.logger.info("Web language and project support registered")
    }
}

private object WebProjectTypeProvider : ProjectTypeProvider {
    override val id = "org.cosmicide.plugins.web.projectType"
    override val displayName = "Web projects"
    override val description = "Recognizes JavaScript/TypeScript npm projects"
    override val languageName = "JavaScript / TypeScript"
    override val fileExtension = "ts"

    override fun supports(projectRoot: File): Boolean {
        return projectRoot.resolve("package.json").isFile
    }
}

private class WebProjectCreationProvider(
    private val commands: CommandExecutionService
) : ProjectCreationProvider {
    override val id = "org.cosmicide.plugins.web.createProject"
    override val displayName = "New Web project"
    override val description =
        "Create an npm project or scaffold one using Vite, Next.js, React, Vue, Svelte, or a custom command."

    override val actionLabel = "Create"

    override val fields = listOf(
        PluginFormField(
            id = "name",
            label = "Project name",
            placeholder = "my-app",
            required = true
        ),
        PluginFormField(
            id = "template",
            label = "Project framework",
            type = PluginFormFieldType.CHOICE,
            defaultValue = "vite",
            options = listOf(
                PluginFormOption("vite", "Vite (Vanilla TS/JS)"),
                PluginFormOption("react", "React (Vite)"),
                PluginFormOption("next", "Next.js"),
                PluginFormOption("vue", "Vue (Vite)"),
                PluginFormOption("svelte", "Svelte (Vite)"),
                PluginFormOption("npm", "Empty npm project"),
                PluginFormOption("custom", "Custom command")
            )
        ),
        PluginFormField(
            id = "command",
            label = "Create command",
            placeholder = "npm create next-app@latest",
        )
    )

    override suspend fun create(
        request: ProjectCreationRequest,
        reporter: OperationReporter
    ): ProjectCreationResult {
        val name = request.values["name"].orEmpty().trim()
        require(name.matches(Regex("[A-Za-z0-9][A-Za-z0-9_.-]*"))) {
            "Project name may contain letters, numbers, underscores, periods, and hyphens"
        }

        val projectsRoot = request.projectsDirectory.canonicalFile
        val root = projectsRoot.resolve(name).canonicalFile
        require(root.parentFile == projectsRoot) { "Invalid project name" }
        require(!root.exists()) { "A project with this name already exists" }

        val template = request.values["template"].orEmpty().ifBlank { "vite" }

        reporter.report(OperationUpdate("Scaffolding Web project…\n"))

        val result = if (template == "npm") {
            createEmptyNpmProject(root, reporter)
        } else {
            val commandParts = buildScaffoldCommand(template, name, request.values["command"])
            commands.execute(
                CommandRequest(
                    command = commandParts.first(),
                    arguments = commandParts.drop(1),
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
        }

        if (!result.successful) {
            if (root.exists()) root.deleteRecursively()
            error(
                result.output.lineSequence().lastOrNull { it.isNotBlank() }
                    ?: "Project scaffolding failed with exit code ${result.exitCode}"
            )
        }

        return ProjectCreationResult(
            project = WebProjectTypeProvider.project(root),
            message = "Web project created successfully"
        )
    }

    private suspend fun createEmptyNpmProject(
        root: File,
        reporter: OperationReporter
    ): CommandResult {
        check(root.mkdirs()) { "Failed to create directory ${root.absolutePath}" }
        return commands.execute(
            CommandRequest(
                command = "npm",
                arguments = listOf("init", "-y"),
                workingDirectory = root
            )
        ) { output ->
            reporter.report(
                OperationUpdate(
                    message = output,
                    kind = OperationMessageKind.OUTPUT
                )
            )
        }
    }

    private fun buildScaffoldCommand(
        template: String,
        name: String,
        customCommand: String?
    ): List<String> {
        return when (template) {
            "vite" -> listOf("npm", "create", "vite@latest", name, "--", "--template", "vanilla-ts")
            "react" -> listOf("npm", "create", "vite@latest", name, "--", "--template", "react-ts")
            "next" -> listOf(
                "npx",
                "create-next-app@latest",
                name,
                "--typescript",
                "--eslint",
                "--no-src-dir",
                "--no-app",
                "--import-alias",
                "@/*"
            )

            "vue" -> listOf("npm", "create", "vite@latest", name, "--", "--template", "vue-ts")
            "svelte" -> listOf(
                "npm",
                "create",
                "vite@latest",
                name,
                "--",
                "--template",
                "svelte-ts"
            )

            "custom" -> {
                val raw = customCommand.orEmpty().trim()
                require(raw.isNotBlank()) { "Custom command cannot be empty" }
                parseCommandString(raw, name)
            }

            else -> error("Unsupported web template: $template")
        }
    }

    private fun parseCommandString(rawCommand: String, projectName: String): List<String> {
        val replaced = if (rawCommand.contains("{name}")) {
            rawCommand.replace("{name}", projectName)
        } else {
            "$rawCommand $projectName"
        }
        return replaced.split(Regex("\\s+"))
    }
}

private object WebProjectCommandProvider : ProjectCommandProvider {
    override val id = "org.cosmicide.plugins.web.commands"
    override val displayName = "npm commands"
    override val description = "npm install, run dev, run build, run start, and run test commands"

    override fun commands(project: Project): List<ProjectCommand> {
        if (!WebProjectTypeProvider.supports(project.root)) return emptyList()

        return buildList {
            add(
                ProjectCommand(
                    id = "$id.install",
                    label = "npm install",
                    command = "npm install",
                    description = "Install project dependencies",
                    kind = ProjectCommandKind.SYNC
                )
            )
            add(
                ProjectCommand(
                    id = "$id.dev",
                    label = "npm run dev",
                    command = "npm run dev",
                    description = "Start local development server",
                    kind = ProjectCommandKind.RUN
                )
            )
            add(
                ProjectCommand(
                    id = "$id.build",
                    label = "npm run build",
                    command = "npm run build",
                    description = "Build project for production",
                    kind = ProjectCommandKind.BUILD
                )
            )
            add(
                ProjectCommand(
                    id = "$id.start",
                    label = "npm start",
                    command = "npm start",
                    description = "Start production server",
                    kind = ProjectCommandKind.RUN
                )
            )
            add(
                ProjectCommand(
                    id = "$id.test",
                    label = "npm test",
                    command = "npm test",
                    description = "Run project test suite",
                    kind = ProjectCommandKind.OTHER
                )
            )
        }
    }
}

private class WebServerProvider(
    private val processes: ToolProcessService,
    private val logger: PluginLogger
) : LspServerProvider {
    override val id = "org.cosmicide.plugins.web.lsp"
    override val displayName = "JavaScript & TypeScript language support"
    override val description = "JS/TS powered by typescript-language-server"
    override val priority = 350

    override fun supports(request: LspServerRequest): Boolean {
        return request.extension.lowercase() in SUPPORTED_FILE_EXTENSIONS
    }

    override fun createDefinition(request: LspServerRequest): LspServerDefinition {
        return LspServerDefinition(
            id = id,
            fileExtensions = SUPPORTED_FILE_EXTENSIONS,
            displayName = "web",
            connectionFactory = {
                WebServerConnection(processes, it, logger)
            },
            textMateGrammarLink = grammarLinkFor(request.extension),
            enableInlayHints = true,
            enableSignatureHelp = true,
            initializationTimeoutMillis = 120_000
        )
    }
}

private class WebServerConnection(
    private val processes: ToolProcessService,
    private val request: LspServerRequest,
    private val logger: PluginLogger
) : LspServerConnection {
    @Volatile
    private var process: Process? = null

    @Synchronized
    override fun start() {
        check(process == null) { "web lsp connection has already started" }
        process = processes.start(
            CommandRequest(
                command = "typescript-language-server",
                arguments = listOf(
                    "--stdio"
                ),
                workingDirectory = request.project.root,
                environment = mapOf(
                    "COSMIC_PROJECT_ROOT" to request.project.root.absolutePath
                )
            ),
            redirectErrorStream = false
        ).also { started ->
            drainStderr(started.errorStream)
            logger.info("web lsp started for ${request.project.name}")
        }
    }

    override val outputStream: OutputStream
        get() = checkNotNull(process) { "web lsp has not started" }.outputStream

    override val inputStream: InputStream
        get() = checkNotNull(process) { "web lsp has not started" }.inputStream

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
                logger.warn("web lsp stderr logger stopped", it)
            }
        }.apply {
            name = "TypeScript-LSP-Stderr"
            isDaemon = true
            start()
        }
    }
}

private const val INSTALL_COMMAND =
    "pacman -S --needed nodejs npm yarn typescript typescript-language-server"

private val SUPPORTED_FILE_EXTENSIONS = setOf("js", "mjs", "cjs", "jsx", "ts", "mts", "cts", "tsx")

private fun grammarLinkFor(extension: String): String =
    when (extension.lowercase()) {
        "js", "mjs", "cjs" ->
            JAVASCRIPT_GRAMMAR

        "jsx" ->
            JAVASCRIPT_REACT_GRAMMAR

        "ts", "mts", "cts" ->
            TYPESCRIPT_GRAMMAR

        "tsx" ->
            TYPESCRIPT_REACT_GRAMMAR

        else ->
            JAVASCRIPT_GRAMMAR
    }

private const val JAVASCRIPT_GRAMMAR =
    "https://raw.githubusercontent.com/microsoft/vscode/main/extensions/javascript/syntaxes/JavaScript.tmLanguage.json"

private const val JAVASCRIPT_REACT_GRAMMAR =
    "https://raw.githubusercontent.com/microsoft/vscode/main/extensions/javascript/syntaxes/JavaScriptReact.tmLanguage.json"

private const val TYPESCRIPT_GRAMMAR =
    "https://raw.githubusercontent.com/microsoft/vscode/main/extensions/typescript-basics/syntaxes/TypeScript.tmLanguage.json"

private const val TYPESCRIPT_REACT_GRAMMAR =
    "https://raw.githubusercontent.com/microsoft/vscode/main/extensions/typescript-basics/syntaxes/TypeScriptReact.tmLanguage.json"