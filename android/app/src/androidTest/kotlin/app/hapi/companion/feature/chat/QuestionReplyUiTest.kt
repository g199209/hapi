package app.hapi.companion.feature.chat

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalTextToolbar
import androidx.compose.ui.platform.TextToolbar
import androidx.compose.ui.platform.TextToolbarStatus
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import app.hapi.companion.R
import app.hapi.companion.feature.chat.blocks.UserTextBlockView
import app.hapi.companion.ui.theme.HapiTheme
import app.hapi.protocol.chat.ChatAttachment
import app.hapi.protocol.chat.UserTextBlock
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class QuestionReplyUiTest {
    @get:Rule val compose = createComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val question = "Model search 的 common optimizer 已经是 TPE；“默认 optimizer 改为 TPE”是否指 Feature climb 和 Strategy search 也一起统一？"
    private val answer = "仅 Model，其他阶段保持原设置"
    private fun envelope(q: String = question, a: String = answer) = "<send_user_message_question_reply>\n" + buildJsonArray {
        add(buildJsonObject { put("questionItemId", "[request_user_input_async,call_test,0]"); put("question", q); put("answer", a) })
    } + "\n</send_user_message_question_reply>\n"
    private fun block(text: String = envelope(), attachments: List<ChatAttachment>? = null, status: String? = null) =
        UserTextBlock("question-message", null, 0, null, text, attachments, status, null, null)

    @Test fun nativeQuestionReplyShowsReadableContextAndAnswer() {
        val message = block()
        compose.setContent { HapiTheme { Column(Modifier.width(360.dp).padding(12.dp)) { UserTextBlockView(message) } } }
        compose.onNodeWithTag("question-reply-0").assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.question_reply_title)).assertIsDisplayed()
        compose.onNodeWithText(answer).assertIsDisplayed()
        compose.onNodeWithText(question).assertExists()
        compose.onNodeWithText("questionItemId", substring = true).assertDoesNotExist()
        compose.onNodeWithText("send_user_message_question_reply", substring = true).assertDoesNotExist()
        assertEquals(envelope(), message.text)
    }

    @Test fun answerRemainsSelectableAndCopiesWithoutWireFields() {
        val toolbar = TestToolbar()
        compose.setContent { HapiTheme { CompositionLocalProvider(LocalTextToolbar provides toolbar) { UserTextBlockView(block()) } } }
        compose.onNodeWithText(answer).performTouchInput { longClick(center) }
        compose.waitUntil(5_000) { toolbar.copy != null }
        compose.runOnIdle { toolbar.selectAll!!.invoke() }
        compose.waitForIdle()
        val clipboard = context.getSystemService(ClipboardManager::class.java)
        compose.runOnIdle { clipboard.setPrimaryClip(ClipData.newPlainText("sentinel", "original")); toolbar.copy!!.invoke() }
        compose.waitUntil(5_000) { clipboard.primaryClip?.getItemAt(0)?.text?.toString() == answer }
    }

    @Test fun longQuestionExpandsAndKeepsStateAcrossRecreation() {
        val state = StateRestorationTester(compose)
        state.setContent { HapiTheme { Column(Modifier.width(320.dp).verticalScroll(rememberScrollState())) { UserTextBlockView(block(envelope(q = question.repeat(4)))) } } }
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("question-reply-expand-0").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(context.getString(R.string.question_reply_more)).performClick()
        compose.onNodeWithText(context.getString(R.string.question_reply_less)).assertExists()
        state.emulateSavedInstanceStateRestore()
        compose.onNodeWithText(context.getString(R.string.question_reply_less)).assertExists()
        compose.onNodeWithText(context.getString(R.string.question_reply_less)).performScrollTo().performClick()
        compose.onNodeWithText(context.getString(R.string.question_reply_more)).assertExists()
    }

    @Test fun oversizedReplyOpensReadableFullMessageAndKeepsAttachmentAndFailedStatus() {
        var opened: String? = null
        val inspection = object : ChatInspectionActions {
            override fun openTool(id: String) = Unit
            override fun openGroup(id: String) = Unit
            override fun openMessage(id: String) { opened = id }
        }
        val message = block(envelope(a = "答案\n".repeat(200)),
            status = "failed", attachments = listOf(ChatAttachment("file", "notes.md", "text/markdown", 42.0, "/uploads/notes.md")),
        )
        compose.setContent { HapiTheme { CompositionLocalProvider(LocalChatInspection provides inspection) {
            Column(Modifier.width(360.dp).verticalScroll(rememberScrollState())) { UserTextBlockView(message) }
        } } }
        compose.onNodeWithText("📎 notes.md").assertExists()
        compose.onNodeWithText(context.getString(R.string.chat_not_delivered)).assertExists()
        compose.onNodeWithText(context.getString(R.string.chat_view_full_message)).performScrollTo().performClick()
        compose.runOnIdle { assertEquals(message.id, opened) }
    }

    private class TestToolbar : TextToolbar {
        override var status = TextToolbarStatus.Hidden
        var copy: (() -> Unit)? = null
        var selectAll: (() -> Unit)? = null
        override fun showMenu(rect: Rect, onCopyRequested: (() -> Unit)?, onPasteRequested: (() -> Unit)?, onCutRequested: (() -> Unit)?, onSelectAllRequested: (() -> Unit)?) {
            copy = onCopyRequested; selectAll = onSelectAllRequested; status = TextToolbarStatus.Shown
        }
        override fun hide() { status = TextToolbarStatus.Hidden }
    }
}
