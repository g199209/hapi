package app.hapi.companion.feature.chat.history

import app.hapi.data.api.*
import app.hapi.protocol.window.*
import app.hapi.protocol.wire.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.*

class ConversationHistoryTest {
    private fun session(caps: ConversationHistoryCapabilities = ConversationHistoryCapabilities(true, true, true)) = Session(
        id = "source", namespace = "default", seq = 1, createdAt = 1, updatedAt = 1, active = true,
        metadataVersion = 1, agentStateVersion = 1, thinking = false, thinkingAt = 0,
        metadata = SessionMetadata("/project", "host", capabilities = SessionCapabilities(conversationHistory = caps),
            conversationHistoryPoints = mapOf("local" to true, "queued" to true, "false" to false)),
    )
    private fun window() = MessageWindowState("source", epoch = 4, syncGeneration = 1, messages = listOf(
        DecryptedMessage(id = "hub-row", localId = "local", seq = 1, createdAt = 1, invokedAt = OptionalField.Present(1),
            content = buildJsonObject { put("role", "user"); put("content", "message") }).asWindowMessage(),
    ))
    private fun context() = historyContext(session(), window())
    private open class Api : ConversationHistoryApi {
        val forks = mutableListOf<Pair<String, String?>>()
        val rewinds = mutableListOf<Pair<String, String>>()
        override suspend fun forkConversation(sessionId: String, messageLocalId: String?): ForkConversationResponse {
            forks += sessionId to messageLocalId
            return ForkConversationResponse("child")
        }
        override suspend fun rewindConversation(sessionId: String, messageLocalId: String) { rewinds += sessionId to messageLocalId }
    }

    @Test fun `native advertised invoked points use localId not rendered hub ids`() {
        val context = context()
        assertTrue(context.availability.ready)
        assertEquals(setOf("local"), context.availability.nativePoints)
        assertFalse(context.availability.supports(HistoryKind.ForkBefore, "hub-row"))
        assertFalse(context.availability.supports(HistoryKind.RewindBefore, "false"))
        assertTrue(context.availability.supports(HistoryKind.ForkBefore, "local"))
    }
    @Test fun `shared Codex exposes forks but no rewind and concurrent clients ignore legacy owner`() {
        val s = session(ConversationHistoryCapabilities(true, true, false)).let { it.copy(
            metadata = it.metadata!!.copy(capabilities = it.metadata!!.capabilities!!.copy(concurrentClients = true)),
            agentState = AgentState(controlledByUser = true),
        ) }
        val c = historyContext(s, window())
        assertTrue(c.availability.ready)
        assertTrue(c.availability.forkCurrent)
        assertFalse(c.availability.rewindToMessage)
        assertFalse(historyContext(s.copy(metadata = s.metadata!!.copy(capabilities = s.metadata!!.capabilities!!.copy(concurrentClients = false))), window()).availability.ready)
    }
    @Test fun `busy inactive queued diverged missing epoch and pending operations block mutations`() {
        val s = session(); val w = window()
        assertFalse(historyContext(s.copy(thinking = true), w).availability.ready)
        assertFalse(historyContext(s.copy(active = false), w).availability.ready)
        assertFalse(historyContext(s.copy(metadata = s.metadata!!.copy(conversationHistoryDiverged = true)), w).availability.ready)
        assertFalse(historyContext(s, w.copy(epoch = null)).availability.ready)
        assertFalse(historyContext(s, w.copy(requiresLatestReset = true)).availability.ready)
        assertTrue(historyContext(s, w.copy(requiresLatestReset = true, viewMode = MessageViewMode.History)).availability.ready)
        assertFalse(historyContext(s, w, blocked = true).availability.ready)
        val queued = w.messages.single().copy(wire = w.messages.single().wire.copy(localId = "queued", invokedAt = OptionalField.Present(null)))
        val c = historyContext(s, w.copy(messages = w.messages + queued))
        assertFalse(c.availability.ready)
        assertEquals(setOf("local"), c.availability.nativePoints)
    }
    @Test fun `fork current and before send distinct bodies and emit returned child only once`() = runTest {
        val api = Api(); val children = mutableListOf<String>()
        val vm = ConversationHistoryController("source", api, ::context, {}, children::add, backgroundScope)
        vm.request(HistoryKind.ForkCurrent); vm.confirm(); vm.confirm(); runCurrent()
        assertEquals(listOf<Pair<String, String?>>("source" to null), api.forks)
        assertEquals(listOf("child"), children); assertNull(vm.state.value.request)
        assertEquals("child", vm.state.value.forkedSessionId)
        vm.dismiss(); assertEquals("child", vm.state.value.forkedSessionId)
        vm.acknowledgeFork("different"); assertEquals("child", vm.state.value.forkedSessionId)
        vm.acknowledgeFork("child")
        vm.request(HistoryKind.ForkBefore, "local", "draft"); vm.confirm(); runCurrent()
        assertEquals("source" to "local", api.forks.last()); assertEquals(2, children.size)
    }
    @Test fun `confirmation survives caller redraw but changed epoch or current tail invalidates it`() = runTest {
        val api = Api(); var context = context()
        val vm = ConversationHistoryController("source", api, { context }, {}, {}, backgroundScope)
        vm.request(HistoryKind.ForkBefore, "local"); context = context.copy(epoch = 5); vm.confirm(); runCurrent()
        assertEquals(HistoryErrorKind.Stale, vm.state.value.error?.kind); assertTrue(api.forks.isEmpty())
        vm.dismiss(); vm.request(HistoryKind.ForkCurrent); context = context.copy(tailRevision = 99); vm.confirm(); runCurrent()
        assertTrue(api.forks.isEmpty()); assertEquals(HistoryErrorKind.Stale, vm.state.value.error?.kind)
    }
    @Test fun `historical point remains valid after unrelated tail changes but busy transition is rejected`() = runTest {
        val api = Api(); var c = context()
        val vm = ConversationHistoryController("source", api, { c }, {}, {}, backgroundScope)
        vm.request(HistoryKind.ForkBefore, "local"); c = c.copy(tailRevision = 7); vm.confirm(); runCurrent()
        assertEquals(1, api.forks.size)
        vm.acknowledgeFork("child")
        vm.request(HistoryKind.RewindBefore, "local"); c = c.copy(availability = c.availability.copy(ready = false)); vm.confirm(); runCurrent()
        assertTrue(api.rewinds.isEmpty()); assertEquals(HistoryErrorKind.Stale, vm.state.value.error?.kind)
    }
    @Test fun `confirmed point survives row recycling but not removal from native catalog`() = runTest {
        val api = Api(); var c = context()
        val vm = ConversationHistoryController("source", api, { c }, {}, {}, backgroundScope)
        vm.request(HistoryKind.ForkBefore, "local")
        c = c.copy(availability = c.availability.copy(nativePoints = emptySet()))
        vm.confirm(); runCurrent(); assertEquals(1, api.forks.size)
        vm.acknowledgeFork("child")
        c = context(); vm.request(HistoryKind.ForkBefore, "local")
        c = c.copy(nativePointsCatalog = emptySet())
        vm.confirm(); runCurrent(); assertEquals(1, api.forks.size)
        assertEquals(HistoryErrorKind.Stale, vm.state.value.error?.kind)
    }
    @Test fun `pending operation refuses dismiss duplicate confirm and replacement action`() = runTest {
        val gate = CompletableDeferred<Unit>(); var calls = 0
        val api = object : Api() { override suspend fun rewindConversation(sessionId: String, messageLocalId: String) { calls++; gate.await() } }
        val vm = ConversationHistoryController("source", api, ::context, {}, {}, backgroundScope)
        vm.request(HistoryKind.RewindBefore, "local"); vm.confirm(); runCurrent()
        vm.dismiss(); vm.confirm(); vm.request(HistoryKind.ForkCurrent)
        assertEquals(HistoryKind.RewindBefore, vm.state.value.request?.kind)
        assertTrue(vm.state.value.pending); assertEquals(1, calls)
        gate.complete(Unit); runCurrent(); assertNull(vm.state.value.request)
    }
    @Test fun `completed rewind retries refresh only after a refresh failure`() = runTest {
        val api = Api(); var refreshes = 0
        val vm = ConversationHistoryController("source", api, ::context, { if (++refreshes == 1) error("offline") }, {}, backgroundScope)
        vm.request(HistoryKind.RewindBefore, "local"); vm.confirm(); runCurrent()
        assertEquals(1, api.rewinds.size); assertTrue(vm.state.value.refreshOnly)
        assertEquals(1, refreshes); vm.confirm(); runCurrent()
        assertEquals(1, api.rewinds.size); assertEquals(2, refreshes); assertNull(vm.state.value.request)
    }
    @Test fun `dismissing a refresh failure does not re-enable an already executed rewind`() = runTest {
        val api = Api(); var failing = true
        val vm = ConversationHistoryController("source", api, ::context, { if (failing) error("offline") }, {}, backgroundScope)
        vm.request(HistoryKind.RewindBefore, "local"); vm.confirm(); runCurrent()
        vm.dismiss(); vm.request(HistoryKind.RewindBefore, "local")
        assertTrue(vm.state.value.refreshOnly)
        failing = false; vm.confirm(); runCurrent()
        assertEquals(1, api.rewinds.size); assertNull(vm.state.value.request)
    }
    @Test fun `ambiguous boundary offers a fork only after explicit second confirmation`() = runTest {
        val api = object : Api() { override suspend fun rewindConversation(sessionId: String, messageLocalId: String) {
            super.rewindConversation(sessionId, messageLocalId)
            throw ApiError(409, "ambiguous_native_boundary_fork_safe")
        } }
        val vm = ConversationHistoryController("source", api, ::context, { error("safe failure must not reset history") }, {}, backgroundScope)
        vm.request(HistoryKind.RewindBefore, "local"); vm.confirm(); runCurrent()
        assertTrue(vm.state.value.offerFork); assertTrue(api.forks.isEmpty())
        vm.confirm(); runCurrent(); assertTrue(api.forks.isEmpty())
        vm.confirmFallback(); runCurrent()
        assertEquals(listOf<Pair<String, String?>>("source" to "local"), api.forks); assertNull(vm.state.value.request)
    }
    @Test fun `unknown rewind outcome never retries mutation and reconciles immediately`() = runTest {
        var calls = 0; var refreshes = 0
        val api = object : Api() { override suspend fun rewindConversation(sessionId: String, messageLocalId: String) { calls++; error("connection lost") } }
        val vm = ConversationHistoryController("source", api, ::context, { refreshes++ }, {}, backgroundScope)
        vm.request(HistoryKind.RewindBefore, "local"); vm.confirm(); runCurrent()
        assertTrue(vm.state.value.refreshOnly); assertEquals(1, refreshes)
        vm.confirm(); runCurrent(); assertEquals(1, calls); assertEquals(2, refreshes)
    }
    @Test fun `hydrate failure resets history but ordinary busy rejection leaves it intact`() = runTest {
        var refreshes = 0; var status = 409
        val api = object : Api() { override suspend fun rewindConversation(sessionId: String, messageLocalId: String) {
            throw ApiError(status, body = if (status == 500) """{"hydrateFailed":true}""" else """{"error":"Session is busy"}""")
        } }
        val vm = ConversationHistoryController("source", api, ::context, { refreshes++ }, {}, backgroundScope)
        vm.request(HistoryKind.RewindBefore, "local"); vm.confirm(); runCurrent(); assertEquals(0, refreshes)
        status = 500; vm.confirm(); runCurrent(); assertEquals(1, refreshes); assertTrue(vm.state.value.refreshOnly)
    }
    @Test fun `unsupported actions and missing API do not open a dialog`() = runTest {
        val api = Api(); val c = context().copy(availability = context().availability.copy(rewindToMessage = false))
        val vm = ConversationHistoryController("source", api, { c }, {}, {}, backgroundScope)
        vm.request(HistoryKind.RewindBefore, "local"); assertNull(vm.state.value.request)
        vm.request(HistoryKind.ForkBefore, "hub-row"); assertNull(vm.state.value.request)
        val absent = ConversationHistoryController("source", null, ::context, {}, {}, backgroundScope)
        absent.request(HistoryKind.ForkCurrent); assertNull(absent.state.value.request)
    }
}
