package app.hapi.companion.feature.chat

import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import app.hapi.companion.ui.markdown.LocalMarkdownRenderCache
import app.hapi.companion.ui.markdown.MarkdownRenderCache
import app.hapi.companion.ui.theme.HapiTheme
import app.hapi.data.store.ChatHistoryPagingState
import app.hapi.protocol.chat.AgentTextBlock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Real LazyColumn regressions for large pages and in-flight reader movement. */
class ChatTranscriptAnchorTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var list: LazyListState
    private lateinit var scope: CoroutineScope
    private val applied = AtomicLong(-1)
    private val needsOlder = AtomicBoolean(false)
    private val pageProgress = AtomicBoolean(false)
    private val needsOlderAtAck = AtomicBoolean(false)
    private val markdown = MarkdownRenderCache()
    private fun row(index: Int, extra: String = "") = AgentTextBlock(
        id = "message-$index", localId = null, createdAt = 100000L + index, invokedAt = 100000L + index,
        text = "Message $index\n\nReading content.$extra", meta = null,
    )
    private val state = mutableStateOf(ChatUiState(
        sessionId = "scroll-anchor-test", header = ChatHeaderUi("Anchor test", active = true, thinking = true, subtitle = null),
        flavor = null, basePath = null, blocks = (0..39).map { row(it) }, permissionOverrides = emptyMap(),
        hasMore = true, isLoadingOlder = false, isSyncingTail = false, isInitialLoading = false,
        loadFailed = false, warning = null, tailRevision = 0,
    ))
    private fun mount() {
        markdown.prepare(state.value.blocks.map { (it as AgentTextBlock).text }.toSet())
        compose.setContent {
            list = rememberLazyListState()
            scope = rememberCoroutineScope()
            val reading = remember { TranscriptReadingState(false) }
            HapiTheme {
                CompositionLocalProvider(LocalMarkdownRenderCache provides markdown) {
                    ChatTranscript(state.value, ChatHistoryPagingState(), 0, false, { _, needs -> needsOlder.set(needs) },
                        { version, progress -> needsOlderAtAck.set(needsOlder.get()); pageProgress.set(progress); applied.set(version) }, {}, {}, Modifier.height(500.dp), list, reading)
                }
            }
        }
        compose.waitUntil(10_000) { applied.get() == 0L }
    }
    private fun anchor() = compose.runOnIdle {
        list.layoutInfo.visibleItemsInfo.first { it.key != "chat-history-control" }.let { it.key.toString() to it.offset }
    }
    private fun investigate(label: String, index: Int, offset: Int, prependCount: Int = 200) {
        mount()
        compose.runOnIdle { scope.launch { list.scrollToItem(index, offset) } }
        compose.waitForIdle()
        val before = anchor()
        val beforeIndex = compose.runOnIdle { list.firstVisibleItemIndex }
        val beforeNeedsOlder = needsOlder.get()
        val grownTail = row(39, "\n\n" + "Agent still streaming. ".repeat(100))
        val next = (-prependCount..-1).map { row(it) } + state.value.blocks.dropLast(1) + grownTail
        markdown.prepare(next.map { (it as AgentTextBlock).text }.toSet())
        compose.runOnIdle { state.value = state.value.copy(blocks = next, historyVersion = 1, messagesVersion = 1) }
        compose.waitUntil(10_000) { applied.get() == 1L }
        compose.waitForIdle()
        // With the control previously at the top, new rows may fill its old
        // area. The original message must keep its own pixel position.
        val after = compose.runOnIdle {
            list.layoutInfo.visibleItemsInfo.firstOrNull { it.key == before.first }
                ?.let { it.key.toString() to it.offset }
        }
        val afterIndex = compose.runOnIdle { list.firstVisibleItemIndex }
        val result = JSONObject().put("case", label).put("beforeKey", before.first).put("beforeOffset", before.second)
            .put("afterKey", after?.first).put("afterOffset", after?.second).put("beforeIndex", beforeIndex)
            .put("afterIndex", afterIndex).put("prependedRows", prependCount).put("simultaneousTailGrowth", true)
            .put("beforeNeedsOlder", beforeNeedsOlder).put("afterNeedsOlder", needsOlder.get()).put("pageMadeProgress", pageProgress.get())
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        File(context.filesDir, "scroll-$label.json").writeText(result.toString(2))
        android.util.Log.i("HapiScrollInvestigation", result.toString())
        assertEquals("Prepending must retain the visible message even while the agent is streaming", before, after)
        assertEquals("Corrected viewport must be reported before acknowledging the page", false, needsOlderAtAck.get())
    }
    @Test fun repeatedFullPagesKeepTheSameMessageAndOffset() {
        mount()
        compose.runOnIdle { scope.launch { list.scrollToItem(4, 20) } }
        compose.waitForIdle()
        val before = anchor()
        repeat(3) { page ->
            val next = (-200 * (page + 1)..-200 * page - 1).map { row(it) } + state.value.blocks
            markdown.prepare(next.map { (it as AgentTextBlock).text }.toSet())
            compose.runOnIdle { state.value = state.value.copy(blocks = next.take(800), historyVersion = page + 1L, messagesVersion = page + 1L) }
            compose.waitUntil(10_000) { applied.get() == page + 1L }
            compose.waitForIdle()
            assertEquals(before, anchor())
        }
    }

    @Test fun boundedPagePreservesAnchorBeyondNearestKeyMap() {
        state.value = state.value.copy(blocks = (200..999).map { row(it) })
        mount()
        compose.runOnIdle { scope.launch { list.scrollToItem(4, 20) } }
        compose.waitForIdle()
        val before = anchor()
        val next = (0..199).map { row(it) } + state.value.blocks.dropLast(200)
        markdown.prepare(next.map { (it as AgentTextBlock).text }.toSet())
        compose.runOnIdle { state.value = state.value.copy(blocks = next, historyVersion = 1, messagesVersion = 1) }
        compose.waitUntil(10_000) { applied.get() == 1L }
        compose.waitForIdle()
        assertEquals(before, anchor())
    }

    @Test fun pageDuringFlingWaitsAndUsesTheReadersFinalPosition() {
        state.value = state.value.copy(blocks = (0..199).map { row(it) })
        mount()
        compose.runOnIdle { scope.launch { list.scrollToItem(30, 20) } }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        var finishedAnchor: Pair<String, Int>? = null
        lateinit var motion: kotlinx.coroutines.Job
        try {
            compose.runOnIdle {
                motion = scope.launch {
                    list.animateScrollBy(-1600f, tween(1000))
                    finishedAnchor = list.layoutInfo.visibleItemsInfo.first { it.key != "chat-history-control" }
                        .let { it.key.toString() to it.offset }
                }
            }
            compose.mainClock.advanceTimeBy(300)
            val next = (-200..-1).map { row(it) } + state.value.blocks
            markdown.prepare(next.map { (it as AgentTextBlock).text }.toSet())
            compose.runOnIdle { state.value = state.value.copy(blocks = next, historyVersion = 1, messagesVersion = 1) }
            compose.mainClock.advanceTimeBy(100)
            compose.runOnIdle {
                assertEquals("Page must not interrupt an active fling", true, motion.isActive)
                assertEquals("Pending page is not laid out mid-fling", 0L, applied.get())
                assertEquals(201, list.layoutInfo.totalItemsCount)
                // A newer streaming snapshot coalesces with the pending page.
                state.value = state.value.copy(blocks = next.dropLast(1) + row(199, "\n\n" + "Still streaming. ".repeat(100)), messagesVersion = 2)
            }
        } finally {
            compose.mainClock.autoAdvance = true
        }
        compose.waitForIdle()
        compose.waitUntil(10_000) { applied.get() == 1L }
        assertEquals(true, motion.isCompleted && !motion.isCancelled)
        assertEquals(finishedAnchor, anchor())
    }

    @Test fun pageDuringTouchDragDoesNotTakeOverThePointer() {
        state.value = state.value.copy(blocks = (0..199).map { row(it) })
        mount()
        compose.runOnIdle { scope.launch { list.scrollToItem(30, 20) } }
        compose.waitForIdle()
        val transcript = compose.onNodeWithTag("chat-transcript")
        transcript.performTouchInput {
            down(center)
            moveBy(Offset(0f, 100f), delayMillis = 100)
        }
        compose.runOnIdle { assertEquals(true, list.isScrollInProgress) }
        val next = (-200..-1).map { row(it) } + state.value.blocks
        markdown.prepare(next.map { (it as AgentTextBlock).text }.toSet())
        compose.runOnIdle { state.value = state.value.copy(blocks = next, historyVersion = 1, messagesVersion = 1) }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(true, list.isScrollInProgress)
            assertEquals(0L, applied.get())
        }
        val beforeMovement = anchor()
        transcript.performTouchInput { moveBy(Offset(0f, 120f), delayMillis = 100) }
        compose.waitForIdle()
        val atRelease = anchor()
        org.junit.Assert.assertNotEquals("The original pointer must still move the list", beforeMovement, atRelease)
        // A stationary pause before release avoids testing a second, variable fling.
        transcript.performTouchInput { advanceEventTime(300); up() }
        compose.waitUntil(10_000) { applied.get() == 1L }
        compose.waitForIdle()
        assertEquals(atRelease, anchor())
    }

    @Test fun historyControlFirstVisible() = investigate("header", 0, 0)
    @Test fun messageFirstVisibleLargePage() = investigate("message", 4, 20)
    @Test fun messageFirstVisibleSmallPageControl() = investigate("small", 4, 20, 20)
}
