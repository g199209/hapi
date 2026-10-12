package app.hapi.companion.ui.markdown

import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.sp

internal val LocalMarkdownSelectable = staticCompositionLocalOf { false }

/** Compose joins selected Text nodes directly. Add logical separators without
 * changing visible spacing, and keep them inside the native selection model. */
@Composable
internal fun MarkdownSelectionText(
    text: AnnotatedString,
    suffix: String = "",
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    color: Color = Color.Unspecified,
    softWrap: Boolean = true,
) {
    if (suffix.isEmpty()) {
        Text(text, modifier, color = color, style = style, softWrap = softWrap)
        return
    }
    val copiedText = buildAnnotatedString {
        append(text)
        // A space separates list markers/table cells when copied but must not
        // change column widths or alignment in the rendered layout.
        if (suffix == " ") {
            withStyle(SpanStyle(fontSize = 0.sp, letterSpacing = 0.sp)) { append(suffix) }
        } else {
            append(suffix)
        }
    }
    // App typography trims line-height padding. Appending a newline changes
    // which line is last, especially at large font scales, so subtracting one
    // nominal line height is insufficient. Measure the original visible text
    // with the same font/constraints and keep precisely its height.
    val textMeasurer = rememberTextMeasurer()
    val layout = if ('\n' !in suffix) modifier else modifier.clipToBounds().layout { measurable, constraints ->
        val visible = textMeasurer.measure(text, style, softWrap = softWrap, constraints = constraints)
        // Constrain the Text itself, so the selection registrar sees only
        // visible bounds. Cropping just an outer wrapper leaves the hidden
        // newline's touch area overlapping the following paragraph.
        val measured = measurable.measure(constraints.copy(minHeight = visible.size.height, maxHeight = visible.size.height))
        layout(measured.width, visible.size.height) {
            measured.place(0, 0)
        }
    }
    Text(copiedText, layout, color = color, style = style, softWrap = softWrap)
}
