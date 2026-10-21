import { afterEach, describe, expect, it, vi } from 'vitest';
import { mkdtemp, rm } from 'node:fs/promises';
import type { ApiSessionClient } from '@/api/apiSession';
import type { AgentState, Metadata } from '@/api/types';
import type { SessionBootstrapResult } from '@/agent/sessionFactory';
import { SharedCodexRoot, type RootHost } from './root';
import { codexPlanProposalId } from './plan';
import { RPC_METHODS } from '@hapi/protocol/rpcMethods';

type NativeTurn = { id: string; status: string; items: unknown[] };

vi.mock('../codexAppServerClient', () => ({
    CodexAppServerClient: class {
        initialized = false;
        thread = { id: 'thread', turns: [] as NativeTurn[], name: undefined as string | null | undefined, preview: undefined as string | undefined };
        settings: Record<string, unknown> = { model: 'mock', collaborationMode: { mode: 'default' } };
        queue: Array<{ id: string; clientUserMessageId: unknown; input: unknown }> = [];
        notify?: (method: string, params: unknown) => void;
        abandoned?: () => void;
        setNotificationHandler(handler: typeof this.notify) { this.notify = (method, params) => {
            if (method === 'thread/name/updated' && (params as { threadId?: string }).threadId === 'thread') this.thread.name = (params as { threadName: string | null }).threadName;
            handler?.(method, params);
        }; }
        setTransportAbandonedHandler(handler: (() => void) | null) { this.abandoned = handler ?? undefined; }
        setServerRequestHandler() {}
        async connect() {}
        async initialize() { this.initialized = true; }
        isInitialized() { return this.initialized; }
        async disconnect() { this.initialized = false; }
        async request(method: string, params: Record<string, unknown> = {}) {
            if (method === 'thread/read' || method === 'thread/resume') { const thread = structuredClone(this.thread); if (thread.name === undefined) delete (thread as { name?: string }).name; if (thread.preview === undefined) delete thread.preview; return { ...this.settings, thread }; }
            if (method === 'thread/name/set') { this.thread.name = params.name as string; this.notify?.('thread/name/updated', { threadId: 'thread', threadName: params.name }); return {}; }
            if (method === 'thread/list') return { data: [] };
            if (method === 'thread/queue/list') return { data: this.queue };
            if (method === 'thread/settings/update') {
                this.settings = { ...this.settings, ...params };
                this.notify?.('thread/settings/updated', { threadId: 'thread', threadSettings: this.settings });
                return {};
            }
            if (method === 'thread/queue/add') {
                const entry = { id: `queued-${this.queue.length}`, clientUserMessageId: params.clientUserMessageId, input: params.input };
                this.queue.push(entry);
                return { queuedSubmission: entry };
            }
            throw new Error(`Unexpected request: ${method}`);
        }
    },
    isIndeterminateError: () => false
}));
vi.mock('../utils/buildHapiMcpBridge', () => ({ buildHapiMcpBridge: async () => ({
    mcpServers: {}, server: { stop() {} }
}) }));

const cleanups: Array<() => Promise<void>> = [];
afterEach(async () => {
    try { for (const cleanup of cleanups.splice(0)) await cleanup(); }
    finally { vi.useRealTimers(); }
});

async function fixture(opts?: { hubArchived?: boolean; end?: RootHost['end'] }) {
    const directory = await mkdtemp('/tmp/hapi-shared-root-');
    let state: AgentState = { steeringActive: true };
    let metadata: Metadata = { path: directory, host: 'test', flavor: 'codex' };
    let reconnect: (() => void) | null = null;
    const updateState = vi.fn((fn: (value: AgentState) => AgentState) => { state = fn(state); });
    const rpc = new Map<string, (raw: unknown) => Promise<unknown>>();
    const send = vi.fn();
    const userMessages = vi.fn();
    const sessionEvents = vi.fn();
    const hubArchivedListeners: Array<() => void> = [];
    const session = {
        sessionId: 'sid', getMetadata: () => metadata,
        hubArchived: opts?.hubArchived ?? false,
        updateMetadata: (fn: (value: Metadata) => Metadata) => { metadata = fn(metadata); },
        updateAgentState: updateState, keepAlive() {},
        onUserMessage() {}, onCancelQueuedMessage() {}, onRetryQueuedMessage() {},
        onReconnect: (fn: (() => void) | null) => { reconnect = fn; },
        on(event: string, listener: () => void) {
            if (event === 'hub-archived') hubArchivedListeners.push(listener);
        },
        rpcHandlerManager: { registerHandler: (name: string, handler: (raw: unknown) => Promise<unknown>) => rpc.set(name, handler) },
        sendSessionEvent: sessionEvents, sendAgentMessage: send, emitSessionReady() {},
        sendUserMessage: userMessages, emitMessagesConsumed() {}, emitSteerIndeterminate() {}, syncNativeQueuedMessage() {},
        sendSessionDeath() {}, async flush() {}, close() {}
    } as unknown as ApiSessionClient;
    const end = opts?.end ?? (async () => { throw new Error('Unexpected root archive'); });
    const root = new SharedCodexRoot({ session, workingDirectory: directory } as SessionBootstrapResult, {
        directory, generation: 'test', endpoint: 'mock', settingsFor: () => undefined,
        create: async () => { throw new Error('Unexpected root creation'); },
        end
    } satisfies RootHost);
    cleanups.push(async () => { await root.close(false); await rm(directory, { recursive: true, force: true }); });
    await root.prepare();
    await root.bind('thread', { model: 'mock', thread: { turns: [] } }, false);
    // The runtime clears startup progress after its ready webhook.
    root.restore.finish();
    const native = root.client as unknown as {
        initialized: boolean;
        thread: { id: string; turns: NativeTurn[]; name?: string | null; preview?: string };
        queue: Array<{ id: string; clientUserMessageId: string; input: unknown }>;
        notify(method: string, params: unknown): void;
        abandoned(): void;
    };
    return {
        root, native, rpc, send, userMessages, sessionEvents, metadata: () => metadata, state: () => state, updateState,
        reconnect: () => reconnect?.(),
        emitHubArchived: () => { for (const listener of hubArchivedListeners) listener(); },
        hubArchivedListenerCount: () => hubArchivedListeners.length,
        end,
    };
}

it('binds only full canonical history, ignoring the provisional resume item view', async () => {
    const f = await fixture();
    f.native.thread.turns = [{ id: 'turn', status: 'completed', items: [
        { id: 'canonical', type: 'agentMessage', text: 'canonical history' }
    ] }];
    await f.root.bind('thread', { thread: { turns: [{ id: 'turn', status: 'completed', items: [
        { id: 'item-1', type: 'agentMessage', text: 'provisional duplicate' }
    ] }] } }, false);
    expect(f.send.mock.calls.filter(([body]) => body.type === 'message').map(([body]) => body.message))
        .toEqual(['canonical history']);
});

it('Default settings leave the current native permission policy unchanged', async () => {
    const f = await fixture();
    f.root.acceptSettings({ model: 'mock', approvalPolicy: 'never', sandboxPolicy: { type: 'dangerFullAccess' } });
    const request = vi.spyOn(f.root.client, 'request');
    expect((await f.root.applySettings({ permissionMode: 'default' })).applied.permissionMode).toBe('yolo');
    expect(request).not.toHaveBeenCalled();
    await f.root.applySettings({ permissionMode: 'default', model: 'changed' });
    const params = request.mock.calls.find(([method]) => method === 'thread/settings/update')?.[1];
    expect(params).toMatchObject({ model: 'changed' });
    expect(params).not.toHaveProperty('approvalPolicy');
    expect(params).not.toHaveProperty('sandboxPolicy');
});

async function completePlan(f: Awaited<ReturnType<typeof fixture>>, status = 'completed') {
    await f.root.applySettings({ collaborationMode: 'plan' });
    const turn = { id: 'plan-turn', status: 'inProgress', items: [{ id: 'plan-item', type: 'plan', text: '# Implement me' }] };
    f.native.thread.turns.push(turn);
    f.native.notify('turn/started', { threadId: 'thread', turn: { id: turn.id } });
    f.native.notify('item/completed', { threadId: 'thread', turnId: turn.id, item: turn.items[0] });
    expect(f.state().codexPlanProposalId).toBeNull();
    turn.status = status;
    f.native.notify('turn/completed', { threadId: 'thread', turn: { id: turn.id, status } });
    await vi.waitFor(() => expect(f.send).toHaveBeenCalledWith(expect.objectContaining({ name: 'ExitPlanMode' }), expect.any(String)));
    return codexPlanProposalId('thread', turn.id, 'plan-item');
}

describe('shared plan actions', () => {
    it('applies remote change_title as metadata.name then lets native terminal rename win', async () => {
        const f = await fixture();
        const item = { id: 'title', type: 'mcpToolCall', server: 'hapi', tool: 'change_title',
            arguments: { title: 'Remote title' }, status: 'completed', result: { content: [], isError: false } };
        f.native.notify('item/completed', { threadId: 'thread', turnId: 'turn', item });
        await vi.waitFor(() => expect(f.metadata().name).toBe('Remote title'));
        f.native.notify('thread/name/updated', { threadId: 'thread', threadName: 'Terminal title' });
        await vi.waitFor(() => expect(f.metadata().name).toBe('Terminal title'));
        await f.root.refresh();
        expect(f.metadata().name).toBe('Terminal title');
    });

    it('preserves content while native turns, mode changes and disconnects withdraw controls', async () => {
        const f = await fixture();
        const id = await completePlan(f);
        expect(f.state().codexPlanProposalId).toBe(id);
        expect(f.state().requests).toEqual({});
        await f.root.applySettings({ collaborationMode: 'default' });
        expect(f.state().codexPlanProposalId).toBeNull();
        await f.root.applySettings({ collaborationMode: 'plan' });
        expect(f.state().codexPlanProposalId).toBe(id);
        f.native.initialized = false; f.native.abandoned();
        expect(f.state().codexPlanProposalId).toBeNull();
        await vi.waitFor(() => expect(f.state().codexPlanProposalId).toBe(id));
        f.native.notify('turn/started', { threadId: 'thread', turn: { id: 'new' } });
        f.native.notify('turn/completed', { threadId: 'thread', turn: { id: 'plan-turn', status: 'completed' } });
        expect(f.state().codexPlanProposalId).toBeNull();
        expect(f.send.mock.calls.some(([message]) => message.input?.plan === '# Implement me')).toBe(true);
    });

    it.each(['failed', 'interrupted'])('does not offer a proposal from a %s turn', async status => {
        const f = await fixture();
        await completePlan(f, status);
        await f.root.refresh();
        expect(f.state().codexPlanProposalId).toBeNull();
    });

    it('uses only the latest root turn when replaying history', async () => {
        const f = await fixture();
        const id = await completePlan(f);
        f.reconnect();
        await f.root.refresh();
        await vi.waitFor(() => expect(f.state().codexPlanProposalId).toBe(id));
        f.native.thread.turns.push({ id: 'new', status: 'completed', items: [] });
        await f.root.refresh();
        expect(f.state().codexPlanProposalId).toBeNull();
        f.native.notify('item/completed', { threadId: 'child', turnId: 'child-turn', item: { id: 'p', type: 'plan', text: 'child' } });
        expect(f.state().codexPlanProposalId).toBeNull();
    });

    it('switches mode and submits once across repeated Web actions and lost replies', async () => {
        const f = await fixture();
        await f.root.activate();
        const id = await completePlan(f);
        const action = () => f.rpc.get(RPC_METHODS.ImplementCodexPlan)!({ planId: id });
        const request = vi.spyOn(f.root.client, 'request');
        expect(await Promise.all([action(), action()])).toEqual([{ ok: true }, { ok: true }]);
        f.reconnect();
        expect(await action()).toEqual({ ok: true });
        expect(request.mock.calls.filter(([method]) => method === 'thread/queue/add')).toHaveLength(1);
        const settingsIndex = request.mock.calls.findIndex(([method]) => method === 'thread/settings/update');
        const queueIndex = request.mock.calls.findIndex(([method]) => method === 'thread/queue/add');
        expect(settingsIndex).toBeLessThan(queueIndex);
        expect(request.mock.calls[settingsIndex][1]).toMatchObject({ collaborationMode: { mode: 'default' } });
        expect(f.native.queue[0]).toMatchObject({ input: [{ type: 'text', text: 'Implement the plan.' }] });
        expect(f.state().codexPlanProposalId).toBeNull();
    });

    it('does not let a slow history snapshot resurrect a plan after native continuation', async () => {
        const f = await fixture();
        await completePlan(f);
        const request = f.root.client.request.bind(f.root.client);
        let release!: () => void;
        const blocked = new Promise<void>(resolve => { release = resolve; });
        let reading!: () => void;
        const started = new Promise<void>(resolve => { reading = resolve; });
        vi.spyOn(f.root.client, 'request').mockImplementation(async (method, params) => {
            const result = await request(method, params);
            if (method === 'thread/read' && (params as { includeTurns?: boolean }).includeTurns) {
                reading(); await blocked;
            }
            return result;
        });
        const refresh = f.root.refresh();
        await started;
        f.native.notify('turn/started', { threadId: 'thread', turn: { id: 'terminal-continued' } });
        release(); await refresh;
        expect(f.state().codexPlanProposalId).toBeNull();
    });

    it('does not change mode when native input appears during the action preflight', async () => {
        const f = await fixture();
        await f.root.activate();
        const id = await completePlan(f);
        const request = f.root.client.request.bind(f.root.client);
        const spy = vi.spyOn(f.root.client, 'request').mockImplementation(async (method, params) => {
            const result = await request(method, params);
            if (method === 'thread/queue/list') f.native.notify('turn/started', { threadId: 'thread', turn: { id: 'terminal' } });
            return result;
        });
        expect(await f.rpc.get(RPC_METHODS.ImplementCodexPlan)!({ planId: id })).toMatchObject({ ok: false, code: 'stale_plan' });
        expect(spy.mock.calls.some(([method]) => method === 'thread/settings/update')).toBe(false);
        expect(f.native.queue).toHaveLength(0);
    });

    it('rejects stale proposals and native activity arriving during the mode switch', async () => {
        const f = await fixture();
        await f.root.activate();
        const id = await completePlan(f);
        const action = (planId = id) => f.rpc.get(RPC_METHODS.ImplementCodexPlan)!({ planId });
        expect(await action('old')).toMatchObject({ ok: false, code: 'stale_plan' });
        const request = f.root.client.request.bind(f.root.client);
        vi.spyOn(f.root.client, 'request').mockImplementation(async (method, params) => {
            const result = await request(method, params);
            if (method === 'thread/settings/update') f.native.notify('turn/started', { threadId: 'thread', turn: { id: 'terminal' } });
            return result;
        });
        expect(await action()).toMatchObject({ ok: false, code: 'stale_plan' });
        expect(f.native.queue).toHaveLength(0);
    });

    it('does not resend an implementation with an unknown queue outcome', async () => {
        const f = await fixture();
        await f.root.activate();
        const id = await completePlan(f);
        const request = f.root.client.request.bind(f.root.client);
        const spy = vi.spyOn(f.root.client, 'request').mockImplementation(async (method, params) => {
            const result = await request(method, params);
            // An invalid response schema leaves delivery indeterminate even after acceptance.
            return method === 'thread/queue/add' ? {} : result;
        });
        const action = () => f.rpc.get(RPC_METHODS.ImplementCodexPlan)!({ planId: id });
        expect(await action()).toMatchObject({ ok: false, code: 'indeterminate' });
        f.native.queue = []; // Absence is not proof of cancellation or delivery.
        expect(await action()).toMatchObject({ ok: false, code: 'indeterminate' });
        expect(spy.mock.calls.filter(([method]) => method === 'thread/queue/add')).toHaveLength(1);
    });
});

describe('shared steering availability', () => {
    it('keeps idle sessions online without polling usage or publishing agent-state updates', async () => {
        const f = await fixture();
        const requests = vi.spyOn(f.root.client, 'request');
        const heartbeat = vi.spyOn(f.root.session, 'keepAlive');
        const updates = f.updateState.mock.calls.length;
        vi.useFakeTimers();

        await f.root.activate();
        await vi.advanceTimersByTimeAsync(5 * 60_000);

        expect(heartbeat).toHaveBeenCalled();
        expect(requests).not.toHaveBeenCalled();
        expect(f.updateState).toHaveBeenCalledTimes(updates);
    });

    it('publishes root turn transitions, ignores child turns, and clears on shutdown', async () => {
        const f = await fixture();
        expect(f.state().steeringActive).toBe(false);
        f.native.notify('turn/started', { threadId: 'thread', turn: { id: 'turn' } });
        expect(f.state().steeringActive).toBe(true);
        f.native.notify('turn/completed', { threadId: 'thread', turn: { id: 'old-turn' } });
        expect(f.state().steeringActive).toBe(true);
        f.native.notify('turn/completed', { threadId: 'thread', turn: { id: 'turn', status: 'completed' } });
        expect(f.state().steeringActive).toBe(false);
        f.native.notify('turn/started', { threadId: 'child', turn: { id: 'child-turn' } });
        expect(f.state().steeringActive).toBe(false);
        f.native.notify('turn/started', { threadId: 'thread', turn: { id: 'next' } });
        f.root.stopAccepting();
        expect(f.state().steeringActive).toBe(false);
    });

    it('reconciles native and Hub reconnects without publishing on every refresh', async () => {
        const f = await fixture();
        const updates = f.updateState.mock.calls.length;
        await f.root.refresh(); await f.root.refresh();
        expect(f.updateState).toHaveBeenCalledTimes(updates);
        f.native.thread.turns = [{ id: 'busy', status: 'inProgress', items: [] }];
        f.reconnect();
        await vi.waitFor(() => expect(f.state().steeringActive).toBe(true));
        f.native.initialized = false; f.native.abandoned();
        expect(f.state().steeringActive).toBe(false);
        await vi.waitFor(() => expect(f.state().steeringActive).toBe(true));
        f.native.thread.turns = [{ id: 'busy', status: 'completed', items: [] }];
        f.reconnect();
        await vi.waitFor(() => expect(f.state().steeringActive).toBe(false));
    });

    it('ends the shared root on hub-archived metadata (#1911 C2 / AC6)', async () => {
        const end = vi.fn<RootHost['end']>(async () => {});
        const f = await fixture({ end });
        await f.root.activate();
        expect(f.hubArchivedListenerCount()).toBe(1);
        f.emitHubArchived();
        await vi.waitFor(() => expect(end).toHaveBeenCalledTimes(1));
        expect(end.mock.calls[0]?.[0]).toBe(f.root);
    });

    it('ends when hubArchived was latched before activate (production order, #1911 AC6)', async () => {
        // Bootstrap may refuse CAS / noteHubArchived before Codex bind+activate
        // registers controls — same late-listener miss as other flavors.
        const end = vi.fn<RootHost['end']>(async () => {});
        const f = await fixture({ hubArchived: true, end });
        await f.root.activate();
        await vi.waitFor(() => expect(end).toHaveBeenCalledTimes(1));
    });
});


describe('native title synchronization', () => {
    it('hydrates native name and preview, refreshes missed changes, and handles native clear', async () => {
        const f = await fixture();
        f.native.thread.name = 'Native name'; f.native.thread.preview = 'Native first message';
        await f.root.refresh(); expect(f.metadata().name).toBe('Native name');
        f.native.thread.name = null; await f.root.refresh();
        expect(f.metadata().name).toBe('Native first message');
        f.native.thread.name = 'Renamed while offline'; f.reconnect();
        await vi.waitFor(() => expect(f.metadata().name).toBe('Renamed while offline'));
        f.native.notify('thread/name/updated', { threadId: 'thread', threadName: null });
        expect(f.metadata().name).toBe('Native first message');
        f.native.notify('thread/name/updated', { threadId: 'thread' });
        expect(f.metadata().name).toBe('Native first message');
        f.native.notify('thread/name/updated', { threadId: 'child', threadName: 'Child title' });
        expect(f.metadata().name).toBe('Native first message');
    });
    it('does not let an older snapshot replace a live native rename', async () => {
        const f = await fixture();
        const original = f.root.readThread.bind(f.root);
        let release!: (thread: Record<string, unknown>) => void;
        vi.spyOn(f.root, 'readThread').mockImplementationOnce(() => new Promise(resolve => { release = resolve; }));
        const refresh = f.root.refresh();
        f.native.notify('thread/name/updated', { threadId: 'thread', threadName: 'New name' });
        release({ ...(await original()), name: 'Stale name', preview: 'old preview' });
        await refresh; expect(f.metadata().name).toBe('New name');
    });
    it('uses native preview after the first live user message without a separate rename', async () => {
        const f = await fixture();
        f.native.thread.name = null; f.native.thread.preview = ''; await f.root.refresh();
        f.native.thread.preview = 'First live prompt';
        f.native.notify('item/completed', { threadId: 'thread', turnId: 'turn', item: { id: 'user', type: 'userMessage', content: [{ type: 'text', text: 'First live prompt' }] } });
        await vi.waitFor(() => expect(f.metadata().name).toBe('First live prompt'));
    });
    it('writes native name through the session RPC and keeps later native names authoritative', async () => {
        const f = await fixture(); await f.root.activate();
        const request = vi.spyOn(f.root.client, 'request');
        expect(await f.rpc.get(RPC_METHODS.RenameCodexSession)!({ name: ' HAPI   title ' })).toEqual({ success: true, name: 'HAPI title' });
        expect(request).toHaveBeenCalledWith('thread/name/set', { threadId: 'thread', name: 'HAPI title' });
        expect(f.metadata().name).toBe('HAPI title');
        request.mockRejectedValueOnce(new Error('native rename failed'));
        await expect(f.rpc.get(RPC_METHODS.RenameCodexSession)!({ name: 'Unconfirmed' })).rejects.toThrow('native rename failed');
        expect(f.metadata().name).toBe('HAPI title');
        f.native.notify('thread/name/updated', { threadId: 'thread', threadName: 'Desktop title' });
        expect(f.metadata().name).toBe('Desktop title');
    });
});


it('keeps a later native rename when an earlier title-tool completion is still queued', async () => {
    const f = await fixture();
    f.native.notify('item/completed', { threadId: 'thread', turnId: 'turn', item: {
        id: 'late-title', type: 'mcpToolCall', server: 'hapi', tool: 'change_title',
        arguments: { title: 'Older tool title' }, status: 'completed', result: { content: [], isError: false }
    } });
    f.native.notify('thread/name/updated', { threadId: 'thread', threadName: 'Later native title' });
    await vi.waitFor(() => expect(f.send).toHaveBeenCalled());
    await f.root.refresh();
    expect(f.metadata().name).toBe('Later native title');
    expect(f.native.thread.name).toBe('Later native title');
});

it('reads every history page using 100-turn batches without resuming the thread', async () => {
    const f = await fixture();
    const original = f.root.client.request.bind(f.root.client);
    const request = vi.spyOn(f.root.client, 'request').mockImplementation(async (method, params) => {
        if (method === 'thread/read') return { thread: { id: 'thread', historyMode: 'paginated' } };
        if (method === 'thread/turns/list') return (params as Record<string, unknown>)?.cursor
            ? { data: [{ id: 'b', items: [] }], nextCursor: null }
            : { data: [{ id: 'a', items: [] }], nextCursor: 'next' };
        return original(method, params);
    });
    const thread = await f.root.readThread();
    expect((thread.turns as Array<{ id: string }>).map(turn => turn.id)).toEqual(['a', 'b']);
    expect(request.mock.calls.filter(([method]) => method === 'thread/turns/list').map(([, params]) => params)).toEqual([
        { threadId: 'thread', cursor: undefined, limit: 100, sortDirection: 'asc', itemsView: 'full' },
        { threadId: 'thread', cursor: 'next', limit: 100, sortDirection: 'asc', itemsView: 'full' }
    ]);
    expect(request.mock.calls.some(([method]) => method === 'thread/resume')).toBe(false);
});

it('tracks compaction only for the root and withdraws steering until completion', async () => {
    const f = await fixture();
    f.native.notify('turn/started', { threadId: 'thread', turn: { id: 'turn' } });
    f.native.notify('item/started', { threadId: 'child', turnId: 'turn', item: { type: 'contextCompaction', id: 'child-compact' } });
    expect(f.state().codexCompaction).toBeNull();
    f.native.notify('item/started', { threadId: 'thread', turnId: 'turn', item: { type: 'contextCompaction', id: 'root-compact' } });
    expect(f.state().codexCompaction?.itemId).toBe('root-compact');
    expect(f.state().steeringActive).toBe(false);
    f.native.notify('item/completed', { threadId: 'child', turnId: 'turn', item: { type: 'contextCompaction', id: 'child-compact' } });
    expect(f.state().codexCompaction?.itemId).toBe('root-compact');
    f.native.notify('item/completed', { threadId: 'thread', turnId: 'turn', item: { type: 'contextCompaction', id: 'root-compact' } });
    expect(f.state().codexCompaction).toBeNull();
    expect(f.state().steeringActive).toBe(true);
    await new Promise(resolve => setImmediate(resolve));
    expect(f.sessionEvents.mock.calls.some(([event]) => event.type === 'message' && event.message.includes('Compaction started'))).toBe(false);
});


it('injects async answers immediately and projects them only after a native userMessage', async () => {
    const f = await fixture();
    const question = { type: 'agentMessage', id: 'question', delivery: 'async',
        questions: [{ title: 'Choose a layout', options: ['Compact', 'Expanded'] }] };
    f.native.notify('turn/started', { threadId: 'thread', turn: { id: 'working', status: 'inProgress' } });
    f.native.notify('item/completed', { threadId: 'thread', turnId: 'working', item: question });
    const id = 'codex-async:thread:question';
    await vi.waitFor(() => expect(f.state().requests?.[id]).toBeDefined());
    const request = vi.spyOn(f.root.client, 'request');
    let accept!: (value: unknown) => void;
    request.mockImplementationOnce(() => new Promise(resolve => { accept = resolve; }));
    const submitting = f.rpc.get(RPC_METHODS.Permission)!({ id, approved: true, answers: { '0': ['Expanded'] } });
    await vi.waitFor(() => expect(accept).toBeTypeOf('function'));
    expect(request.mock.calls[0]).toMatchObject(['turn/start', { threadId: 'thread', clientUserMessageId: `${id}:answer` }]);
    expect(f.userMessages).not.toHaveBeenCalled();
    expect(f.state().requests?.[id]).toBeDefined();
    accept({ turn: { id: 'working' } }); await submitting;
    expect(f.native.queue).toEqual([]);
    expect(f.userMessages).not.toHaveBeenCalled();
    expect(f.state().completedRequests?.[id]?.status).toBe('approved');
    const input = (request.mock.calls[0][1] as { input: unknown }).input;
    f.native.notify('item/completed', { threadId: 'thread', turnId: 'working', item: {
        id: 'native-answer', type: 'userMessage', clientId: `${id}:answer`, content: input,
    } });
    await vi.waitFor(() => expect(f.userMessages).toHaveBeenCalled());
    expect(f.userMessages.mock.calls[0][0]).toContain('<send_user_message_question_reply>');
    expect(f.userMessages.mock.calls[0][2]).toBe(`${id}:answer`);
    expect(request.mock.calls.some(([method]) => method === 'thread/queue/add')).toBe(false);
});

it('leaves a rejected async answer actionable without publishing a user message', async () => {
    const f = await fixture();
    f.native.notify('item/completed', { threadId: 'thread', turnId: 'working', item: {
        type: 'agentMessage', id: 'question', delivery: 'async', questions: [{ title: 'Choose' }],
    } });
    const id = 'codex-async:thread:question';
    await vi.waitFor(() => expect(f.state().requests?.[id]).toBeDefined());
    const request = vi.spyOn(f.root.client, 'request').mockRejectedValueOnce(new Error('Rejected by native'));
    await expect(f.rpc.get(RPC_METHODS.Permission)!({ id, approved: true, answers: { '0': ['Answer'] } })).rejects.toThrow('Rejected by native');
    expect(f.state().requests?.[id]).toBeDefined();
    expect(f.state().completedRequests?.[id]).toBeUndefined();
    expect(f.userMessages).not.toHaveBeenCalled();
    expect(request.mock.calls.map(([method]) => method)).toEqual(['turn/start']);
});
