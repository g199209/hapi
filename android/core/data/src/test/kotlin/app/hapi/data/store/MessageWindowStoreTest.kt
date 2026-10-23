package app.hapi.data.store

import app.hapi.data.api.MessagesApi
import app.hapi.data.api.MessagesQuery
import app.hapi.protocol.window.MessageStatus
import app.hapi.protocol.window.MessageWindowLogic
import app.hapi.protocol.window.MessageWindowState
import app.hapi.protocol.window.MessageViewMode
import app.hapi.protocol.window.MessagePosition
import app.hapi.protocol.window.OlderLoadOutcome
import app.hapi.protocol.window.PersistedMessageWindow
import app.hapi.protocol.window.INITIAL_PAGE_SIZE
import app.hapi.protocol.window.PAGE_SIZE
import app.hapi.protocol.window.asWindowMessage
import app.hapi.protocol.window.buildOptimisticMessage
import app.hapi.protocol.wire.DecryptedMessage
import app.hapi.protocol.wire.MessagesPage
import app.hapi.protocol.wire.MessagesResponse
import app.hapi.protocol.wire.OptionalField
import app.hapi.protocol.wire.QueuedStateResponse
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Targeted coverage for what the pagination fixtures cannot reach: tail-sync
 * single-flight/trailing behavior, concurrent-ingest preservation across a
 * reset replace (identity baseline), the cursor-no-advance guard, snapshot
 * round-trips + LRU, seed-on-resume, and Mutex discipline under parallelism.
 */
class MessageWindowStoreTest {

    @get:Rule
    val temp = TemporaryFolder()

    // ------------------------------------------------------------- fixtures --

    private fun agentRow(id: String, seq: Long, at: Long): DecryptedMessage = DecryptedMessage(
        id = id,
        seq = seq,
        createdAt = at,
        invokedAt = OptionalField.Present(at),
        content = buildJsonObject {
            put("role", "agent")
            put("content", buildJsonObject {
                put("type", "codex")
                put("data", buildJsonObject {
                    put("type", "message")
                    put("message", id)
                })
            })
        },
    )

    private fun latestPage(
        messages: List<DecryptedMessage>,
        epoch: Long,
        reset: Boolean = false,
        hasMore: Boolean = false,
    ): MessagesResponse {
        val newest = messages.lastOrNull()
        val oldest = messages.firstOrNull()
        return MessagesResponse(
            messages = messages,
            page = MessagesPage(
                direction = "latest",
                limit = 200,
                epoch = epoch,
                reset = reset,
                nextBeforeSeq = oldest?.seq,
                nextBeforeAt = oldest?.positionAt,
                nextAfterSeq = null,
                nextAfterAt = null,
                snapshotHeadSeq = newest?.seq,
                snapshotHeadAt = newest?.positionAt,
                hasMore = hasMore,
            ),
        )
    }

    private fun afterPage(
        messages: List<DecryptedMessage>,
        epoch: Long,
        nextAfter: Pair<Long, Long>?,
        hasMore: Boolean,
    ): MessagesResponse = MessagesResponse(
        messages = messages,
        page = MessagesPage(
            direction = "after",
            limit = 200,
            epoch = epoch,
            reset = false,
            nextBeforeSeq = null,
            nextBeforeAt = null,
            nextAfterSeq = nextAfter?.second,
            nextAfterAt = nextAfter?.first,
            snapshotHeadSeq = nextAfter?.second,
            snapshotHeadAt = nextAfter?.first,
            hasMore = hasMore,
        ),
    )

    /** Api whose responses are released manually, so tests control interleaving. */
    private class GatedMessagesApi : MessagesApi {
        val requests = mutableListOf<MessagesQuery>()
        private val responses = Channel<MessagesResponse>(Channel.UNLIMITED)

        fun release(response: MessagesResponse) {
            check(responses.trySend(response).isSuccess)
        }

        override suspend fun getMessages(sessionId: String, query: MessagesQuery): MessagesResponse {
            requests += query
            return responses.receive()
        }

        override suspend fun getQueuedState(sessionId: String, localIds: List<String>): QueuedStateResponse =
            QueuedStateResponse(emptyList(), emptyList())
    }

    // ------------------------------------------------------------ tail sync --

    private fun historyState(rows: List<DecryptedMessage>) = MessageWindowState(
        "history", messages = rows.map { it.asWindowMessage() }, viewMode = MessageViewMode.History,
        epoch = 1, hasMore = true, historyVersion = 7, messagesVersion = 12,
        oldestPosition = rows.first().let { MessagePosition(it.positionAt, it.seq!!) },
        newestPosition = rows.last().let { MessagePosition(it.positionAt, it.seq!!) },
    )

    private fun beforePage(rows: List<DecryptedMessage>, epoch: Long, hasMore: Boolean = true) =
        latestPage(rows, epoch, hasMore = hasMore).let { it.copy(page = it.page.copy(direction = "before")) }

    @Test fun `invalidation revalidates the reading range without clearing it or loading latest`() = runTest {
        val api = GatedMessagesApi()
        val initial = historyState((100L..399L).map { agentRow("a-$it", it, it * 1000) })
        val store = MessageWindowStore("history", api, backgroundScope, initialState = initial)
        store.onMessageEvent(app.hapi.protocol.wire.SyncEvent.MessagesInvalidated(sessionId = "history"))
        runCurrent()
        assertEquals(initial.messages, store.state.value.messages)
        assertNull(store.state.value.epoch)
        assertTrue(store.state.value.isSyncingTail)
        assertEquals(listOf<MessagesQuery>(MessagesQuery.Before(399000, 400, 200)), api.requests)
        // A backdated tool result is inserted; an obsolete row disappears.
        api.release(beforePage((200L..399L).filter { it != 250L }.map { agentRow("a-$it", it, it * 1000) } +
            agentRow("late-result", 900, 250001), 2))
        runCurrent()
        assertEquals(initial.messages, store.state.value.messages) // No partial-page replacement.
        api.release(beforePage((1L..199L).map { agentRow("a-$it", it, it * 1000) }, 2))
        runCurrent()
        val state = store.state.value
        assertEquals(2, api.requests.size)
        assertTrue(api.requests.all { it is MessagesQuery.Before })
        assertEquals(300, state.messages.size)
        assertEquals("a-100", state.messages.first().id)
        assertEquals("a-399", state.messages.last().id)
        assertTrue(state.messages.first() === initial.messages.first())
        assertTrue(state.messages.last() === initial.messages.last())
        assertTrue(state.messages.any { it.id == "late-result" })
        assertTrue(state.messages.none { it.id == "a-250" })
        assertEquals(2L, state.epoch)
        assertEquals(MessageViewMode.History, state.viewMode)
        assertEquals(8L, state.historyVersion)
        assertTrue(state.messagesVersion > initial.messagesVersion)
        assertTrue(state.requiresLatestReset)
        assertEquals(false, state.isSyncingTail)
    }

    @Test fun `history refresh failure retains rows and retry revalidates instead of jumping`() = runTest {
        var fail = true
        val rows = (100L..110L).map { agentRow("a-$it", it, it * 1000) }
        val api = object : MessagesApi {
            override suspend fun getMessages(sessionId: String, query: MessagesQuery): MessagesResponse {
                assertTrue(query is MessagesQuery.Before)
                if (fail) error("offline")
                return beforePage(rows, 2)
            }
            override suspend fun getQueuedState(sessionId: String, localIds: List<String>) = QueuedStateResponse(emptyList(), emptyList())
        }
        val store = MessageWindowStore("history", api, backgroundScope, initialState = historyState(rows))
        store.onMessageEvent(app.hapi.protocol.wire.SyncEvent.MessagesInvalidated(sessionId = "history"))
        runCurrent()
        assertEquals(rows.map { it.id }, store.state.value.messages.map { it.id })
        assertEquals("offline", store.state.value.warning)
        assertTrue(store.fetchOlder() is OlderLoadOutcome.Failed)
        assertNull(store.state.value.epoch)
        fail = false
        store.syncTail()
        assertEquals(2L, store.state.value.epoch)
        assertNull(store.state.value.warning)
    }

    @Test fun `a second invalidation rejects every page of the superseded history refresh`() = runTest {
        val rows = listOf(agentRow("kept", 100, 100000), agentRow("removed", 101, 101000))
        val responses = List(2) { kotlinx.coroutines.CompletableDeferred<MessagesResponse>() }
        var requests = 0
        val api = object : MessagesApi {
            override suspend fun getMessages(sessionId: String, query: MessagesQuery) = responses[requests++].await()
            override suspend fun getQueuedState(sessionId: String, localIds: List<String>) = QueuedStateResponse(emptyList(), emptyList())
        }
        val store = MessageWindowStore("history", api, backgroundScope, initialState = historyState(rows))
        repeat(2) {
            store.onMessageEvent(app.hapi.protocol.wire.SyncEvent.MessagesInvalidated(sessionId = "history"))
            runCurrent()
        }
        responses[1].complete(beforePage(rows.take(1), 3))
        runCurrent()
        responses[0].complete(beforePage(rows, 2))
        runCurrent()
        assertEquals(listOf("kept"), store.state.value.messages.map { it.id })
        assertEquals(3L, store.state.value.epoch)
    }

    @Test fun `history refresh refuses mixed epochs and keeps the reading snapshot`() = runTest {
        val api = GatedMessagesApi()
        val initial = historyState((100L..399L).map { agentRow("a-$it", it, it * 1000) })
        val store = MessageWindowStore("history", api, backgroundScope, initialState = initial)
        store.onMessageEvent(app.hapi.protocol.wire.SyncEvent.MessagesInvalidated(sessionId = "history"))
        runCurrent()
        api.release(beforePage((200L..399L).map { agentRow("a-$it", it, it * 1000) }, 2))
        runCurrent()
        api.release(beforePage((100L..199L).map { agentRow("a-$it", it, it * 1000) }, 3))
        runCurrent()
        assertEquals(initial.messages, store.state.value.messages)
        assertNull(store.state.value.epoch)
        assertEquals(false, store.state.value.isSyncingTail)
        assertTrue(store.state.value.warning!!.contains("History changed"))
    }

    @Test fun `replay gap with an epoch reset refreshes history in place`() = runTest {
        val api = GatedMessagesApi()
        val rows = listOf(agentRow("reading", 100, 100000), agentRow("reading-next", 101, 101000))
        val store = MessageWindowStore("history", api, backgroundScope, initialState = historyState(rows))
        val sync = backgroundScope.launch { store.syncTail() }
        runCurrent()
        api.release(latestPage(listOf(agentRow("newest", 999, 999000)), 2, reset = true, hasMore = true))
        runCurrent()
        assertEquals(rows.map { it.id }, store.state.value.messages.map { it.id })
        assertTrue(api.requests.last() is MessagesQuery.Before)
        api.release(beforePage(rows, 2))
        sync.join()
        assertEquals(rows.map { it.id }, store.state.value.messages.map { it.id })
        assertEquals(2L, store.state.value.epoch)
    }

    @Test fun `explicit clear prevents a late history refresh from resurrecting rows`() = runTest {
        val api = GatedMessagesApi()
        val rows = listOf(agentRow("reading", 100, 100000))
        val store = MessageWindowStore("history", api, backgroundScope, initialState = historyState(rows))
        store.onMessageEvent(app.hapi.protocol.wire.SyncEvent.MessagesInvalidated(sessionId = "history"))
        runCurrent()
        store.clear()
        api.release(beforePage(rows, 2))
        runCurrent()
        assertTrue(store.state.value.messages.isEmpty())
        assertNull(store.state.value.epoch)
    }

    @Test fun `history refresh keeps queued drafts and explicit latest still catches up`() = runTest {
        val api = GatedMessagesApi()
        val rows = listOf(agentRow("reading", 100, 100000))
        val pending = buildOptimisticMessage(localId = "draft", text = "Next input", createdAt = 500000, status = MessageStatus.Sending)
        val initial = historyState(rows).copy(messages = rows.map { it.asWindowMessage() } + pending)
        val store = MessageWindowStore("history", api, backgroundScope, initialState = initial)
        store.onMessageEvent(app.hapi.protocol.wire.SyncEvent.MessagesInvalidated(sessionId = "history"))
        runCurrent()
        api.release(beforePage(rows, 2))
        runCurrent()
        assertTrue(store.state.value.messages.any { it.id == "draft" && it.status == MessageStatus.Sending })
        val latest = backgroundScope.launch { store.syncTail(allowingHistoryReset = true) }
        runCurrent()
        assertTrue(api.requests.last() is MessagesQuery.Latest)
        api.release(latestPage(listOf(agentRow("live", 999, 999000)), 2, hasMore = true))
        latest.join()
        assertEquals(listOf("draft", "live"), store.state.value.messages.map { it.id })
        assertEquals(false, store.state.value.requiresLatestReset)
    }

    @Test
    fun `history reset poisons older requests and reloads authoritative epoch without resurrecting removed rows`() = runTest {
        val stale = kotlinx.coroutines.CompletableDeferred<MessagesResponse>()
        var latestCount = 0
        val api = object : MessagesApi {
            override suspend fun getMessages(sessionId: String, query: MessagesQuery): MessagesResponse {
                if (query is MessagesQuery.After) return stale.await()
                return if (++latestCount == 1) latestPage(listOf(agentRow("kept", 1, 100), agentRow("removed", 2, 200)), 1)
                    else latestPage(listOf(agentRow("kept", 1, 100)), 2)
            }
            override suspend fun getQueuedState(sessionId: String, localIds: List<String>) = QueuedStateResponse(queuedLocalIds = emptyList(), invokedLocalMessages = emptyList())
        }
        val store = MessageWindowStore("s", api, backgroundScope)
        store.syncTail()
        val previous = backgroundScope.launch { store.syncTail() }
        runCurrent()
        store.clear()
        store.syncTail()
        stale.complete(afterPage(listOf(agentRow("removed", 2, 200)), 1, 200L to 2L, false))
        previous.join()
        assertEquals(listOf("kept"), store.state.value.messages.map { it.id })
        assertEquals(2L, store.state.value.epoch)
    }

    @Test fun `native history retains 800 rows across SSE and preserves policy on clear`() = runTest {
        val initial = MessageWindowState(
            sessionId = "history", viewMode = MessageViewMode.History, epoch = 1,
            messages = (1L..800L).map { agentRow("a-$it", it, it * 1000).asWindowMessage() },
        )
        val api = GatedMessagesApi()
        val store = MessageWindowStore("history", api, backgroundScope,
            initialState = initial, historyRetentionLimit = 800)
        store.ingestSseMessages(listOf(agentRow("new", 801, 801000).asWindowMessage()))
        assertEquals(800, store.state.value.messages.size)
        assertEquals("a-1", store.state.value.messages.first().id)
        assertTrue(store.state.value.requiresLatestReset)
        val history = store.state.value
        api.release(latestPage(listOf(agentRow("new", 801, 801000)), epoch = 1))
        store.reconcileQueuedState()
        assertEquals(listOf<MessagesQuery>(MessagesQuery.Latest(limit = 200)), api.requests)
        val validated = store.state.value
        assertEquals(history.syncGeneration + 1, validated.syncGeneration)
        assertEquals(history.messages, validated.messages)
        assertEquals(history.oldestPosition, validated.oldestPosition)
        assertEquals(history.newestPosition, validated.newestPosition)
        assertEquals(history.hasMore, validated.hasMore)
        assertEquals(history.historyVersion, validated.historyVersion)
        assertTrue(validated.requiresLatestReset)
        assertEquals(false, validated.isSyncingTail)
        store.setViewMode(MessageViewMode.Tail)
        assertEquals(400, store.state.value.messages.size)
        assertNull(store.state.value.epoch)
        store.clear()
        assertEquals(800, store.state.value.historyRetentionLimit)
    }

    @Test fun `gap recovery replaces truncated history on epoch change or explicit reset`() = runTest {
        for ((epoch, reset) in listOf(2L to false, 1L to true)) {
            val api = GatedMessagesApi()
            val store = MessageWindowStore("history", api, backgroundScope, initialState = MessageWindowState(
                sessionId = "history", viewMode = MessageViewMode.History, epoch = 1,
                requiresLatestReset = true, hasMore = true,
                oldestPosition = MessagePosition(1000, 1), newestPosition = MessagePosition(1000, 1),
                messages = listOf(agentRow("obsolete", 1, 1000).asWindowMessage()),
            ))
            // Empty authoritative latest also covers deleted history.
            api.release(latestPage(emptyList(), epoch = epoch, reset = reset))
            store.reconcileQueuedState()
            assertEquals(1, api.requests.size)
            assertEquals(epoch, store.state.value.epoch)
            assertTrue(store.state.value.messages.isEmpty())
            assertEquals(false, store.state.value.requiresLatestReset)
            assertEquals(false, store.state.value.hasMore)
        }
    }

    @Test fun `gap recovery drains a fresh epoch check while truncated history sync is in flight`() = runTest {
        val api = GatedMessagesApi()
        val store = MessageWindowStore("history", api, backgroundScope, initialState = MessageWindowState(
            sessionId = "history", viewMode = MessageViewMode.History, epoch = 1,
            requiresLatestReset = true, messages = listOf(agentRow("obsolete", 1, 1000).asWindowMessage()),
        ))
        val first = launch { store.syncTail() }
        runCurrent()
        val recovery = launch { store.reconcileQueuedState() }
        runCurrent()
        assertEquals(1, api.requests.size)
        api.release(latestPage(emptyList(), epoch = 1))
        first.join()
        runCurrent()
        assertEquals(listOf<MessagesQuery>(MessagesQuery.Latest(200), MessagesQuery.Latest(200)), api.requests)
        assertEquals(listOf("obsolete"), store.state.value.messages.map { it.id })
        assertEquals(false, recovery.isCompleted)
        api.release(latestPage(listOf(agentRow("replacement", 2, 2000)), epoch = 2))
        runCurrent()
        assertTrue(api.requests.last() is MessagesQuery.Before)
        // The old range was deleted; do not replace it with the newer live tail.
        api.release(beforePage(emptyList(), epoch = 2, hasMore = false))
        recovery.join()
        assertEquals(2L, store.state.value.epoch)
        assertTrue(store.state.value.messages.isEmpty())
    }

    @Test fun `older cursor stall is paused without applying an empty page`() = runTest {
        val api = GatedMessagesApi()
        val initial = MessageWindowState("history", hasMore = true, epoch = 1,
            oldestPosition = MessagePosition(1000, 1))
        api.release(MessagesResponse(emptyList(), MessagesPage(
            direction = "before", limit = 200, epoch = 1, reset = false,
            nextBeforeAt = 1000, nextBeforeSeq = 1, nextAfterAt = null, nextAfterSeq = null,
            snapshotHeadAt = null, snapshotHeadSeq = null, hasMore = true,
        )))
        val store = MessageWindowStore("history", api, backgroundScope, initialState = initial)
        assertEquals(OlderLoadOutcome.Stopped(OlderLoadOutcome.StopReason.CursorDidNotAdvance), store.fetchOlder())
        assertEquals(0, store.state.value.messages.size)
        assertTrue(store.state.value.hasMore)
        assertEquals(false, store.state.value.isLoadingMore)
    }

    @Test fun `cancelled fetch releases reservation and cannot overwrite a new request`() = runTest {
        val api = GatedMessagesApi()
        val store = MessageWindowStore("history", api, backgroundScope,
            initialState = MessageWindowState("history", hasMore = true, epoch = 1,
                oldestPosition = MessagePosition(1000, 1)))
        val request = backgroundScope.launch { store.fetchOlder() }
        runCurrent()
        assertTrue(store.state.value.isLoadingMore)
        assertEquals(OlderLoadOutcome.Stopped(OlderLoadOutcome.StopReason.Busy), store.fetchOlder())
        request.cancel()
        request.join()
        assertEquals(false, store.state.value.isLoadingMore)
    }

    @Test
    fun `cold sync requests the initial page size`() = runTest {
        val api = GatedMessagesApi()
        val store = MessageWindowStore("s", api, backgroundScope)

        val sync = launch { store.syncTail() }
        runCurrent()
        assertEquals(1, api.requests.size)
        assertTrue(api.requests.single() is MessagesQuery.Latest)
        assertEquals(INITIAL_PAGE_SIZE, (api.requests.single() as MessagesQuery.Latest).limit)

        api.release(latestPage(listOf(agentRow("a-1", 1, 1000)), epoch = 0))
        sync.join()
    }

    @Test
    fun `reset sync keeps the full latest page size`() = runTest {
        val api = GatedMessagesApi()
        val initial = MessageWindowLogic.createState("s").copy(requiresLatestReset = true)
        val store = MessageWindowStore("s", api, backgroundScope, initialState = initial)

        val sync = launch { store.syncTail() }
        runCurrent()
        assertEquals(1, api.requests.size)
        assertTrue(api.requests.single() is MessagesQuery.Latest)
        assertEquals(PAGE_SIZE, (api.requests.single() as MessagesQuery.Latest).limit)

        api.release(latestPage(listOf(agentRow("a-1", 1, 1000)), epoch = 1))
        sync.join()
    }

    @Test
    fun `concurrent syncTail calls coalesce into a single run`() = runTest {
        val api = GatedMessagesApi()
        val store = MessageWindowStore("s", api, backgroundScope)

        val first = launch { store.syncTail() }
        runCurrent()
        assertEquals(1, api.requests.size)

        val second = launch { store.syncTail() }
        runCurrent()
        // The second caller joined the in-flight run instead of issuing its own.
        assertEquals(1, api.requests.size)

        api.release(latestPage(listOf(agentRow("a-1", 1, 1000)), epoch = 0))
        first.join()
        second.join()
        assertEquals(1, api.requests.size)
        assertEquals(listOf("a-1"), store.state.value.messages.map { it.id })
    }

    @Test
    fun `ensureAfterCurrent requests a trailing run and drains it`() = runTest {
        val api = GatedMessagesApi()
        val store = MessageWindowStore("s", api, backgroundScope)

        val first = launch { store.syncTail() }
        runCurrent()
        assertEquals(1, api.requests.size)

        val drain = launch { store.syncTail(ensureAfterCurrent = true) }
        runCurrent()
        assertEquals(1, api.requests.size)

        api.release(latestPage(listOf(agentRow("a-1", 1, 1000)), epoch = 0))
        first.join()
        runCurrent()
        // The trailing run started and issued its own (after-cursor) request.
        assertEquals(2, api.requests.size)
        assertTrue(api.requests[1] is MessagesQuery.After)
        assertTrue(drain.isActive)

        api.release(afterPage(emptyList(), epoch = 0, nextAfter = 1000L to 1L, hasMore = false))
        drain.join()
        assertEquals(2, api.requests.size)
    }

    @Test
    fun `reset replace preserves rows that arrived while the request was in flight`() = runTest {
        val api = GatedMessagesApi()
        val store = MessageWindowStore("s", api, backgroundScope)

        // Seed epoch + cursor with a completed latest sync.
        val seed = launch { store.syncTail() }
        runCurrent()
        api.release(latestPage(listOf(agentRow("a-1", 1, 1000)), epoch = 0))
        seed.join()

        // Second sync goes down the after-cursor path and hangs on the api.
        val sync = launch { store.syncTail() }
        runCurrent()
        assertTrue(api.requests.last() is MessagesQuery.After)

        // A live SSE row lands while the request is in flight.
        store.ingestSseMessages(listOf(agentRow("b-2", 2, 2000).asWindowMessage()))

        // The server answers with a reset page that does not contain b-2.
        api.release(latestPage(listOf(agentRow("c-3", 3, 3000)), epoch = 1, reset = true))
        sync.join()

        val state = store.state.value
        // a-1 (captured in the request baseline) was replaced; the concurrent
        // b-2 and the authoritative c-3 both survive.
        assertEquals(listOf("b-2", "c-3"), state.messages.map { it.id })
        assertEquals(1L, state.epoch)
        assertEquals(3000L to 3L, state.newestPosition!!.let { it.at to it.seq })
    }

    @Test
    fun `a tail cursor that does not advance aborts with a warning instead of spinning`() = runTest {
        val api = GatedMessagesApi()
        val store = MessageWindowStore("s", api, backgroundScope)

        val seed = launch { store.syncTail() }
        runCurrent()
        api.release(latestPage(listOf(agentRow("a-1", 1, 1000)), epoch = 0))
        seed.join()

        val sync = launch { store.syncTail() }
        runCurrent()
        // hasMore with a non-advancing nextAfter is a protocol violation.
        api.release(afterPage(listOf(agentRow("b-2", 2, 2000)), epoch = 0, nextAfter = 1000L to 1L, hasMore = true))
        sync.join()

        val state = store.state.value
        assertEquals("Message tail cursor did not advance", state.warning)
        assertTrue(!state.isSyncingTail)
        assertEquals(2, api.requests.size)
    }

    // ------------------------------------------------------------ snapshots --

    @Test
    fun `snapshot round-trip restores interrupted sends and flags stale snapshots`() = runBlocking {
        val snapshots = WindowSnapshots(temp.newFolder(), io = Dispatchers.Unconfined)

        val queuedSending = buildOptimisticMessage(
            localId = "local-1",
            text = "hello",
            createdAt = 1000,
            status = MessageStatus.Sending,
        )
        val fresh = PersistedMessageWindow(
            messages = listOf(queuedSending, agentRow("a-1", 1, 500).asWindowMessage()),
            hasMore = true,
            oldestPositionAt = 500,
            oldestPositionSeq = 1,
            newestPositionAt = 500,
            newestPositionSeq = 1,
            epoch = 3,
        )
        snapshots.save("session-a", fresh)

        val loaded = snapshots.load("session-a")!!
        val hydrated = MessageWindowLogic.hydrate("session-a", loaded)
        // Interrupted `sending` on a queued row restores to `queued`.
        assertEquals(
            MessageStatus.Queued,
            hydrated.messages.first { it.id == "local-1" }.status,
        )
        assertEquals(3L, hydrated.epoch)
        assertTrue(!hydrated.requiresLatestReset)
        assertEquals(500L to 1L, hydrated.newestPosition!!.let { it.at to it.seq })

        // A snapshot without a usable epoch hydrates flagged for a latest reset.
        val stale = fresh.copy(epoch = null)
        snapshots.save("session-b", stale)
        val staleHydrated = MessageWindowLogic.hydrate("session-b", snapshots.load("session-b")!!)
        assertTrue(staleHydrated.requiresLatestReset)
        assertNull(staleHydrated.epoch)
    }

    @Test
    fun `snapshots are LRU-capped at ten sessions`() = runBlocking {
        val dir = temp.newFolder()
        val snapshots = WindowSnapshots(dir, io = Dispatchers.Unconfined)
        repeat(12) { index ->
            snapshots.save(
                "session-$index",
                PersistedMessageWindow(messages = listOf(agentRow("a-$index", 1, 1000).asWindowMessage())),
            )
        }
        val files = dir.listFiles { file: File -> file.name.endsWith(".window.json") }.orEmpty()
        assertEquals(10, files.size)
    }

    // ----------------------------------------------------------------- seed --

    @Test
    fun `seedFrom carries rows and older cursor but forces a latest reset`() = runTest {
        val api = GatedMessagesApi()
        val source = MessageWindowStore("old-session", api, backgroundScope)
        val seed = launch { source.syncTail() }
        runCurrent()
        api.release(latestPage(listOf(agentRow("a-1", 1, 1000)), epoch = 5, hasMore = true))
        seed.join()

        val target = MessageWindowStore("new-session", api, backgroundScope)
        target.seedFrom(source)

        val state = target.state.value
        assertEquals("new-session", state.sessionId)
        assertEquals(listOf("a-1"), state.messages.map { it.id })
        assertTrue(state.hasMore)
        assertTrue(state.requiresLatestReset)
        assertNull(state.epoch)
        assertNull(state.newestPosition)
        assertEquals(1000L to 1L, state.oldestPosition!!.let { it.at to it.seq })
    }

    // ------------------------------------------------------ mutex discipline --

    @Test
    fun `parallel mutations from real threads serialize through the state mutex`() = runBlocking {
        val api = GatedMessagesApi()
        val store = MessageWindowStore("s", api, this)
        withContext(Dispatchers.Default) {
            (0 until 64).map { index ->
                launch {
                    store.appendOptimistic(
                        localId = "local-$index",
                        text = "message $index",
                        createdAt = 1000L + index,
                    )
                    store.updateStatus("local-$index", MessageStatus.Queued)
                }
            }.forEach { it.join() }
        }
        val state = store.state.value
        assertEquals(64, state.messages.size)
        assertTrue(state.messages.all { it.status == MessageStatus.Queued && it.isQueuedForInvocation })
    }
}
