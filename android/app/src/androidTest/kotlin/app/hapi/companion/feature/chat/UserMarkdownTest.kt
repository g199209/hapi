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
import androidx.compose.ui.platform.LocalTextToolbar
import androidx.compose.ui.platform.TextToolbar
import androidx.compose.ui.platform.TextToolbarStatus
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import app.hapi.companion.R
import app.hapi.companion.feature.chat.blocks.AgentTextBlockView
import app.hapi.companion.feature.chat.blocks.UserTextBlockView
import app.hapi.companion.ui.markdown.LocalMarkdownLinkHandler
import app.hapi.companion.ui.markdown.LocalMarkdownRenderCache
import app.hapi.companion.ui.markdown.MarkdownLinkHandler
import app.hapi.companion.ui.markdown.MarkdownRenderCache
import app.hapi.companion.ui.theme.HapiTheme
import app.hapi.protocol.chat.AgentTextBlock
import app.hapi.protocol.chat.ChatAttachment
import app.hapi.protocol.chat.UserTextBlock
import app.hapi.protocol.markdown.HrefDecision
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class UserMarkdownTest {
    @get:Rule val compose = createComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val toolbar = TestToolbar()
    private val runUrl = "http://10.42.42.8:17391/runs/127e9f70-2667-4348-8a5f-d10a95fb36f0"

    private fun block(text: String, attachments: List<ChatAttachment>? = null) = UserTextBlock(
        id = "user", localId = null, createdAt = 0, invokedAt = null,
        text = text, attachments = attachments, status = null, originalText = null, meta = null,
    )

    @Test fun explicitUrlIsRenderedOnceAndDispatchesItsDestination() {
        var opened: String? = null
        val source = "正确的是这个 [$runUrl]($runUrl)"
        val handler = object : MarkdownLinkHandler {
            override fun onFilePath(path: String, line: Int?) = Unit
            override fun onUrl(url: String, decision: HrefDecision) { opened = url }
        }
        compose.setContent {
            HapiTheme {
                CompositionLocalProvider(LocalMarkdownLinkHandler provides handler) {
                    Column(Modifier.width(360.dp)) { UserTextBlockView(block(source)) }
                }
            }
        }
        val node = compose.onNodeWithText("正确的是这个 $runUrl", useUnmergedTree = true)
        node.assertIsDisplayed()
        compose.onNodeWithText(source).assertDoesNotExist()
        val layouts = mutableListOf<TextLayoutResult>()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val offset = "正确的是这个 ".length + 7
        node.performTouchInput { click(layouts.single().getBoundingBox(offset).center) }
        compose.runOnIdle { assertEquals(runUrl, opened) }
    }

    @Test fun formattedBubbleCopiesRenderedBodyWithEnteredLineBreaksAndExcludesAttachments() {
        val source = "# Title\n\nAlpha  **beta**\nLine two with `code`\n\n1. first\n2. second\n\n```text\nval x = 1\nval y = 2\n```"
        val expected = "Title\nAlpha  beta\nLine two with code\n1. first\n2. second\nval x = 1\nval y = 2"
        val message = block(source, listOf(ChatAttachment("file", "notes.md", "text/markdown", 42.0, "/uploads/notes.md")))
        compose.setContent {
            HapiTheme {
                CompositionLocalProvider(LocalTextToolbar provides toolbar) {
                    Column(Modifier.width(360.dp).verticalScroll(rememberScrollState())) { UserTextBlockView(message) }
                }
            }
        }
        compose.onNodeWithText("Alpha  beta\nLine two with code", substring = true).assertExists()
        compose.onNodeWithText("📎 notes.md").assertExists()
        compose.onNodeWithText("Title", substring = true).performTouchInput { longClick(Offset(10f, 18f)) }
        compose.waitUntil(5_000) { toolbar.copy != null }
        compose.runOnIdle { toolbar.selectAll!!.invoke() }
        compose.waitForIdle()
        assertEquals(expected, copySelection())
        assertEquals(source, message.text)
    }

    @Test fun userAndAgentKeepSeparateSoftBreakBehaviorWithTheSameCachedDocument() {
        val source = "Alpha\nBeta"
        val cache = MarkdownRenderCache().apply { document(source) }
        val parsed = cache.cached(source)
        compose.setContent {
            HapiTheme {
                CompositionLocalProvider(LocalMarkdownRenderCache provides cache) {
                    Column(Modifier.width(360.dp)) {
                        UserTextBlockView(block(source))
                        AgentTextBlockView(AgentTextBlock(id = "agent", localId = null, createdAt = 0, invokedAt = null, text = source, meta = null))
                    }
                }
            }
        }
        compose.onNodeWithText("Alpha\nBeta").assertIsDisplayed()
        compose.onNodeWithText("Alpha Beta").assertIsDisplayed()
        assertSame(parsed, cache.cached(source))
    }

    @Test fun longMessageKeepsBoundedMarkdownPreviewAndOpensItsOriginalReader() {
        val source = "**Alpha**\n\n" + "Line\n".repeat(130)
        val message = block(source)
        var opened: String? = null
        val actions = object : ChatInspectionActions {
            override fun openTool(id: String) = Unit
            override fun openGroup(id: String) = Unit
            override fun openMessage(id: String) { opened = id }
        }
        compose.setContent {
            HapiTheme {
                CompositionLocalProvider(LocalChatInspection provides actions) {
                    Column(Modifier.width(360.dp).verticalScroll(rememberScrollState())) { UserTextBlockView(message) }
                }
            }
        }
        compose.onNodeWithText("Alpha", substring = true).assertExists()
        compose.onNodeWithText(source).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.chat_view_full_message)).performScrollTo().performClick()
        compose.runOnIdle { assertEquals("user", opened) }
        assertTrue(messagePreview(source).end < source.length)
        assertEquals(source, message.text)
    }

    private fun copySelection(): String {
        val clipboard = context.getSystemService(ClipboardManager::class.java)
        compose.runOnIdle {
            clipboard.setPrimaryClip(ClipData.newPlainText("test", "not-copied"))
            toolbar.copy!!.invoke()
        }
        compose.waitUntil(5_000) { clipboard.primaryClip?.getItemAt(0)?.text?.toString()?.let { it != "not-copied" } == true }
        return clipboard.primaryClip!!.getItemAt(0).text.toString()
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
        override fun hide() {
            status = TextToolbarStatus.Hidden
            copy = null
            selectAll = null
        }
    }
}
