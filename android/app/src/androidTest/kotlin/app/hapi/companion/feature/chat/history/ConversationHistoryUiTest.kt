package app.hapi.companion.feature.chat.history

import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import app.hapi.companion.R
import app.hapi.companion.ui.theme.HapiTheme
import app.hapi.data.api.*
import app.hapi.protocol.chat.UserTextBlock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ConversationHistoryUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val point = UserTextBlock("hub-id", "local", 1, 1, "中文 prompt **history**", null, "sent", null, null)
    private fun context(rewind: Boolean = true, ready: Boolean = true) = HistoryContext(HistoryAvailability(true, true, rewind, setOf("local"), ready), 7)
    private fun label(id: Int) = compose.activity.getString(id)
    private open class Api : ConversationHistoryApi {
        val forks = mutableListOf<String?>()
        var rewinds = 0
        override suspend fun forkConversation(sessionId: String, messageLocalId: String?): ForkConversationResponse { forks += messageLocalId; return ForkConversationResponse("child") }
        override suspend fun rewindConversation(sessionId: String, messageLocalId: String) { rewinds++ }
    }

    @Test fun sharedCodexOffersForkWithoutRewindAndBusyButtonsStayDisabled() {
        val availability = mutableStateOf(context(rewind = false).availability)
        compose.setContent { HapiTheme { CompositionLocalProvider(LocalConversationHistory provides HistoryInteractions(availability.value, false) { _, _, _ -> }) {
            MessageHistoryActions(point)
        } } }
        compose.onNodeWithText(label(R.string.history_fork)).assertIsEnabled()
        compose.onNodeWithText(label(R.string.history_rewind)).assertDoesNotExist()
        compose.runOnIdle { availability.value = context(rewind = false, ready = false).availability }
        compose.onNodeWithText(label(R.string.history_fork)).assertIsNotEnabled()
    }

    @Test fun confirmationShowsExactBoundarySurvivesRestorationAndCancelNeverMutates() {
        val api = Api()
        val holderScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val controller = ConversationHistoryController("source", api, { context() }, {}, {}, holderScope)
        val restore = StateRestorationTester(compose)
        restore.setContent {
            val state by controller.state.collectAsState()
            HapiTheme { CompositionLocalProvider(LocalConversationHistory provides HistoryInteractions(context().availability, state.pending, controller::request)) {
                MessageHistoryActions(point)
                ConversationHistoryDialog(state, controller::confirm, controller::confirmFallback, controller::dismiss)
            } }
        }
        compose.onNodeWithText(label(R.string.history_rewind)).performClick()
        compose.onNodeWithText(point.text).assertIsDisplayed()
        // The holder-owned controller remains authoritative across saved-state restoration.
        restore.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("history-cancel").performClick()
        compose.runOnIdle { assertEquals(0, api.rewinds); assertTrue(api.forks.isEmpty()) }
        holderScope.cancel()
    }

    @Test fun pendingRewindCannotBeSubmittedTwiceAndSuccessRefreshesOnce() {
        val gate = CompletableDeferred<Unit>()
        var refreshes = 0
        val api = object : Api() { override suspend fun rewindConversation(sessionId: String, messageLocalId: String) { rewinds++; gate.await() } }
        compose.setContent {
            val scope = rememberCoroutineScope()
            val controller = remember { ConversationHistoryController("source", api, { context() }, { refreshes++ }, {}, scope) }
            val state by controller.state.collectAsState()
            HapiTheme { CompositionLocalProvider(LocalConversationHistory provides HistoryInteractions(context().availability, state.pending, controller::request)) {
                MessageHistoryActions(point); ConversationHistoryDialog(state, controller::confirm, controller::confirmFallback, controller::dismiss)
            } }
        }
        compose.onNodeWithText(label(R.string.history_rewind)).performClick()
        compose.onNodeWithTag("history-confirm").performClick()
        compose.onNodeWithTag("history-confirm").assertIsNotEnabled()
        compose.onNodeWithTag("history-cancel").assertIsNotEnabled()
        compose.runOnIdle { assertEquals(1, api.rewinds); gate.complete(Unit) }
        compose.waitUntil { refreshes == 1 }
        compose.onNodeWithTag("history-confirm").assertDoesNotExist()
    }

    @Test fun ambiguousRewindNeedsExplicitForkConfirmation() {
        val api = object : Api() { override suspend fun rewindConversation(sessionId: String, messageLocalId: String) {
            rewinds++; throw ApiError(409, "ambiguous_native_boundary_fork_safe")
        } }
        compose.setContent {
            val scope = rememberCoroutineScope()
            val controller = remember { ConversationHistoryController("source", api, { context() }, {}, {}, scope) }
            val state by controller.state.collectAsState()
            HapiTheme { CompositionLocalProvider(LocalConversationHistory provides HistoryInteractions(context().availability, state.pending, controller::request)) {
                MessageHistoryActions(point); ConversationHistoryDialog(state, controller::confirm, controller::confirmFallback, controller::dismiss)
            } }
        }
        compose.onNodeWithText(label(R.string.history_rewind)).performClick()
        compose.onNodeWithTag("history-confirm").performClick()
        compose.onNodeWithText(label(R.string.history_fallback_title)).assertIsDisplayed()
        compose.runOnIdle { assertTrue(api.forks.isEmpty()) }
        compose.onNodeWithTag("history-confirm").performClick()
        compose.runOnIdle { assertEquals(listOf("local"), api.forks); assertEquals(1, api.rewinds) }
    }
}
