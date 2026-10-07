package app.hapi.companion.feature.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import app.hapi.companion.ui.theme.HapiTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ChatHeaderStatusTest {
    @get:Rule val compose = createComposeRule()

    @Test fun statusStaysOnTheSubtitleLineAndUpdatesWithoutResizingTheHeader() {
        val header = mutableStateOf(ChatHeaderUi("Session", "Codex · GMF-PC", active = true, thinking = true))
        val reconnecting = mutableStateOf(false)
        var retries = 0
        compose.setContent { HapiTheme { Box(Modifier.width(230.dp)) { ChatTitle(header.value, reconnecting.value, { retries++ }) } } }
        val status = compose.onNodeWithTag("chat-session-status", useUnmergedTree = true)
        status.assertTextEquals("Working").assertIsDisplayed()
        val firstHeight = compose.onNodeWithTag("chat-header").fetchSemanticsNode().boundsInRoot.height
        val meta = compose.onNodeWithText("Codex · GMF-PC").fetchSemanticsNode().boundsInRoot
        val bounds = status.fetchSemanticsNode().boundsInRoot
        assertEquals(meta.top, bounds.top, 1f)
        assertTrue(bounds.left >= meta.right)
        compose.runOnIdle { header.value = header.value.copy(thinking = false) }
        status.assertTextEquals("Idle")
        assertEquals(firstHeight, compose.onNodeWithTag("chat-header").fetchSemanticsNode().boundsInRoot.height, 1f)
        compose.runOnIdle { header.value = header.value.copy(pendingRequestKinds = listOf("input")) }
        status.assertTextEquals("Needs input")
        assertEquals(firstHeight, compose.onNodeWithTag("chat-header").fetchSemanticsNode().boundsInRoot.height, 1f)
        compose.runOnIdle { reconnecting.value = true }
        status.assertTextEquals("Reconnecting… Tap to retry")
        compose.onNodeWithTag("chat-header").performClick()
        assertEquals(1, retries)
        assertEquals(firstHeight, compose.onNodeWithTag("chat-header").fetchSemanticsNode().boundsInRoot.height, 1f)
    }

    @Test fun largeFontsKeepStatusVisibleWithoutAddingARow() {
        val header = mutableStateOf(ChatHeaderUi("A long session title", "Codex · A very long machine name", active = true, thinking = true))
        compose.setContent {
            HapiTheme {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                    Box(Modifier.width(230.dp)) { ChatTitle(header.value, false, {}) }
                }
            }
        }
        compose.onNodeWithTag("chat-session-status").assertTextEquals("Working").assertIsDisplayed()
        val height = compose.onNodeWithTag("chat-header").fetchSemanticsNode().boundsInRoot.height
        compose.runOnIdle { header.value = header.value.copy(pendingRequestKinds = listOf("permission")) }
        compose.onNodeWithTag("chat-session-status").assertTextEquals("Needs approval").assertIsDisplayed()
        assertEquals(height, compose.onNodeWithTag("chat-header").fetchSemanticsNode().boundsInRoot.height, 1f)
    }
}
