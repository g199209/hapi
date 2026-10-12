package app.hapi.companion.feature.chat

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import android.graphics.Bitmap
import app.hapi.companion.HapiApp
import app.hapi.companion.MainActivity
import app.hapi.companion.R
import app.hapi.data.api.MessagesQuery
import app.hapi.data.sse.ConnectionState
import app.hapi.data.sse.SseEngine
import app.hapi.data.sse.SseSubscriptionKey
import app.hapi.data.sse.SseTransport
import app.hapi.data.sse.TransportEvent
import app.hapi.protocol.chat.NormalizedAgentContent
import app.hapi.protocol.chat.NormalizedMessage
import app.hapi.protocol.chat.normalizeDecryptedMessage
import app.hapi.protocol.wire.SyncEvent
import app.hapi.protocol.wire.SyncEvents
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

/** Opt-in live fault injection. Supply an EMPTY isolated session via -e
 * recoverySessionId and choose -e expectRecovery true/false for fixed/baseline.
 * The transport gate is confined to this emulator process and session; REST,
 * SSE handshakes, heartbeats and session metadata continue normally. */
class AcceptedSendLiveTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun acceptedFirstMessageRecoversWithMessageEventsWithheld() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        val id = args.getString("recoverySessionId")
        assumeTrue("Live validation requires an explicitly supplied isolated session", !id.isNullOrBlank())
        val sessionId = id!!
        val expectedRecovery = args.getString("expectRecovery", "true") == "true"
        val app = instrumentation.targetContext.applicationContext as HapiApp
        compose.waitUntil(15_000) { app.appGraph.activeHubGraph.value != null }
        val graph = app.appGraph.activeHubGraph.value!!
        runBlocking {
            assertTrue("Live validation session must be empty", graph.session.api.getMessages(sessionId, MessagesQuery.Latest(200)).messages.none {
                normalizeDecryptedMessage(it) is NormalizedMessage.User
            })
        }
        val engine = graph.sseEngine
        val field = SseEngine::class.java.getDeclaredField("transport").apply { isAccessible = true }
        val original = field.get(engine) as SseTransport
        val dropped = AtomicInteger()
        val gate = object : SseTransport {
            override fun open(url: String, lastEventId: String?) = original.open(url, lastEventId).filter { frame ->
                val event = (frame as? TransportEvent.Event)?.let { SyncEvents.parse(it.event.data) }
                val eventSession = when (event) {
                    is SyncEvent.MessageReceived -> event.sessionId
                    is SyncEvent.MessagesConsumed -> event.sessionId
                    is SyncEvent.MessagesInvalidated -> event.sessionId
                    else -> null
                }
                val suppress = eventSession == sessionId
                if (suppress) dropped.incrementAndGet()
                !suppress
            }
        }
        val key = SseSubscriptionKey.Session(sessionId)
        val prompt = "Reply exactly HAPI_SEND_FAULT_OK. Do not call tools or modify files."
        try {
            field.set(engine, gate)
            engine.requestReconnect(SseSubscriptionKey.Global)
            compose.runOnUiThread { app.appGraph.pendingOpenSessionId.value = sessionId }
            compose.waitUntil(15_000) { engine.connectionState(key).value.phase == ConnectionState.Phase.Connected }
            compose.onNode(hasSetTextAction()).performTextReplacement(prompt)
            compose.onNodeWithContentDescription(instrumentation.targetContext.getString(R.string.chat_composer_send)).performClick()
            val rows = runBlocking {
                withTimeout(45_000) {
                    var rows = graph.session.api.getMessages(sessionId, MessagesQuery.Latest(200)).messages
                    while (rows.none { (normalizeDecryptedMessage(it) as? NormalizedMessage.Agent)?.content?.filterIsInstance<NormalizedAgentContent.Text>()?.any { part -> part.text.contains("HAPI_SEND_FAULT_OK") } == true }) {
                        delay(250)
                        rows = graph.session.api.getMessages(sessionId, MessagesQuery.Latest(200)).messages
                    }
                    rows
                }
            }
            assertEquals("Input is accepted exactly once", 1, rows.count { normalizeDecryptedMessage(it) is NormalizedMessage.User })
            assertTrue("Gate really withheld live message frames", dropped.get() > 0)
            compose.waitUntil(10_000) { engine.connectionState(key).value.phase == ConnectionState.Phase.Connected }
            assertEquals(ConnectionState.Phase.Connected, engine.connectionState(key).value.phase)
            if (expectedRecovery) {
                compose.waitUntil(20_000) {
                    compose.onAllNodesWithText("HAPI_SEND_FAULT_OK").fetchSemanticsNodes().isNotEmpty()
                }
                compose.onNodeWithText("HAPI_SEND_FAULT_OK").assertIsDisplayed()
                compose.onNodeWithText(instrumentation.targetContext.getString(R.string.chat_empty_title)).assertDoesNotExist()
            } else {
                // Give the old page time to process all metadata after the
                // backend already finished: the old implementation stays empty.
                Thread.sleep(3_000)
                compose.onNodeWithText(instrumentation.targetContext.getString(R.string.chat_empty_title)).assertIsDisplayed()
                compose.onNodeWithText("HAPI_SEND_FAULT_OK").assertDoesNotExist()
            }
            val dir = File(instrumentation.targetContext.getExternalFilesDir(null), "hapi-sync-validation").apply { mkdirs() }
            val label = if (expectedRecovery) "fixed" else "baseline"
            File(dir, "$label.png").outputStream().use { output ->
                requireNotNull(instrumentation.uiAutomation.takeScreenshot()).compress(Bitmap.CompressFormat.PNG, 100, output)
            }
            File(dir, "$label.json").writeText("""{"sessionId":"$sessionId","expectRecovery":$expectedRecovery,"withheldFrames":${dropped.get()},"submittedUserRows":1,"sseConnected":true}""")
        } finally {
            field.set(engine, original)
            engine.requestReconnect(SseSubscriptionKey.Global)
            engine.requestReconnect(key)
        }
    }
}
