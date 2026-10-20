package app.hapi.companion.feature.chat

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import app.hapi.companion.ui.theme.HapiTheme
import app.hapi.protocol.wire.CodexRestoreProgress
import org.junit.Rule
import org.junit.Test

class SessionRestoreUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun readingUsesActualCountsAndSwitchesToKnownProgressThenClears() {
        val state = mutableStateOf<CodexRestoreProgress?>(CodexRestoreProgress("history", 0, 100))
        compose.setContent { HapiTheme {
            Column(Modifier.width(360.dp)) {
                ChatTitle(ChatHeaderUi("Session", "Codex · quantlab2", active = true, thinking = false,
                    restoring = state.value != null), false, {})
                state.value?.let { SessionRestoreBanner(it) }
            }
        } }
        compose.onNodeWithTag("restore-indeterminate").assertIsDisplayed()
        compose.onNodeWithTag("restore-progress").assertDoesNotExist()
        compose.onNodeWithText("100", substring = true).assertIsDisplayed()
        compose.runOnIdle { state.value = CodexRestoreProgress("projecting", 0, 1200, 5975) }
        compose.onNodeWithTag("restore-progress").assertIsDisplayed()
        compose.onNodeWithText("1200 / 5975").assertIsDisplayed()
        compose.runOnIdle { state.value = CodexRestoreProgress("syncing", 0) }
        compose.onNodeWithTag("restore-indeterminate").assertIsDisplayed()
        compose.runOnIdle { state.value = null }
        compose.onNodeWithTag("session-restore").assertDoesNotExist()
    }

    @Test fun emptyChildSetKeepsAnIndeterminateIndicator() {
        compose.setContent { HapiTheme { SessionRestoreBanner(CodexRestoreProgress("children", 0, 0, 0)) } }
        compose.onNodeWithTag("restore-indeterminate").assertIsDisplayed()
        compose.onNodeWithTag("restore-progress").assertDoesNotExist()
    }
}
