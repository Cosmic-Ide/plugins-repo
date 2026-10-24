package org.cosmicide.plugins.zig

import org.cosmicide.editor.EditorExtensionPoints
import org.cosmicide.editor.LspServerConnection
import org.cosmicide.editor.LspServerDefinition
import org.cosmicide.editor.LspServerProvider
import org.cosmicide.editor.LspServerRequest
import org.cosmicide.plugin.api.CosmicPlugin
import org.cosmicide.plugin.api.PluginContext
import org.cosmicide.plugin.api.PluginLogger
import org.cosmicide.plugin.api.PluginSetupAction
import org.cosmicide.plugin.api.plugin
import org.cosmicide.plugin.api.register
import org.cosmicide.project.CommandRequest
import org.cosmicide.project.IdeServices
import org.cosmicide.project.Project
import org.cosmicide.project.ProjectCommand
import org.cosmicide.project.ProjectCommandKind
import org.cosmicide.project.ProjectCommandProvider
import org.cosmicide.project.ProjectExtensionPoints
import org.cosmicide.project.ProjectTypeProvider
import org.cosmicide.project.ToolProcessService
import java.io.File
import java.io.InputStream
import java.io.OutputStream

class ZigPlugin : CosmicPlugin by plugin(
    id = "org.cosmicide.plugins.zig",
    setupActions = listOf(
        PluginSetupAction(
            id = "org.cosmicide.plugins.zig.installToolchain",
            label = "Install Zig and ZLS",
            command = ZIG_INSTALL_COMMAND,
            description = "Install the Zig compiler and ZLS language server."
        )
    )
) {
    override fun activate(context: PluginContext) {
        val processes = context.services.require(IdeServices.TOOL_PROCESS)
        context.register(
            point = EditorExtensionPoints.LSP_SERVER_PROVIDER,
            extension = ZlsProvider(processes, context.logger),
            priority = 350
        )
        context.register(
            point = ProjectExtensionPoints.TYPE_PROVIDER,
            extension = ZigProjectTypeProvider,
            priority = 350
        )
        context.register(
            point = ProjectExtensionPoints.COMMAND_PROVIDER,
            extension = ZigProjectCommandProvider,
            priority = 350
        )
        context.logger.info("Zig language and project support registered")
    }
}

private class ZlsProvider(
    private val processes: ToolProcessService,
    private val logger: PluginLogger
) : LspServerProvider {
    override val id = "org.cosmicide.plugins.zig.lsp"
    override val displayName = "Zig language support"
    override val description = "Zig editing powered by ZLS"
    override val priority = 350

    override fun supports(request: LspServerRequest): Boolean =
        request.extension.equals("zig", ignoreCase = true)

    override fun createDefinition(request: LspServerRequest): LspServerDefinition =
        LspServerDefinition(
            id = id,
            fileExtensions = setOf("zig"),
            displayName = "ZLS",
            connectionFactory = { ZlsConnection(processes, it, logger) },
            enableInlayHints = true,
            enableSignatureHelp = true,
            initializationTimeoutMillis = 120_000
        )
}

private class ZlsConnection(
    private val processes: ToolProcessService,
    private val request: LspServerRequest,
    private val logger: PluginLogger
) : LspServerConnection {
    @Volatile
    private var process: Process? = null

    @Synchronized
    override fun start() {
        check(process == null) { "ZLS connection has already started" }
        process = processes.start(
            CommandRequest(
                command = "zls",
                workingDirectory = request.project.root,
                environment = mapOf("COSMIC_PROJECT_ROOT" to request.project.root.absolutePath)
            ),
            redirectErrorStream = false
        ).also { started ->
            drainStderr(started.errorStream)
            logger.info("ZLS started for ${request.project.name}")
        }
    }

    override val outputStream: OutputStream
        get() = checkNotNull(process) { "ZLS has not started" }.outputStream

    override val inputStream: InputStream
        get() = checkNotNull(process) { "ZLS has not started" }.inputStream

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
                stderr.bufferedReader().useLines { lines -> lines.forEach(logger::debug) }
            }.onFailure { logger.warn("ZLS stderr logger stopped", it) }
        }.apply {
            name = "ZLS-Stderr"
            isDaemon = true
            start()
        }
    }
}

private object ZigProjectTypeProvider : ProjectTypeProvider {
    override val id = "org.cosmicide.plugins.zig.projectType"
    override val displayName = "Zig projects"
    override val description = "Recognizes projects containing build.zig"
    override val languageName = "Zig"
    override val fileExtension = "zig"

    override fun supports(projectRoot: File): Boolean =
        projectRoot.resolve("build.zig").isFile
}

private object ZigProjectCommandProvider : ProjectCommandProvider {
    override val id = "org.cosmicide.plugins.zig.commands"
    override val displayName = "Zig commands"
    override val description = "Build, run, test, and clean Zig projects"

    override fun commands(project: Project): List<ProjectCommand> {
        if (!ZigProjectTypeProvider.supports(project.root)) return emptyList()
        return listOf(
            command(
                "build",
                "zig build",
                "Build",
                "Build the Zig project",
                ProjectCommandKind.BUILD
            ),
            command(
                "run",
                "zig build run",
                "Run",
                "Build and run the Zig project",
                ProjectCommandKind.RUN
            ),
            command("test", "zig build test", "Test", "Run Zig project tests"),
            command(
                "clean",
                "rm -rf zig-cache .zig-cache zig-out",
                "Clean",
                "Remove Zig build outputs"
            )
        )
    }

    private fun command(
        name: String,
        command: String,
        label: String,
        description: String,
        kind: ProjectCommandKind = ProjectCommandKind.OTHER
    ) = ProjectCommand("$id.$name", label, command, description, kind)
}

private val ZIG_INSTALL_COMMAND = """
    set -e
    pacman -S --needed --noconfirm zig zls
""".trimIndent()
