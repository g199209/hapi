package app.hapi.companion.feature.chat

import app.hapi.companion.feature.chat.blocks.previewToolCall
import app.hapi.protocol.chat.*
import kotlin.test.*

class TranscriptRowsTest {
    private fun text(id: String) = AgentTextBlock(id, null, 0, 0, text = id, meta = null)
    private fun thinking(id: String) = AgentReasoningBlock(id, null, 0, 0, text = id, meta = null)
    private fun tool(id: String) = previewToolCall(id, "Read", input = mapOf("file_path" to "$id.kt"))
    private fun pending(id: String) = tool(id).also {
        it.tool = it.tool.copy(permission = ToolPermission(id, "pending", presence = setOf("id", "status")))
    }

    @Test fun `enabled keeps all original blocks and identities`() {
        val blocks = listOf(text("a"), thinking("b"), tool("c"))
        val rows = transcriptRows(blocks, true)
        assertEquals(blocks.map { it.stableId }, rows.map { it.id })
        rows.zip(blocks).forEach { (row, block) -> assertSame(block, (row as TranscriptRow.Content).block) }
    }

    @Test fun `one divider replaces each consecutive mixed activity run`() {
        val rows = transcriptRows(listOf(thinking("r0"), tool("t0"), text("a"), tool("t1"),
            thinking("r1"), tool("t2"), text("b"), thinking("r2")), false)
        assertEquals(listOf("activity-divider", "agent-text", "activity-divider", "agent-text", "activity-divider"), rows.map { it.contentKind })
        assertEquals(listOf(listOf("r0", "t0"), listOf("t1", "r1", "t2"), listOf("r2")),
            rows.filterIsInstance<TranscriptRow.ActivityDivider>().map { it.sourceIds })
        assertEquals(rows.size, rows.map { it.id }.distinct().size)
    }

    @Test fun `empty and activity only windows do not add extra rows`() {
        assertTrue(transcriptRows(emptyList(), false).isEmpty())
        assertEquals(1, transcriptRows((1..800).map { thinking("r$it") }, false).size)
        assertEquals(2, transcriptRows(listOf(text("a"), text("b")), false).size)
    }

    @Test fun `tool groups and assistant terminal output collapse but user shell stays visible`() {
        val group = buildVisibleChatBlocks(listOf(tool("one"), tool("two")), ToolGroupingOptions(false)).single()
        assertIs<ToolGroupBlock>(group)
        fun terminal(id: String, source: String) = CliOutputBlock(id, null, 0, 0, text = "terminal", source = source, meta = null)
        val rows = transcriptRows(listOf(group, terminal("output", "assistant"), thinking("r"), terminal("user-shell", "user")), false)
        assertEquals(2, rows.size)
        assertIs<TranscriptRow.ActivityDivider>(rows.first())
        assertEquals("user-shell", rows.last().id)
    }

    @Test fun `questions and approvals stay visible until answered then join their neighbors`() {
        val question = pending("question").also { it.tool = it.tool.copy(name = "request_user_input") }
        val approval = pending("approval")
        val blocks = listOf(thinking("r0"), question, tool("t"), approval, thinking("r1"))
        val rows = transcriptRows(blocks, false)
        assertEquals(listOf("activity-divider", "tool-call", "activity-divider", "tool-call", "activity-divider"), rows.map { it.contentKind })
        question.tool = question.tool.copy(permission = question.tool.permission!!.copy(status = "approved"))
        approval.tool = approval.tool.copy(permission = approval.tool.permission!!.copy(status = "denied"))
        assertEquals(1, transcriptRows(blocks, false).size)
    }

    @Test fun `actionable plan remains readable until its menu is gone`() {
        val plan = tool("proposal").also { it.tool = it.tool.copy(name = "ExitPlanMode") }
        assertIs<TranscriptRow.Content>(transcriptRows(listOf(plan), false, CodexPlanActions(proposalId = "proposal")).single())
        assertIs<TranscriptRow.Content>(transcriptRows(listOf(plan), false, CodexPlanActions(pendingPlanId = "proposal")).single())
        assertIs<TranscriptRow.ActivityDivider>(transcriptRows(listOf(plan), false).single())
    }

    @Test fun `streaming into the hidden run keeps its key and source records intact`() {
        val first = thinking("r")
        val before = transcriptRows(listOf(first, tool("t")), false).single()
        val after = transcriptRows(listOf(first, tool("t"), tool("t2")), false).single()
        assertEquals(before.id, after.id)
        assertEquals(listOf("r", "t", "t2"), after.sourceIds)
        assertEquals("r", first.text)
    }

    @Test fun `processes with pending child approvals retain their inspector entry`() {
        val process = tool("process").also { it.children = listOf(tool("child"), pending("child-question")) }
        val raw = listOf(thinking("r"), process)
        val pendingIds = pendingActivityBlocks(raw)
        assertEquals(setOf("process"), pendingIds)
        val summaries = TranscriptProjection().project(raw)
        assertIs<TranscriptRow.Content>(transcriptRows(summaries, false, pendingActionBlockIds = pendingIds).last())
        process.children = emptyList()
        assertTrue(pendingActivityBlocks(raw).isEmpty())
        assertEquals(1, transcriptRows(raw, false).size)
    }
}
