import type { ApiSessionClient } from '@/api/apiSession';
import { record, string } from './gateway';

type Compaction = { turnId: string; itemId: string };
type Checkpoint = Compaction & { threadId: string };

/** Root-scoped live state. Snapshot reads cannot overwrite newer native events. */
export class SharedCodexCompaction {
    revision = 0;
    private value: Compaction | null = null;
    private published = false;
    private readonly completed = new Set<string>();
    private checkpoint: Checkpoint | undefined;
    constructor(private readonly session: Pick<ApiSessionClient, 'updateAgentState' | 'getMetadata' | 'updateMetadata'>,
        private readonly changed: () => void) {
        this.checkpoint = session.getMetadata()?.codexCompactionCheckpoint;
    }
    get active(): boolean { return this.value !== null; }

    private publish(value: Compaction | null): void {
        if (this.published && this.value?.itemId === value?.itemId && this.value?.turnId === value?.turnId) return;
        this.value = value; this.published = true;
        this.session.updateAgentState(state => ({ ...state, codexCompaction: value }));
        this.changed();
    }
    // Detach hides the live status, but must retain the start for the next bridge.
    clear(): void { this.revision++; this.publish(null); }
    private remember(value: Checkpoint | undefined): void {
        if (this.checkpoint?.threadId === value?.threadId && this.checkpoint?.turnId === value?.turnId
            && this.checkpoint?.itemId === value?.itemId) return;
        this.checkpoint = value;
        this.session.updateMetadata(metadata => ({ ...metadata, codexCompactionCheckpoint: value }));
    }
    private finish(): void { this.remember(undefined); this.publish(null); }

    notification(method: string, params: Record<string, unknown>): void {
        const turnId = string(params.turnId) ?? string(record(params.turn).id);
        const item = record(params.item);
        if (item.type === 'contextCompaction' && (method === 'item/started' || method === 'item/completed')) {
            const itemId = string(item.id);
            if (!turnId || !itemId) return;
            this.revision++;
            if (method === 'item/started') {
                if (!this.completed.has(itemId)) {
                    const threadId = string(params.threadId);
                    if (threadId) this.remember({ threadId, turnId, itemId });
                    this.publish({ turnId, itemId });
                }
            } else {
                this.completed.add(itemId);
                if (this.completed.size > 128) this.completed.delete(this.completed.values().next().value!);
                if ((this.value?.itemId === itemId && this.value.turnId === turnId)
                    || (this.checkpoint?.itemId === itemId && this.checkpoint.turnId === turnId)) this.finish();
            }
        } else if (method === 'turn/started') {
            this.revision++;
            if ((this.value && turnId !== this.value.turnId)
                || (this.checkpoint && turnId !== this.checkpoint.turnId)) this.finish();
        } else if (method === 'turn/completed' || method === 'thread/compacted') {
            this.revision++;
            if (!turnId || this.value?.turnId === turnId || this.checkpoint?.turnId === turnId) this.finish();
        } else if (method === 'thread/status/changed' && record(params.status).type !== 'active') {
            this.revision++; this.finish();
        }
    }

    snapshot(thread: Record<string, unknown>, revision: number): void {
        if (revision !== this.revision) return;
        const turns = Array.isArray(thread.turns) ? thread.turns.map(record) : [];
        const checkpoint = this.checkpoint;
        const turn = turns.find(turn => turn.id === checkpoint?.turnId && turn.status === 'inProgress');
        const items = Array.isArray(turn?.items) ? turn.items.map(record) : [];
        // A canonical history item is evidence of completion, even when the
        // containing turn is still running. An empty active turn is normal
        // during automatic compaction, including after native input arrives.
        if (checkpoint && thread.id === checkpoint.threadId && turn
            && !items.some(item => item.id === checkpoint.itemId)
            && !this.completed.has(checkpoint.itemId)) {
            this.publish({ turnId: checkpoint.turnId, itemId: checkpoint.itemId });
        } else this.finish();
    }
}
