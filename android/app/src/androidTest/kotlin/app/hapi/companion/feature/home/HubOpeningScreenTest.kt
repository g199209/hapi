package app.hapi.companion.feature.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import app.hapi.companion.R
import app.hapi.companion.di.HubOpeningState
import app.hapi.companion.ui.theme.HapiTheme
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*

class HubOpeningScreenTest {
    @get:Rule val compose = createComposeRule()
    private val hub = "https://home.example"
    private val state = mutableStateOf(HubOpeningState(hub))
    private val enabled = mutableStateOf(true)
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private var retries = 0
    private var cacheClicks = 0
    private var mounts = 0
    private var disposals = 0
    private val actions = mutableListOf<String>()
    private fun mount() {
        compose.setContent { HapiTheme {
            HubOpeningBoundary(hub, state.value, enabled.value, listOf(hub, "https://other.example"),
                onRetry = { retries++ }, onSwitchHub = { actions += it }, onPairAnother = { actions += "pair" }) {
                DisposableEffect(Unit) { mounts++; onDispose { disposals++ } }
                Box(Modifier.fillMaxSize().clickable { cacheClicks++ }) { Text("Cached session") }
            }
        } }
    }
    @Test fun checkingAndFailureHideCachedRecordsAndCannotClickThrough() {
        mount()
        compose.onNodeWithText("Cached session").assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.hub_opening_connecting)).assertIsDisplayed()
        compose.runOnIdle { state.value = HubOpeningState(hub, HubOpeningState.Phase.Failed) }
        compose.onNodeWithText(context.getString(R.string.hub_opening_failed)).assertIsDisplayed()
        compose.onNodeWithText("Cached session").assertDoesNotExist()
        compose.onNodeWithTag("hub-opening-gate").performTouchInput { click(Offset(20f, 20f)) }
        compose.runOnIdle { assertEquals(0, cacheClicks) }
    }
    @Test fun retryAndRecoveryRevealTheExistingScreenWithoutLosingItsState() {
        state.value = HubOpeningState(hub, HubOpeningState.Phase.Failed); mount()
        compose.onNodeWithText(context.getString(R.string.settings_retry)).performTouchInput { click(center) }
        compose.runOnIdle { assertEquals(1, retries); state.value = HubOpeningState(hub, HubOpeningState.Phase.Ready) }
        compose.onNodeWithText("Cached session").assertIsDisplayed()
        compose.runOnIdle { state.value = HubOpeningState(hub) }
        compose.onNodeWithText("Cached session").assertDoesNotExist()
        compose.runOnIdle { state.value = HubOpeningState(hub, HubOpeningState.Phase.Ready) }
        compose.onNodeWithText("Cached session").assertIsDisplayed()
        compose.runOnIdle { assertEquals(1, mounts); assertEquals(0, disposals) }
    }
    @Test fun unreachableHubCanSwitchOrPairAnotherHub() {
        state.value = HubOpeningState(hub, HubOpeningState.Phase.Failed); mount()
        compose.onNodeWithText(context.getString(R.string.home_switch_hub)).performTouchInput { click(center) }
        compose.onNodeWithText("other.example").performClick()
        compose.onNodeWithText(context.getString(R.string.home_switch_hub)).performClick()
        compose.onNodeWithText(context.getString(R.string.home_pair_another)).performClick()
        compose.runOnIdle { assertEquals(listOf("https://other.example", "pair"), actions) }
    }
    @Test fun oldHubsReadyStateCannotUnblockANewHub() {
        state.value = HubOpeningState("https://old.example", HubOpeningState.Phase.Ready); mount()
        compose.onNodeWithText("Cached session").assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.hub_opening_connecting)).assertIsDisplayed()
    }
    @Test fun pairingRemainsAccessibleWhenTheActiveHubIsDown() {
        state.value = HubOpeningState(hub, HubOpeningState.Phase.Failed); enabled.value = false; mount()
        compose.onNodeWithTag("hub-opening-gate").assertDoesNotExist()
        compose.onNodeWithText("Cached session").assertIsDisplayed()
    }
    @Test fun smallLandscapeViewportAndLargeFontsCanScrollToHubControls() {
        compose.setContent { HapiTheme {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density, 2f)) {
                Box(Modifier.size(width = 480.dp, height = 220.dp)) {
                    HubOpeningBoundary(hub, HubOpeningState(hub, HubOpeningState.Phase.Failed)) { Text("Cached session") }
                }
            }
        } }
        repeat(4) { compose.onNode(hasScrollAction()).performTouchInput { swipeUp(durationMillis = 400) } }
        compose.onNodeWithText(context.getString(R.string.home_switch_hub)).assertIsDisplayed()
            .performTouchInput { click(center) }
        compose.onNodeWithText(context.getString(R.string.home_pair_another)).assertIsDisplayed()
        compose.onNodeWithText("Cached session").assertDoesNotExist()
    }
    @Test fun hiddenComposerLosesFocusAndKeepsItsDraftWhenConnectionReturns() {
        state.value = HubOpeningState(hub, HubOpeningState.Phase.Ready)
        compose.setContent { HapiTheme {
            HubOpeningBoundary(hub, state.value) {
                val requester = remember { FocusRequester() }
                BasicTextField("draft", {}, Modifier.testTag("cached-editor").focusRequester(requester))
                LaunchedEffect(Unit) { requester.requestFocus() }
            }
        } }
        compose.onNodeWithTag("cached-editor").assertIsFocused()
        compose.runOnIdle { state.value = HubOpeningState(hub, HubOpeningState.Phase.Failed) }
        compose.onNodeWithTag("cached-editor").assertDoesNotExist()
        compose.runOnIdle { state.value = HubOpeningState(hub, HubOpeningState.Phase.Ready) }
        compose.onNodeWithTag("cached-editor").assertIsNotFocused().assertTextEquals("draft")
    }
}
