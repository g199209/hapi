package app.hapi.companion.feature.chat

import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.unit.dp
import app.hapi.companion.ui.markdown.LocalMarkdownRenderCache
import app.hapi.companion.ui.markdown.MarkdownRenderCache
import app.hapi.companion.ui.theme.HapiTheme
import app.hapi.data.api.MessagesApi
import app.hapi.data.api.MessagesQuery
import app.hapi.data.store.ChatHistoryPagingState
import app.hapi.data.store.MessageWindowStore
import app.hapi.protocol.chat.AgentTextBlock
import app.hapi.protocol.window.MessagePosition
import app.hapi.protocol.window.MessageViewMode
import app.hapi.protocol.window.MessageWindowState
import app.hapi.protocol.window.asWindowMessage
import app.hapi.protocol.wire.DecryptedMessage
import app.hapi.protocol.wire.MessagesPage
import app.hapi.protocol.wire.MessagesResponse
import app.hapi.protocol.wire.OptionalField
import app.hapi.protocol.wire.QueuedStateResponse
import app.hapi.protocol.wire.SyncEvent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Exercise the real SSE window transitions together with the real LazyColumn. */
class ChatLiveReadingTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var list: LazyListState
    private lateinit var scope: CoroutineScope
    private lateinit var store: MessageWindowStore
    private lateinit var reading: TranscriptReadingState
    private var responseGate: CompletableDeferred<Unit>? = null
    private var epoch = 1L
    private val initial = (1L..300L).map { row(it) }
    private var server = initial
    private val queries = mutableListOf<MessagesQuery>()
    private fun row(seq: Long, at: Long = seq * 1000) = DecryptedMessage(
        id = "message-$seq", seq = seq, createdAt = at, invokedAt = OptionalField.Present(at), content = JsonPrimitive("Message $seq"),
    )
    private fun text(row: DecryptedMessage) = "Message ${row.seq}\n\n" + "Reading older output. ".repeat(3)

    private fun mount() {
        val markdown = MarkdownRenderCache().apply { prepare((initial + row(999, 20000)).map(::text).toSet()) }
        compose.setContent {
            list = rememberLazyListState()
            scope = rememberCoroutineScope()
            reading = remember { TranscriptReadingState() }
            store = remember {
                val api = object : MessagesApi {
                    override suspend fun getMessages(sessionId: String, query: MessagesQuery): MessagesResponse {
                        queries += query
                        val snapshot = server
                        val version = epoch
                        responseGate?.await()
                        val rows = when (query) {
                            is MessagesQuery.Before -> snapshot.filter { MessagePosition(it.positionAt, it.seq!!) < MessagePosition(query.beforeAt, query.beforeSeq) }.takeLast(query.limit)
                            else -> snapshot.takeLast(query.limit)
                        }
                        val first = rows.firstOrNull()
                        return MessagesResponse(rows, MessagesPage(
                            direction = if (query is MessagesQuery.Before) "before" else "latest", limit = query.limit,
                            epoch = version, reset = false, hasMore = first != null && snapshot.first().positionAt < first.positionAt,
                            nextBeforeAt = first?.positionAt, nextBeforeSeq = first?.seq,
                            snapshotHeadAt = snapshot.last().positionAt, snapshotHeadSeq = snapshot.last().seq,
                        ))
                    }
                    override suspend fun getQueuedState(sessionId: String, localIds: List<String>) = QueuedStateResponse(emptyList(), emptyList())
                }
                MessageWindowStore("live-reading", api, scope, initialState = MessageWindowState(
                    "live-reading", messages = initial.map { it.asWindowMessage() }, epoch = 1, hasMore = false,
                    oldestPosition = MessagePosition(1000, 1), newestPosition = MessagePosition(300000, 300),
                ))
            }
            val window by store.state.collectAsState()
            val state = ChatUiState(
                sessionId = "live-reading", header = ChatHeaderUi("Live reading", subtitle = null, active = true, thinking = true),
                flavor = null, basePath = null, blocks = window.messages.map { msg -> AgentTextBlock(
                    id = msg.id, localId = null, createdAt = msg.createdAt, invokedAt = msg.positionAt, text = text(msg.wire), meta = null,
                ) }, permissionOverrides = emptyMap(), hasMore = window.hasMore, isLoadingOlder = false,
                isSyncingTail = window.isSyncingTail, isInitialLoading = false, loadFailed = false, warning = window.warning,
                tailRevision = window.tailRevision, messagesVersion = window.messagesVersion, historyVersion = window.historyVersion,
                requiresLatestReset = window.requiresLatestReset,
            )
            HapiTheme {
                CompositionLocalProvider(LocalMarkdownRenderCache provides markdown) {
                    ChatTranscript(state, ChatHistoryPagingState(), 0, false,
                        { follows, _ -> scope.launch { store.setViewMode(if (follows) MessageViewMode.Tail else MessageViewMode.History) } },
                        { _, _ -> }, {}, {}, Modifier.height(500.dp), list, reading)
                }
            }
        }
        compose.waitForIdle()
    }

    private fun anchor() = compose.runOnIdle {
        list.layoutInfo.visibleItemsInfo.first { it.index >= list.firstVisibleItemIndex && it.index > 0 }.let { it.key to it.offset }
    }

    @Test fun backdatedToolInvalidationAndOngoingOutputKeepTheHistoryAnchor() {
        mount()
        compose.onNodeWithTag("chat-transcript").performTouchInput { swipeDown(durationMillis = 400) }
        compose.runOnIdle { scope.launch { list.scrollToItem(40, 25) } }
        compose.waitForIdle()
        val before = anchor()
        repeat(3) { iteration ->
            compose.runOnIdle {
                assertFalse(reading.followsTail)
                responseGate = CompletableDeferred()
                server = (server + row(999L + iteration, 20000L + iteration)).sortedBy { it.positionAt }
                epoch += 1
                scope.launch { store.onMessageEvent(SyncEvent.MessagesInvalidated(sessionId = "live-reading")) }
            }
            compose.waitForIdle()
            assertEquals("No empty/latest frame while refreshing", before, anchor())
            compose.runOnIdle { responseGate!!.complete(Unit); responseGate = null }
            compose.waitForIdle()
            assertEquals(before, anchor())
            compose.runOnIdle {
                assertFalse(reading.followsTail)
                assertFalse(store.state.value.isSyncingTail)
                assertEquals(epoch, store.state.value.epoch)
                scope.launch { store.ingestSseMessages(listOf(row(1000L + iteration, 400000L + iteration).asWindowMessage())) }
            }
            compose.waitForIdle()
            assertEquals(before, anchor())
        }
        assertTrue(queries.all { it is MessagesQuery.Before })
    }

    @Test fun invalidationWhileFollowingStillShowsTheLiveBottom() {
        mount()
        compose.runOnIdle {
            assertTrue(reading.followsTail)
            server = server + row(999, 400000)
            epoch += 1
            scope.launch { store.onMessageEvent(SyncEvent.MessagesInvalidated(sessionId = "live-reading")) }
        }
        compose.waitForIdle()
        compose.runOnIdle {
            assertTrue(reading.followsTail)
            assertFalse(list.canScrollForward)
            assertEquals("message-999", list.layoutInfo.visibleItemsInfo.last().key)
        }
        assertTrue(queries.first() is MessagesQuery.Latest)
    }
}
