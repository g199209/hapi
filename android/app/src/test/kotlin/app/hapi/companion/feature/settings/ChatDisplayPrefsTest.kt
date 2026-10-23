package app.hapi.companion.feature.settings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.rules.TemporaryFolder

class ChatDisplayPrefsTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun `explicit visibility survives a cold start while unset sessions stay hidden`() = runTest {
        val file = File(tmp.root, "chat.preferences_pb")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val store = PreferenceDataStoreFactory.create(scope = scope) { file }
            val prefs = ChatDisplayPrefs(store)
            assertFalse(prefs.showActivity("hub-a", "session-a").first())
            ThemePrefs(store).setDynamicColor(false)
            prefs.setShowActivity("hub-a", "session-a", true)
            assertTrue(prefs.showActivity("hub-a", "session-a").first())
            assertFalse(prefs.showActivity("hub-a", "session-b").first())
            assertFalse(ThemePrefs(store).settings.first().dynamicColor)
        } finally { scope.coroutineContext[Job]?.cancelAndJoin() }
        val restored = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val prefs = ChatDisplayPrefs(PreferenceDataStoreFactory.create(scope = restored) { file })
            assertTrue(prefs.showActivity("hub-a", "session-a").first())
            assertFalse(prefs.showActivity("hub-a", "session-b").first())
            prefs.setShowActivity("hub-a", "session-a", false)
            assertFalse(prefs.showActivity("hub-a", "session-a").first())
        } finally { restored.coroutineContext[Job]?.cancelAndJoin() }
    }

    @Test fun `same session IDs on different hubs remain independent and global choice is ignored`() = runTest {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val store = PreferenceDataStoreFactory.create(scope = scope) { File(tmp.root, "hubs.preferences_pb") }
            store.edit { it[booleanPreferencesKey("chat_show_tools_and_thinking")] = true }
            val prefs = ChatDisplayPrefs(store)
            assertFalse(prefs.showActivity("hub-a", "same-session").first())
            assertFalse(prefs.showActivity("hub-b", "same-session").first())
            prefs.setShowActivity("hub-a", "same-session", true)
            assertTrue(prefs.showActivity("hub-a", "same-session").first())
            assertFalse(prefs.showActivity("hub-b", "same-session").first())
            assertFalse(prefs.showActivity("hub-a", "new-session").first())
        } finally { scope.coroutineContext[Job]?.cancelAndJoin() }
    }

    @Test fun `concurrent session writes retain independent values`() = runTest {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val prefs = ChatDisplayPrefs(PreferenceDataStoreFactory.create(scope = scope) { File(tmp.root, "writes.preferences_pb") })
            listOf(
                scope.launch { prefs.setShowActivity("hub", "a", false) },
                scope.launch { prefs.setShowActivity("hub", "b", false) },
            ).joinAll()
            prefs.setShowActivity("hub", "a", true)
            assertTrue(prefs.showActivity("hub", "a").first())
            assertFalse(prefs.showActivity("hub", "b").first())
        } finally { scope.coroutineContext[Job]?.cancelAndJoin() }
    }
}
