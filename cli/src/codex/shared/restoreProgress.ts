import type { AgentState } from '@/api/types';
import type { ApiSessionClient } from '@/api/apiSession';
import { logger } from '@/ui/logger';

type Progress = NonNullable<AgentState['codexRestore']>;

/** Startup progress only. Counts describe a phase, never an estimated overall percent. */
export class CodexRestoreProgress {
    private readonly startedAt = Date.now();
    private phase?: Progress['phase'];
    private phaseAt = this.startedAt;
    private publishedAt = 0;
    private finished = false;
    constructor(private readonly session: Pick<ApiSessionClient, 'updateAgentState'>) {}

    report(phase: Progress['phase'], completed?: number, total?: number): void {
        if (this.finished) return;
        const now = Date.now();
        if (phase !== this.phase) {
            if (this.phase) logger.debug('[Codex restore] phase', { phase: this.phase, ms: now - this.phaseAt });
            this.phase = phase; this.phaseAt = now;
        } else if (now - this.publishedAt < 250 && completed !== total) return;
        this.publishedAt = now;
        const progress: Progress = { phase, startedAt: this.startedAt,
            ...(completed === undefined ? {} : { completed }), ...(total === undefined ? {} : { total }) };
        this.session.updateAgentState(state => ({ ...state, codexRestore: progress }));
    }

    finish(): void {
        if (this.finished) return;
        this.finished = true;
        const now = Date.now();
        logger.debug('[Codex restore] complete', { phase: this.phase, phaseMs: now - this.phaseAt, totalMs: now - this.startedAt });
        this.session.updateAgentState(state => ({ ...state, codexRestore: null }));
    }
}
