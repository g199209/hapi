package app.hapi.companion.ui.markdown

import android.util.Log
import android.content.ClipData
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalTextToolbar
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import org.commonmark.node.Node
import app.hapi.protocol.markdown.InlineMath
import ru.noties.jlatexmath.JLatexMathDrawable

internal const val MATH_ANNOTATION = "hapi.math"

private data class MathKey(val latex: String, val pixels: Float, val color: Int, val display: Boolean)

/** TeX's font/parser caches are shared. Serialize compilation off the UI thread;
 * compiled drawables are reused on the UI thread, including failed parses. */
private object MathDrawableCache {
    private val entries = LinkedHashMap<MathKey, JLatexMathDrawable?>(128, 0.75f, true)
    @Synchronized fun compile(key: MathKey): JLatexMathDrawable? {
        if (entries.containsKey(key)) return entries[key]
        val drawable = if (key.latex.length > 8192) null else try {
            JLatexMathDrawable.builder((if (key.display) "\\displaystyle " else "\\textstyle ") + key.latex)
                .textSize(key.pixels).color(key.color).padding(1).build()
                .takeIf { it.intrinsicWidth in 1..32767 && it.intrinsicHeight in 1..16383 }
        } catch (error: Exception) {
            Log.w("HapiMath", "TeX rendering failed", error)
            null
        } catch (_: StackOverflowError) { null }
        entries[key] = drawable
        while (entries.size > 128) entries.remove(entries.keys.first())
        return drawable
    }
}

internal data class MathContent(val ids: Map<Node, String>, val content: Map<String, InlineTextContent>)

@Composable
internal fun rememberMathContent(parent: Node, style: TextStyle): MathContent {
    val nodes = remember(parent) {
        buildList {
            fun visit(node: Node) {
                if (node is InlineMath) add(node)
                var child = node.firstChild
                while (child != null) { visit(child); child = child.next }
            }
            visit(parent)
        }
    }
    val density = LocalDensity.current
    val pixels = with(density) { style.fontSize.toPx() }
    val color = if (style.color == Color.Unspecified) LocalContentColor.current else style.color
    val keys = remember(nodes, pixels, color) { nodes.map { MathKey(it.latex, pixels, color.toArgb(), it.display) } }
    val compiled by produceState<Map<MathKey, JLatexMathDrawable?>>(emptyMap(), keys) {
        value = withContext(Dispatchers.Default) { keys.associateWith { MathDrawableCache.compile(it) } }
    }
    return remember(nodes, keys, compiled, density) {
        val ids = mutableMapOf<Node, String>()
        val content = mutableMapOf<String, InlineTextContent>()
        for ((index, node) in nodes.withIndex()) {
            val drawable = compiled[keys[index]] ?: continue
            val id = "math-$index"
            ids[node] = id
            val width = with(density) { drawable.intrinsicWidth.toSp() }
            val height = with(density) { drawable.intrinsicHeight.toSp() }
            content[id] = InlineTextContent(Placeholder(width, height, PlaceholderVerticalAlign.TextCenter)) {
                FormulaCanvas(drawable, node)
            }
        }
        MathContent(ids, content)
    }
}

/** An equation is one visual object. Its long-press action copies the complete
 * TeX; text selections starting in prose can also include that object. */
@Composable
private fun FormulaCanvas(drawable: JLatexMathDrawable, node: InlineMath) {
    val clipboard = LocalClipboard.current
    val toolbar = LocalTextToolbar.current
    val scope = rememberCoroutineScope()
    var bounds by remember { mutableStateOf(Rect.Zero) }
    val comment = LocalMarkdownComment.current
    var menuOpen by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize()) {
        Canvas(
            Modifier.fillMaxSize().testTag("rendered-math")
                .semantics { contentDescription = node.latex }
                .onGloballyPositioned { bounds = it.boundsInWindow() }
                .pointerInput(node.source, clipboard, toolbar, comment) {
                    detectTapGestures(onLongPress = {
                        if (comment != null) {
                            menuOpen = true
                            return@detectTapGestures
                        }
                        toolbar.showMenu(
                            rect = bounds,
                            onCopyRequested = {
                                scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("LaTeX", node.source))) }
                                toolbar.hide()
                            },
                            onPasteRequested = null, onCutRequested = null, onSelectAllRequested = null,
                        )
                    })
                },
        ) { drawable.draw(drawContext.canvas.nativeCanvas) }
        // An atomic formula has no text-selection focus. Its popup must not share
        // SelectionContainer's toolbar, which hides itself when selection is empty.
        if (comment != null) androidx.compose.foundation.text.selection.DisableSelection {
            androidx.compose.material3.DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                androidx.compose.material3.DropdownMenuItem(
                    text = { androidx.compose.material3.Text(androidx.compose.ui.res.stringResource(android.R.string.copy)) },
                    onClick = { menuOpen = false; scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("LaTeX", node.source))) } },
                )
                androidx.compose.material3.DropdownMenuItem(
                    text = { androidx.compose.material3.Text(androidx.compose.ui.res.stringResource(app.hapi.companion.R.string.annotation_add)) },
                    onClick = { menuOpen = false; comment(node.source) },
                )
            }
        }
    }
}

internal fun AnnotatedString.Builder.appendMath(node: InlineMath, ids: Map<Node, String>, registry: MathCopyRegistry?) {
    val id = ids[node]
    val token = if (id != null) registry?.tokenFor(node.source) else null
    if (id == null || token == null) append(node.source) else {
        pushStringAnnotation(MATH_ANNOTATION, id)
        appendInlineContent(id, token)
        pop()
    }
}

@Composable
internal fun MathBlock(latex: String, source: String, suffix: String, closed: Boolean = true) {
    if (!closed) {
        MarkdownSelectionText(AnnotatedString(source), suffix = suffix)
        return
    }
    val node = remember(latex, source) { InlineMath(latex, source, display = true) }
    val style = LocalTextStyle.current
    val math = rememberMathContent(node, style)
    val registry = LocalMathCopyRegistry.current
    val text = remember(node, math, registry) { buildAnnotatedString { appendMath(node, math.ids, registry) } }
    Box(Modifier.fillMaxWidth().testTag("math-block").horizontalScroll(rememberScrollState())) {
        MarkdownSelectionText(text, suffix = suffix, style = style, softWrap = false, inlineContent = math.content)
    }
}
