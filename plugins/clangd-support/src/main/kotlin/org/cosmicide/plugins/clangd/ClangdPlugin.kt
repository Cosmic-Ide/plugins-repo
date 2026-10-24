package org.cosmicide.plugins.clangd

import org.cosmicide.editor.registerLspServer
import org.cosmicide.plugin.api.CosmicPlugin
import org.cosmicide.plugin.api.PluginContext
import org.cosmicide.plugin.api.PluginSetupAction
import org.cosmicide.plugin.api.plugin

class ClangdPlugin : CosmicPlugin by plugin(
    id = "org.cosmicide.plugins.clangd",
    setupActions = listOf(
        PluginSetupAction(
            id = "org.cosmicide.plugins.clangd.installToolchain",
            label = "Install Clang tools",
            command = """
                set -e
                pacman -S --needed --noconfirm clang
            """.trimIndent(),
            description = "Install Clang and the clangd language server."
        )
    )
) {
    override fun activate(context: PluginContext) {
        context.registerLspServer(
            id = "org.cosmicide.plugins.clangd.lsp",
            displayName = "Clangd language support"
        ) {
            command = "clangd"
            args = listOf(
                "--background-index",
                "--clang-tidy",
                "--completion-style=detailed",
                "--header-insertion=iwyu"
            )

            registerGrammar(C_TEXTMATE_GRAMMAR, "c", "i")
            registerGrammar(
                CPP_TEXTMATE_GRAMMAR,
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
            registerGrammar(OBJECTIVE_C_TEXTMATE_GRAMMAR, "m")
            registerGrammar(OBJECTIVE_CPP_TEXTMATE_GRAMMAR, "mm", "M")
            registerGrammar(CUDA_CPP_TEXTMATE_GRAMMAR, "cu", "cuh")
        }

        context.logger.info("Clangd language support registered")
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
