import { afterEach, describe, expect, it, vi } from 'vitest';
import { listWindowsSharedCodexSessions, archiveWindowsSharedCodexSession } from './history';

const state = vi.hoisted(() => ({ calls: [] as string[], request: vi.fn(), disconnect: vi.fn() }));
vi.mock('../codexAppServerClient', () => ({ CodexAppServerClient: class {
    connect = async () => {};
    initialize = async () => ({ codexHome: '/tmp' });
    disconnect = state.disconnect;
    request(method: string, params: unknown) { state.calls.push(method); return state.request(method, params); }
} }));
afterEach(() => { vi.unstubAllEnvs(); state.calls = []; state.request.mockReset(); state.disconnect.mockClear(); });

describe('Windows paginated native Codex history', () => {
    function configure() { vi.stubEnv('CODEX_HOME', '/tmp'); vi.stubEnv('HAPI_CODEX_APP_SERVER_ENDPOINT', 'ws://127.0.0.1:4513'); }
    it('keeps existing file discovery for Unix and unconfigured engines', async () => {
        vi.stubEnv('HAPI_CODEX_APP_SERVER_ENDPOINT', undefined);
        expect(await listWindowsSharedCodexSessions()).toBeUndefined();
        vi.stubEnv('HAPI_CODEX_APP_SERVER_ENDPOINT', 'unix:///tmp/engine.sock');
        expect(await listWindowsSharedCodexSessions()).toBeUndefined();
        expect(state.calls).toEqual([]);
    });
    it('lists every native page without resuming threads or requiring JSONL files', async () => {
        configure();
        state.request.mockImplementation(async (_method, params) => params.cursor
            ? { data: [{ id: 'second', cwd: '/tmp', name: 'Native name', updatedAt: 20 }], nextCursor: null }
            : { data: [{ id: 'first', cwd: '/tmp', preview: 'First input', updatedAt: 10 }], nextCursor: 'page-2' });
        const result = await listWindowsSharedCodexSessions();
        expect(result).toMatchObject([{ id: 'first', file: 'codex://threads/first', modifiedAt: 10000 }, { id: 'second', title: 'Native name' }]);
        expect(state.calls).toEqual(['thread/list', 'thread/list']);
        expect(state.disconnect).toHaveBeenCalledOnce();
    });
    it('uses real input only for matching and marks imports for V2 projection', async () => {
        configure();
        state.request.mockImplementation(async method => method === 'thread/read'
            ? { thread: { id: 'native', cwd: '/tmp', name: 'Title', updatedAt: 1 } }
            : { data: [{ items: [{ type: 'userMessage', content: [{ type: 'text', text: 'Actual input' }] }] }], nextCursor: null });
        const result = await listWindowsSharedCodexSessions(new Set(['native']));
        expect(result).toMatchObject([{ sharedProjection: true, messages: [{ role: 'user', content: { text: 'Actual input' } }] }]);
        expect(state.calls).toEqual(['thread/read', 'thread/turns/list']);
    });
    it('rejects repeated native cursors rather than silently truncating discovery', async () => {
        configure(); state.request.mockResolvedValue({ data: [], nextCursor: 'repeated' });
        await expect(listWindowsSharedCodexSessions()).rejects.toThrow('repeated a cursor');
        expect(state.disconnect).toHaveBeenCalledOnce();
    });
    it('archives through the native engine after checking workspace access', async () => {
        configure(); state.request.mockResolvedValue({ thread: { id: 'native', cwd: '/tmp' } });
        expect(await archiveWindowsSharedCodexSession('native', async () => false)).toMatchObject({ success: false });
        expect(state.calls).toEqual(['thread/read']); state.calls = [];
        expect(await archiveWindowsSharedCodexSession('native', async () => true)).toEqual({ success: true, archivedPath: 'codex://threads/native' });
        expect(state.calls).toEqual(['thread/read', 'thread/archive']);
    });
});
