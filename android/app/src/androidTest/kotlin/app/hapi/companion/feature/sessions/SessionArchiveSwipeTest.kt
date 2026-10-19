package app.hapi.companion.feature.sessions

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import app.hapi.companion.ui.theme.HapiTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class SessionArchiveSwipeTest {
    @get:Rule val compose = createComposeRule()
    private val pending = mutableStateOf(false)
    private val failure = mutableStateOf(0L)
    private val allowed = mutableStateOf(true)
    private var opens = 0
    private var menus = 0
    private var archives = 0
    private var hold = 650L
    private fun row() = compose.onNodeWithTag("session-archive-container-session")
    private fun mount() {
        compose.setContent { HapiTheme {
            hold = LocalViewConfiguration.current.longPressTimeoutMillis + 100
            Box(Modifier.width(360.dp)) { SessionArchiveSwipe("session", allowed.value, pending.value, failure.value,
                { opens++ }, { menus++ }, { archives++; pending.value = true }) {
                Box(Modifier.fillMaxWidth().height(88.dp).padding(16.dp)) { Text("Session item") }
            } }
        } }
    }
    private fun holdAndMove(fraction: Float) {
        row().performTouchInput {
            down(Offset(width * 0.85f, height / 2f))
            advanceEventTime(hold)
            moveTo(Offset(width * fraction, height / 2f))
        }
    }

    @Test fun tapAndStationaryHoldKeepOpenAndMenuActionsSeparate() {
        mount()
        row().performTouchInput { click(center) }
        compose.runOnIdle { assertEquals(1, opens); assertEquals(0, menus) }
        // A sub-slop MOVE carries the held timestamp before a separate UP batch.
        row().performTouchInput { down(center); advanceEventTime(hold); moveBy(Offset(1f, 0f)) }
        compose.runOnIdle { assertEquals(0, menus) }
        row().performTouchInput { up() }
        compose.runOnIdle { assertEquals("opens=$opens archives=$archives", 1, menus); assertEquals(1, opens); assertEquals(0, archives) }
    }

    @Test fun longLeftDragShowsFeedbackAndArchivesOnlyOnReleaseOnce() {
        mount(); holdAndMove(0.18f)
        compose.onNodeWithTag("session-archive-background-session").assertExists()
        compose.runOnIdle { assertEquals(0, archives); assertEquals(0, menus) }
        row().performTouchInput { up() }
        compose.runOnIdle { assertEquals(1, archives); assertEquals(0, opens); assertEquals(0, menus) }
        compose.onNodeWithTag("session-row-session").assertIsNotEnabled()
    }

    @Test fun shortDragSpringsBackWithoutOpeningMenuOrArchiving() {
        mount(); holdAndMove(0.74f)
        compose.onNodeWithTag("session-archive-background-session").assertExists()
        row().performTouchInput { up() }
        compose.onNodeWithTag("session-archive-background-session").assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, archives); assertEquals(0, menus); assertEquals(0, opens) }
    }

    @Test fun cancellationAfterCrossingThresholdDoesNotArchive() {
        mount(); holdAndMove(0.18f)
        row().performTouchInput { cancel() }
        compose.onNodeWithTag("session-archive-background-session").assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, archives); assertEquals(0, menus); assertEquals(0, opens) }
        holdAndMove(0.18f)
        row().performTouchInput { down(1, Offset(width * .7f, centerY)); up(0); up(1) }
        compose.onNodeWithTag("session-archive-background-session").assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, archives); assertEquals(0, menus) }
    }

    @Test fun fastSwipeAndRightDragNeverBecomeArchiveOrClick() {
        mount()
        row().performTouchInput { swipe(Offset(width * .85f, centerY), Offset(width * .2f, centerY), durationMillis = 150) }
        row().performTouchInput { down(Offset(width * .2f, centerY)); advanceEventTime(hold); moveTo(Offset(width * .8f, centerY)); up() }
        compose.runOnIdle { assertEquals(0, archives); assertEquals(0, menus); assertEquals(0, opens) }
        compose.onNodeWithTag("session-archive-background-session").assertDoesNotExist()
    }

    @Test fun verticalScrollWinsWithoutOpeningOrArchivingRows() {
        lateinit var list: androidx.compose.foundation.lazy.LazyListState
        compose.setContent { HapiTheme {
            list = rememberLazyListState()
            LazyColumn(Modifier.width(360.dp).height(400.dp), state = list) {
                items(20) { index -> SessionArchiveSwipe("$index", true, false, 0,
                    { opens++ }, { menus++ }, { archives++ }) {
                    Box(Modifier.fillMaxWidth().height(88.dp).padding(16.dp)) { Text("Item $index") }
                } }
            }
        } }
        compose.onNodeWithTag("session-archive-container-0").performTouchInput { swipeUp(durationMillis = 150) }
        compose.runOnIdle {
            assertTrue(list.firstVisibleItemIndex > 0 || list.firstVisibleItemScrollOffset > 0)
            assertEquals(0, archives); assertEquals(0, menus); assertEquals(0, opens)
        }
    }

    @Test fun failedRequestRestoresRowAndAllowsAnotherExplicitGesture() {
        mount(); holdAndMove(0.18f); row().performTouchInput { up() }
        compose.runOnIdle { assertEquals(1, archives); failure.value++; pending.value = false }
        compose.onNodeWithTag("session-archive-background-session").assertDoesNotExist()
        compose.onNodeWithTag("session-row-session").assertIsEnabled()
        holdAndMove(0.18f); row().performTouchInput { up() }
        compose.runOnIdle { assertEquals(2, archives) }
    }

    @Test fun archivedRowKeepsMenuAndAccessibilityWithoutSwipe() {
        allowed.value = false; mount(); holdAndMove(0.18f); row().performTouchInput { up() }
        compose.runOnIdle { assertEquals(0, archives); assertEquals(0, menus) }
        compose.onNodeWithTag("session-row-session").performClick()
        compose.onNodeWithTag("session-row-session").performSemanticsAction(SemanticsActions.OnLongClick) { it() }
        compose.runOnIdle { assertEquals(1, opens); assertEquals(1, menus); allowed.value = true }
        val actions = compose.onNodeWithTag("session-row-session").fetchSemanticsNode().config[SemanticsActions.CustomActions]
        compose.runOnIdle { assertTrue(actions.single().action()); assertEquals(1, archives) }
    }
}
