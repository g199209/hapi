import { describe, expect, it, vi } from 'vitest';
import type { AgentState, Metadata } from '@/api/types';
import { SharedCodexCompaction } from './compaction';

function fixture(checkpoint?: Metadata['codexCompactionCheckpoint']) {
    let metadata: Metadata = { path: '/tmp', host: 'test', codexCompactionCheckpoint: checkpoint };
    let state: AgentState = { controlledByUser: false };
    const updateAgentState = vi.fn((change: (state: AgentState) => AgentState) => { state = change(state); });
    const changed = vi.fn();
    const compaction = new SharedCodexCompaction({ updateAgentState, getMetadata: () => metadata,
        updateMetadata: change => { metadata = change(metadata); } }, changed);
    compaction.clear(); updateAgentState.mockClear(); changed.mockClear();
    return { compaction, metadata: () => metadata, state: () => state, updateAgentState, changed };
}
const params = (turnId = 'turn', itemId = 'compact') => ({ threadId: 'thread', turnId, item: { type: 'contextCompaction', id: itemId } });
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
    it('restores an observed start across detach and a fresh bridge with an empty native turn', () => {
        const f = fixture();
        f.compaction.notification('item/started', params());
        f.compaction.clear();
        const checkpoint = f.metadata().codexCompactionCheckpoint;
        expect(checkpoint).toEqual({ threadId: 'thread', turnId: 'turn', itemId: 'compact' });
        const g = fixture(checkpoint);
        g.compaction.snapshot({ id: 'thread', turns: [{ id: 'turn', status: 'inProgress', items: [] }] }, g.compaction.revision);
        expect(g.state().codexCompaction).toEqual({ turnId: 'turn', itemId: 'compact' });
        // Direct native/async user input can also arrive while compaction runs.
        g.compaction.snapshot({ id: 'thread', turns: [{ id: 'turn', status: 'inProgress', items: [{ type: 'userMessage' }] }] }, g.compaction.revision);
        expect(g.compaction.active).toBe(true);
    });
    it('retires the checkpoint when canonical history contains the completed item', () => {
        const f = fixture({ threadId: 'thread', turnId: 'turn', itemId: 'compact' });
        f.compaction.snapshot({ id: 'thread', turns: [{ id: 'turn', status: 'inProgress', items: [params().item] }] }, f.compaction.revision);
        expect(f.compaction.active).toBe(false);
        expect(f.metadata().codexCompactionCheckpoint).toBeUndefined();
    });
    it('never infers live compaction from completed history, including the last item of an active turn', () => {
        for (const status of ['completed', 'inProgress']) {
            const f = fixture();
            f.compaction.snapshot({ id: 'thread', turns: [{ id: 'turn', status, items: [params().item] }] }, f.compaction.revision);
            expect(f.compaction.active).toBe(false);
        }
    });
    it('retires a checkpoint belonging to another thread, turn or completed turn', () => {
        for (const thread of [
            { id: 'other', turns: [{ id: 'turn', status: 'inProgress', items: [] }] },
            { id: 'thread', turns: [{ id: 'next', status: 'inProgress', items: [] }] },
            { id: 'thread', turns: [{ id: 'turn', status: 'completed', items: [] }] },
        ]) {
            const f = fixture({ threadId: 'thread', turnId: 'turn', itemId: 'compact' });
            f.compaction.snapshot(thread, f.compaction.revision);
            expect(f.compaction.active).toBe(false);
            expect(f.metadata().codexCompactionCheckpoint).toBeUndefined();
        }
    });
    it('ignores snapshots overtaken by native start, completion and idle events', () => {
        const f = fixture();
        const empty = { id: 'thread', turns: [] };
        const revision = f.compaction.revision;
        f.compaction.notification('item/started', params());
        f.compaction.snapshot(empty, revision);
        expect(f.compaction.active).toBe(true);
        const activeRevision = f.compaction.revision;
        f.compaction.notification('item/completed', params());
        f.compaction.snapshot({ id: 'thread', turns: [{ id: 'turn', status: 'inProgress', items: [] }] }, activeRevision);
        expect(f.compaction.active).toBe(false);
        expect(f.metadata().codexCompactionCheckpoint).toBeUndefined();
    });
    it('does not write state repeatedly during routine idle refreshes', () => {
        const f = fixture();
        f.compaction.snapshot({ turns: [] }, f.compaction.revision);
        f.compaction.snapshot({ turns: [] }, f.compaction.revision);
        expect(f.updateAgentState).not.toHaveBeenCalled();
    });
});
