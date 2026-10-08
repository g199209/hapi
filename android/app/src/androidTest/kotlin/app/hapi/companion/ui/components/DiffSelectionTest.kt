package app.hapi.companion.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalTextToolbar
import androidx.compose.ui.platform.TextToolbar
import androidx.compose.ui.platform.TextToolbarStatus
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import app.hapi.companion.R
import app.hapi.companion.ui.theme.HapiTheme
import app.hapi.protocol.git.UnifiedDiffParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test

class DiffSelectionTest {
    @get:Rule val compose = createComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val toolbar = TestToolbar()
    private val patch = """--- a/example.kt
+++ b/example.kt
@@ -1,3 +1,4 @@ heading excluded
 alpha beta
-old value
+new value
+
 \t中文 emoji 👩🏽‍💻  tail
@@ -20 +21 @@ another heading
 omega end
""".replace("\\t", "\t")
    private val expected = "alpha beta\nold value\nnew value\n\n\t中文 emoji 👩🏽‍💻  tail\nomega end"

    private fun mount(compact: Boolean = false) {
        val file = UnifiedDiffParser.parse(patch).single()
        compose.setContent {
            HapiTheme {
                CompositionLocalProvider(LocalTextToolbar provides toolbar) {
                    Box(Modifier.width(360.dp).verticalScroll(rememberScrollState())) {
                        DiffView(file, Modifier.testTag("diff"), compact, compactLineLimit = 4)
                    }
                }
            }
        }
    }

    private fun beginSelection() {
        compose.onNodeWithText("alpha beta", substring = true).performTouchInput {
            longClick(Offset(8f, centerY))
        }
        compose.waitUntil(5_000) { toolbar.copy != null }
    }

    private fun copyAll(): String {
        beginSelection()
        compose.runOnIdle { assertNotNull(toolbar.selectAll); toolbar.selectAll!!.invoke() }
        compose.waitForIdle()
        return copySelection()
    }

    private fun copySelection(): String {
        val clipboard = context.getSystemService(ClipboardManager::class.java)
        compose.runOnIdle { clipboard.setPrimaryClip(ClipData.newPlainText("test", "not-copied")); toolbar.copy!!.invoke() }
        compose.waitUntil(5_000) { clipboard.primaryClip?.getItemAt(0)?.text?.toString()?.let { it != "not-copied" } == true }
        return clipboard.primaryClip!!.getItemAt(0).text.toString()
    }

    @Test fun longPressCopiesAWordWithoutLineNumbersOrMarkers() {
        mount()
        beginSelection()
        assertEquals("alpha", copySelection())
    }

    @Test fun selectAllPreservesNewlinesWhitespaceAndUnicodeButExcludesDiffChrome() {
        mount()
        assertEquals(expected, copyAll())
    }

    @Test fun compactSelectionIncludesOnlyVisibleRowsAndExpansionMakesTheRestSelectable() {
        mount(compact = true)
        assertEquals("alpha beta\nold value\nnew value", copyAll())
        compose.onNodeWithText(context.getString(R.string.diff_show_more, 4)).performClick()
        compose.waitForIdle()
        assertEquals(expected, copyAll())
    }

    @Test fun longPressDragSelectsAcrossRowsInsideTheScrollingReader() {
        mount()
        val first = compose.onNodeWithText("alpha beta", substring = true).getUnclippedBoundsInRoot()
        val last = compose.onNodeWithText("new value", substring = true).getUnclippedBoundsInRoot()
        val root = compose.onNodeWithTag("diff").getUnclippedBoundsInRoot()
        val density = context.resources.displayMetrics.density
        val start = Offset((first.left.value - root.left.value) * density + 5f, ((first.top.value + first.bottom.value) / 2 - root.top.value) * density)
        val end = Offset((last.right.value - root.left.value) * density - 1f, ((last.top.value + last.bottom.value) / 2 - root.top.value) * density)
        compose.onNodeWithTag("diff").performTouchInput {
            down(start)
            advanceEventTime(750)
            moveTo(end)
            advanceEventTime(100)
            up()
        }
        compose.waitUntil(5_000) { toolbar.copy != null }
        // Dragging to the trailing edge selects the logical line break too.
        assertEquals("alpha beta\nold value\nnew value\n", copySelection())
    }

    private class TestToolbar : TextToolbar {
        override var status = TextToolbarStatus.Hidden
        var copy: (() -> Unit)? = null
        var selectAll: (() -> Unit)? = null
        override fun showMenu(rect: Rect, onCopyRequested: (() -> Unit)?, onPasteRequested: (() -> Unit)?, onCutRequested: (() -> Unit)?, onSelectAllRequested: (() -> Unit)?) {
            copy = onCopyRequested
            selectAll = onSelectAllRequested
            status = TextToolbarStatus.Shown
        }
        override fun hide() { status = TextToolbarStatus.Hidden; copy = null; selectAll = null }
    }
}
