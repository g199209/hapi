package app.hapi.companion.feature.newsession

import androidx.activity.compose.setContent
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import app.hapi.companion.HapiApp
import app.hapi.companion.MainActivity
import app.hapi.companion.ui.theme.HapiTheme
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

/** Opt-in: host supplies its own new native fixture threads, never user threads. */
class CodexSessionImportLiveTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun originalLocalAndRemoteThreadsImportAndRepeatedImportReusesSession() {
        val args = InstrumentationRegistry.getArguments()
        val machines = args.getString("importMachines")?.split(',').orEmpty()
        val threads = args.getString("importThreads")?.split(',').orEmpty()
        val flavors = args.getString("importFlavors")?.split(',') ?: List(threads.size) { "codex" }
        assumeTrue("Two explicit native fixture threads are required", machines.size == 2 && threads.size == 2)
        require(flavors.size == threads.size && flavors.all { it in listOf("codex", "opencode", "dsh") })
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as HapiApp
        compose.waitUntil(15_000) { app.appGraph.activeHubGraph.value != null }
        val graph = app.appGraph.activeHubGraph.value!!
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val completed = CopyOnWriteArrayList<String>()
        val controller = CodexSessionImportController(ApiCodexSessionImportGateway(graph.session.api), scope, "load failed", "import failed") { _, _, id -> completed += id }
        val results = mutableListOf<String>()
        try {
            compose.runOnUiThread {
                compose.activity.setContent {
                    val state by controller.state.collectAsState()
                    HapiTheme {
                        if (state.open) CodexSessionImportSheet(
                            state, controller::close, controller::refresh, controller::setQuery,
                            controller::setDirectory, controller::select, controller::confirm,
                        )
                    }
                }
            }
            for (index in machines.indices) {
                var firstId: String? = null
                repeat(2) {
                    val count = completed.size
                    compose.runOnUiThread { controller.open(machines[index], "Fixture machine $index", null, flavors[index]) }
                    compose.waitUntil(90_000) { !controller.state.value.loading }
                    assertTrue(controller.state.value.error.orEmpty(), controller.state.value.sessions.any { it.id == threads[index] })
                    compose.runOnUiThread { controller.setQuery(threads[index]) }
                    compose.onNodeWithTag("codex-import-${threads[index]}").performClick()
                    compose.onNodeWithTag("codex-import-confirm").performClick()
                    compose.waitUntil(90_000) { completed.size > count || !controller.state.value.importing }
                    assertEquals(controller.state.value.error, count + 1, completed.size)
                    val id = completed.last()
                    val session = runBlocking { graph.session.api.getSession(id) }.session
                    val nativeId = when (flavors[index]) {
                        "dsh" -> session.metadata?.dshSessionId
                        "opencode" -> session.metadata?.opencodeSessionId
                        else -> session.metadata?.codexSessionId
                    }
                    assertEquals(threads[index], nativeId)
                    assertEquals(machines[index], session.metadata?.machineId)
                    assertTrue(session.active)
                    if (firstId == null) firstId = id else assertEquals(firstId, id)
                }
                results += firstId!!
            }
            File(app.filesDir, "codex-import-live-result").writeText(results.joinToString(","))
        } finally {
            scope.cancel()
        }
    }
}
