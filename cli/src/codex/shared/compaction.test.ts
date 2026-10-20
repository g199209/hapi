import { describe, expect, it, vi } from 'vitest';
import type { AgentState } from '@/api/types';
import { SharedCodexCompaction } from './compaction';

function fixture() {
    let state: AgentState = { controlledByUser: false };
    const updateAgentState = vi.fn((change: (state: AgentState) => AgentState) => { state = change(state); });
    const changed = vi.fn();
    const compaction = new SharedCodexCompaction({ updateAgentState }, changed);
    compaction.clear(); updateAgentState.mockClear(); changed.mockClear();
    return { compaction, state: () => state, updateAgentState, changed };
}
const params = (turnId = 'turn', itemId = 'compact') => ({ turnId, item: { type: 'contextCompaction', id: itemId } });
describe('shared Codex compaction state', () => {
    it('publishes start immediately, deduplicates and ignores unrelated completions', () => {
        const f = fixture();
        f.compaction.notification('item/started', params());
        f.compaction.notification('item/started', params());
        expect(f.updateAgentState).toHaveBeenCalledTimes(1);
        expect(f.state().codexCompaction).toEqual({ turnId: 'turn', itemId: 'compact' });
        f.compaction.notification('item/completed', params('old', 'old-compact'));
        f.compaction.notification('turn/completed', { turn: { id: 'old' } });
        expect(f.compaction.active).toBe(true);
        f.compaction.notification('item/completed', params());
        expect(f.state().codexCompaction).toBeNull();
        f.compaction.notification('item/started', params());
        expect(f.compaction.active).toBe(false);
    });
    it('withdraws on matching turn completion, new turn, idle status and disconnect', () => {
        for (const [method, p] of [
            ['turn/completed', { turn: { id: 'turn' } }],
            ['turn/started', { turn: { id: 'next' } }],
            ['thread/status/changed', { status: { type: 'idle' } }],
            ['thread/compacted', { turnId: 'turn' }],
        ] as const) {
            const f = fixture(); f.compaction.notification('item/started', params());
            f.compaction.notification(method, p);
            expect(f.compaction.active).toBe(false);
        }
        const f = fixture(); f.compaction.notification('item/started', params()); f.compaction.clear();
        expect(f.state().codexCompaction).toBeNull();
    });
    it('hydrates an in-progress last item, never a completed turn or a stale snapshot', () => {
        const f = fixture();
        const thread = { turns: [{ id: 'turn', status: 'inProgress', items: [params().item] }] };
        f.compaction.snapshot(thread, f.compaction.revision);
        expect(f.compaction.active).toBe(true);
        const revision = f.compaction.revision;
        f.compaction.notification('item/completed', params());
        f.compaction.snapshot(thread, revision);
        f.compaction.snapshot(thread, f.compaction.revision);
        expect(f.compaction.active).toBe(false);
        const g = fixture();
        g.compaction.snapshot({ turns: [{ id: 'turn', status: 'completed', items: [params().item] }] }, g.compaction.revision);
        expect(g.compaction.active).toBe(false);
        g.compaction.snapshot({ turns: [{ id: 'turn', status: 'inProgress', items: [params().item, { type: 'reasoning' }] }] }, g.compaction.revision);
        expect(g.compaction.active).toBe(false);
    });
    it('does not write state repeatedly during routine idle refreshes', () => {
        const f = fixture();
        f.compaction.snapshot({ turns: [] }, f.compaction.revision);
        f.compaction.snapshot({ turns: [] }, f.compaction.revision);
        expect(f.updateAgentState).not.toHaveBeenCalled();
    });
});
