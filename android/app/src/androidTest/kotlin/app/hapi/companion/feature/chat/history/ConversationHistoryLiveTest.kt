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

    @Test fun forkBeforeAndCurrentUseIndependentNativeThreadsPreserveSourceAndReturnToDraft() {
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
        val boundary = sourceUsers.last().localId!!
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
        compose.waitUntil(30_000) { compose.onAllNodesWithTag("history-fork-$boundary").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("history-fork-$boundary").performScrollTo().performClick()
        compose.onNodeWithTag("history-confirm").performClick()
        compose.waitUntil(60_000) { app.appGraph.openChatSessionId.value?.let { it != sourceId } == true }
        val beforeChild = app.appGraph.openChatSessionId.value!!
        children += beforeChild; record()
        compose.waitUntil(60_000) { users(beforeChild).size == 1 }
        val before = runBlocking { graph.session.api.getSession(beforeChild) }.session
        assertNotEquals(nativeSource, before.metadata!!.codexSessionId)
        natives += before.metadata!!.codexSessionId!!; record()
        assertEquals(userText(sourceUsers.first()), userText(users(beforeChild).single()))
        assertEquals(2, users(sourceId).size)
        compose.onNodeWithTag("chat-composer-input").assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitUntil(30_000) { app.appGraph.openChatSessionId.value == sourceId }
        compose.onNodeWithTag("chat-composer-input").assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("保留原 draft")))
        compose.onNodeWithTag("chat-composer-input").performTextClearance()
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.chat_session_actions)).performClick()
        compose.onNodeWithText(compose.activity.getString(R.string.history_fork_current)).performClick()
        compose.onNodeWithTag("history-confirm").performClick()
        compose.waitUntil(60_000) { app.appGraph.openChatSessionId.value?.let { it != sourceId && it != beforeChild } == true }
        val currentChild = app.appGraph.openChatSessionId.value!!
        children += currentChild; record()
        compose.waitUntil(60_000) { users(currentChild).size == 2 }
        val current = runBlocking { graph.session.api.getSession(currentChild) }.session
        natives += current.metadata!!.codexSessionId!!; record()
        assertEquals(3, (natives + nativeSource).distinct().size)
        assertEquals(sourceUsers.map(::userText), users(currentChild).map(::userText))
        assertEquals(2, users(sourceId).size)
        compose.onNodeWithTag("chat-composer-input").assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitUntil(30_000) { app.appGraph.openChatSessionId.value == sourceId }
        compose.onNodeWithTag("chat-composer-input").assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
        record()
    }
}
