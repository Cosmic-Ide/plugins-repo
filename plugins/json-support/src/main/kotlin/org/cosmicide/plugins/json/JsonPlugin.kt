package org.cosmicide.plugins.json

import org.cosmicide.editor.registerLspServer
import org.cosmicide.plugin.api.CosmicPlugin
import org.cosmicide.plugin.api.PluginContext
import org.cosmicide.plugin.api.PluginSetupAction
import org.cosmicide.plugin.api.plugin

class JSONPlugin : CosmicPlugin by plugin(
    id = "org.cosmicide.plugins.json",
    setupActions = listOf(
        PluginSetupAction(
            id = "org.cosmicide.plugins.json.installToolchain",
            label = "Install JSON language server",
            command = """
                set -e
                pacman -S --needed --noconfirm vscode-json-languageserver
            """.trimIndent(),
            description = "Install Node.js and vscode-json-languageserver."
        )
    )
) {
    override fun activate(context: PluginContext) {
        context.registerLspServer(
            id = "org.cosmicide.plugins.json.lsp",
            displayName = "JSON language support"
        ) {
            command = "vscode-json-languageserver"
            args = listOf("--stdio")
            enableInlayHints = false

            registerGrammar(JSON_TEXTMATE_GRAMMAR, "json")
            registerGrammar(JSONC_TEXTMATE_GRAMMAR, "jsonc", "json5")
        }

        context.logger.info("JSON language support registered")
    }
}

private const val JSON_TEXTMATE_GRAMMAR =
    "https://raw.githubusercontent.com/microsoft/vscode/main/extensions/json/syntaxes/JSON.tmLanguage.json"

private const val JSONC_TEXTMATE_GRAMMAR =
    "https://raw.githubusercontent.com/microsoft/vscode/main/extensions/json/syntaxes/JSONC.tmLanguage.json"
