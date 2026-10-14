import { CodexAppServerClient } from '../codexAppServerClient';
import { normalizeSessionDisplayTitle } from '@/agent/sessionDisplayRename';
import { externalAppServerEndpoint, initializeSharedClient } from './launch';
import { codexHome } from './registry';
import { record } from './gateway';

/** Native explicit name, then the native first-message preview. */
export function nativeCodexTitle(thread: Record<string, unknown>): string | undefined {
    return (typeof thread.name === 'string' && thread.name.trim())
        || (typeof thread.preview === 'string' && thread.preview.trim()) || undefined;
}

export async function renameNativeCodexThread(client: CodexAppServerClient, threadId: string, rawName: string): Promise<Record<string, unknown>> {
    const name = normalizeSessionDisplayTitle(rawName);
    if (!name) throw new Error('A non-empty title is required');
    await client.request('thread/name/set', { threadId, name });
    const thread = record(record(await client.request('thread/read', { threadId, includeTurns: false })).thread);
    if (thread.name !== name) throw new Error('Native title update is not confirmed; refresh before retrying');
    return thread;
}

/** Rename without loading/resuming the conversation or changing its settings. */
export async function renameInactiveCodexThread(threadId: string, name: string, canRename: (cwd: string) => Promise<boolean>): Promise<string> {
    const endpoint = externalAppServerEndpoint({});
    if (!endpoint) throw new Error('Inactive Codex rename requires the machine\'s shared app-server endpoint');
    const client = new CodexAppServerClient({ endpoint });
    try {
        await initializeSharedClient(client, codexHome());
        const thread = record(record(await client.request('thread/read', { threadId, includeTurns: false })).thread);
        if (typeof thread.cwd !== 'string' || !await canRename(thread.cwd)) throw new Error('Path is outside workspace roots');
        const renamed = await renameNativeCodexThread(client, threadId, name);
        return renamed.name as string;
    } finally { await client.disconnect(); }
}
