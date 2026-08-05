package org.cosmicide.plugins.vue

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

class VuePlugin : CosmicPlugin {
    override val setupActions = listOf(
        PluginSetupAction(
            id = "org.cosmicide.plugins.vue.installToolchain",
            label = "Install Vue Language Server",
            command = VUE_INSTALL_COMMAND,
            description = "Install vue-language-server using pacman."
        )
    )

    override fun activate(context: PluginContext) {
        val processService = context.services.require(IdeServices.TOOL_PROCESS)
        val owner = context.descriptor.id

        context.registerDisposable(
            context.extensions.register(
                point = EditorExtensionPoints.LSP_SERVER_PROVIDER,
                extension = VueServerProvider(processService, context.logger),
                ownerPluginId = owner,
                priority = 350
            )
        )

        context.logger.info("Vue language support registered")
    }
}

private class VueServerProvider(
    private val processes: ToolProcessService,
    private val logger: PluginLogger
) : LspServerProvider {
    override val id = "org.cosmicide.plugins.vue.lsp"
    override val displayName = "Vue language support"
    override val description = "Vue editing features powered by vue-language-server"
    override val priority = 350

    override fun supports(request: LspServerRequest): Boolean {
        return request.extension.lowercase() in VUE_FILE_EXTENSIONS
    }

    override fun createDefinition(request: LspServerRequest): LspServerDefinition {
        return LspServerDefinition(
            id = id,
            fileExtensions = VUE_FILE_EXTENSIONS,
            displayName = "vue-language-server",
            connectionFactory = {
                VueServerConnection(processes, it, logger)
            },
            textMateGrammarLink = VUE_TEXTMATE_GRAMMAR,
            enableInlayHints = true,
            enableSignatureHelp = true,
            initializationTimeoutMillis = 120_000
        )
    }
}

private class VueServerConnection(
    private val processes: ToolProcessService,
    private val request: LspServerRequest,
    private val logger: PluginLogger
) : LspServerConnection {
    @Volatile
    private var process: Process? = null

    @Synchronized
    override fun start() {
        check(process == null) { "Vue LSP connection has already started" }
        process = processes.start(
            CommandRequest(
                command = "vue-language-server",
                arguments = listOf("--stdio"),
                workingDirectory = request.project.root,
                environment = mapOf(
                    "COSMIC_PROJECT_ROOT" to request.project.root.absolutePath
                )
            ),
            redirectErrorStream = false
        ).also { started ->
            drainStderr(started.errorStream)
            logger.info("Vue LSP started for ${request.project.name}")
        }
    }

    override val outputStream: OutputStream
        get() = checkNotNull(process) { "Vue LSP has not started" }.outputStream

    override val inputStream: InputStream
        get() = checkNotNull(process) { "Vue LSP has not started" }.inputStream

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
                logger.warn("Vue LSP stderr logger stopped", it)
            }
        }.apply {
            name = "Vue-LSP-Stderr"
            isDaemon = true
            start()
        }
    }
}

private const val VUE_INSTALL_COMMAND = "pacman -S --needed vue-language-server"

private val VUE_FILE_EXTENSIONS = setOf("vue")

private const val VUE_TEXTMATE_GRAMMAR =
    "https://raw.githubusercontent.com/vuejs/language-tools/master/packages/vscode/syntaxes/vue.tmLanguage.json"
