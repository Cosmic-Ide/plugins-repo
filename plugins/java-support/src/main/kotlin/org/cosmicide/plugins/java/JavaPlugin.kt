package org.cosmicide.plugins.java

import org.cosmicide.editor.EditorExtensionPoints
import org.cosmicide.editor.LspServerConnection
import org.cosmicide.editor.LspServerDefinition
import org.cosmicide.editor.LspServerProvider
import org.cosmicide.editor.LspServerRequest
import org.cosmicide.plugin.api.CosmicPlugin
import org.cosmicide.plugin.api.PluginContext
import org.cosmicide.plugin.api.PluginLogger
import org.cosmicide.plugin.api.PluginSetupAction
import org.cosmicide.plugins.AndroidPluginServices
import org.cosmicide.project.CommandRequest
import org.cosmicide.project.IdeServices
import org.cosmicide.project.ToolProcessService
import java.io.File
import java.io.InputStream
import java.io.OutputStream

class JavaPlugin : CosmicPlugin {

    private lateinit var pluginDir: String

    override val setupActions: List<PluginSetupAction>
        get() = listOf(
            PluginSetupAction(
                id = "org.cosmicide.plugins.java.installLSP",
                label = "Install Java LSP",
                command = """rm -rf "$pluginDir/jdtls" && mkdir -p "$pluginDir/jdtls" && curl -fL https://download.eclipse.org/jdtls/snapshots/jdt-language-server-latest.tar.gz | unpigz | tar -x -C "$pluginDir/jdtls"""",
                description = "Install Eclipse JDT Language Server."
            )
        )

    override fun activate(context: PluginContext) {
        val processService = context.services.require(IdeServices.TOOL_PROCESS)

        pluginDir = context.services.require(AndroidPluginServices.PLUGIN_DIRECTORY).absolutePath

        context.registerDisposable(
            context.extensions.register(
                point = EditorExtensionPoints.LSP_SERVER_PROVIDER, extension = JavaServerProvider(
                    processService, context, context.logger
                ), ownerPluginId = context.descriptor.id, priority = 350
            )
        )

        context.logger.info("Java language support registered")
    }
}

private class JavaServerProvider(
    private val processes: ToolProcessService,
    private val pluginContext: PluginContext,
    private val logger: PluginLogger
) : LspServerProvider {

    override val id = "org.cosmicide.plugins.java.lsp"
    override val displayName = "Java language support"
    override val description = "Java editing powered by Eclipse JDT Language Server"
    override val priority = 350

    override fun supports(request: LspServerRequest): Boolean {
        return request.extension.equals("java", ignoreCase = true)
    }

    override fun createDefinition(
        request: LspServerRequest
    ): LspServerDefinition {
        return LspServerDefinition(
            id = id,
            fileExtensions = setOf("java"),
            displayName = "JDT LS",
            connectionFactory = {
                JdtServerConnection(processes, it, pluginContext, logger)
            },
            textMateGrammarLink = JAVA_TEXTMATE_GRAMMAR,
            enableInlayHints = true,
            enableSignatureHelp = true,
            initializationTimeoutMillis = 120_000
        )
    }
}

private class JdtServerConnection(
    private val processes: ToolProcessService,
    private val request: LspServerRequest,
    private val pluginContext: PluginContext,
    private val logger: PluginLogger
) : LspServerConnection {

    @Volatile
    private var process: Process? = null

    @Synchronized
    override fun start() {
        check(process == null) {
            "JDT LS connection has already started"
        }

        val pluginRoot = pluginContext.services.get(AndroidPluginServices.PLUGIN_DIRECTORY)!!

        val jdtlsDir = pluginRoot.resolve("jdtls")
        val launcherJar = findEquinoxLauncher(jdtlsDir)
            ?: error("Equinox launcher not found in ${jdtlsDir.resolve("plugins")}")

        val workspaceId =
            "${request.project.name}-${request.project.root.absolutePath.hashCode().toUInt()}"

        val configurationDir =
            pluginRoot.resolve("cache/jdtls-config/$workspaceId").apply { mkdirs() }

        val workspaceDir =
            pluginRoot.resolve("cache/jdtls-workspace/$workspaceId").apply { mkdirs() }

        process = processes.start(
            CommandRequest(
                command = "java", arguments = listOf(
                    "-Djdk.xml.maxGeneralEntitySizeLimit=0",
                    "-Djdk.xml.totalEntitySizeLimit=0",
                    "-Djdk.lang.Process.launchMechanism=FORK",
                    "-Declipse.application=org.eclipse.jdt.ls.core.id1",
                    "-Dosgi.bundles.defaultStartLevel=4",
                    "-Declipse.product=org.eclipse.jdt.ls.core.product",
                    "-Dlog.level=WARNING",
                    "-Xms256m",
                    "-Xmx1G",
                    "-XX:+UseG1GC",
                    "-XX:+TieredCompilation",
                    "-XX:TieredStopAtLevel=1",
                    "-Dorg.eclipse.jdt.ls.lombok.support=false",
                    "--add-modules=ALL-SYSTEM",
                    "--add-opens",
                    "java.base/java.util=ALL-UNNAMED",
                    "--add-opens",
                    "java.base/java.lang=ALL-UNNAMED",
                    "-Dosgi.checkConfiguration=false",
                    "-Dosgi.sharedConfiguration.area=${jdtlsDir.resolve("config_linux")}",
                    "-Dosgi.sharedConfiguration.area.readOnly=true",
                    "-Dosgi.configuration.cascaded=true",
                    "-jar",
                    launcherJar.absolutePath,
                    "-configuration",
                    configurationDir.absolutePath,
                    "-data",
                    workspaceDir.absolutePath
                ), workingDirectory = request.project.root, environment = mapOf(
                    "COSMIC_PROJECT_ROOT" to request.project.root.absolutePath
                )
            ), redirectErrorStream = false
        ).also {
            drainStderr(it.errorStream)
            logger.info("JDT LS started for ${request.project.name}")
        }
    }

    override val outputStream: OutputStream
        get() = checkNotNull(process) {
            "JDT LS has not started"
        }.outputStream

    override val inputStream: InputStream
        get() = checkNotNull(process) {
            "JDT LS has not started"
        }.inputStream

    override val isClosed: Boolean
        get() = process?.isAlive != true

    @Synchronized
    override fun close() {
        process?.destroy()
        process = null
    }

    private fun drainStderr(stderr: InputStream) {
        Thread {
            runCatching {
                stderr.bufferedReader().useLines { lines ->
                    lines.forEach(logger::debug)
                }
            }
        }.apply {
            name = "JDTLS-Stderr"
            isDaemon = true
            start()
        }
    }
}

private fun findEquinoxLauncher(jdtlsDir: File): File? {
    return jdtlsDir.resolve("plugins").listFiles()?.firstOrNull {
        it.name.startsWith("org.eclipse.equinox.launcher_") && it.extension == "jar"
        }
}

private const val JAVA_TEXTMATE_GRAMMAR =
    "https://raw.githubusercontent.com/microsoft/vscode/main/extensions/java/syntaxes/java.tmLanguage.json"