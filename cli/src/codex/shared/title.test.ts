import { afterEach, describe, expect, it, vi } from 'vitest';
import { nativeCodexTitle, renameInactiveCodexThread, renameNativeCodexThread } from './title';
import type { CodexAppServerClient } from '../codexAppServerClient';
const f = vi.hoisted(() => ({ thread: { id: 'native', cwd: '/allowed', name: 'Prior', preview: 'First prompt' }, calls: [] as string[], disconnect: vi.fn() }));
vi.mock('../codexAppServerClient', () => ({ CodexAppServerClient: class {
    async connect() { f.calls.push('connect'); }
    async initialize() { f.calls.push('initialize'); return { codexHome: '/tmp' }; }
    async disconnect() { f.disconnect(); }
    async request(method: string, params: Record<string, unknown>) {
        f.calls.push(method);
        if (method === 'thread/read') return { thread: { ...f.thread } };
        if (method === 'thread/name/set') { f.thread.name = params.name as string; return {}; }
        throw new Error('Unexpected native mutation: ' + method);
    }
} }));
afterEach(() => { vi.unstubAllEnvs(); f.calls = []; f.thread.name = 'Prior'; f.disconnect.mockClear(); });
describe('native Codex title', () => {
    it('uses native name before preview and leaves empty threads untitled', () => {
        expect(nativeCodexTitle({ name: ' Native ', preview: 'First prompt' })).toBe('Native');
        expect(nativeCodexTitle({ name: null, preview: 'First prompt' })).toBe('First prompt');
        expect(nativeCodexTitle({ name: '', preview: '' })).toBeUndefined();
    });
    it('renames an unloaded thread without resume/start or settings overrides', async () => {
        vi.stubEnv('CODEX_HOME', '/tmp'); vi.stubEnv('HAPI_CODEX_APP_SERVER_ENDPOINT', 'unix:///tmp/native.sock');
        const canRename = vi.fn(async () => true);
        expect(await renameInactiveCodexThread('native', 'HAPI title', canRename)).toBe('HAPI title');
        expect(canRename).toHaveBeenCalledWith('/allowed');
        expect(f.calls).toEqual(['connect', 'initialize', 'thread/read', 'thread/name/set', 'thread/read']);
        expect(f.disconnect).toHaveBeenCalledTimes(1);
    });
    it('enforces workspace roots before native mutation', async () => {
        vi.stubEnv('CODEX_HOME', '/tmp'); vi.stubEnv('HAPI_CODEX_APP_SERVER_ENDPOINT', 'unix:///tmp/native.sock');
        await expect(renameInactiveCodexThread('native', 'Outside', async () => false)).rejects.toThrow('outside workspace');
        expect(f.calls).not.toContain('thread/name/set'); expect(f.disconnect).toHaveBeenCalledTimes(1);
    });
    it('does not accept a native ACK without the expected confirmed title', async () => {
        const client = { request: vi.fn(async method => method === 'thread/read' ? { thread: { name: 'Other concurrent rename' } } : {}) } as unknown as CodexAppServerClient;
        await expect(renameNativeCodexThread(client, 'native', 'Requested')).rejects.toThrow('not confirmed');
        await expect(renameNativeCodexThread(client, 'native', '  ')).rejects.toThrow('non-empty');
    });
});
