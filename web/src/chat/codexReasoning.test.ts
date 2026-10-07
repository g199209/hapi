import { describe, expect, it } from 'vitest'
import { coalesceCodexReasoning, reasoningParagraphs, reasoningPreview } from './codexReasoning'
import type { AgentReasoningBlock, ChatBlock } from './types'

function reasoning(text: string, item: string, turn = 'turn', thread = 'root'): AgentReasoningBlock {
    return { kind: 'agent-reasoning', id: `codex:${thread}:${turn}:${item}:agent_reasoning`, localId: null,
        createdAt: 123, invokedAt: null, text, meta: undefined }
}

describe('shared Codex reasoning presentation', () => {
    it('repairs persisted heading fragments, merges overlaps and keeps the first key stable', () => {
        const texts = ['**Inspect**', '**Inspect****Build**', '**Build**', '**Build**', '**Build****Verify**', '**Verify**']
        const input = texts.map((s, i) => reasoning(s, String(i)))
        const result = coalesceCodexReasoning(input)
        expect(result).toHaveLength(1)
        expect(result[0]).toMatchObject({ id: input[0].id, text: '**Inspect**\n\n**Build**\n\n**Verify**' })
        expect(input[0].text).toBe('**Inspect**')
        expect(coalesceCodexReasoning(input.slice(0, 2))[0].id).toBe(result[0].id)
    })

    it('handles cumulative snapshots and older prefixes without duplicating or discarding new content', () => {
        const result = coalesceCodexReasoning([
            reasoning('A\n\nB', '1'), reasoning('A\n\nB\n\nC', '2'), reasoning('A\n\nB', '3'), reasoning('C', '4'),
        ])
        expect(result[0]).toMatchObject({ text: 'A\n\nB\n\nC' })
    })

    it('retains deliberate repeated stages and markdown bodies', () => {
        const result = coalesceCodexReasoning([reasoning('A', '1'), reasoning('B', '2'), reasoning('A', '3')])
        expect(result[0]).toMatchObject({ text: 'A\n\nB\n\nA' })
        expect(reasoningParagraphs('Text with **** in a code example')).toEqual(['Text with **** in a code example'])
        expect(reasoningParagraphs('```\n****\n```')).toEqual(['```\n****\n```'])
        expect(reasoningPreview('**Inspect**\n\n**Verify**')).toBe('Verify')
    })

    it('never merges across another visible block, turn, thread, or unscoped provider', () => {
        const boundary = { kind: 'agent-text', id: 'reply', text: 'Update', createdAt: 123, localId: null } as ChatBlock
        const input = [reasoning('A', '1'), boundary, reasoning('A', '2'), reasoning('A', '3', 'next'),
            reasoning('A', '4', 'next', 'child'), { ...reasoning('A', '5'), id: 'unscoped' }]
        expect(coalesceCodexReasoning(input)).toHaveLength(input.length)
    })
})
