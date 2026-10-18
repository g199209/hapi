package app.hapi.companion.feature.chat.annotations

import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.platform.app.InstrumentationRegistry
import app.hapi.companion.HapiApp
import app.hapi.companion.MainActivity
import app.hapi.companion.R
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

/** Opt-in real Hub/Codex test, only against an explicitly supplied task fixture. */
class ResponseAnnotationLiveTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Test fun selectionCommentAndRequestReachNativeCodexExactlyOnce() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val id = InstrumentationRegistry.getArguments().getString("annotationFixture").orEmpty()
        assumeTrue("An explicit task-owned fixture is required", id.isNotBlank())
        val app = instrumentation.targetContext.applicationContext as HapiApp
        compose.waitUntil(15_000) { app.appGraph.activeHubGraph.value != null }
        val graph = app.appGraph.activeHubGraph.value!!
        fun users() = runBlocking { graph.session.api.getMessages(id, limit = 100).messages }.filter { it.content.jsonObject["role"]?.jsonPrimitive?.content == "user" }
        val before = users()
        assertEquals(1, before.size)
        assertTrue(before.single().content.toString().contains("HAPI_ANNOTATION_SOURCE"))
        compose.runOnUiThread { app.appGraph.pendingOpenSessionId.value = id }
        compose.waitUntil(30_000) { app.appGraph.openChatSessionId.value == id }
        compose.waitUntil(30_000) { compose.onAllNodesWithText("中文 gamma", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        val node = compose.onNodeWithText("中文 gamma", useUnmergedTree = true)
        val layouts = mutableListOf<TextLayoutResult>()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        node.performTouchInput { longClick(layouts.single().getBoundingBox(5).center) }
        val automation = instrumentation.uiAutomation
        automation.serviceInfo = automation.serviceInfo.apply { flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS }
        var action: AccessibilityNodeInfo? = null
        compose.waitUntil(5_000) {
            val roots = automation.windows.mapNotNull { it.root } + listOfNotNull(automation.rootInActiveWindow)
            action = roots.firstNotNullOfOrNull { it.findAccessibilityNodeInfosByText(compose.activity.getString(R.string.annotation_add)).firstOrNull() }
            action != null
        }
        val bounds = android.graphics.Rect()
        action!!.getBoundsInScreen(bounds)
        val now = android.os.SystemClock.uptimeMillis()
        for (eventAction in listOf(android.view.MotionEvent.ACTION_DOWN, android.view.MotionEvent.ACTION_UP)) {
            val event = android.view.MotionEvent.obtain(now, android.os.SystemClock.uptimeMillis(), eventAction, bounds.exactCenterX(), bounds.exactCenterY(), 0)
            event.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
            assertTrue(automation.injectInputEvent(event, true)); event.recycle()
        }
        compose.onNodeWithTag("annotation-comment").performTextInput("请只回复 HAPI_ANNOTATION_OK")
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("annotation-comment").assertTextContains("请只回复 HAPI_ANNOTATION_OK")
        compose.onNodeWithTag("annotation-confirm").performClick()
        compose.onNodeWithTag("annotation-tray").assertExists()
        compose.onNodeWithTag("chat-composer-input").performTextClearance()
        compose.onNodeWithTag("chat-composer-input").performTextInput("额外请求：遵循 comment。")
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.chat_composer_send)).performClick()
        compose.waitUntil(60_000) { users().size == 2 }
        val user = users().last()
        val text = user.content.jsonObject["content"]!!.jsonObject["text"]!!.jsonPrimitive.content
        val prompt = ResponseAnnotations.parse(text)!!
        assertEquals("额外请求：遵循 comment。", prompt.request)
        assertEquals("gamma", prompt.annotations.single().text)
        assertEquals("请只回复 HAPI_ANNOTATION_OK", prompt.annotations.single().annotation)
        assertTrue(prompt.annotations.single().source!!.messageId.startsWith("msg_"))
        compose.waitUntil(90_000) { compose.onAllNodesWithText("HAPI_ANNOTATION_OK", substring = true, useUnmergedTree = true).fetchSemanticsNodes().any { !it.config.toString().contains("请只回复") } }
        assertEquals(2, users().size)
        compose.onNodeWithTag("annotation-tray").assertDoesNotExist()
        File(app.filesDir, "response-annotation-live-results.json").writeText(buildJsonObject { put("sessionId", id); put("localId", user.localId); put("sentText", text); put("userMessages", 2) }.toString())
    }
}
