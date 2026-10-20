import { afterEach, describe, expect, it, vi } from 'vitest';
import type { AgentState } from '@/api/types';
import { CodexRestoreProgress } from './restoreProgress';

afterEach(() => vi.useRealTimers());
describe('Codex startup progress', () => {
    it('publishes real phase counts, throttles updates and clears after ready', () => {
        vi.useFakeTimers(); vi.setSystemTime(1_000);
        let state: AgentState = { controlledByUser: false };
        const updateAgentState = vi.fn((change: (state: AgentState) => AgentState) => { state = change(state); });
        const progress = new CodexRestoreProgress({ updateAgentState });
        progress.report('history', 0);
        progress.report('history', 25);
        expect(updateAgentState).toHaveBeenCalledTimes(1);
        vi.advanceTimersByTime(300); progress.report('history', 100);
        expect(state.codexRestore).toEqual({ phase: 'history', startedAt: 1000, completed: 100 });
        progress.report('projecting', 0, 350);
        progress.report('projecting', 350, 350);
        expect(state.codexRestore).toMatchObject({ completed: 350, total: 350 });
        progress.finish();
        expect(state).toEqual({ controlledByUser: false, codexRestore: null });
        progress.report('history', 0); progress.finish();
        expect(state.codexRestore).toBeNull();
    });
});
