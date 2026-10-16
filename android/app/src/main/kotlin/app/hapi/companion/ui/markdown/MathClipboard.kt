package app.hapi.companion.ui.markdown

import android.content.ClipData
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard

/** One selectable character per formula, like an inline image. Tokens are scoped
 * to this message and exclude all characters already present in its source. */
internal class MathCopyRegistry(private val source: String) {
    private val sourceTokens = mutableMapOf<String, String>()
    private val tokenSources = mutableMapOf<Char, String>()
    private var next = 0xE000

    fun tokenFor(latexSource: String): String? {
        sourceTokens[latexSource]?.let { return it }
        while (next <= 0xF8FF && next.toChar() in source) next++
        if (next > 0xF8FF) return null
        val token = (next++).toChar()
        tokenSources[token] = latexSource
        return token.toString().also { sourceTokens[latexSource] = it }
    }

    fun decode(text: String): String = buildString {
        for (character in text) append(tokenSources[character] ?: character.toString())
    }
}

internal val LocalMathCopyRegistry = staticCompositionLocalOf<MathCopyRegistry?> { null }

@Composable
internal fun MathClipboard(source: String, content: @Composable () -> Unit) {
    val registry = remember(source) { MathCopyRegistry(source) }
    val platform = LocalClipboard.current
    val clipboard = remember(platform, registry) {
        object : Clipboard by platform {
            override suspend fun setClipEntry(clipEntry: ClipEntry?) {
                val text = clipEntry?.clipData?.takeIf { it.itemCount == 1 }?.getItemAt(0)?.text?.toString()
                val decoded = text?.let(registry::decode)
                platform.setClipEntry(
                    if (decoded != null && decoded != text) ClipEntry(ClipData.newPlainText("text", decoded))
                    else clipEntry,
                )
            }
        }
    }
    CompositionLocalProvider(LocalMathCopyRegistry provides registry, LocalClipboard provides clipboard, content = content)
}
