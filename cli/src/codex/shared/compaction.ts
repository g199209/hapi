import type { ApiSessionClient } from '@/api/apiSession';
import { record, string } from './gateway';

type Compaction = { turnId: string; itemId: string };

/** Root-scoped live state. Snapshot reads cannot overwrite newer native events. */
export class SharedCodexCompaction {
    revision = 0;
    private value: Compaction | null = null;
    private published = false;
    private readonly completed = new Set<string>();
    constructor(private readonly session: Pick<ApiSessionClient, 'updateAgentState'>,
        private readonly changed: () => void) {}
    get active(): boolean { return this.value !== null; }

    private publish(value: Compaction | null): void {
        if (this.published && this.value?.itemId === value?.itemId && this.value?.turnId === value?.turnId) return;
        this.value = value; this.published = true;
        this.session.updateAgentState(state => ({ ...state, codexCompaction: value }));
        this.changed();
    }
    clear(): void { this.revision++; this.publish(null); }

    notification(method: string, params: Record<string, unknown>): void {
        const turnId = string(params.turnId) ?? string(record(params.turn).id);
        const item = record(params.item);
        if (item.type === 'contextCompaction' && (method === 'item/started' || method === 'item/completed')) {
            const itemId = string(item.id);
            if (!turnId || !itemId) return;
            this.revision++;
            if (method === 'item/started') {
                if (!this.completed.has(itemId)) this.publish({ turnId, itemId });
            } else {
                this.completed.add(itemId);
                if (this.completed.size > 128) this.completed.delete(this.completed.values().next().value!);
                if (this.value?.itemId === itemId && this.value.turnId === turnId) this.publish(null);
            }
        } else if (method === 'turn/started') {
            this.revision++;
            if (this.value && turnId !== this.value.turnId) this.publish(null);
        } else if (method === 'turn/completed' || method === 'thread/compacted') {
            this.revision++;
            if (!turnId || this.value?.turnId === turnId) this.publish(null);
        } else if (method === 'thread/status/changed' && record(params.status).type !== 'active') {
            this.clear();
        }
    }

    snapshot(thread: Record<string, unknown>, revision: number): void {
        if (revision !== this.revision) return;
        const turns = Array.isArray(thread.turns) ? thread.turns.map(record) : [];
        const turn = turns.find(turn => turn.status === 'inProgress');
        const item = record(Array.isArray(turn?.items) ? turn.items.at(-1) : undefined);
        const turnId = string(turn?.id), itemId = string(item.id);
        this.publish(item.type === 'contextCompaction' && turnId && itemId && !this.completed.has(itemId)
            ? { turnId, itemId } : null);
    }
}
