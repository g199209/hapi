import type { LocalCodexSessionSummary, LocalCodexSessionWithMessages } from '@/modules/common/codexSessions';
import { CodexAppServerClient } from '../codexAppServerClient';
import { isCodexSubagentSource } from '../utils/codexSessionMetadata';
import { externalAppServerEndpoint, initializeSharedClient } from './launch';
import { codexHome } from './registry';
import { record, string } from './gateway';

/** Windows paginated histories need not have any materialized JSONL file. */
export async function listWindowsSharedCodexSessions(ids?: Set<string>): Promise<Array<LocalCodexSessionSummary | LocalCodexSessionWithMessages> | undefined> {
    const endpoint = externalAppServerEndpoint({});
    if (!endpoint?.startsWith('ws:')) return undefined;
    const client = new CodexAppServerClient({ endpoint });
    try {
        await initializeSharedClient(client, codexHome());
        const threads: Record<string, unknown>[] = [];
        if (ids) {
            for (const threadId of ids) threads.push(record(record(await client.request('thread/read', { threadId, includeTurns: false })).thread));
        } else {
            let cursor: string | undefined;
            const seen = new Set<string>();
            do {
                const page = record(await client.request('thread/list', { cursor, limit: 100, archived: false, sourceKinds: [], sortKey: 'updated_at' }));
                if (!Array.isArray(page.data)) throw new Error('Invalid native Codex thread list');
                threads.push(...page.data.map(record));
                cursor = string(page.nextCursor);
                if (cursor && seen.has(cursor)) throw new Error('Native Codex thread pagination repeated a cursor');
                if (cursor) seen.add(cursor);
            } while (cursor);
        }
        const sessions: Array<LocalCodexSessionSummary | LocalCodexSessionWithMessages> = [];
        for (const thread of threads) {
            const id = string(thread.id), cwd = string(thread.cwd);
            if (!id || !cwd || thread.archived === true || isCodexSubagentSource(thread.source) || 'subAgent' in record(thread.source)) continue;
            const summary: LocalCodexSessionSummary = {
                id, cwd, title: string(thread.name) ?? string(thread.preview) ?? id.slice(0, 8),
                file: string(thread.path) ?? `codex://threads/${id}`,
                modifiedAt: typeof thread.updatedAt === 'number' ? thread.updatedAt * 1000 : 0,
                source: typeof thread.source === 'string' ? thread.source : null,
                cliVersion: string(thread.cliVersion), forkedFromId: string(thread.forkedFromId)
            };
            if (!ids) { sessions.push(summary); continue; }
            // Only real native input is used for import matching. The shared
            // V2 projection writes history after resume, so never seed it here.
            const messages: LocalCodexSessionWithMessages['messages'] = [];
            let cursor: string | undefined;
            const seen = new Set<string>();
            do {
                const page = record(await client.request('thread/turns/list', { threadId: id, cursor, limit: 1, sortDirection: 'asc', itemsView: 'full' }));
                if (!Array.isArray(page.data)) throw new Error('Invalid native Codex turns');
                for (const turn of page.data.map(record)) {
                    for (const item of Array.isArray(turn.items) ? turn.items.map(record) : []) {
                        if (item.type !== 'userMessage' || !Array.isArray(item.content)) continue;
                        const text = item.content.map(record).filter(c => c.type === 'text').map(c => string(c.text) ?? '').join('\n');
                        if (text.trim()) messages.push({ role: 'user', content: { type: 'text', text }, meta: { sentFrom: 'cli' } });
                    }
                }
                cursor = string(page.nextCursor);
                if (cursor && seen.has(cursor)) throw new Error('Native Codex turn pagination repeated a cursor');
                if (cursor) seen.add(cursor);
            } while (!messages.length && cursor);
            sessions.push({ ...summary, messages, sharedProjection: true });
        }
        return sessions;
    } finally { await client.disconnect(); }
}

export async function archiveWindowsSharedCodexSession(id: string, canArchive: (session: LocalCodexSessionSummary) => Promise<boolean>): Promise<{ success: true; archivedPath: string } | { success: false; error: string } | undefined> {
    const endpoint = externalAppServerEndpoint({});
    if (!endpoint?.startsWith('ws:')) return undefined;
    const client = new CodexAppServerClient({ endpoint });
    try {
        await initializeSharedClient(client, codexHome());
        const thread = record(record(await client.request('thread/read', { threadId: id, includeTurns: false })).thread);
        const cwd = string(thread.cwd);
        if (!cwd || !await canArchive({ id, cwd, title: '', file: '', modifiedAt: 0 })) return { success: false, error: 'Codex session is outside workspace roots' };
        await client.request('thread/archive', { threadId: id });
        return { success: true, archivedPath: string(thread.path) ?? `codex://threads/${id}` };
    } finally { await client.disconnect(); }
}
