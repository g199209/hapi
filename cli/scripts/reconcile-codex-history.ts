/** Reconcile a HAPI mirror against a read-only native V2 snapshot.
 * Stop the target HAPI bridge and Hub, back up the DB, then use --apply.
 * This script never connects to or modifies the native Codex daemon.
 */
import { Database } from 'bun:sqlite';
import { readFileSync, writeFileSync } from 'node:fs';
import { SharedCodexProjection } from '../src/codex/shared/projection';
import type { ApiSessionClient } from '../src/api/apiSession';
import { getAllMessages, addProjectedMessage, bumpMessageEpoch } from '../../hub/src/store/messages';

const [database, sid, snapshotPath, reportPath, flag] = process.argv.slice(2);
if (!database || !sid || !snapshotPath || !reportPath || (flag && flag !== '--apply')) {
    throw new Error('Usage: reconcile-codex-history.ts DB SESSION NATIVE_SNAPSHOT REPORT [--apply]');
}
const apply = flag === '--apply';
const db = new Database(database, apply ? { readwrite: true, strict: true } : { readonly: true, strict: true });
const native = JSON.parse(readFileSync(snapshotPath, 'utf8'));
const stored = db.query('SELECT metadata, metadata_version FROM sessions WHERE id = ?').get(sid) as { metadata: string; metadata_version: number };
const originalMetadata = JSON.parse(stored.metadata);
if (native.id !== originalMetadata.codexSessionId || !Array.isArray(native.turns)) throw new Error('Native thread mismatch or incomplete snapshot');
if (!originalMetadata.capabilities?.concurrentClients) throw new Error('Session is not a shared native mirror');
let metadata = { ...originalMetadata, conversationHistoryTurns: {}, conversationHistoryPoints: {}, codexReasoningReplayExcluded: [] };
const projected = new Map<string, { localId: string; at: number; content: any }>();
const put = (content: unknown, localId: string, at: number) => {
    if (!Number.isSafeInteger(at)) throw new Error(`Invalid native position: ${localId}`);
    projected.set(localId, { localId, at, content });
};
const session = {
    getMetadata: () => metadata,
    updateMetadata: (update: (value: any) => any) => { metadata = update(metadata); },
    sendUserMessage: (text: string, _meta: unknown, id: string, at: number) => put({ role: 'user', content: { type: 'text', text }, meta: { sentFrom: 'cli' } }, id, at),
    sendAgentMessage: (body: unknown, id: string, at: number) => put({ role: 'agent', content: { type: 'codex', data: body }, meta: { sentFrom: 'cli' } }, id, at)
} as unknown as ApiSessionClient;
await new SharedCodexProjection(session, native.id, async () => {}).history(native);
const rows = getAllMessages(db, sid);
const prefix = `codex:${native.id}:`;
const firstNative = rows.find(row => row.localId && (row.localId.startsWith(prefix) || projected.has(row.localId)));
if (!firstNative && rows.length) throw new Error('Cannot identify V2 replay boundary');
const pruned = rows.filter(row => {
    const content = row.content as any;
    const legacySeed = originalMetadata.codexSourceSessionId === native.id && !row.localId && row.seq < (firstNative?.seq ?? 0)
        && (content?.role === 'user' || (content?.role === 'agent' && content.content?.type === 'codex'));
    const staleProjection = row.localId?.startsWith(prefix) && !projected.has(row.localId)
        && (content?.role === 'user' || ['message', 'reasoning', 'tool-call', 'tool-call-result'].includes(content?.content?.data?.type));
    return legacySeed || staleProjection;
});
const report = { sessionId: sid, threadId: native.id, apply, before: rows.length, canonical: projected.size,
    removed: pruned.map(row => ({ id: row.id, seq: row.seq, localId: row.localId })),
    preservedExtra: rows.filter(row => !projected.has(row.localId ?? '') && !pruned.includes(row)).length,
    projected: [...projected.values()].map(({ localId, at }) => ({ localId, at })) };
writeFileSync(reportPath, JSON.stringify(report, null, 2));
if (apply) {
    db.transaction(() => {
        for (const row of pruned) db.query('DELETE FROM messages WHERE session_id = ? AND id = ?').run(sid, row.id);
        for (const item of projected.values()) addProjectedMessage(db, sid, item.content, item.localId, item.at);
        // Non-transcript usage events remain useful; locate them at their
        // native turn boundary instead of the old mirror receipt timestamp.
        const ends = new Map<string, number>();
        let previous = 0;
        for (const turn of native.turns) {
            const base = Math.max(turn.startedAt * 1000, previous);
            previous = base + turn.items.length * 2;
            ends.set(turn.id, previous);
        }
        for (const row of rows) {
            if (!row.localId?.startsWith(prefix) || projected.has(row.localId) || pruned.includes(row)) continue;
            const turnId = row.localId.slice(prefix.length).split(':')[0];
            const at = ends.get(turnId);
            if (at !== undefined) db.query('UPDATE messages SET invoked_at = ? WHERE id = ?').run(at, row.id);
        }
        // Preserve the original replay checkpoint; only history navigation IDs change.
        db.query('UPDATE sessions SET metadata = ?, metadata_version = metadata_version + 1 WHERE id = ?').run(JSON.stringify({
            ...originalMetadata, conversationHistoryTurns: metadata.conversationHistoryTurns,
            conversationHistoryPoints: metadata.conversationHistoryPoints, codexReasoningReplayExcluded: []
        }), sid);
        bumpMessageEpoch(db, sid);
    })();
}
console.log(JSON.stringify({ sessionId: sid, apply, before: rows.length, canonical: projected.size, removed: pruned.length,
    preservedExtra: report.preservedExtra, after: apply ? getAllMessages(db, sid).length : undefined }));
db.close();
