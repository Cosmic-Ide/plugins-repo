package org.cosmicide.plugins.lua

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
import org.cosmicide.project.ProjectTypeProvider
import org.cosmicide.project.ToolProcessService
import java.io.File
import java.io.InputStream
import java.io.OutputStream

class LuaPlugin : CosmicPlugin {
    override val setupActions = listOf(
        PluginSetupAction(
            id = "org.cosmicide.plugins.lua.installToolchain",
            label = "Install Lua tools",
            command = LUA_INSTALL_COMMAND,
            description = "Install Lua, LuaLS, StyLua, LuaRocks, and Luacheck."
        )
    )

    override fun activate(context: PluginContext) {
        val processService = context.services.require(IdeServices.TOOL_PROCESS)
        val owner = context.descriptor.id

        listOf(
            context.extensions.register(
                point = EditorExtensionPoints.LSP_SERVER_PROVIDER,
                extension = LuaLanguageServerProvider(processService, context.logger),
                ownerPluginId = owner,
                priority = 350
            ),
            context.extensions.register(
                point = ProjectExtensionPoints.TYPE_PROVIDER,
                extension = LuaProjectTypeProvider,
                ownerPluginId = owner,
                priority = 350
            ),
            context.extensions.register(
                point = ProjectExtensionPoints.CREATION_PROVIDER,
                extension = LuaProjectCreationProvider,
                ownerPluginId = owner,
                priority = 350
            ),
            context.extensions.register(
                point = ProjectExtensionPoints.COMMAND_PROVIDER,
                extension = LuaProjectCommandProvider,
                ownerPluginId = owner,
                priority = 350
            )
        ).forEach(context::registerDisposable)

        context.logger.info("Lua language, project, and LuaLS support registered")
    }
}

private class LuaLanguageServerProvider(
    private val processes: ToolProcessService,
    private val logger: PluginLogger
) : LspServerProvider {
    override val id = "org.cosmicide.plugins.lua.luals"
    override val displayName = "Lua language support"
    override val description = "Lua editing powered by LuaLS"
    override val priority = 350

    override fun supports(request: LspServerRequest): Boolean {
        return request.extension.equals("lua", ignoreCase = true)
    }

    override fun createDefinition(request: LspServerRequest): LspServerDefinition {
        return LspServerDefinition(
            id = id,
            fileExtensions = setOf("lua"),
            displayName = "LuaLS",
            connectionFactory = {
                LuaLanguageServerConnection(processes, it, logger)
            },
            textMateGrammarLink = LUA_TEXTMATE_GRAMMAR,
            enableInlayHints = true,
            enableSignatureHelp = true,
            initializationTimeoutMillis = 120_000
        )
    }
}

private class LuaLanguageServerConnection(
    private val processes: ToolProcessService,
    private val request: LspServerRequest,
    private val logger: PluginLogger
) : LspServerConnection {
    @Volatile
    private var process: Process? = null

    @Synchronized
    override fun start() {
        check(process == null) { "LuaLS connection has already started" }
        process = processes.start(
            CommandRequest(
                command = "lua-language-server",
                workingDirectory = request.project.root,
                environment = mapOf(
                    "COSMIC_PROJECT_ROOT" to request.project.root.absolutePath
                )
            ),
            redirectErrorStream = false
        ).also { started ->
            drainStderr(started.errorStream)
            logger.info("LuaLS started for ${request.project.name}")
        }
    }

    override val outputStream: OutputStream
        get() = checkNotNull(process) { "LuaLS has not started" }.outputStream

    override val inputStream: InputStream
        get() = checkNotNull(process) { "LuaLS has not started" }.inputStream

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
                logger.warn("LuaLS stderr logger stopped", it)
            }
        }.apply {
            name = "Lua-Language-Server-Stderr"
            isDaemon = true
            start()
        }
    }
}

private object LuaProjectTypeProvider : ProjectTypeProvider {
    override val id = "org.cosmicide.plugins.lua.projectType"
    override val displayName = "Lua projects"
    override val description = "Recognizes configured Lua projects and Lua source roots"
    override val languageName = "Lua"
    override val fileExtension = "lua"

    override fun supports(projectRoot: File): Boolean {
        return projectRoot.resolve(".luarc.json").isFile ||
            projectRoot.resolve(".luarc.jsonc").isFile ||
            projectRoot.resolve("stylua.toml").isFile ||
            projectRoot.listFiles()?.any { it.isFile && it.extension.equals("lua", true) } == true
    }
}

private object LuaProjectCreationProvider : ProjectCreationProvider {
    override val id = "org.cosmicide.plugins.lua.createProject"
    override val displayName = "New Lua project"
    override val description = "Create a Lua application or module"
    override val actionLabel = "Create"

    override val fields = listOf(
        PluginFormField(
            id = "name",
            label = "Project directory",
            placeholder = "hello-lua",
            required = true
        ),
        PluginFormField(
            id = "kind",
            label = "Project kind",
            type = PluginFormFieldType.CHOICE,
            defaultValue = "application",
            options = listOf(
                PluginFormOption("application", "Application"),
                PluginFormOption("module", "Module")
            )
        )
    )

    override suspend fun create(
        request: ProjectCreationRequest,
        reporter: OperationReporter
    ): ProjectCreationResult {
        val name = request.values["name"].orEmpty().trim()
        require(name.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]*"))) {
            "Project directory may contain letters, numbers, dots, underscores, and hyphens"
        }

        val kind = request.values["kind"].orEmpty().ifBlank { "application" }
        require(kind == "application" || kind == "module") {
            "Unsupported Lua project kind"
        }

        val projectsRoot = request.projectsDirectory.canonicalFile
        val root = projectsRoot.resolve(name).canonicalFile
        require(root.parentFile == projectsRoot) { "Invalid project directory" }
        require(!root.exists()) { "A project with this name already exists" }
        require(root.mkdirs()) { "Could not create the project directory" }

        try {
            reporter.report(OperationUpdate("Creating Lua project…\n"))
            root.resolve(".luarc.json").writeText(LUA_CONFIG_TEMPLATE)
            root.resolve("stylua.toml").writeText(STYLUA_CONFIG_TEMPLATE)
            if (kind == "application") {
                root.resolve("main.lua").writeText(LUA_APPLICATION_TEMPLATE)
            } else {
                root.resolve("init.lua").writeText(LUA_MODULE_TEMPLATE)
            }
        } catch (error: Throwable) {
            root.deleteRecursively()
            throw error
        }

        return ProjectCreationResult(
            project = LuaProjectTypeProvider.project(root),
            message = "Lua project created successfully"
        )
    }
}

private object LuaProjectCommandProvider : ProjectCommandProvider {
    override val id = "org.cosmicide.plugins.lua.commands"
    override val displayName = "Lua commands"
    override val description = "Run, check, format, and maintain Lua projects"

    override fun commands(project: Project): List<ProjectCommand> {
        if (!LuaProjectTypeProvider.supports(project.root)) return emptyList()

        return buildList {
            val entryFile = when {
                project.root.resolve("main.lua").isFile -> "main.lua"
                project.root.resolve("init.lua").isFile -> "init.lua"
                else -> null
            }
            if (entryFile != null) {
                add(
                    command(
                        name = "run",
                        command = "lua $entryFile",
                        label = "Run $entryFile",
                        description = "Run the project entry file",
                        kind = ProjectCommandKind.RUN
                    )
                )
            }
            add(
                command(
                    name = "repl",
                    command = "lua -i",
                    label = "Lua REPL",
                    description = "Open an interactive Lua session",
                    kind = ProjectCommandKind.RUN
                )
            )
            add(
                command(
                    name = "check",
                    command = "find . -type f -name '*.lua' -exec luac -p {} +",
                    label = "Check syntax",
                    description = "Check every Lua source file for syntax errors",
                    kind = ProjectCommandKind.BUILD
                )
            )
            add(
                command(
                    name = "format",
                    command = "stylua .",
                    label = "Format project",
                    description = "Format all Lua files with StyLua"
                )
            )
            add(
                ProjectCommand(
                    id = "$id.quality",
                    label = "Quality",
                    children = listOf(
                        command(
                            name = "format.check",
                            command = "stylua --check .",
                            label = "Check formatting",
                            description = "Check formatting without changing files"
                        ),
                        command(
                            name = "lint",
                            command = "luacheck .",
                            label = "Lint project",
                            description = "Analyze Lua sources with Luacheck",
                            kind = ProjectCommandKind.BUILD
                        )
                    )
                )
            )
            add(
                ProjectCommand(
                    id = "$id.rocks",
                    label = "LuaRocks",
                    children = listOf(
                        command(
                            name = "rocks.list",
                            command = "luarocks list",
                            label = "List installed rocks",
                            description = "List installed LuaRocks packages"
                        ),
                        command(
                            name = "rocks.path",
                            command = "luarocks path",
                            label = "Show paths",
                            description = "Print LuaRocks path environment settings"
                        )
                    )
                )
            )
            add(
                ProjectCommand(
                    id = "$id.information",
                    label = "Tool information",
                    children = listOf(
                        command(
                            name = "version.lua",
                            command = "lua -v",
                            label = "Lua version",
                            description = "Show the installed Lua version"
                        ),
                        command(
                            name = "version.luals",
                            command = "lua-language-server --version",
                            label = "LuaLS version",
                            description = "Show the installed LuaLS version"
                        ),
                        command(
                            name = "version.stylua",
                            command = "stylua --version",
                            label = "StyLua version",
                            description = "Show the installed StyLua version"
                        )
                    )
                )
            )
        }
    }

    private fun command(
        name: String,
        command: String,
        label: String,
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

private const val LUA_INSTALL_COMMAND =
    "pacman -S --needed lua lua-language-server stylua luarocks luacheck"
private const val LUA_TEXTMATE_GRAMMAR =
    "https://raw.githubusercontent.com/microsoft/vscode/main/extensions/lua/syntaxes/lua.tmLanguage.json"
private val LUA_CONFIG_TEMPLATE = """
    {
      "runtime.version": "Lua 5.5",
      "workspace.checkThirdParty": false
    }
""".trimIndent() + "\n"
private val STYLUA_CONFIG_TEMPLATE = """
    column_width = 100
    indent_type = "Spaces"
    indent_width = 4
""".trimIndent() + "\n"
private val LUA_APPLICATION_TEMPLATE = """
    print("Hello, Lua!")
""".trimIndent() + "\n"
private val LUA_MODULE_TEMPLATE = """
    local module = {}

    return module
""".trimIndent() + "\n"
