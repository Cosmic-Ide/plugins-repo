package org.cosmicide.plugins.diff

import android.view.View
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.rosemoe.sora.langs.textmate.TextMateLanguage
import io.github.rosemoe.sora.langs.textmate.registry.GrammarRegistry
import io.github.rosemoe.sora.langs.textmate.registry.ThemeRegistry
import io.github.rosemoe.sora.langs.textmate.registry.model.DefaultGrammarDefinition
import org.cosmicide.editor.EditorExtensionPoints
import org.cosmicide.editor.EditorLanguageProvider
import org.cosmicide.editor.EditorLanguageRequest
import org.cosmicide.editor.EditorPreviewMatchRequest
import org.cosmicide.editor.EditorPreviewPresentation
import org.cosmicide.editor.EditorPreviewProvider
import org.cosmicide.editor.EditorPreviewRenderRequest
import org.cosmicide.plugin.api.CosmicPlugin
import org.cosmicide.plugin.api.PluginContext
import org.cosmicide.plugin.api.plugin
import org.cosmicide.plugin.api.register
import org.eclipse.tm4e.core.registry.IGrammarSource
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URL

class DiffPlugin : CosmicPlugin by plugin(id = "org.cosmicide.plugins.diff") {
    override fun activate(context: PluginContext) {
        context.register(
            point = EditorExtensionPoints.LANGUAGE_PROVIDER,
            extension = DiffLanguageProvider,
            priority = 360
        )
        context.register(
            point = EditorExtensionPoints.PREVIEW_PROVIDER,
            extension = DiffPreviewProvider,
            priority = 360
        )
        context.logger.info("Diff syntax highlighting and preview registered")
    }
}

private object DiffLanguageProvider : EditorLanguageProvider {
    override val id = "org.cosmicide.plugins.diff.textmate"
    override val displayName = "Diff syntax highlighting"
    override val description = "TextMate syntax highlighting for unified diff files"
    override val priority = 360

    override fun supports(request: EditorLanguageRequest): Boolean {
        return request.file.extension.lowercase() in setOf("diff", "patch")
    }

    override fun configure(request: EditorLanguageRequest): Boolean {
        val cacheFile = request.editor.context.cacheDir
            .resolve("textmate-grammars")
            .also(File::mkdirs)
            .resolve("diff.tmLanguage.json")
        val grammarText = if (cacheFile.isFile) {
            cacheFile.readText()
        } else {
            downloadGrammar().also(cacheFile::writeText)
        }
        val definition = DefaultGrammarDefinition.withGrammarSource(
            IGrammarSource.fromString(
                IGrammarSource.ContentType.JSON,
                grammarText.removePrefix("\uFEFF")
            ),
            "source.diff",
            null
        )
        val themeRegistry = ThemeRegistry.getInstance()
        val grammarRegistry = GrammarRegistry(GrammarRegistry.getInstance()).apply {
            setTheme(themeRegistry.currentThemeModel)
        }
        request.editor.setEditorLanguage(
            TextMateLanguage.create(definition, grammarRegistry, themeRegistry, false)
        )
        return true
    }

    private fun downloadGrammar(): String {
        val connection = URL(DIFF_GRAMMAR_URL).openConnection().apply {
            connectTimeout = 10_000
            readTimeout = 20_000
        }
        return connection.getInputStream().use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var totalBytes = 0
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                totalBytes += count
                require(totalBytes <= 512 * 1024) { "Diff grammar exceeds the cache limit" }
                output.write(buffer, 0, count)
            }
            output.toString(Charsets.UTF_8.name()).also {
                require(it.isNotBlank()) { "Diff grammar is empty" }
            }
        }
    }
}

private object DiffPreviewProvider : EditorPreviewProvider {
    override val id = "org.cosmicide.plugins.diff.preview"
    override val displayName = "Diff preview"
    override val description = "Renders unified diffs in a GitHub-style layout"
    override val priority = 360
    override val presentation = EditorPreviewPresentation.CODE_AND_PREVIEW

    override fun supports(request: EditorPreviewMatchRequest): Boolean {
        return request.file.extension.lowercase() in setOf("diff", "patch")
    }

    override fun createView(request: EditorPreviewRenderRequest): View {
        return ComposeView(request.context).apply {
            setViewCompositionStrategy(
                ViewCompositionStrategy.DisposeOnDetachedFromWindowOrReleasedFromPool
            )
        }
    }

    override fun updateView(view: View, request: EditorPreviewRenderRequest) {
        val composeView = view as ComposeView
        val content = request.content.orEmpty()
        val contentKey = "${request.file.absolutePath}:${content.hashCode()}"
        if (composeView.tag == contentKey) return
        composeView.tag = contentKey
        composeView.setContent {
            DiffPreviewContent(parseDiff(content))
        }
    }

    override fun releaseView(view: View) {
        (view as ComposeView).disposeComposition()
    }
}

@Composable
private fun DiffPreviewContent(rows: List<DiffRow>) {
    val scrollState = rememberScrollState()
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.surface
    ) {
        SelectionContainer {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(scrollState)
                    .padding(12.dp)
            ) {
                rows.forEach { row -> DiffRowContent(row) }
            }
        }
    }
}

@Composable
private fun DiffRowContent(row: DiffRow) {
    val textColor = when (row.kind) {
        DiffRowKind.ADDITION -> Color(0xFF1A7F37)
        DiffRowKind.DELETION -> Color(0xFFCF222E)
        DiffRowKind.HUNK -> Color(0xFF0969DA)
        else -> MaterialTheme.colorScheme.onSurface
    }
    val backgroundColor = when (row.kind) {
        DiffRowKind.ADDITION -> Color(0x221A7F37)
        DiffRowKind.DELETION -> Color(0x22CF222E)
        else -> Color.Transparent
    }
    Surface(modifier = Modifier.fillMaxWidth(), color = backgroundColor) {
        Row(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = row.oldLine?.toString().orEmpty(),
                modifier = Modifier
                    .padding(horizontal = 4.dp, vertical = 3.dp)
                    .weight(0.08f),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp
            )
            Text(
                text = row.newLine?.toString().orEmpty(),
                modifier = Modifier
                    .padding(horizontal = 4.dp, vertical = 3.dp)
                    .weight(0.08f),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp
            )
            Text(
                text = row.prefix + row.text,
                modifier = Modifier
                    .padding(horizontal = 8.dp, vertical = 3.dp)
                    .weight(0.84f),
                color = textColor,
                fontFamily = FontFamily.Monospace,
                fontSize = 13.sp
            )
        }
    }
}

private enum class DiffRowKind {
    HEADER,
    HUNK,
    CONTEXT,
    ADDITION,
    DELETION
}

private data class DiffRow(
    val oldLine: Int?,
    val newLine: Int?,
    val prefix: String,
    val text: String,
    val kind: DiffRowKind
)

private fun parseDiff(content: String): List<DiffRow> {
    var oldLine = 0
    var newLine = 0
    return content.lineSequence().map { line ->
        when {
            line.startsWith("@@") -> {
                val match = Regex("""@@ -(\d+)(?:,\d+)? \+(\d+)(?:,\d+)? @@""").find(line)
                if (match != null) {
                    oldLine = match.groupValues[1].toInt()
                    newLine = match.groupValues[2].toInt()
                }
                DiffRow(null, null, "", line, DiffRowKind.HUNK)
            }

            line.startsWith("+") && !line.startsWith("+++") ->
                DiffRow(null, newLine++, "+", line.drop(1), DiffRowKind.ADDITION)

            line.startsWith("-") && !line.startsWith("---") ->
                DiffRow(oldLine++, null, "-", line.drop(1), DiffRowKind.DELETION)

            line.startsWith(" ") ->
                DiffRow(oldLine++, newLine++, " ", line.drop(1), DiffRowKind.CONTEXT)

            else -> DiffRow(null, null, "", line, DiffRowKind.HEADER)
        }
    }.toList()
}

private const val DIFF_GRAMMAR_URL =
    "https://raw.githubusercontent.com/microsoft/vscode/refs/heads/main/extensions/diff/syntaxes/diff.tmLanguage.json"
