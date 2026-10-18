package app.hapi.companion.feature.chat.annotations

import android.content.ClipData
import android.content.ClipboardManager
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.platform.app.InstrumentationRegistry
import app.hapi.companion.R
import app.hapi.companion.feature.chat.ChatInteractions
import app.hapi.companion.feature.chat.ComposerUiState
import app.hapi.companion.feature.chat.LocalChatInteractions
import app.hapi.companion.feature.chat.blocks.AgentTextBlockView
import app.hapi.companion.feature.chat.blocks.UserTextBlockView
import app.hapi.companion.feature.chat.composer.ChatComposer
import app.hapi.companion.ui.theme.HapiTheme
import app.hapi.protocol.chat.AgentTextBlock
import app.hapi.protocol.chat.UserTextBlock
import androidx.compose.foundation.text.input.TextFieldState
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ResponseAnnotationUiTest {
    @get:Rule val compose = createComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val drafts = AnnotationDrafts {}
    private val source = "Alpha beta\n\n中文 **gamma**\n\n```text\nval x = 1\n```"
    private fun block(text: String = source) = AgentTextBlock("msg_source", null, 0, null, text = text, meta = null)
    private fun mount() {
        compose.setContent { HapiTheme {
            CompositionLocalProvider(LocalChatInteractions provides ChatInteractions("codex", emptyMap(), { _, _ -> }, {}, commentOnResponse = drafts::request)) {
                AgentTextBlockView(block())
                AnnotationDialog(drafts)
            }
        } }
    }
    private fun selectGamma() {
        val node = compose.onNodeWithText("中文 gamma\n", useUnmergedTree = true)
        val layouts = mutableListOf<TextLayoutResult>()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val point = layouts.single().getBoundingBox(5).center
        node.performTouchInput { longClick(point) }
    }
    private fun nativeClick(text: String) {
        val automation = instrumentation.uiAutomation
        automation.serviceInfo = automation.serviceInfo.apply { flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS }
        var node: AccessibilityNodeInfo? = null
        compose.waitUntil(5_000) {
            val roots = automation.windows.mapNotNull { it.root } + listOfNotNull(automation.rootInActiveWindow)
            node = roots.firstNotNullOfOrNull { it.findAccessibilityNodeInfosByText(text).firstOrNull() }
            node != null
        }
        val bounds = android.graphics.Rect()
        node!!.getBoundsInScreen(bounds)
        val now = android.os.SystemClock.uptimeMillis()
        for (action in listOf(android.view.MotionEvent.ACTION_DOWN, android.view.MotionEvent.ACTION_UP)) {
            val event = android.view.MotionEvent.obtain(now, android.os.SystemClock.uptimeMillis(), action, bounds.exactCenterX(), bounds.exactCenterY(), 0)
            event.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
            assertTrue(automation.injectInputEvent(event, true)); event.recycle()
        }
        compose.waitForIdle()
    }
    @Test fun nativeCommentCapturesSelectionWithoutChangingClipboard() {
        mount()
        compose.runOnIdle { context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("sentinel", "original clipboard")) }
        selectGamma(); nativeClick(context.getString(R.string.annotation_add))
        compose.waitUntil { drafts.edit.value != null }
        compose.runOnIdle {
            assertEquals("gamma", drafts.edit.value!!.value.text)
            assertEquals("msg_source", drafts.edit.value!!.value.source!!.messageId)
            assertEquals("original clipboard", context.getSystemService(ClipboardManager::class.java).primaryClip!!.getItemAt(0).text.toString())
        }
        compose.onNodeWithTag("annotation-comment").performTextInput("解释这部分")
        compose.onNodeWithTag("annotation-confirm").performClick()
        compose.runOnIdle { assertEquals("解释这部分", drafts.items.value.single().value.annotation) }
    }
    private fun mountFormula() {
        compose.setContent { HapiTheme {
            CompositionLocalProvider(LocalChatInteractions provides ChatInteractions("codex", emptyMap(), { _, _ -> }, {}, commentOnResponse = drafts::request)) {
                Column(Modifier.padding(top = 48.dp)) {
                    AgentTextBlockView(block("Math $" + "x^2" + "$"))
                    AnnotationDialog(drafts)
                }
            }
        } }
        compose.waitUntil(20_000) { compose.onAllNodesWithTag("rendered-math", useUnmergedTree = true).fetchSemanticsNodes().size == 1 }
        compose.onNodeWithTag("rendered-math", useUnmergedTree = true).performTouchInput { longClick(center) }
    }
    @Test fun formulaCommentUsesCompleteTexWithoutClipboardTokens() {
        mountFormula()
        compose.onNodeWithText(context.getString(R.string.annotation_add)).performClick()
        compose.waitUntil { drafts.edit.value != null }
        compose.runOnIdle { assertEquals("$" + "x^2" + "$", drafts.edit.value!!.value.text) }
    }
    @Test fun formulaCopyRemainsAvailableWithCommentMenu() {
        mountFormula()
        compose.onNodeWithText(context.getString(android.R.string.copy)).performClick()
        val clipboard = context.getSystemService(ClipboardManager::class.java)
        compose.waitUntil { clipboard.primaryClip?.getItemAt(0)?.text?.toString() == "$" + "x^2" + "$" }
        compose.runOnIdle { assertNull(drafts.edit.value) }
    }
    @Test fun nativeSelectAllCommentsAcrossMarkdownBlocks() {
        mount(); selectGamma()
        nativeClick(context.getString(android.R.string.selectAll))
        nativeClick(context.getString(R.string.annotation_add))
        compose.waitUntil { drafts.edit.value != null }
        compose.runOnIdle {
            val quote = drafts.edit.value!!.value.text
            assertTrue(quote.startsWith("Alpha beta\n中文 gamma"))
            assertTrue(quote.contains("val x = 1"))
            assertFalse(quote.contains("**")); assertFalse(quote.contains("```"))
        }
    }
    @Test fun dialogRecreationKeepsChineseEditingBuffer() {
        val restoration = StateRestorationTester(compose)
        drafts.request("quoted text", "msg_source")
        restoration.setContent { HapiTheme { AnnotationDialog(drafts) } }
        compose.onNodeWithTag("annotation-comment").performTextInput("候选词已经上屏")
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("annotation-comment").assertTextContains("候选词已经上屏")
        compose.onNodeWithTag("annotation-confirm").performClick()
        compose.runOnIdle { assertEquals("候选词已经上屏", drafts.items.value.single().value.annotation) }
    }
    @Test fun trayEditsRemovesAndSupportsAnnotationOnlySend() {
        drafts.request("first", null); drafts.confirm()
        drafts.request("second", null); drafts.confirm()
        var sends = 0
        compose.setContent { HapiTheme {
            Column {
                ChatComposer(ComposerUiState("", false, true, hasAnnotations = true), TextFieldState(), {}, { sends++ }, {}, {}, annotations = drafts)
                AnnotationDialog(drafts)
            }
        } }
        compose.onNodeWithTag("annotation-edit-0").performClick()
        compose.onNodeWithTag("annotation-comment").performTextInput("updated")
        compose.onNodeWithTag("annotation-confirm").performClick()
        compose.runOnIdle { assertEquals("updated", drafts.items.value.first().value.annotation) }
        compose.onNodeWithTag("annotation-remove-1").performClick()
        compose.onNodeWithContentDescription(context.getString(R.string.chat_composer_send)).assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(1, sends); assertEquals(1, drafts.items.value.size) }
    }
    @Test fun importedCodexAnnotationsRenderAsCards() {
        val text = ResponseAnnotations.encode("extra request", listOf(ResponseAnnotation("selected text", "my comment", AnnotationSource("msg_source", 0, 13))))
        compose.setContent { HapiTheme { UserTextBlockView(UserTextBlock("user", null, 0, null, text, null, null, null, null)) } }
        compose.onNodeWithText("〔1〕 selected text").assertExists()
        compose.onNodeWithText("my comment").assertExists()
        compose.onNodeWithText("extra request").assertExists()
        compose.onNodeWithText("# Response annotations:", substring = true).assertDoesNotExist()
    }
}
