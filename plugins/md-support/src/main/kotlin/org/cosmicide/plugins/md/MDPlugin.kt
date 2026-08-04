package org.cosmicide.plugins.md

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
import org.cosmicide.project.ToolProcessService
import java.io.InputStream
import java.io.OutputStream

class MDPlugin : CosmicPlugin {
    override val setupActions = listOf(
        PluginSetupAction(
            id = "org.cosmicide.plugins.md.installToolchain",
            label = "Install Markdown LSP",
            command = MD_INSTALL_COMMAND,
            description = "Install markdown language server."
        )
    )

    override fun activate(context: PluginContext) {
        val processService = context.services.require(IdeServices.TOOL_PROCESS)
        val owner = context.descriptor.id

        context.registerDisposable(
            context.extensions.register(
                point = EditorExtensionPoints.LSP_SERVER_PROVIDER,
                extension = MarkdownServerProvider(processService, context.logger),
                ownerPluginId = owner,
                priority = 350
            )
        )

        context.logger.info("Markdown language support registered")
    }
}

private class MarkdownServerProvider(
    private val processes: ToolProcessService,
    private val logger: PluginLogger
) : LspServerProvider {
    override val id = "org.cosmicide.plugins.md.lsp"
    override val displayName = "Markdown language support"
    override val description = "Markdown editing features"
    override val priority = 350

    override fun supports(request: LspServerRequest): Boolean {
        return request.extension.lowercase() in MD_FILE_EXTENSIONS
    }

    override fun createDefinition(request: LspServerRequest): LspServerDefinition {
        return LspServerDefinition(
            id = id,
            fileExtensions = MD_FILE_EXTENSIONS,
            displayName = "clangd",
            connectionFactory = {
                MarkdownServerConnection(processes, it, logger)
            },
            textMateGrammarLink = MD_TEXTMATE_GRAMMAR,
            enableInlayHints = true,
            enableSignatureHelp = true,
            initializationTimeoutMillis = 120_000
        )
    }
}

private class MarkdownServerConnection(
    private val processes: ToolProcessService,
    private val request: LspServerRequest,
    private val logger: PluginLogger
) : LspServerConnection {
    @Volatile
    private var process: Process? = null

    @Synchronized
    override fun start() {
        check(process == null) { "markdown lsp connection has already started" }
        process = processes.start(
            CommandRequest(
                command = "vscode-markdown-languageserver",
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
            logger.info("markdown lsp started for ${request.project.name}")
        }
    }

    override val outputStream: OutputStream
        get() = checkNotNull(process) { "markdown lsp has not started" }.outputStream

    override val inputStream: InputStream
        get() = checkNotNull(process) { "markdown lsp has not started" }.inputStream

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
                logger.warn("markdown lsp stderr logger stopped", it)
            }
        }.apply {
            name = "Markdown-Stderr"
            isDaemon = true
            start()
        }
    }
}

private const val MD_INSTALL_COMMAND = "pacman -S --needed vscode-markdown-languageserver"
private val MD_FILE_EXTENSIONS = setOf(
    "md", "markdown"
)


private const val MD_TEXTMATE_GRAMMAR =
    "https://raw.githubusercontent.com/microsoft/vscode-markdown-tm-grammar/main/syntaxes/markdown.tmLanguage"
