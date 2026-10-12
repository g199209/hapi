package app.hapi.companion.feature.chat

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import app.hapi.companion.R
import app.hapi.companion.feature.chat.blocks.AgentTextBlockView
import app.hapi.companion.ui.markdown.LocalMarkdownLinkHandler
import app.hapi.companion.ui.markdown.Markdown
import app.hapi.companion.ui.markdown.MarkdownLinkHandler
import app.hapi.companion.ui.theme.HapiTheme
import app.hapi.protocol.chat.AgentTextBlock
import app.hapi.protocol.markdown.HrefDecision
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class AgentTextSelectionTest {
    @get:Rule val compose = createComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val toolbar = TestToolbar()
    private val source = """Alpha beta

中文 **gamma**

1. item one
2. item two

> quoted text

```text
val x = 1
val y = 2
```

| Key | Value |
| --- | --- |
| K | V |

Last line."""
    private val expected = "Alpha beta\n中文 gamma\n1. item one\n2. item two\nquoted text\nval x = 1\nval y = 2\nKey Value\nK V\nLast line."

    private fun block(text: String, id: String = "agent") = AgentTextBlock(id = id, localId = null, createdAt = 0, invokedAt = null, text = text, meta = null)
    private fun mount(text: String = source) {
        compose.setContent {
            HapiTheme {
                CompositionLocalProvider(LocalTextToolbar provides toolbar) {
                    Column(Modifier.width(360.dp).verticalScroll(rememberScrollState())) {
                        AgentTextBlockView(block(text), Modifier.testTag("agent"))
                        AgentTextBlockView(block("Neighbor reply excluded", "other"))
                    }
                }
            }
        }
    }

    private fun beginSelection(label: String = "Alpha beta") {
        compose.onNodeWithText(label, substring = true).performTouchInput { longClick(Offset(10f, 18f)) }
        compose.waitUntil(5_000) { toolbar.copy != null }
    }

    private fun copySelection(): String {
        val clipboard = context.getSystemService(ClipboardManager::class.java)
        compose.runOnIdle { clipboard.setPrimaryClip(ClipData.newPlainText("test", "not-copied")); toolbar.copy!!.invoke() }
        compose.waitUntil(5_000) { clipboard.primaryClip?.getItemAt(0)?.text?.toString()?.let { it != "not-copied" } == true }
        return clipboard.primaryClip!!.getItemAt(0).text.toString()
    }

    @Test fun ordinaryReplySupportsLongPressWordCopy() {
        mount()
        beginSelection()
        assertEquals("Alpha", copySelection())
    }

    @Test fun laterParagraphFirstLineRemainsSelectableBelowALinkParagraph() {
        mount("[Alpha beta](https://example.com)\n\nGamma delta")
        beginSelection("Gamma delta")
        assertEquals("Gamma", copySelection())
    }

    @Test fun selectAllCopiesThisReplyWithBlockSeparatorsAndExcludesCodeHeaderActions() {
        mount()
        beginSelection()
        compose.runOnIdle { assertNotNull(toolbar.selectAll); toolbar.selectAll!!.invoke() }
        compose.waitForIdle()
        assertEquals(expected, copySelection())
    }

    @Test fun dragAcrossParagraphsPreservesTheirSeparation() {
        mount("Alpha beta\n\nGamma delta")
        val first = compose.onNodeWithText("Alpha beta", substring = true).getUnclippedBoundsInRoot()
        val last = compose.onNodeWithText("Gamma delta").getUnclippedBoundsInRoot()
        val root = compose.onNodeWithTag("agent").getUnclippedBoundsInRoot()
        val density = context.resources.displayMetrics.density
        val start = Offset((first.left.value - root.left.value) * density + 8, (first.top.value - root.top.value) * density + 18)
        val end = Offset((last.right.value - root.left.value) * density - 2, (last.top.value - root.top.value) * density + 18)
        compose.onNodeWithTag("agent").performTouchInput {
            down(start); advanceEventTime(750); moveTo(end); advanceEventTime(100); up()
        }
        compose.waitUntil(5_000) { toolbar.copy != null }
        assertEquals("Alpha beta\nGamma delta", copySelection())
    }

    @Test fun codeHeaderCopyStillCopiesOnlyItsOriginalCode() {
        mount()
        compose.onNodeWithContentDescription(context.getString(R.string.chat_copy_full_content)).performClick()
        compose.runOnIdle {
            assertEquals("val x = 1\nval y = 2", context.getSystemService(ClipboardManager::class.java).primaryClip!!.getItemAt(0).text.toString())
        }
    }

    @Test fun linksStillOpenOnTapAndCanBeSelectedOnLongPress() {
        var opened = 0
        val handler = object : MarkdownLinkHandler {
            override fun onFilePath(path: String, line: Int?) = Unit
            override fun onUrl(url: String, decision: HrefDecision) { opened++ }
        }
        compose.setContent {
            HapiTheme {
                CompositionLocalProvider(LocalTextToolbar provides toolbar, LocalMarkdownLinkHandler provides handler) {
                    AgentTextBlockView(block("Visit [docs](https://example.com) now."))
                }
            }
        }
        val node = compose.onNodeWithText("Visit docs now.", useUnmergedTree = true)
        val layouts = mutableListOf<TextLayoutResult>()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val point = layouts.single().getBoundingBox(7).center
        node.performTouchInput { click(point) }
        compose.runOnIdle { assertEquals(1, opened) }
        node.performTouchInput { longClick(point) }
        compose.waitUntil(5_000) { toolbar.copy != null }
        assertEquals("docs", copySelection())
        compose.runOnIdle { assertEquals(1, opened) }
    }

    @Test fun enablingSelectionKeepsRenderedHeightAtNormalAndLargeFonts() {
        val sample = "# Heading\n\nParagraph one\n\n1. first\n2. second\n\n> Quote\n\n```text\ncode\n```\n\n| A | B |\n| --- | --- |\n| C | D |"
        val samples = listOf(sample, "# Heading\n\nTail", "Paragraph one\n\nTail", "1. first\n2. second\n\nTail", "> Quote\n\nTail", "```text\ncode\n```\n\nTail", "| A | B |\n| --- | --- |\n| C | D |\n\nTail")
        compose.setContent {
            HapiTheme {
                Column(Modifier.width(360.dp).verticalScroll(rememberScrollState())) {
                    for (scale in listOf(1f, 2f)) {
                        CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, scale)) {
                            samples.forEachIndexed { index, text ->
                                Markdown(text, Modifier.testTag("normal-$scale-$index"))
                                Markdown(text, Modifier.testTag("selected-$scale-$index"), selectable = true)
                            }
                        }
                    }
                }
            }
        }
        val differences = mutableListOf<String>()
        for (scale in listOf(1f, 2f)) {
            samples.indices.forEach { index ->
                val normal = compose.onNodeWithTag("normal-$scale-$index").getUnclippedBoundsInRoot()
                val selected = compose.onNodeWithTag("selected-$scale-$index").getUnclippedBoundsInRoot()
                val oldHeight = (normal.bottom - normal.top).value
                val newHeight = (selected.bottom - selected.top).value
                if (kotlin.math.abs(oldHeight - newHeight) > 2f) differences += "scale=$scale sample=$index: $oldHeight -> $newHeight"
            }
        }
        assertTrue("Selection must preserve layout: ${differences.joinToString()}", differences.isEmpty())
    }

    private class TestToolbar : TextToolbar {
        override var status = TextToolbarStatus.Hidden
        var copy: (() -> Unit)? = null
        var selectAll: (() -> Unit)? = null
        override fun showMenu(rect: Rect, onCopyRequested: (() -> Unit)?, onPasteRequested: (() -> Unit)?, onCutRequested: (() -> Unit)?, onSelectAllRequested: (() -> Unit)?) {
            copy=onCopyRequested;selectAll=onSelectAllRequested;status=TextToolbarStatus.Shown
        }
        override fun hide() { status=TextToolbarStatus.Hidden;copy=null;selectAll=null }
    }
}
