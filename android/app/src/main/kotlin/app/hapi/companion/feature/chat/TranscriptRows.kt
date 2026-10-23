package app.hapi.companion.feature.chat

import app.hapi.protocol.chat.AgentReasoningBlock
import app.hapi.protocol.chat.CliOutputBlock
import app.hapi.protocol.chat.ToolCallBlock
import app.hapi.protocol.chat.ToolGroupBlock
import app.hapi.protocol.chat.VisibleChatBlock

/** Presentation rows only: hidden activity never changes the conversation or its inspectors. */
internal sealed interface TranscriptRow {
    val id: String
    val contentKind: String
    val sourceIds: List<String>

    data class Content(val block: VisibleChatBlock) : TranscriptRow {
        override val id: String get() = block.stableId
        override val contentKind: String get() = block.contentKind
        override val sourceIds: List<String> = listOf(block.stableId)
    }

    data class ActivityDivider(override val sourceIds: List<String>) : TranscriptRow {
        override val id: String get() = "chat-activity-divider:${sourceIds.first()}"
        override val contentKind: String get() = "activity-divider"
    }
}

internal data class TranscriptSnapshot(val state: ChatUiState, val rows: List<TranscriptRow>)

internal fun transcriptRows(
    blocks: List<VisibleChatBlock>,
    showActivity: Boolean,
    planActions: CodexPlanActions = CodexPlanActions(),
    pendingActionBlockIds: Set<String> = emptySet(),
): List<TranscriptRow> {
    if (showActivity) return blocks.map { TranscriptRow.Content(it) }
    val rows = mutableListOf<TranscriptRow>()
    var hidden = mutableListOf<String>()
    fun flush() {
        if (hidden.isNotEmpty()) {
            rows += TranscriptRow.ActivityDivider(hidden)
            hidden = mutableListOf()
        }
    }
    for (block in blocks) {
        val hide = block.stableId !in pendingActionBlockIds && when (block) {
            is AgentReasoningBlock, is ToolGroupBlock -> true
            is CliOutputBlock -> block.source != "user"
            is ToolCallBlock -> block.tool.permission?.status != "pending" &&
                !planActions.forPlan(block.tool.id).isVisible
            else -> false
        }
        if (hide) hidden += block.stableId else {
            flush()
            rows += TranscriptRow.Content(block)
        }
    }
    flush()
    return rows
}

/** Keep the process/group entry when a nested tool needs the user's response. */
internal fun pendingActivityBlocks(blocks: List<VisibleChatBlock>): Set<String> {
    fun pending(block: VisibleChatBlock): Boolean = when (block) {
        is ToolCallBlock -> block.tool.permission?.status == "pending" || block.children.any(::pending)
        is ToolGroupBlock -> block.tools.any(::pending)
        else -> false
    }
    return blocks.filter(::pending).mapTo(mutableSetOf()) { it.stableId }
}
