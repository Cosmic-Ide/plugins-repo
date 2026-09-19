package org.cosmicide.plugins.vue

import org.cosmicide.editor.registerLspServer
import org.cosmicide.plugin.api.CosmicPlugin
import org.cosmicide.plugin.api.PluginContext
import org.cosmicide.plugin.api.PluginSetupAction
import org.cosmicide.plugin.api.plugin

class VuePlugin : CosmicPlugin by plugin(
    id = "org.cosmicide.plugins.vue",
    setupActions = listOf(
        PluginSetupAction(
            id = "org.cosmicide.plugins.vue.installToolchain",
            label = "Install Vue Language Server",
            command = "pacman -S --needed vue-language-server",
            description = "Install vue-language-server using pacman."
        )
    )
) {
    override fun activate(context: PluginContext) {
        context.registerLspServer(
            id = "org.cosmicide.plugins.vue.lsp",
            displayName = "Vue language support"
        ) {
            command = "vue-language-server"
            args = listOf("--stdio")

            registerGrammar(VUE_TEXTMATE_GRAMMAR, "vue")
        }

        context.logger.info("Vue language support registered")
    }
}

private const val VUE_TEXTMATE_GRAMMAR =
    "https://raw.githubusercontent.com/vuejs/language-tools/master/packages/vscode/syntaxes/vue.tmLanguage.json"
