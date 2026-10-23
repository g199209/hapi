package app.hapi.companion.feature.chat

import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import app.hapi.companion.feature.chat.blocks.previewToolCall
import app.hapi.companion.ui.markdown.LocalMarkdownRenderCache
import app.hapi.companion.ui.markdown.MarkdownRenderCache
import app.hapi.companion.ui.theme.HapiTheme
import app.hapi.data.store.ChatHistoryPagingState
import app.hapi.protocol.chat.*
import app.hapi.protocol.wire.HapiJson
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ChatActivityDisplayTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var list: LazyListState
    private lateinit var scope: CoroutineScope
    private lateinit var reading: TranscriptReadingState
    private val showActivity = mutableStateOf(false)
    private val version = AtomicLong(-1)
    private val markdown = MarkdownRenderCache()
    private fun text(i: Int) = AgentTextBlock("text-$i", null, i.toLong(), i.toLong(), text = "Message $i\n\nOlder conversation text.", meta = null)
    private fun thinking(i: Int) = AgentReasoningBlock("thinking-$i", null, i.toLong(), i.toLong(), text = "Thinking $i", meta = null)
    private fun tool(i: Int) = previewToolCall("tool-$i", "Read", input = mapOf("file_path" to "file-$i.kt"))
    private fun rows(range: IntRange): List<VisibleChatBlock> = range.flatMap { listOf(text(it), thinking(it), tool(it)) }
    private val state = mutableStateOf(ChatUiState(
        sessionId = "activity-test", header = ChatHeaderUi("Display test", null, active = true, thinking = true),
        flavor = "codex", basePath = null, blocks = emptyList(), permissionOverrides = emptyMap(),
        hasMore = true, isLoadingOlder = false, isSyncingTail = false, isInitialLoading = false,
        loadFailed = false, warning = null, tailRevision = 0,
    ))

    private fun mount(blocks: List<VisibleChatBlock>, onResolve: (String, PermissionAction) -> Unit = { _, _ -> }) {
        markdown.prepare(blocks.mapNotNull { when (it) { is AgentTextBlock -> it.text; is AgentReasoningBlock -> it.text; else -> null } }.toSet())
        state.value = state.value.copy(blocks = blocks)
        compose.setContent {
            list = rememberLazyListState()
            scope = rememberCoroutineScope()
            reading = remember { TranscriptReadingState(false) }
            HapiTheme {
                CompositionLocalProvider(
                    LocalMarkdownRenderCache provides markdown,
                    LocalChatInteractions provides ChatInteractions("codex", emptyMap(), onResolve, {}),
                ) {
                    ChatTranscript(state.value, ChatHistoryPagingState(), 0, false, { _, _ -> },
                        { laidOut, _ -> version.set(laidOut) }, {}, {}, Modifier.height(700.dp), list, reading, showActivity.value)
                }
            }
        }
        compose.waitUntil(10000) { version.get() == 0L }
        compose.waitForIdle()
    }

    private fun anchor() = compose.runOnIdle {
        list.layoutInfo.visibleItemsInfo.first { it.index >= list.firstVisibleItemIndex && it.index > 0 }.let { it.key to it.offset }
    }

    @Test fun mixedHiddenRunsRenderOneDividerEachAndCanBeShownAgain() {
        mount(listOf(tool(0), thinking(0), tool(1), text(0), thinking(1), tool(2), text(1)))
        compose.onAllNodesWithTag("chat-activity-divider").assertCountEquals(2)
        compose.onNodeWithTag("chat-row-tool-0").assertDoesNotExist()
        compose.onNodeWithTag("chat-row-thinking-0").assertDoesNotExist()
        compose.onNodeWithText("Message 0", substring = true).assertIsDisplayed()
        compose.runOnIdle { showActivity.value = true }
        compose.waitForIdle()
        compose.onAllNodesWithTag("chat-activity-divider").assertCountEquals(0)
        compose.onNodeWithTag("chat-row-tool-0").assertIsDisplayed()
    }

    @Test fun togglingVisibilityKeepsTheVisibleAgentMessageAndOffset() {
        showActivity.value = true
        mount(rows(0..79))
        compose.runOnIdle { scope.launch { list.scrollToItem(91, 23) } }
        compose.waitForIdle()
        val before = anchor()
        assertEquals("text-30", before.first)
        compose.runOnIdle { showActivity.value = false }
        compose.waitForIdle()
        assertEquals(before, anchor())
        compose.runOnIdle { showActivity.value = true }
        compose.waitForIdle()
        assertEquals(before, anchor())
        compose.runOnIdle { assertFalse(reading.followsTail) }
    }

    @Test fun hiddenStreamingDoesNotGrowTheDividerOrMoveHistory() {
        mount(rows(0..79))
        compose.runOnIdle { scope.launch { list.scrollToItem(61, 23) } }
        compose.waitForIdle()
        val before = anchor()
        repeat(4) { n ->
            compose.runOnIdle {
                state.value = state.value.copy(blocks = state.value.blocks + listOf(thinking(100 + n), tool(100 + n)), messagesVersion = n + 1L)
            }
            compose.waitForIdle()
            assertEquals(before, anchor())
            compose.runOnIdle { assertEquals(161, list.layoutInfo.totalItemsCount) }
        }
    }

    @Test fun aLargePrependKeepsTheVisibleMessageWithActivityHidden() {
        mount(rows(0..79))
        compose.runOnIdle { scope.launch { list.scrollToItem(7, 23) } }
        compose.waitForIdle()
        val before = anchor()
        val next = rows(-200..-1) + state.value.blocks
        markdown.prepare(next.filterIsInstance<AgentTextBlock>().map { it.text }.toSet())
        compose.runOnIdle { state.value = state.value.copy(blocks = next, historyVersion = 1, messagesVersion = 1) }
        compose.waitUntil(10000) { version.get() == 1L }
        compose.waitForIdle()
        assertEquals(before, anchor())
    }

    @Test fun hiddenOnlyPagesStillReachLayoutAndShareOneDivider() {
        mount(listOf(thinking(0), tool(0)))
        compose.runOnIdle { state.value = state.value.copy(blocks = listOf(tool(-1)) + state.value.blocks, historyVersion = 1, messagesVersion = 1) }
        compose.waitUntil(10000) { version.get() == 1L }
        compose.onAllNodesWithTag("chat-activity-divider").assertCountEquals(1)
        compose.runOnIdle { assertEquals(2, list.layoutInfo.totalItemsCount) }
    }

    @Test fun aPendingQuestionRemainsSelectableAndSubmittingKeepsTheAnswerFlow() {
        var submitted: Pair<String, PermissionAction>? = null
        val question = tool(1).also { block ->
            block.tool = block.tool.copy(name = "request_user_input", state = "running",
                input = HapiJson.parseToJsonElement("""{"questions":[{"id":"target","question":"Choose a target","options":[{"label":"Alpha"},{"label":"Beta"}]}]}"""),
                permission = ToolPermission("reply", "pending", presence = emptySet()))
        }
        mount(listOf(thinking(0), question, tool(2))) { id, action -> submitted = id to action }
        compose.onNodeWithText("Alpha").performScrollTo().performClick()
        compose.onNodeWithText("Submit").performScrollTo().performClick()
        compose.runOnIdle { assertEquals("reply" to PermissionAction.NestedAnswers(mapOf("target" to listOf("Alpha"))), submitted) }
        compose.runOnIdle {
            val answered = tool(1).also { it.tool = question.tool.copy(permission = question.tool.permission!!.copy(status = "approved")) }
            state.value = state.value.copy(blocks = listOf(thinking(0), answered, tool(2)), messagesVersion = 1)
        }
        compose.waitForIdle()
        compose.onAllNodesWithTag("chat-activity-divider").assertCountEquals(1)
        compose.onNodeWithText("Submit").assertDoesNotExist()
    }
}
