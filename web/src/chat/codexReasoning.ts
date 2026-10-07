import type { AgentReasoningBlock, ChatBlock } from './types'

// Only the shared bridge's identities establish a common thread AND turn.
// Other providers and unscoped reasoning must retain their original semantics.
function scope(id: string): string | undefined {
    return /^(codex:[^:]+:[^:]+):[^:]+:agent_reasoning$/.exec(id)?.[1]
}

export function reasoningParagraphs(text: string): string[] {
    return text.trim().split(/\n\s*\n/).flatMap(part => {
        // Older bridge versions concatenated adjacent summary headings. Repair
        // only a paragraph made entirely of headings; never alter prose/code.
        return /^(?:\*\*[^*\n]+\*\*){2,}$/.test(part)
            ? part.replace(/\*\*\*\*/g, '**\n\n**').split('\n\n')
            : [part]
    }).filter(Boolean)
}

function appendSnapshot(previous: string[], next: string[]): string[] {
    // Ignore an older multi-part prefix without deleting newer content.
    if (next.length > 1 && next.length < previous.length && next.every((p, i) => previous[i] === p)) return previous
    let overlap = Math.min(previous.length, next.length)
    while (overlap > 0 && !next.slice(0, overlap).every((p, i) => previous[previous.length - overlap + i] === p)) overlap--
    return [...previous, ...next.slice(overlap)]
}

/** One stable card for consecutive snapshots; never cross a visible message,
 * tool, thread or turn boundary. Stored messages remain untouched. */
export function coalesceCodexReasoning(blocks: ChatBlock[]): ChatBlock[] {
    const result: ChatBlock[] = []
    let current: AgentReasoningBlock | undefined
    let parts: string[] = []
    for (const block of blocks) {
        const key = block.kind === 'agent-reasoning' ? scope(block.id) : undefined
        if (!key || block.kind !== 'agent-reasoning') {
            current = undefined
            result.push(block)
            continue
        }
        const next = reasoningParagraphs(block.text)
        if (current && scope(current.id) === key) {
            parts = appendSnapshot(parts, next)
            current.text = parts.join('\n\n')
            current.usage = block.usage ?? current.usage
            current.model = block.model ?? current.model
            current.durationMs = block.durationMs ?? current.durationMs
        } else {
            parts = next
            current = { ...block, text: parts.join('\n\n') }
            result.push(current)
        }
    }
    return result
}

export function reasoningPreview(text: string): string {
    const latest = reasoningParagraphs(text).at(-1) ?? ''
    return latest.replace(/^\*\*(.*?)\*\*$/, '$1').replace(/^#{1,6}\s+/, '').replace(/\s+/g, ' ').trim()
}
