import { describe, expect, it, vi } from 'vitest';
import type { ApiSessionClient } from '@/api/apiSession';
import type { Metadata } from '@/api/types';
import { SharedCodexProjection } from './projection';

function fixture(existing = false) {
    let metadata: Metadata = { path: '/test', host: 'test', ...(existing ? { conversationHistoryTurns: { user: 'turn' } } : {}) };
    const send = vi.fn();
    const session = { getMetadata: () => metadata, sendAgentMessage: send, sendUserMessage() {},
        updateMetadata: (fn: (m: Metadata) => Metadata) => { metadata = fn(metadata); }
    } as unknown as ApiSessionClient;
    return { create: () => new SharedCodexProjection(session, 'thread', async () => {}), send, metadata: () => metadata };
}
function item(id: string) { return { id, type: 'reasoning', summary: ['**Inspect**', '**Verify**'], content: [] }; }
function history(...items: unknown[]) { return { turns: [{ id: 'turn', status: 'completed', items }] }; }

describe('reasoning upgrade replay checkpoint', () => {
    it('avoids appending previously absent old summaries on upgrade while preserving new live and missed events', async () => {
        const f = fixture(true); const projection = f.create();
        await projection.history(history(item('old')));
        expect(f.send).not.toHaveBeenCalled();
        expect(f.metadata().codexReasoningReplayExcluded).toEqual(['old']);
        await projection.notification('item/completed', { threadId: 'thread', turnId: 'turn', item: item('live') });
        expect(f.send).toHaveBeenCalledWith(expect.objectContaining({ message: '**Inspect**\n\n**Verify**' }), expect.any(String));
        const restored = f.create(); f.send.mockClear();
        await restored.history(history(item('old'), item('live'), item('missed')));
        expect(f.send.mock.calls.map(([body]) => body.id)).toEqual([
            'codex:thread:turn:live:agent_reasoning', 'codex:thread:turn:missed:agent_reasoning',
        ]);
    });
    it('preserves full reasoning history on a new import and after another cold attachment', async () => {
        const f = fixture(); await f.create().history(history(item('old')));
        expect(f.metadata().codexReasoningReplayExcluded).toEqual([]);
        expect(f.send).toHaveBeenCalledTimes(1);
        f.send.mockClear(); await f.create().history(history(item('old')));
        expect(f.send).toHaveBeenCalledTimes(1);
    });
});
