package org.cosmicide.plugins.md

import org.cosmicide.editor.registerLspServer
import org.cosmicide.plugin.api.CosmicPlugin
import org.cosmicide.plugin.api.PluginContext
import org.cosmicide.plugin.api.PluginSetupAction
import org.cosmicide.plugin.api.plugin

class MDPlugin : CosmicPlugin by plugin(
    id = "org.cosmicide.plugins.md",
    setupActions = listOf(
        PluginSetupAction(
            id = "org.cosmicide.plugins.md.installToolchain",
            label = "Install markdown-oxide LSP",
            command = "pacman -S markdown-oxide",
            description = "Install markdown-oxide language server for Markdown."
        )
    )
) {
    override fun activate(context: PluginContext) {
        context.registerLspServer(
            id = "org.cosmicide.plugins.md.lsp",
            displayName = "Markdown language support"
        ) {
            command = "markdown-oxide"
            registerGrammar(MD_TEXTMATE_GRAMMAR, "md", "markdown", "mdown", "mkdn")
        }

        context.logger.info("Markdown language support registered")
    }
}

private const val MD_TEXTMATE_GRAMMAR =
    "https://raw.githubusercontent.com/microsoft/vscode/main/extensions/markdown-basics/syntaxes/markdown.tmLanguage.json"
