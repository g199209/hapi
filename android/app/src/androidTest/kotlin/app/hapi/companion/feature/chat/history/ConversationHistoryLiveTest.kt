package app.hapi.companion.feature.chat.history

import androidx.compose.ui.test.*
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import app.hapi.companion.HapiApp
import app.hapi.companion.MainActivity
import app.hapi.companion.R
import app.hapi.protocol.wire.DecryptedMessage
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

/** Opt-in. The host must supply a newly created two-turn native fixture. */
class ConversationHistoryLiveTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun listForkUsesIndependentNativeThreadAndPreservesSourceDraft() {
        val sourceId = InstrumentationRegistry.getArguments().getString("forkFixture").orEmpty()
        assumeTrue("An explicit task-owned two-turn fixture is required", sourceId.isNotBlank())
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as HapiApp
        compose.waitUntil(15_000) { app.appGraph.activeHubGraph.value != null }
        val graph = app.appGraph.activeHubGraph.value!!
        fun messages(id: String): List<DecryptedMessage> = runBlocking { graph.session.api.getMessages(id, limit = 200).messages }
        fun users(id: String) = messages(id).filter { (it.content as? JsonObject)?.get("role")?.jsonPrimitive?.content == "user" }
        fun userText(message: DecryptedMessage) = message.content.jsonObject["content"]!!.jsonObject["text"]!!.jsonPrimitive.content
        val original = runBlocking { graph.session.api.getSession(sourceId) }.session
        val sourceUsers = users(sourceId)
        assertEquals(2, sourceUsers.size)
        assertTrue(sourceUsers.all { it.content.toString().contains("HAPI_HISTORY_FIXTURE_") })
        assertTrue(original.metadata?.capabilities?.conversationHistory?.forkCurrent == true)
        assertTrue(original.metadata?.capabilities?.conversationHistory?.rewindToMessage == false)
        val nativeSource = original.metadata!!.codexSessionId!!
        val children = mutableListOf<String>()
        val natives = mutableListOf<String>()
        val result = File(app.filesDir, "conversation-history-live-results.json")
        fun record() {
            result.writeText(buildJsonObject {
                put("source", sourceId); putJsonArray("children") { children.forEach { add(it) } }
                put("sourceNativeId", nativeSource); putJsonArray("childNativeIds") { natives.forEach { add(it) } }
            }.toString())
        }
        compose.runOnUiThread { app.appGraph.pendingOpenSessionId.value = sourceId }
        compose.waitUntil(30_000) { app.appGraph.openChatSessionId.value == sourceId }
        compose.onNodeWithTag("chat-composer-input").performTextClearance()
        compose.onNodeWithTag("chat-composer-input").performTextInput("保留原 draft")
        compose.runOnUiThread {
            compose.activity.currentFocus?.let { focused ->
                compose.activity.getSystemService(android.view.inputmethod.InputMethodManager::class.java)
                    .hideSoftInputFromWindow(focused.windowToken, 0)
            }
        }
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.chat_back)).performClick()
        compose.waitUntil(30_000) { app.appGraph.openChatSessionId.value == null }
        val sourceTitle = requireNotNull(original.metadata?.name)
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.sessions_search_open)).performClick()
        compose.onNode(hasSetTextAction()).performTextInput(sourceTitle)
        compose.onNodeWithTag("session-row-$sourceId").performTouchInput { longClick(center) }
        compose.waitUntil(30_000) { compose.onAllNodesWithText(compose.activity.getString(R.string.history_fork_current)).fetchSemanticsNodes().size == 1 }
        compose.onNodeWithText(compose.activity.getString(R.string.history_fork_current)).assertIsEnabled().performClick()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("history-confirm").assertIsDisplayed().performClick()
        compose.waitUntil(60_000) { app.appGraph.openChatSessionId.value?.let { it != sourceId } == true }
        val child = app.appGraph.openChatSessionId.value!!
        children += child; record()
        compose.waitUntil(60_000) { users(child).size == 2 }
        val detail = runBlocking { graph.session.api.getSession(child) }.session
        natives += detail.metadata!!.codexSessionId!!; record()
        assertNotEquals(nativeSource, natives.single())
        assertEquals(sourceUsers.map(::userText), users(child).map(::userText))
        assertEquals(2, users(sourceId).size)
        compose.onNodeWithTag("chat-composer-input").assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.chat_session_actions)).performClick()
        compose.onNodeWithText(compose.activity.getString(R.string.history_fork_current)).assertDoesNotExist()
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.chat_back)).performClick()
        compose.waitUntil(30_000) { app.appGraph.openChatSessionId.value == null }
        compose.onNodeWithTag("session-row-$sourceId").performClick()
        compose.waitUntil(30_000) { app.appGraph.openChatSessionId.value == sourceId }
        compose.onNodeWithTag("chat-composer-input").assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("保留原 draft")))
        compose.onNodeWithTag("chat-composer-input").performTextClearance()
        record()
    }
}
