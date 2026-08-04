package org.cosmicide.plugins.json

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

class JSONPlugin : CosmicPlugin {
    override val setupActions = listOf(
        PluginSetupAction(
            id = "org.cosmicide.plugins.json.installToolchain",
            label = "Install JSON language server",
            command = JSON_INSTALL_COMMAND,
            description = "Install Node.js and vscode-json-languageserver."
        )
    )

    override fun activate(context: PluginContext) {
        val processService = context.services.require(IdeServices.TOOL_PROCESS)
        val owner = context.descriptor.id

        context.registerDisposable(
            context.extensions.register(
                point = EditorExtensionPoints.LSP_SERVER_PROVIDER,
                extension = JsonServerProvider(processService, context.logger),
                ownerPluginId = owner,
                priority = 350
            )
        )

        context.logger.info("JSON language support registered")
    }
}

private class JsonServerProvider(
    private val processes: ToolProcessService,
    private val logger: PluginLogger
) : LspServerProvider {
    override val id = "org.cosmicide.plugins.json.lsp"
    override val displayName = "JSON language support"
    override val description = "JSON editing powered by vscode-json-languageserver"
    override val priority = 350

    override fun supports(request: LspServerRequest): Boolean {
        return request.extension.lowercase() in JSON_FILE_EXTENSIONS
    }

    override fun createDefinition(request: LspServerRequest): LspServerDefinition {
        return LspServerDefinition(
            id = id,
            fileExtensions = JSON_FILE_EXTENSIONS,
            displayName = "vscode-json-languageserver",
            connectionFactory = {
                JsonServerConnection(processes, it, logger)
            },
            textMateGrammarLink = grammarLinkFor(request.extension),
            enableInlayHints = false,
            enableSignatureHelp = false,
            initializationTimeoutMillis = 60_000
        )
    }
}

private class JsonServerConnection(
    private val processes: ToolProcessService,
    private val request: LspServerRequest,
    private val logger: PluginLogger
) : LspServerConnection {
    @Volatile
    private var process: Process? = null

    @Synchronized
    override fun start() {
        check(process == null) { "JSON LSP connection has already started" }
        process = processes.start(
            CommandRequest(
                command = "vscode-json-languageserver",
                arguments = listOf("--stdio"),
                workingDirectory = request.project.root,
                environment = mapOf(
                    "COSMIC_PROJECT_ROOT" to request.project.root.absolutePath
                )
            ),
            redirectErrorStream = false
        ).also { started ->
            drainStderr(started.errorStream)
            logger.info("JSON LSP started for ${request.project.name}")
        }
    }

    override val outputStream: OutputStream
        get() = checkNotNull(process) { "JSON LSP has not started" }.outputStream

    override val inputStream: InputStream
        get() = checkNotNull(process) { "JSON LSP has not started" }.inputStream

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
                logger.warn("JSON LSP stderr logger stopped", it)
            }
        }.apply {
            name = "JSON-LSP-Stderr"
            isDaemon = true
            start()
        }
    }
}

private const val JSON_INSTALL_COMMAND = "pacman -S --needed vscode-json-languageserver"

private val JSON_FILE_EXTENSIONS = setOf("json", "jsonc", "json5")

private fun grammarLinkFor(extension: String): String =
    when (extension.lowercase()) {
        "jsonc", "json5" -> JSONC_TEXTMATE_GRAMMAR
        else -> JSON_TEXTMATE_GRAMMAR
    }

private const val JSON_TEXTMATE_GRAMMAR =
    "https://raw.githubusercontent.com/microsoft/vscode/main/extensions/json/syntaxes/JSON.tmLanguage.json"

private const val JSONC_TEXTMATE_GRAMMAR =
    "https://raw.githubusercontent.com/microsoft/vscode/main/extensions/json/syntaxes/JSONC.tmLanguage.json"