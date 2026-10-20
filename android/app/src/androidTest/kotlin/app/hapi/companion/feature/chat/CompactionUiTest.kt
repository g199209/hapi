package app.hapi.companion.feature.chat

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import app.hapi.companion.R
import app.hapi.companion.feature.chat.composer.QueuedMessagesBar
import app.hapi.companion.ui.theme.HapiTheme
import org.junit.Rule
import org.junit.Test

class CompactionUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun compactionShowsWaitingWithoutAFalseDeliveryErrorOrRetry() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val row = mutableStateOf(QueuedRowUi("server", "local", "Follow up question", emptyList(), null,
            canAct = true, canSteer = false, indeterminate = true, waitingForCompaction = true))
        compose.setContent { HapiTheme {
            Column(Modifier.width(360.dp)) {
                ChatTitle(ChatHeaderUi("Session", "Codex · quantlab2", active = true, thinking = true,
                    compacting = row.value.waitingForCompaction), false, {})
                QueuedMessagesBar(listOf(row.value), {}, {}, {}, {})
            }
        } }
        compose.onNodeWithText(context.getString(R.string.chat_status_compacting)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.chat_compaction_waiting)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.chat_delivery_unknown)).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.chat_delivery_retry)).assertDoesNotExist()
        compose.runOnIdle { row.value = row.value.copy(waitingForCompaction = false) }
        compose.onNodeWithText(context.getString(R.string.chat_status_working)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.chat_delivery_unknown)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.chat_delivery_retry)).assertIsDisplayed()
    }
}
