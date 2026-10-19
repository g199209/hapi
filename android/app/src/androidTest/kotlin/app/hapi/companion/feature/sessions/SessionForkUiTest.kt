package app.hapi.companion.feature.sessions

import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import app.hapi.companion.R
import app.hapi.companion.feature.chat.SessionOverflowMenu
import app.hapi.companion.feature.chat.history.ConversationHistoryDialog
import app.hapi.companion.ui.theme.HapiTheme
import app.hapi.protocol.wire.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class SessionForkUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private fun label(id: Int) = compose.activity.getString(id)
    private val row = SessionRowUi(SessionSummary("source", active = true), "Source title", null, null, "codex", false)

    @Test fun listMenuShowsOneForkEntryAndDisablesItUntilReady() {
        val enabled = mutableStateOf(false); var calls = 0
        compose.setContent { HapiTheme { SessionActionsSheet(
            row, {}, {}, {}, {}, {}, {}, {}, {}, onFork = { calls++ }, forkEnabled = enabled.value,
        ) } }
        compose.onNodeWithText(label(R.string.history_fork_current)).assertIsNotEnabled()
        compose.runOnIdle { enabled.value = true }
        compose.onNodeWithText(label(R.string.history_fork_current)).assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(1, calls) }
    }

    @Test fun chatOverflowHasNoForkEntry() {
        compose.setContent { HapiTheme { SessionOverflowMenu(true, {}, {}, {}) } }
        compose.onNodeWithContentDescription(label(R.string.chat_session_actions)).performClick()
        compose.onNodeWithText(label(R.string.history_fork_current)).assertDoesNotExist()
        compose.onNodeWithText(label(R.string.chat_open_files)).assertExists()
    }

    @Test fun listConfirmationSurvivesRecreationAndCancelDoesNotFork() {
        var forks = 0
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val api = object : SessionForkGateway {
            override suspend fun load(sessionId: String) = Session(sessionId, "default", 1, 1, 1, active = true,
                metadataVersion = 1, agentStateVersion = 1, thinking = false, thinkingAt = 0,
                metadata = SessionMetadata("/project", "host", capabilities = SessionCapabilities(
                    conversationHistory = ConversationHistoryCapabilities(forkCurrent = true))))
            override suspend fun fork(sessionId: String): String { forks++; return "child" }
        }
        val controller = SessionForkController(api, scope, {}, onLoadError = { _, _ -> })
        val restore = StateRestorationTester(compose)
        restore.setContent { val state by controller.state.collectAsState(); HapiTheme { ConversationHistoryDialog(state.dialog, controller::confirm, controller::dismiss) } }
        compose.runOnIdle { controller.select("source", row.title) }
        compose.waitUntil { controller.state.value.ready }
        compose.runOnIdle { controller.request() }
        compose.onNodeWithText(row.title).assertExists()
        restore.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("history-cancel").performClick()
        compose.runOnIdle { assertEquals(0, forks) }
        scope.cancel()
    }
}
