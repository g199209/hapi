package app.hapi.protocol.chat

// Mirrors web/src/chat/codexReasoning.ts; only shared-bridge IDs carry scope.
private val reasoningScope = Regex("^(codex:[^:]+:[^:]+):[^:]+:agent_reasoning$")
private val joinedHeadings = Regex("^(?:\\*\\*[^*\\n]+\\*\\*){2,}$")

fun reasoningParagraphs(text: String): List<String> = text.trim().split(Regex("\\n\\s*\\n")).flatMap {
    if (joinedHeadings.matches(it)) it.replace("****", "**\n\n**").split("\n\n") else listOf(it)
}.filter { it.isNotEmpty() }

fun reasoningPreview(text: String): String = (reasoningParagraphs(text).lastOrNull() ?: "")
    .replace(Regex("^\\*\\*(.*?)\\*\\*$"), "$1")
    .replace(Regex("^#{1,6}\\s+"), "")
    .replace(Regex("\\s+"), " ").trim()

private fun scope(id: String) = reasoningScope.matchEntire(id)?.groupValues?.get(1)

private fun appendSnapshot(previous: List<String>, next: List<String>): List<String> {
    if (next.size > 1 && next.size < previous.size && previous.take(next.size) == next) return previous
    var overlap = minOf(previous.size, next.size)
    while (overlap > 0 && previous.takeLast(overlap) != next.take(overlap)) overlap--
    return previous + next.drop(overlap)
}

internal fun coalesceCodexReasoning(blocks: List<ChatBlock>): MutableList<ChatBlock> {
    val result = mutableListOf<ChatBlock>()
    var current: AgentReasoningBlock? = null
    var parts = emptyList<String>()
    for (block in blocks) {
        val key = if (block is AgentReasoningBlock) scope(block.id) else null
        if (key == null || block !is AgentReasoningBlock) {
            current = null
            result.add(block)
            continue
        }
        val next = reasoningParagraphs(block.text)
        val previous = current
        if (previous != null && scope(previous.id) == key) {
            parts = appendSnapshot(parts, next)
            previous.text = parts.joinToString("\n\n")
            previous.usage = block.usage ?: previous.usage
            previous.model = block.model ?: previous.model
            previous.durationMs = block.durationMs ?: previous.durationMs
        } else {
            parts = next
            current = AgentReasoningBlock(
                id = block.id, localId = block.localId, createdAt = block.createdAt,
                invokedAt = block.invokedAt, durationMs = block.durationMs,
                usage = block.usage, model = block.model, text = parts.joinToString("\n\n"), meta = block.meta,
            )
            result.add(current)
        }
    }
    return result
}
