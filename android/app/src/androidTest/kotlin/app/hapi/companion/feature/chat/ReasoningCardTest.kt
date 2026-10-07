package app.hapi.companion.feature.chat

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.performClick
import app.hapi.companion.feature.chat.blocks.AgentReasoningBlockView
import app.hapi.companion.ui.theme.HapiTheme
import app.hapi.protocol.chat.AgentReasoningBlock
import org.junit.Rule
import org.junit.Test

class ReasoningCardTest {
    @get:Rule val compose = createComposeRule()

    private fun block(text: String) = AgentReasoningBlock(
        id = "codex:thread:turn:first:agent_reasoning", localId = null,
        createdAt = 1, invokedAt = null, text = text, meta = null,
    )

    @Test fun previewsLatestSummaryAndKeepsExpansionAcrossUpdatesAndRestoration() {
        val state = mutableStateOf(block("**Inspect files**\n\n**Run tests**"))
        val restoration = StateRestorationTester(compose)
        restoration.setContent { HapiTheme { AgentReasoningBlockView(state.value) } }
        compose.onNodeWithText("Run tests").assertIsDisplayed()
        compose.onNodeWithText("Inspect files").assertDoesNotExist()
        compose.onNodeWithText("Reasoning").performClick()
        compose.onNodeWithText("Inspect files").assertIsDisplayed()
        compose.runOnIdle { state.value = block("**Inspect files**\n\n**Run tests**\n\n**Verify result**") }
        compose.onNodeWithText("Verify result").assertIsDisplayed()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("Inspect files").assertIsDisplayed()
        compose.onNodeWithText("Reasoning").performClick()
        compose.onNodeWithText("Inspect files").assertDoesNotExist()
        compose.onNodeWithText("Verify result").assertIsDisplayed()
    }
}
