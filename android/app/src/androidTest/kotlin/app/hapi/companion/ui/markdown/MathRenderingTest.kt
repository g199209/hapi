package app.hapi.companion.ui.markdown

import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import java.io.File
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalTextToolbar
import androidx.compose.ui.platform.TextToolbar
import androidx.compose.ui.platform.TextToolbarStatus
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import app.hapi.companion.ui.theme.HapiTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class MathRenderingTest {
    @get:Rule val compose = createComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val toolbar = MathToolbar()

    private fun mount(text: String, dark: Boolean = false, scale: Float = 1f) {
        compose.setContent {
            HapiTheme(darkTheme = dark, dynamicColor = false) {
                Surface {
                CompositionLocalProvider(
                    LocalDensity provides Density(LocalDensity.current.density, scale),
                    LocalTextToolbar provides toolbar,
                ) {
                    Column(Modifier.width(320.dp).verticalScroll(rememberScrollState()).testTag("body")) {
                        Markdown(text, selectable = true)
                    }
                }
                }
            }
        }
    }

    private fun awaitMath(count: Int) {
        try {
            compose.waitUntil(20_000) { compose.onAllNodesWithTag("rendered-math", useUnmergedTree = true).fetchSemanticsNodes().size == count }
        } catch (error: Throwable) {
            compose.onRoot(useUnmergedTree = true).printToLog("HapiMathTree")
            throw error
        }
    }

    @Test fun screenshotObjectiveAndInlineGreekRenderAsNativePlaceholders() {
        mount("""参数 \(\theta\)。

${'$'}${'$'} J(\theta)=\frac{\sum_k\sum_{d\in V_k}\mathrm{PnL}(d;M_k,\theta)}{\sum_k |V_k|} ${'$'}${'$'}

${'$'}${'$'} \theta^*=\arg\max_\theta J(\theta) ${'$'}${'$'}

输出 \(M_{\mathrm{full}}\) 和 \((M_{\mathrm{full}},\theta^*)\)。""")
        awaitMath(5)
        val nodes = compose.onAllNodesWithTag("rendered-math", useUnmergedTree = true).fetchSemanticsNodes()
        assertTrue(nodes.all { it.boundsInRoot.width > 0 && it.boundsInRoot.height > 0 })
        // The fraction is laid out vertically, instead of printing \frac text.
        assertTrue(nodes[1].boundsInRoot.height > nodes[0].boundsInRoot.height)
        compose.onRoot().captureToImage().asAndroidBitmap().let { bitmap ->
            File(context.filesDir, "math-rendering-screenshot.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    @Test fun selectAllKeepsTextAndSelectableLatexInLogicalOrder() {
        val formula = "\$\$\n\\frac{1}{2}\n\$\$"
        mount("Alpha beta\n\n$formula\n\nOmega \\(x_i\\).")
        awaitMath(2)
        compose.onNodeWithText("Alpha beta", substring = true).performTouchInput { longClick(Offset(10f, 18f)) }
        compose.waitUntil(5_000) { toolbar.selectAll != null }
        compose.runOnIdle { toolbar.selectAll!!.invoke() }
        compose.waitForIdle()
        val clipboard = context.getSystemService(ClipboardManager::class.java)
        compose.runOnIdle {
            clipboard.setPrimaryClip(ClipData.newPlainText("test", "not-copied"))
            toolbar.copy!!.invoke()
        }
        compose.waitUntil(5_000) { clipboard.primaryClip?.getItemAt(0)?.text?.toString() != "not-copied" }
        assertEquals("Alpha beta\n$formula\nOmega \\(x_i\\).", clipboard.primaryClip!!.getItemAt(0).text.toString())
    }

    @Test fun longDisplayFormulaScrollsAtPhoneWidth() {
        mount("\$\$ " + List(25) { "x_{$it}^2" }.joinToString(" + ") + " \$\$")
        awaitMath(1)
        val block = compose.onNodeWithTag("math-block")
        val range = block.fetchSemanticsNode().config[SemanticsProperties.HorizontalScrollAxisRange]
        assertTrue(range.maxValue() > 0)
        block.performTouchInput { swipeLeft() }
        compose.waitForIdle()
        assertTrue(block.fetchSemanticsNode().config[SemanticsProperties.HorizontalScrollAxisRange].value() > 0)
    }

    @Test fun darkLargeFontMatricesAndMathFencesRenderWithoutOverlappingFollowingText() {
        mount("""Before \(\sqrt{\alpha^2+\beta^2}\).

```math
\begin{pmatrix}a & b \\ c & d\end{pmatrix}
```

After gamma""", dark = true, scale = 1.8f)
        awaitMath(2)
        val math = compose.onNodeWithTag("math-block").getUnclippedBoundsInRoot()
        val after = compose.onNodeWithText("After gamma").getUnclippedBoundsInRoot()
        assertTrue(after.top >= math.bottom)
    }

    @Test fun unfinishedStreamingFormulaAndInvalidTexStayReadable() {
        mount("Before\n\n\$\$\n\\frac{1}{2}")
        compose.onNodeWithText("\$\$\n\\frac{1}{2}", substring = true).assertExists()
        assertEquals(0, compose.onAllNodesWithTag("rendered-math", useUnmergedTree = true).fetchSemanticsNodes().size)
    }

    @Test fun formulaItselfSupportsLongPressCopy() {
        val formula = "\$\$ \\frac{a}{b} \$\$"
        mount(formula)
        awaitMath(1)
        compose.onNodeWithTag("rendered-math", useUnmergedTree = true).performTouchInput { longClick(center) }
        compose.waitUntil(5_000) { toolbar.copy != null }
        compose.runOnIdle { toolbar.selectAll?.invoke() }
        compose.waitForIdle()
        val clipboard = context.getSystemService(ClipboardManager::class.java)
        compose.runOnIdle { toolbar.copy!!.invoke() }
        compose.waitUntil(5_000) { clipboard.primaryClip?.getItemAt(0)?.text?.toString() == formula }
        assertEquals(formula, clipboard.primaryClip!!.getItemAt(0).text.toString())
    }

    @Test fun codeWithDelimitersAndCurrencyIsNotRenderedAsMath() {
        mount("Literal `\\(x\\) \$y\$` and price \$5 or \$10.")
        compose.onNodeWithText("\\(x\\) \$y\$", substring = true).assertExists()
        assertEquals(0, compose.onAllNodesWithTag("rendered-math", useUnmergedTree = true).fetchSemanticsNodes().size)
    }
}

private class MathToolbar : TextToolbar {
    var copy: (() -> Unit)? = null
    var selectAll: (() -> Unit)? = null
    override var status: TextToolbarStatus = TextToolbarStatus.Hidden
    override fun hide() { status = TextToolbarStatus.Hidden }
    override fun showMenu(rect: Rect, onCopyRequested: (() -> Unit)?, onPasteRequested: (() -> Unit)?, onCutRequested: (() -> Unit)?, onSelectAllRequested: (() -> Unit)?) {
        copy = onCopyRequested
        selectAll = onSelectAllRequested
        status = TextToolbarStatus.Shown
    }
}
