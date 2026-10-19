package app.hapi.companion.feature.sessions

import app.hapi.companion.feature.chat.history.HistoryErrorKind
import app.hapi.protocol.wire.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SessionForkControllerTest {
    private fun session(id: String = "source") = Session(
        id = id, namespace = "default", seq = 1, createdAt = 1, updatedAt = 1, active = true,
        metadataVersion = 1, agentStateVersion = 1, thinking = false, thinkingAt = 0, lastMessageAt = 10,
        metadata = SessionMetadata("/project", "host", capabilities = SessionCapabilities(
            conversationHistory = ConversationHistoryCapabilities(forkCurrent = true))),
    )
    private inner class Gateway : SessionForkGateway {
        var session = session()
        var failLoad = false
        var failFork = false
        var gate: CompletableDeferred<Unit>? = null
        val forks = mutableListOf<String>()
        override suspend fun load(sessionId: String): Session {
            if (failLoad) error("offline")
            return session.copy(id = sessionId)
        }
        override suspend fun fork(sessionId: String): String {
            forks += sessionId
            gate?.await()
            if (failFork) error("fork rejected")
            return "child"
        }
    }
    private fun TestScope.controller(gateway: SessionForkGateway?, onForked: () -> Unit = {}, onError: (String, String?) -> Unit = { _, _ -> }) =
        SessionForkController(gateway, backgroundScope, onForked, StandardTestDispatcher(testScheduler), onError)

    @Test fun `selection fetches native capability and rejects unsupported or non-ready sessions`() = runTest {
        val api = Gateway(); val controller = controller(api)
        controller.select("source", "标题"); assertTrue(controller.state.value.loading); runCurrent()
        assertTrue(controller.state.value.ready); assertTrue(controller.state.value.showAction)
        for (session in listOf(api.session.copy(active = false), api.session.copy(thinking = true), api.session.copy(
            metadata = api.session.metadata!!.copy(conversationHistoryDiverged = true)), api.session.copy(agentState = AgentState(controlledByUser = true)))) {
            api.session = session; controller.select("source", "标题"); runCurrent()
            assertFalse(controller.state.value.ready); controller.request(); assertNull(controller.state.value.dialog.request)
        }
        api.session = session().copy(metadata = session().metadata!!.copy(capabilities = null))
        controller.select("source", "标题"); runCurrent(); assertFalse(controller.state.value.showAction)
        assertTrue(api.forks.isEmpty())
    }

    @Test fun `current fork submits selected source once and retains navigation until acknowledged`() = runTest {
        val api = Gateway().apply { gate = CompletableDeferred() }; var refreshes = 0
        val controller = controller(api, { refreshes++ })
        controller.select("source", "标题"); runCurrent(); controller.request(); controller.confirm(); controller.confirm(); runCurrent()
        assertEquals(listOf("source"), api.forks); assertTrue(controller.state.value.dialog.pending)
        controller.dismiss(); assertFalse(controller.select("other", "other"))
        api.gate!!.complete(Unit); runCurrent()
        assertEquals(1, refreshes); assertEquals("child", controller.state.value.dialog.forkedSessionId)
        controller.dismiss(); controller.acknowledgeFork("wrong"); assertEquals("child", controller.state.value.dialog.forkedSessionId)
        controller.acknowledgeFork("child"); assertNull(controller.state.value.sessionId)
    }

    @Test fun `canceling confirmation never forks and concurrent Codex ignores legacy owner`() = runTest {
        val api = Gateway().apply { session = session.copy(agentState = AgentState(controlledByUser = true),
            metadata = session.metadata!!.copy(capabilities = session.metadata!!.capabilities!!.copy(concurrentClients = true))) }
        val controller = controller(api); controller.select("source", "标题"); runCurrent(); assertTrue(controller.state.value.ready)
        controller.request(); assertEquals("标题", controller.state.value.dialog.request!!.preview)
        controller.dismiss(); controller.confirm(); runCurrent(); assertTrue(api.forks.isEmpty())
    }

    @Test fun `fresh busy state capability loss or new input invalidates confirmation`() = runTest {
        for (change in listOf<(Session) -> Session>({ it.copy(thinking = true) }, { it.copy(lastMessageAt = 11) },
            { it.copy(metadata = it.metadata!!.copy(capabilities = null)) })) {
            val api = Gateway(); val controller = controller(api)
            controller.select("source", "标题"); runCurrent(); controller.request(); api.session = change(api.session)
            controller.confirm(); runCurrent(); assertTrue(api.forks.isEmpty())
            assertEquals(HistoryErrorKind.Stale, controller.state.value.dialog.error?.kind)
            controller.confirm(); runCurrent(); assertTrue(api.forks.isEmpty())
        }
    }

    @Test fun `late selection result cannot change another session or a dismissed menu`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val api = object : SessionForkGateway {
            override suspend fun load(sessionId: String): Session { if (sessionId == "old") gate.await(); return session(sessionId) }
            override suspend fun fork(sessionId: String) = error("Unexpected fork")
        }
        val controller = controller(api)
        controller.select("old", "old"); runCurrent(); controller.select("new", "new"); runCurrent(); gate.complete(Unit); runCurrent()
        assertEquals("new", controller.state.value.sessionId); assertTrue(controller.state.value.ready)
        controller.select("old", "old"); controller.dismiss(); runCurrent(); assertNull(controller.state.value.sessionId)
    }

    @Test fun `load failure reports once and reopening the menu can recover`() = runTest {
        val errors = mutableListOf<String>()
        val api = Gateway().apply { failLoad = true }; val controller = controller(api, onError = { id, _ -> errors += id })
        controller.select("source", "标题"); runCurrent(); assertEquals(listOf("source"), errors); assertFalse(controller.state.value.showAction)
        api.failLoad = false; controller.select("source", "标题"); runCurrent(); assertTrue(controller.state.value.ready)
    }

    @Test fun `failed fork waits for an explicit retry and refreshes again before retry`() = runTest {
        val api = Gateway().apply { failFork = true }; val controller = controller(api)
        controller.select("source", "标题"); runCurrent(); controller.request(); controller.confirm(); runCurrent()
        assertEquals(listOf("source"), api.forks); assertEquals(HistoryErrorKind.RequestFailed, controller.state.value.dialog.error?.kind)
        runCurrent(); assertEquals(1, api.forks.size)
        api.failFork = false; controller.confirm(); runCurrent(); assertEquals(2, api.forks.size); assertEquals("child", controller.state.value.dialog.forkedSessionId)
    }

    @Test fun `missing gateway does not offer a fork action`() = runTest {
        val controller = controller(null); assertFalse(controller.select("source", "标题"))
        controller.request(); controller.confirm(); runCurrent(); assertFalse(controller.state.value.showAction)
    }
}
