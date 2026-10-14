package app.hapi.companion.feature.sessions

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import app.hapi.companion.HapiApp
import app.hapi.companion.MainActivity
import java.io.File
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

/** Opt-in emulator check against real Hub/Runner maintenance. The host waits
 * for ready, performs maintenance, creates done, and sends a fixture message.
 * Only this emulator's read watermarks are changed. */
class UnreadClockLiveTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Test fun seenSessionsRemainReadThroughMaintenanceAndNewMessagesBecomeUnread() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        val ids = args.getString("unreadSessions")?.split(',')?.filter { it.isNotBlank() }.orEmpty()
        val fixtureId = args.getString("unreadFixture")
        assumeTrue("Explicit live session IDs are required", ids.isNotEmpty() && !fixtureId.isNullOrBlank())
        val app = instrumentation.targetContext.applicationContext as HapiApp
        compose.waitUntil(15_000) { app.appGraph.activeHubGraph.value != null }
        val graph = app.appGraph.activeHubGraph.value!!
        runBlocking { graph.sessionStore.refresh(); graph.machineStore.refresh() }
        val watched = ids + fixtureId!!
        val initial = graph.sessionStore.sessions.value.filter { it.id in watched }.associateBy { it.id }
        assertEquals(watched.size, initial.size)
        assertTrue(initial.values.all { it.lastMessageAt != null })
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val ready = File(app.filesDir, "unread-clock-ready")
        val done = File(app.filesDir, "unread-clock-maintenance-done")
        try {
            lateinit var vm: SessionListViewModel
            compose.runOnUiThread {
                initial.values.forEach { graph.lastSeenStore.markSeen(it.id, it.unreadAt) }
                vm = SessionListViewModel(graph.sessionStore, graph.machineStore, graph.lastSeenStore, scope, graph.hubUrl)
                scope.launch { vm.uiState.collect {} }
            }
            compose.waitUntil(15_000) { vm.uiState.value.rows.count { it.summary.id in watched && !it.unread } == watched.size }
            ready.writeText("ready")
            compose.waitUntil(180_000) { done.exists() }
            runBlocking { graph.sessionStore.refresh() }
            compose.waitUntil(15_000) { vm.uiState.value.rows.count { it.summary.id in ids && !it.unread } == ids.size }
            val after = graph.sessionStore.sessions.value.associateBy { it.id }
            assertTrue("Actual changed runtime metadata reached the emulator", ids.any { after[it]!!.metadataVersion > initial[it]!!.metadataVersion })
            for (id in ids) assertEquals("No new conversation clock on maintenance", initial[id]!!.lastMessageAt, after[id]!!.lastMessageAt)
            File(app.filesDir, "unread-clock-new-message-ready").writeText("ready")
            compose.waitUntil(45_000) { vm.uiState.value.rows.any { it.summary.id == fixtureId && it.unread } }
            assertTrue(graph.sessionStore.sessions.value.first { it.id == fixtureId }.unreadAt > initial[fixtureId]!!.unreadAt)
        } finally {
            scope.cancel(); ready.delete(); done.delete(); File(app.filesDir, "unread-clock-new-message-ready").delete()
        }
    }
}
