package org.cosmicide.plugins.clangd

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

class ClangdPlugin : CosmicPlugin {
    override val setupActions = listOf(
        PluginSetupAction(
            id = "org.cosmicide.plugins.clangd.installToolchain",
            label = "Install Clang tools",
            command = CLANG_INSTALL_COMMAND,
            description = "Install Clang and the clangd language server."
        )
    )

    override fun activate(context: PluginContext) {
        val processService = context.services.require(IdeServices.TOOL_PROCESS)
        val owner = context.descriptor.id

        context.registerDisposable(
            context.extensions.register(
                point = EditorExtensionPoints.LSP_SERVER_PROVIDER,
                extension = ClangdServerProvider(processService, context.logger),
                ownerPluginId = owner,
                priority = 350
            )
        )

        context.logger.info("Clangd language support registered")
    }
}

private class ClangdServerProvider(
    private val processes: ToolProcessService,
    private val logger: PluginLogger
) : LspServerProvider {
    override val id = "org.cosmicide.plugins.clangd.lsp"
    override val displayName = "Clangd language support"
    override val description = "C-family editing powered by clangd"
    override val priority = 350

    override fun supports(request: LspServerRequest): Boolean {
        return request.extension.lowercase() in CLANGD_FILE_EXTENSIONS
    }

    override fun createDefinition(request: LspServerRequest): LspServerDefinition {
        return LspServerDefinition(
            id = id,
            fileExtensions = CLANGD_FILE_EXTENSIONS,
            displayName = "clangd",
            connectionFactory = {
                ClangdServerConnection(processes, it, logger)
            },
            textMateGrammarLink = grammarLinkFor(request.extension),
            enableInlayHints = true,
            enableSignatureHelp = true,
            initializationTimeoutMillis = 120_000
        )
    }
}

private class ClangdServerConnection(
    private val processes: ToolProcessService,
    private val request: LspServerRequest,
    private val logger: PluginLogger
) : LspServerConnection {
    @Volatile
    private var process: Process? = null

    @Synchronized
    override fun start() {
        check(process == null) { "clangd connection has already started" }
        process = processes.start(
            CommandRequest(
                command = "clangd",
                arguments = listOf(
                    "--background-index",
                    "--clang-tidy",
                    "--completion-style=detailed",
                    "--header-insertion=iwyu"
                ),
                workingDirectory = request.project.root,
                environment = mapOf(
                    "COSMIC_PROJECT_ROOT" to request.project.root.absolutePath
                )
            ),
            redirectErrorStream = false
        ).also { started ->
            drainStderr(started.errorStream)
            logger.info("clangd started for ${request.project.name}")
        }
    }

    override val outputStream: OutputStream
        get() = checkNotNull(process) { "clangd has not started" }.outputStream

    override val inputStream: InputStream
        get() = checkNotNull(process) { "clangd has not started" }.inputStream

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
                logger.warn("clangd stderr logger stopped", it)
            }
        }.apply {
            name = "Clangd-Stderr"
            isDaemon = true
            start()
        }
    }
}

private const val CLANG_INSTALL_COMMAND = "pacman -S --needed clang"
private val C_FILE_EXTENSIONS = setOf(
    "c", "i"
)
private val CPP_FILE_EXTENSIONS = setOf(
    "h",
    "cc",
    "ccm",
    "cp",
    "cpp",
    "cppm",
    "cxx",
    "cxxm",
    "c++",
    "c++m",
    "h++",
    "hh",
    "hpp",
    "hxx",
    "ii",
    "ino",
    "inl",
    "ipp",
    "ixx",
    "tcc",
    "tpp",
    "txx"
)
private val OBJECTIVE_C_FILE_EXTENSIONS = setOf("m")
private val OBJECTIVE_CPP_FILE_EXTENSIONS = setOf("mm")
private val CUDA_FILE_EXTENSIONS = setOf("cu", "cuh")
private val CLANGD_FILE_EXTENSIONS =
    C_FILE_EXTENSIONS +
        CPP_FILE_EXTENSIONS +
        OBJECTIVE_C_FILE_EXTENSIONS +
        OBJECTIVE_CPP_FILE_EXTENSIONS +
        CUDA_FILE_EXTENSIONS

private fun grammarLinkFor(extension: String): String {
    if (extension == "M") return OBJECTIVE_CPP_TEXTMATE_GRAMMAR

    return when (extension.lowercase()) {
        in C_FILE_EXTENSIONS -> C_TEXTMATE_GRAMMAR
        in OBJECTIVE_C_FILE_EXTENSIONS -> OBJECTIVE_C_TEXTMATE_GRAMMAR
        in OBJECTIVE_CPP_FILE_EXTENSIONS -> OBJECTIVE_CPP_TEXTMATE_GRAMMAR
        in CUDA_FILE_EXTENSIONS -> CUDA_CPP_TEXTMATE_GRAMMAR
        else -> CPP_TEXTMATE_GRAMMAR
    }
}

private const val C_TEXTMATE_GRAMMAR =
    "https://raw.githubusercontent.com/microsoft/vscode/main/extensions/cpp/syntaxes/c.tmLanguage.json"
private const val CPP_TEXTMATE_GRAMMAR =
    "https://raw.githubusercontent.com/microsoft/vscode/main/extensions/cpp/syntaxes/cpp.tmLanguage.json"
private const val CUDA_CPP_TEXTMATE_GRAMMAR =
    "https://raw.githubusercontent.com/microsoft/vscode/main/extensions/cpp/syntaxes/cuda-cpp.tmLanguage.json"
private const val OBJECTIVE_C_TEXTMATE_GRAMMAR =
    "https://raw.githubusercontent.com/textmate/objective-c.tmbundle/master/Syntaxes/Objective-C.tmLanguage"
private const val OBJECTIVE_CPP_TEXTMATE_GRAMMAR =
    "https://raw.githubusercontent.com/textmate/objective-c.tmbundle/master/Syntaxes/Objective-C%2B%2B.tmLanguage"
