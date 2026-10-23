package app.hapi.companion.feature.chat

import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import app.hapi.companion.feature.settings.*
import app.hapi.companion.ui.theme.HapiTheme
import app.hapi.protocol.catalog.PermissionModes
import app.hapi.protocol.wire.HubHealthResponse
import java.io.File
import kotlinx.coroutines.launch
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.flow
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class SessionChatDisplayTest {
    @get:Rule val compose = createComposeRule()
    private fun config(active: Boolean = true) = SessionConfigUi(
        flavor = "codex", active = active, controlledByUser = false, permissionMode = "default",
        permissionModes = PermissionModes.forFlavor("codex"), model = null, modelOptions = null,
        modelOptionsLoading = false, effort = null, effortOptions = null,
    )

    @Test fun sessionSheetDefaultsOffAndSessionHubChoicesRemainIndependent() {
        val session = mutableStateOf("a")
        val hub = mutableStateOf("hub-a")
        lateinit var displayed: State<Boolean?>
        compose.setContent {
            val context = LocalContext.current
            val scope = rememberCoroutineScope()
            val prefs = remember { ChatDisplayPrefs(PreferenceDataStoreFactory.create(scope = scope) {
                File(context.cacheDir, "session-display-${System.nanoTime()}.preferences_pb")
            }) }
            val selectedHub = hub.value
            val selectedSession = session.value
            displayed = rememberSessionActivity(prefs, selectedHub, selectedSession)
            displayed.value?.let { enabledForSession -> HapiTheme {
                SessionConfigSheetContent(config(), {}, {}, {}, enabledForSession, { enabled ->
                    scope.launch { prefs.setShowActivity(selectedHub, selectedSession, enabled) }
                })
            } }
        }
        fun ready() { compose.waitForIdle(); compose.waitUntil(10000) { displayed.value != null }; compose.waitForIdle() }
        ready()
        val toggle = compose.onNodeWithTag("chat-config-show-activity")
        assertTrue(toggle.fetchSemanticsNode().boundsInRoot.top < compose.onNodeWithText("Permission mode").fetchSemanticsNode().boundsInRoot.top)
        toggle.assertIsOff().performClick()
        compose.waitUntil(10000) { displayed.value == true }
        compose.runOnIdle { session.value = "b" }
        ready()
        toggle.assertIsOff().performClick()
        compose.waitUntil(10000) { displayed.value == true }
        compose.runOnIdle { session.value = "a" }
        ready()
        compose.waitUntil(10000) { displayed.value == true }
        toggle.assertIsOn().performClick()
        compose.waitUntil(10000) { displayed.value == false }
        compose.runOnIdle { session.value = "b" }
        ready()
        compose.waitUntil(10000) { displayed.value == true }
        toggle.assertIsOn()
        compose.runOnIdle { hub.value = "hub-b" }
        ready()
        toggle.assertIsOff()
        compose.runOnIdle { hub.value = "hub-a" }
        ready()
        compose.waitUntil(10000) { displayed.value == true }
        toggle.assertIsOn()
    }

    @Test fun openingWaitsForTheSessionPreferenceInsteadOfRenderingATemporaryDefault() {
        val loaded = CompletableDeferred<Unit>()
        val store = object : DataStore<Preferences> {
            override val data = flow { loaded.await(); emit(emptyPreferences()) }
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences) = transform(emptyPreferences())
        }
        lateinit var displayed: State<Boolean?>
        compose.setContent { displayed = rememberSessionActivity(remember { ChatDisplayPrefs(store) }, "hub", "session") }
        compose.runOnIdle { assertNull(displayed.value); loaded.complete(Unit) }
        compose.waitUntil(10000) { displayed.value == false }
    }

    @Test fun inactiveSessionCanChangeDisplayWithoutChangingAgentConfiguration() {
        val show = mutableStateOf(true)
        var agentChanges = 0
        compose.setContent {
            HapiTheme {
                SessionConfigSheetContent(config(active = false), { agentChanges++ }, { agentChanges++ }, { agentChanges++ },
                    show.value, { show.value = it })
            }
        }
        compose.onNodeWithTag("chat-config-show-activity").assertIsOn().performClick().assertIsOff()
        compose.runOnIdle { assertFalse(show.value); assertEquals(0, agentChanges) }
    }

    @Test fun globalSettingsHasNoSessionDisplayControl() {
        compose.setContent {
            val context = LocalContext.current
            val scope = rememberCoroutineScope()
            val store = remember { PreferenceDataStoreFactory.create(scope = scope) {
                File(context.cacheDir, "global-display-${System.nanoTime()}.preferences_pb")
            } }
            val model = remember { SettingsViewModel(ThemePrefs(store), LanguagePrefs(store),
                "https://hub.test", { null }, { HubHealthResponse("ok", 1) }, scope) }
            HapiTheme { SettingsScreen(model, {}, {}, {}) }
        }
        compose.onNodeWithText("Show tool calls and thinking").assertDoesNotExist()
        compose.onNodeWithText("Appearance").assertIsDisplayed()
    }
}
