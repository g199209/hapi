import { afterEach, describe, expect, it, vi } from 'vitest';
import { createServer } from 'node:http';
import { spawn } from 'node:child_process';
import { mkdtemp, mkdir, writeFile, rm, readdir } from 'node:fs/promises';
import { join } from 'node:path';
import { randomUUID } from 'node:crypto';
import type { AgentState, Metadata } from '@/api/types';
import type { ApiSessionClient } from '@/api/apiSession';
import { CodexAppServerClient } from '../codexAppServerClient';
import { initializeSharedClient } from './launch';
import { record } from './gateway';
import { isProcessAlive } from '@/utils/process';
import { RPC_METHODS } from '@hapi/protocol/rpcMethods';

const state = vi.hoisted(() => ({ home: '', sessions: new Map<string, MockSession>(), beforeBootstrap: undefined as (() => Promise<void>) | undefined }));
class MockSession {
    readonly sessionId = randomUUID();
    state: AgentState = { requests: { 'old-worker': { tool: 'request_user_input', arguments: {}, createdAt: 0 } } }; metadata: Metadata;
    messages: unknown[] = []; consumed: string[] = []; dead = false;
    user?: (message: { content: { text: string } }, id?: string) => void;
    rpc = new Map<string, (params: unknown) => Promise<unknown>>();
    rpcHandlerManager = { registerHandler: (name: string, fn: (params: unknown) => Promise<unknown>) => { this.rpc.set(name, fn); } };
    constructor(cwd: string) { this.metadata = { path: cwd, host: 'test', hostPid: process.pid, machineId: 'test', flavor: 'codex', capabilities: { concurrentClients: true } }; }
    getMetadata() { return this.metadata; }
    updateMetadata(fn: (m: Metadata) => Metadata) { this.metadata = fn(this.metadata); }
    updateAgentState(fn: (s: AgentState) => AgentState) { this.state = fn(this.state); }
    onUserMessage(fn: MockSession['user']) { this.user = fn; }
    onCancelQueuedMessage() {} onRetryQueuedMessage() {} onReconnect() {} on() {}
    sendUserMessage(text: string) { this.messages.push({ user: text }); }
    sendAgentMessage(body: unknown) { this.messages.push(body); }
    sendSessionEvent(body: unknown) { this.messages.push(body); }
    emitMessagesConsumed(ids: string[]) { this.consumed.push(...ids); }
    emitSteerIndeterminate() {} keepAlive() {} emitSessionReady() {}
    async setSteerDeliveryState() { return true; }
    syncNativeQueuedMessage() {}
    sendSessionDeath() { this.dead = true; } async flush() {} close() {} isPending() { return false; }
}
vi.mock('@/configuration', () => ({ configuration: { get happyHomeDir() { return state.home; }, apiUrl: 'http://mock-hub', cliApiToken: 'test' } }));
vi.mock('@/ui/logger', () => ({ logger: { debug: (...args: unknown[]) => { if (process.env.HAPI_DEBUG_SHARED_TEST) console.log(...args); }, warn: vi.fn(), info: vi.fn(), debugLargeJson: vi.fn() } }));
vi.mock('@/agent/sessionFactory', () => ({ bootstrapSession: async (options: { workingDirectory: string; metadataOverrides: Partial<Metadata> }) => {
    await state.beforeBootstrap?.();
    const session = new MockSession(options.workingDirectory); session.metadata = { ...session.metadata, ...options.metadataOverrides };
    state.sessions.set(session.sessionId, session);
    return { session: session as unknown as ApiSessionClient, sessionInfo: { id: session.sessionId, namespace: 'test' }, metadata: session.metadata,
        workingDirectory: options.workingDirectory, machineId: 'test', startedBy: 'terminal', api: {} };
}, bootstrapExistingSession: vi.fn(async (options: { sessionId: string; workingDirectory: string; metadataOverrides: Partial<Metadata> }) => {
    const session = state.sessions.get(options.sessionId);
    if (!session) throw new Error('Missing test session');
    session.dead = false; session.metadata = { ...session.metadata, ...options.metadataOverrides };
    return { session: session as unknown as ApiSessionClient, sessionInfo: { id: session.sessionId, namespace: 'test' }, metadata: session.metadata,
        workingDirectory: options.workingDirectory, machineId: 'test', startedBy: 'runner', api: {} };
}) }));
vi.mock('@/runner/controlClient', () => ({ notifyRunnerSessionStarted: vi.fn(async () => ({})) }));
vi.mock('../utils/buildHapiMcpBridge', () => ({ buildHapiMcpBridge: async () => ({ server: { url: 'http://unused', stop() {} }, mcpServers: {} }) }));

// Explicit opt-in: installed official binary, entirely isolated CODEX_HOME, mock Responses API only.
describe.skipIf(process.env.HAPI_RUN_SHARED_CODEX_TESTS !== '1')('installed Codex shared runtime', () => {
    afterEach(() => { vi.unstubAllEnvs(); state.sessions.clear(); state.beforeBootstrap = undefined; });
    it('forks an external named-profile thread with its history and permissions intact', async () => {
        const home = await mkdtemp('/tmp/hapi-named-fork-'); state.home = home;
        const ch = join(home, 'codex'), cwd = join(home, 'work'); await mkdir(ch); await mkdir(cwd);
        let requests = 0;
        const http = createServer((request, response) => {
            request.resume(); request.on('end', () => {
                requests++;
                const id = randomUUID();
                const events = [{ type: 'response.created', response: { id } },
                    { type: 'response.output_item.done', item: { type: 'message', role: 'assistant', id: `msg_${id}`, content: [{ type: 'output_text', text: 'NAMED PROFILE ANSWER' }] } },
                    { type: 'response.completed', response: { id, usage: { input_tokens: 12, output_tokens: 3, total_tokens: 15 } } }];
                response.writeHead(200, { 'Content-Type': 'text/event-stream' });
                response.end(events.map(e => `event: ${e.type}\ndata: ${JSON.stringify(e)}\n\n`).join(''));
            });
        });
        await new Promise<void>(resolve => http.listen(0, '127.0.0.1', resolve));
        const port = (http.address() as { port: number }).port;
        await writeFile(join(ch, 'config.toml'), `model = "mock-model"\nmodel_provider = "mock"\napproval_policy = "never"\nsandbox_mode = "read-only"\n[model_providers.mock]\nname = "Mock"\nbase_url = "http://127.0.0.1:${port}/v1"\nwire_api = "responses"\nrequires_openai_auth = false\nsupports_websockets = false\n[analytics]\nenabled = false\n[feedback]\nenabled = false\n[projects."${cwd}"]\ntrust_level = "trusted"\n`);
        vi.stubEnv('CODEX_HOME', ch);
        const endpoint = `unix://${join(home, 'desktop.sock')}`;
        const { resolveSharedCodex } = await import('./launch'); const command = resolveSharedCodex();
        const engine = spawn(command.command, [...command.args, 'app-server', '--listen', endpoint], { cwd, env: process.env, stdio: ['ignore', 'pipe', 'pipe'] });
        engine.stdout?.resume(); engine.stderr?.resume();
        const owner = new CodexAppServerClient({ endpoint });
        const abort = new AbortController(); let running: Promise<void> | undefined;
        try {
            await vi.waitFor(async () => { await owner.connect(); await initializeSharedClient(owner); }, { timeout: 10_000 });
            const profiles = record(await owner.request('permissionProfile/list', { cwd })).data as Array<{ id: string }>;
            const permissions = profiles.find(p => p.id.includes('read'))?.id;
            expect(permissions, JSON.stringify(profiles)).toBeTypeOf('string');
            const started = record(await owner.request('thread/start', { cwd, model: 'mock-model', permissions, approvalPolicy: 'never' }));
            const threadId = String(record(started.thread).id);
            await owner.request('turn/start', { threadId, input: [{ type: 'text', text: 'NAMED PROFILE PROMPT', text_elements: [] }] });
            await vi.waitFor(async () => expect(JSON.stringify(await owner.request('thread/turns/list', { threadId, itemsView: 'full' }))).toContain('NAMED PROFILE ANSWER'), { timeout: 15_000 });
            const original = record(await owner.request('thread/resume', { threadId }));
            expect(record(original.activePermissionProfile).id).toBe(permissions);
            const { runSharedRuntime } = await import('./runtime');
            let ready!: (value: import('./runtime').RuntimeReady) => void;
            const readiness = new Promise<import('./runtime').RuntimeReady>(resolve => { ready = resolve; });
            running = runSharedRuntime({ workingDirectory: cwd, appServerEndpoint: endpoint, resumeSessionId: threadId }, ready, abort.signal);
            const attached = await Promise.race([readiness, running.then(() => { throw new Error('Stopped before ready'); })]);
            const web = state.sessions.get(attached.sessionId)!;
            const fork = record(await web.rpc.get(RPC_METHODS.ForkConversation)!({}));
            expect(fork.nativeSessionId).not.toBe(threadId); expect(fork.sessionId).toBeTypeOf('string');
            const child = record(await owner.request('thread/resume', { threadId: fork.nativeSessionId }));
            for (const key of ['activePermissionProfile', 'sandbox', 'approvalPolicy', 'approvalsReviewer', 'model', 'reasoningEffort', 'runtimeWorkspaceRoots']) {
                expect(child[key], key).toEqual(original[key]);
            }
            const history = JSON.stringify(await owner.request('thread/turns/list', { threadId: fork.nativeSessionId, itemsView: 'full' }));
            expect(history).toContain('NAMED PROFILE PROMPT'); expect(history).toContain('NAMED PROFILE ANSWER');
            const cleared = record(await web.rpc.get(RPC_METHODS.ClearConversation)!({}));
            const newRoot = state.sessions.get(String(cleared.sessionId))!;
            const fresh = record(await owner.request('thread/resume', { threadId: newRoot.metadata.codexSessionId }));
            expect(fresh.activePermissionProfile).toEqual(original.activePermissionProfile);
            expect(requests).toBe(1); expect(engine.exitCode).toBeNull();
        } finally {
            abort.abort(); await running?.catch(() => {}); await owner.disconnect();
            engine.kill('SIGTERM'); await new Promise<void>(resolve => engine.once('exit', () => resolve()));
            await new Promise<void>(resolve => http.close(() => resolve())); await rm(home, { recursive: true, force: true });
        }
    }, 60_000);
    it('injects async answers into the active turn ahead of ordinary queued prompts', async () => {
        const home = await mkdtemp('/tmp/hapi-native-async-'); state.home = home;
        const ch = join(home, 'codex'), cwd = join(home, 'work'); await mkdir(ch); await mkdir(cwd);
        const endpoint = `unix://${join(home, 'engine.sock')}`;
        const requests: Array<Record<string, unknown>> = [];
        const held = new Map<number, { response: import('node:http').ServerResponse; id: string }>();
        const finish = (response: import('node:http').ServerResponse, id: string, item: unknown, created = false) => {
            const events = [...(created ? [] : [{ type: 'response.created', response: { id } }]),
                { type: 'response.output_item.done', item },
                { type: 'response.completed', response: { id, usage: { input_tokens: 10, output_tokens: 5, total_tokens: 15 } } }];
            if (!created) response.writeHead(200, { 'Content-Type': 'text/event-stream' });
            response.end(events.map(e => `event: ${e.type}\ndata: ${JSON.stringify(e)}\n\n`).join(''));
        };
        const release = (index: number) => {
            const pending = held.get(index)!; held.delete(index);
            finish(pending.response, pending.id, { type: 'message', role: 'assistant', id: `msg_${pending.id}`,
                content: [{ type: 'output_text', text: index === 2 ? 'STILL_WORKING' : 'ASYNC_REPLY_OK' }] }, true);
        };
        const http = createServer((request, response) => {
            const chunks: Buffer[] = [];
            request.on('data', data => chunks.push(Buffer.from(data)));
            request.on('end', () => {
                requests.push(JSON.parse(Buffer.concat(chunks).toString()));
                const index = requests.length, id = randomUUID();
                if (index === 2 || index === 3) {
                    response.writeHead(200, { 'Content-Type': 'text/event-stream' });
                    response.write(`event: response.created\ndata: ${JSON.stringify({ type: 'response.created', response: { id } })}\n\n`);
                    held.set(index, { response, id });
                    return;
                }
                const item = index === 1 || index === 5
                    ? { type: 'function_call', name: 'request_user_input_async', call_id: index === 1 ? 'async-choice' : 'idle-choice', arguments: JSON.stringify({
                        questions: [{ title: '选择布局', options: ['紧凑', '展开'] }]
                    }) }
                    : { type: 'message', role: 'assistant', id: `msg_${id}`, content: [{ type: 'output_text', text: 'ORDINARY_REPLY_OK' }] };
                finish(response, id, item);
            });
        });
        await new Promise<void>(resolve => http.listen(0, '127.0.0.1', resolve));
        const port = (http.address() as { port: number }).port;
        await writeFile(join(ch, 'config.toml'), `model = "gpt-6-astra"\nmodel_provider = "mock"\napproval_policy = "never"\nsandbox_mode = "read-only"\n[model_providers.mock]\nname = "Mock"\nbase_url = "http://127.0.0.1:${port}/v1"\nwire_api = "responses"\nrequires_openai_auth = false\nsupports_websockets = false\n[analytics]\nenabled = false\n[feedback]\nenabled = false\n`);
        vi.stubEnv('CODEX_HOME', ch);
        const { resolveSharedCodex } = await import('./launch'); const command = resolveSharedCodex();
        const engine = spawn(command.command, [...command.args, '-c', 'features.code_mode_host=true', '-c', 'features.send_message_to_user_async=true', '-c', 'features.default_mode_request_user_input=true', 'app-server', '--listen', endpoint], { cwd, env: process.env, stdio: 'ignore' });
        const owner = new CodexAppServerClient({ endpoint });
        const events: Array<{ method: string; params: unknown }> = [];
        owner.setNotificationHandler((method, params) => events.push({ method, params }));
        const abort = new AbortController(); let running: Promise<void> | undefined;
        try {
            await vi.waitFor(async () => { await initializeSharedClient(owner); }, { timeout: 10_000 });
            const thread = record(record(await owner.request('thread/start', { cwd, model: 'gpt-6-astra', sandbox: 'read-only', approvalPolicy: 'never' })).thread);
            const started = record(record(await owner.request('turn/start', { threadId: thread.id, input: [{ type: 'text', text: 'ASK ASYNC', text_elements: [] }] })).turn);
            const { runSharedRuntime } = await import('./runtime');
            let ready!: (value: import('./runtime').RuntimeReady) => void;
            const readiness = new Promise<import('./runtime').RuntimeReady>(resolve => { ready = resolve; });
            running = runSharedRuntime({ workingDirectory: cwd, appServerEndpoint: endpoint, resumeSessionId: String(thread.id) }, ready, abort.signal);
            const attached = await Promise.race([readiness, running.then(() => { throw new Error('Runtime stopped'); })]);
            const web = state.sessions.get(attached.sessionId)!;
            await vi.waitFor(() => expect(Object.keys(web.state.requests ?? {}).some(id => id.startsWith('codex-async:'))).toBe(true), { timeout: 15_000 });
            await vi.waitFor(() => expect(held.has(2)).toBe(true));
            const key = Object.keys(web.state.requests!).find(id => id.startsWith('codex-async:'))!;
            expect(web.messages).toContainEqual(expect.objectContaining({ type: 'tool-call', name: 'request_user_input', callId: key }));
            web.user?.({ content: { text: 'ORDINARY FOLLOWUP' } }, 'ordinary-followup');
            await vi.waitFor(async () => expect(record(await owner.request('thread/queue/list', { threadId: thread.id })).data).toHaveLength(1));
            let answerTimeout: ReturnType<typeof setTimeout> | undefined;
            try {
                await Promise.race([
                    web.rpc.get(RPC_METHODS.Permission)!({ id: key, approved: true, answers: { '0': { answers: ['展开'] } } }),
                    new Promise<never>((_, reject) => { answerTimeout = setTimeout(() => reject(new Error('Answer waited for the active turn to finish')), 5_000); }),
                ]);
            } finally { clearTimeout(answerTimeout); }
            const queued = record(await owner.request('thread/queue/list', { threadId: thread.id })).data as Array<{ clientUserMessageId: string }>;
            expect(queued.map(item => item.clientUserMessageId)).toEqual(['ordinary-followup']);
            expect(events.filter(event => event.method === 'turn/completed')).toHaveLength(0);
            expect(web.state.completedRequests?.[key]).toMatchObject({ status: 'approved', answers: { '0': { answers: ['展开'] } } });
            release(2);
            await vi.waitFor(() => expect(held.has(3)).toBe(true), { timeout: 15_000 });
            const third = JSON.stringify(requests[2]);
            expect(third).toContain('send_user_message_question_reply');
            expect(third).toContain('选择布局'); expect(third).toContain('展开');
            expect(third).not.toContain('ORDINARY FOLLOWUP');
            expect(events.filter(event => event.method === 'turn/completed')).toHaveLength(0);
            release(3);
            await vi.waitFor(() => expect(web.consumed).toContain('ordinary-followup'), { timeout: 15_000 });
            await vi.waitFor(() => expect(requests.length).toBeGreaterThanOrEqual(4));
            expect(JSON.stringify(requests[3])).toContain('ORDINARY FOLLOWUP');
            const completed = events.filter(event => event.method === 'turn/completed').map(event => record(record(event.params).turn).id);
            expect(completed[0]).toBe(started.id);
            await vi.waitFor(() => expect(events.filter(event => event.method === 'turn/completed')).toHaveLength(2));
            // An optional question can remain unanswered after its originating
            // turn has finished. The same native route must then start a turn.
            await owner.request('turn/start', { threadId: thread.id, input: [{ type: 'text', text: 'ASK IDLE', text_elements: [] }] });
            await vi.waitFor(() => expect(events.filter(event => event.method === 'turn/completed')).toHaveLength(3));
            const idleKey = Object.keys(web.state.requests ?? {}).find(id => id.startsWith('codex-async:') && id !== key);
            expect(idleKey).toBeDefined();
            await web.rpc.get(RPC_METHODS.Permission)!({ id: idleKey, approved: true, answers: { '0': ['紧凑'] } });
            await vi.waitFor(() => expect(requests.length).toBeGreaterThanOrEqual(7));
            expect(JSON.stringify(requests[6])).toContain('send_user_message_question_reply');
            expect(JSON.stringify(requests[6])).toContain('紧凑');
            expect(record(await owner.request('thread/queue/list', { threadId: thread.id })).data).toEqual([]);
            expect(engine.exitCode).toBeNull();
        } finally {
            for (const { response } of held.values()) response.end();
            abort.abort(); await owner.disconnect();
            if (engine.exitCode === null) { engine.kill('SIGTERM'); await new Promise<void>(resolve => engine.once('exit', () => resolve())); }
            await running;
            http.closeAllConnections();
            await new Promise<void>(resolve => http.close(() => resolve())); await rm(home, { recursive: true, force: true, maxRetries: 20, retryDelay: 50 });
        }
    }, 60_000);
    it('attaches to a desktop engine, preserves settings and queued input across detach, and arbitrates questions', async () => {
        const home = await mkdtemp('/tmp/hapi-external-'); state.home = home;
        const ch = join(home, 'codex'); const cwd = join(home, 'work'); await mkdir(ch); await mkdir(cwd);
        const endpoint = `unix://${join(home, 'desktop.sock')}`;
        const requests: Array<Record<string, unknown>> = [];
        const http = createServer((request, response) => {
            const chunks: Buffer[] = [];
            request.on('data', data => chunks.push(Buffer.from(data)));
            request.on('end', () => {
                requests.push(JSON.parse(Buffer.concat(chunks).toString()));
                const id = randomUUID();
                const item = requests.length === 1
                    ? { type: 'function_call', name: 'request_user_input', call_id: 'external-question', arguments: JSON.stringify({ questions: [{
                        id: 'choice', header: 'Test', question: 'Select an answer.', options: [
                            { label: 'First', description: 'First option' }, { label: 'Second', description: 'Second option' }
                        ]
                    }] }) }
                    : { type: 'message', role: 'assistant', id: `msg_${id}`, content: [{ type: 'output_text', text: 'EXTERNAL ANSWER' }] };
                const events = [{ type: 'response.created', response: { id } }, { type: 'response.output_item.done', item },
                    { type: 'response.completed', response: { id, usage: { input_tokens: 10, output_tokens: 5, total_tokens: 15 } } }];
                response.writeHead(200, { 'Content-Type': 'text/event-stream' });
                response.end(events.map(e => `event: ${e.type}\ndata: ${JSON.stringify(e)}\n\n`).join(''));
            });
        });
        await new Promise<void>(resolve => http.listen(0, '127.0.0.1', resolve));
        const port = (http.address() as { port: number }).port;
        await writeFile(join(ch, 'config.toml'), `model = "mock-model"\nmodel_provider = "mock"\napproval_policy = "never"\nsandbox_mode = "read-only"\n[model_providers.mock]\nname = "Mock"\nbase_url = "http://127.0.0.1:${port}/v1"\nwire_api = "responses"\nrequires_openai_auth = false\nsupports_websockets = false\nrequest_max_retries = 0\nstream_max_retries = 0\n[analytics]\nenabled = false\n[feedback]\nenabled = false\n[projects."${cwd}"]\ntrust_level = "trusted"\n`);
        vi.stubEnv('CODEX_HOME', ch);
        const { resolveSharedCodex } = await import('./launch');
        const command = resolveSharedCodex();
        const startEngine = () => spawn(command.command, [...command.args, 'app-server', '--listen', endpoint], { cwd, env: process.env, stdio: 'ignore' });
        let engine = startEngine();
        const owner = new CodexAppServerClient({ endpoint });
        let question: { id: string | number; method: string; params: unknown } | undefined;
        const events: Array<{ method: string; params: unknown }> = [];
        owner.setServerRequestHandler(request => { if (request.method === 'item/tool/requestUserInput') question = request; });
        owner.setNotificationHandler((method, params) => events.push({ method, params }));
        const aborts: AbortController[] = []; const executions: Promise<void>[] = [];
        try {
            await vi.waitFor(async () => { await initializeSharedClient(owner); }, { timeout: 10_000 });
            const thread = record(record(await owner.request('thread/start', { cwd, model: 'mock-model', sandbox: 'read-only',
                developerInstructions: 'KEEP_DESKTOP_INSTRUCTIONS', approvalPolicy: 'never' })).thread);
            const threadId = String(thread.id);
            await owner.request('turn/start', { threadId, input: [{ type: 'text', text: 'DESKTOP QUESTION', text_elements: [] }],
                collaborationMode: { mode: 'plan', settings: { model: 'mock-model', reasoning_effort: null, developer_instructions: 'KEEP_DESKTOP_INSTRUCTIONS' } } });
            await vi.waitFor(() => expect(question).toBeDefined());
            const { runSharedRuntime } = await import('./runtime');
            const attach = async () => {
                const abort = new AbortController(); aborts.push(abort);
                let ready!: (value: import('./runtime').RuntimeReady) => void;
                const readiness = new Promise<import('./runtime').RuntimeReady>(resolve => { ready = resolve; });
                // Import-page defaults must not replace a live desktop configuration.
                const running = runSharedRuntime({ workingDirectory: cwd, appServerEndpoint: endpoint, resumeSessionId: threadId,
                    model: 'must-not-replace-desktop', permissionMode: 'yolo', collaborationMode: 'default' }, ready, abort.signal);
                executions.push(running);
                const value = await Promise.race([readiness, running.then(() => { throw new Error('Stopped before ready'); })]);
                expect(value.runtime.external).toBe(true); expect(value.runtime.serverPid).toBeUndefined();
                return { ...value, abort, running, web: state.sessions.get(value.sessionId)! };
            };
            const first = await attach();
            await vi.waitFor(() => expect(Object.keys(first.web.state.requests ?? {})).toHaveLength(1));
            first.web.user?.({ content: { text: 'QUEUED BEFORE DETACH' } }, 'external-queued');
            await vi.waitFor(async () => expect(record(await owner.request('thread/queue/list', { threadId })).data).toHaveLength(1));
            first.abort.abort(); await first.running;
            expect(engine.exitCode).toBeNull(); expect(first.web.dead).toBe(true);
            expect(record(await owner.request('thread/queue/list', { threadId })).data).toHaveLength(1);
            const second = await attach();
            expect(second.sessionId).toBe(first.sessionId);
            await vi.waitFor(() => expect(Object.keys(second.web.state.requests ?? {})).toHaveLength(1));
            const requestId = Object.keys(second.web.state.requests!)[0];
            await second.web.rpc.get(RPC_METHODS.Permission)!({ id: requestId, approved: true, answers: { choice: ['First'] } });
            await vi.waitFor(() => expect(events.some(e => e.method === 'serverRequest/resolved' && record(e.params).requestId === question!.id)).toBe(true));
            await vi.waitFor(() => expect(second.web.consumed).toContain('external-queued'), { timeout: 15_000 });
            await vi.waitFor(() => expect(requests).toHaveLength(3), { timeout: 15_000 });
            const settings = record(await owner.request('thread/resume', { threadId }));
            expect(settings.model).toBe('mock-model'); expect(record(settings.sandbox).type).toBe('readOnly');
            expect(record(settings.collaborationMode).mode).toBe('plan');
            expect(JSON.stringify(requests)).toContain('KEEP_DESKTOP_INSTRUCTIONS');
            expect(JSON.stringify(requests)).not.toContain('mcp__hapi');
            expect(requests.every(request => request.model === 'mock-model')).toBe(true);
            await vi.waitFor(() => expect(events.filter(e => e.method === 'turn/completed')).toHaveLength(2));
            await owner.request('turn/start', { threadId, input: [{ type: 'text', text: 'DESKTOP FOLLOWUP', text_elements: [] }] });
            await vi.waitFor(() => expect(second.web.messages).toContainEqual({ user: 'DESKTOP FOLLOWUP' }));
            await vi.waitFor(() => expect(requests).toHaveLength(4));
            // The desktop engine may restart independently from HAPI. Both
            // subscriptions must reconnect without replaying old messages.
            engine.kill('SIGTERM'); await new Promise<void>(resolve => engine.once('exit', () => resolve()));
            engine = startEngine();
            await vi.waitFor(async () => { await initializeSharedClient(owner); }, { timeout: 10_000 });
            await owner.request('thread/resume', { threadId });
            await vi.waitFor(() => expect(second.web.state.steeringActive).toBe(false));
            await new Promise(resolve => setTimeout(resolve, 1_200));
            await owner.request('turn/start', { threadId, input: [{ type: 'text', text: 'AFTER DAEMON RESTART', text_elements: [] }] });
            await vi.waitFor(() => expect(second.web.messages).toContainEqual({ user: 'AFTER DAEMON RESTART' }), { timeout: 10_000 });
            await vi.waitFor(() => expect(requests).toHaveLength(5));
            const sibling = record(record(await owner.request('thread/start', { cwd })).thread);
            await owner.request('thread/metadata/update', { threadId: sibling.id, gitInfo: { sha: null, branch: null, originUrl: null } });
            const stopClient = new CodexAppServerClient({ endpoint: second.runtime.endpoint });
            stopClient.setServerRequestHandler(() => {}); await initializeSharedClient(stopClient);
            try {
                await second.web.rpc.get(RPC_METHODS.KillSession)!({});
                expect(await stopClient.request('hapi/stopSession', { sessionId: second.sessionId })).toEqual({ stopped: true });
                await second.running;
            } finally { await stopClient.disconnect(); }
            expect(engine.exitCode).toBeNull();
            expect(record(record(await owner.request('thread/read', { threadId: sibling.id })).thread).id).toBe(sibling.id);
            // Brand-new empty HAPI threads still need durable native storage,
            // even though attaching an existing desktop thread must not edit it.
            const emptyAbort = new AbortController(); aborts.push(emptyAbort);
            let emptyReady!: (value: import('./runtime').RuntimeReady) => void;
            const emptyReadiness = new Promise<import('./runtime').RuntimeReady>(resolve => { emptyReady = resolve; });
            const emptyRun = runSharedRuntime({ workingDirectory: cwd, appServerEndpoint: endpoint }, emptyReady, emptyAbort.signal);
            executions.push(emptyRun);
            const empty = await Promise.race([emptyReadiness, emptyRun.then(() => { throw new Error('Empty runtime stopped'); })]);
            const emptyThreadId = empty.runtime.sessions[empty.sessionId].threadId;
            expect((await readdir(join(ch, 'sessions'), { recursive: true })).some(path => path.includes(emptyThreadId))).toBe(true);
            expect(requests).toHaveLength(5);
            emptyAbort.abort(); await emptyRun; expect(engine.exitCode).toBeNull();
            // Foreign-home endpoints fail before binding and never stop that engine.
            const wrongHome = join(home, 'wrong-codex'); await mkdir(wrongHome); vi.stubEnv('CODEX_HOME', wrongHome);
            await expect(runSharedRuntime({ workingDirectory: cwd, appServerEndpoint: endpoint, resumeSessionId: threadId }))
                .rejects.toThrow('different CODEX_HOME');
            expect(engine.exitCode).toBeNull();
        } finally {
            for (const abort of aborts) abort.abort();
            await Promise.allSettled(executions);
            await owner.disconnect();
            if (engine.exitCode === null) { engine.kill('SIGTERM'); await new Promise<void>(resolve => engine.once('exit', () => resolve())); }
            await new Promise<void>(resolve => http.close(() => resolve()));
            await rm(home, { recursive: true, force: true, maxRetries: 20, retryDelay: 50 });
        }
    }, 60_000);
    it('rejects a child ID before creating a HAPI binding or calling native resume', async () => {
        const home = await mkdtemp('/tmp/hapi-shared-child-'); state.home = home;
        const ch = join(home, 'codex'); await mkdir(ch);
        await writeFile(join(ch, 'config.toml'), 'model = "mock-model"\nmodel_provider = "mock"\n[model_providers.mock]\nname = "No model calls"\nbase_url = "http://127.0.0.1:1/v1"\nwire_api = "responses"\nrequires_openai_auth = false\n[analytics]\nenabled = false\n[feedback]\nenabled = false\n');
        vi.stubEnv('CODEX_HOME', ch); vi.stubEnv('HOME', home);
        // Only the read-only ancestry reply is stubbed; startup/shutdown use
        // the installed native engine. No child is actually resumed.
        const originalRequest = CodexAppServerClient.prototype.request;
        const requests = vi.spyOn(CodexAppServerClient.prototype, 'request').mockImplementation(function<T>(this: CodexAppServerClient, method: string, params?: unknown): Promise<T> {
            if (method === 'thread/read' && record(params).threadId === 'child') {
                return Promise.resolve({ thread: { id: 'child', parentThreadId: 'parent' } } as T);
            }
            return originalRequest.call(this, method, params) as Promise<T>;
        });
        const ready = vi.fn();
        try {
            const { runSharedRuntime } = await import('./runtime');
            await expect(runSharedRuntime({ workingDirectory: home, resumeSessionId: 'child' }, ready))
                .rejects.toThrow('Cannot cold-resume a child agent independently');
            expect(ready).not.toHaveBeenCalled(); expect(state.sessions.size).toBe(0);
            expect(requests.mock.calls.some(([method]) => method === 'thread/resume')).toBe(false);
        } finally { requests.mockRestore(); await rm(home, { recursive: true, force: true, maxRetries: 20, retryDelay: 50 }); }
    }, 30_000);
    it.each(['abort', 'failure'])('cleans up the native engine and partial roots on startup %s', async outcome => {
        const home = await mkdtemp('/tmp/hapi-shared-startup-'); state.home = home;
        const ch = join(home, 'codex'); await mkdir(ch);
        await writeFile(join(ch, 'config.toml'), 'model = "mock-model"\nmodel_provider = "mock"\n[model_providers.mock]\nname = "No model calls"\nbase_url = "http://127.0.0.1:1/v1"\nwire_api = "responses"\nrequires_openai_auth = false\n[analytics]\nenabled = false\n[feedback]\nenabled = false\n');
        vi.stubEnv('CODEX_HOME', ch); vi.stubEnv('HOME', home);
        let entered!: () => void; let release!: () => void;
        const reached = new Promise<void>(resolve => { entered = resolve; });
        const gate = new Promise<void>(resolve => { release = resolve; });
        state.beforeBootstrap = async () => { entered(); await gate; if (outcome === 'failure') throw new Error('bootstrap failure'); };
        const abort = new AbortController(); const ready = vi.fn();
        const { runSharedRuntime } = await import('./runtime');
        const running = runSharedRuntime({ workingDirectory: home }, ready, abort.signal);
        const result = outcome === 'failure' ? expect(running).rejects.toThrow('bootstrap failure') : expect(running).resolves.toBeUndefined();
        try {
            await Promise.race([reached, running]);
            if (outcome === 'abort') abort.abort();
            release(); await result;
            expect(ready).not.toHaveBeenCalled();
            const { readRuntimes } = await import('./registry');
            const records = await readRuntimes(); expect(records).toHaveLength(1);
            expect(isProcessAlive(records[0].serverPid!)).toBe(false);
            for (const session of state.sessions.values()) {
                expect(session.dead).toBe(true); expect(session.metadata.lifecycleState).not.toBe('archived');
            }
        } finally { release(); abort.abort(); await running.catch(() => {}); await rm(home, { recursive: true, force: true, maxRetries: 20, retryDelay: 50 }); }
    }, 30_000);
    it('binds empty roots, exchanges messages, isolates /new, and archives only the selected root', async () => {
        const home = await mkdtemp('/tmp/hapi-shared-test-'); state.home = home;
        const ch = join(home, 'codex'); const cwd = join(home, 'work'); await mkdir(ch); await mkdir(cwd);
        const modelRequests: unknown[] = [];
        const http = createServer((request, response) => {
            const chunks: Buffer[] = [];
            request.on('data', chunk => chunks.push(Buffer.from(chunk)));
            request.on('end', () => {
                if (!request.url?.endsWith('/responses')) { response.writeHead(404).end(); return; }
                modelRequests.push(JSON.parse(Buffer.concat(chunks).toString()));
                const id = randomUUID();
                const events = [{ type: 'response.created', response: { id } },
                    { type: 'response.output_item.done', item: { type: 'message', role: 'assistant', id: `msg_${id}`, content: [{ type: 'output_text', text: 'MOCK ANSWER' }] } },
                    { type: 'response.completed', response: { id, usage: { input_tokens: 12, output_tokens: 3, total_tokens: 15 } } }];
                response.writeHead(200, { 'Content-Type': 'text/event-stream' });
                response.end(events.map(e => `event: ${e.type}\ndata: ${JSON.stringify(e)}\n\n`).join(''));
            });
        });
        await new Promise<void>(resolve => http.listen(0, '127.0.0.1', resolve));
        const port = (http.address() as { port: number }).port;
        await writeFile(join(ch, 'config.toml'), `model = "mock-model"\nmodel_provider = "mock_provider"\napproval_policy = "never"\nsandbox_mode = "read-only"\ncheck_for_update_on_startup = false\n[model_providers.mock_provider]\nname = "Isolated mock"\nbase_url = "http://127.0.0.1:${port}/v1"\nwire_api = "responses"\nrequires_openai_auth = false\nsupports_websockets = false\nrequest_max_retries = 0\nstream_max_retries = 0\n[analytics]\nenabled = false\n[feedback]\nenabled = false\n[projects."${cwd}"]\ntrust_level = "trusted"\n`);
        vi.stubEnv('CODEX_HOME', ch); vi.stubEnv('HOME', home); vi.stubEnv('HAPI_SESSION_ID', 'parent-must-not-leak');
        const { runSharedRuntime } = await import('./runtime');
        let ready!: (value: import('./runtime').RuntimeReady) => void;
        const readiness = new Promise<import('./runtime').RuntimeReady>(resolve => { ready = resolve; });
        const abort = new AbortController();
        const running = runSharedRuntime({ workingDirectory: cwd }, ready, abort.signal);
        const clients: CodexAppServerClient[] = [];
        let roots: string[] = [];
        try {
            const { runtime, sessionId } = await Promise.race([readiness, running.then(() => { throw new Error('Runtime stopped before ready'); })]);
            const connect = async () => { const client = new CodexAppServerClient({ endpoint: runtime.endpoint, token: runtime.token });
                client.setServerRequestHandler(() => {}); clients.push(client); await initializeSharedClient(client); return client; };
            const first = await connect(); const second = await connect();
            const initial = runtime.sessions[sessionId].threadId; roots.push(initial);
            const resumed = record(await first.request('thread/resume', { threadId: initial }));
            await second.request('thread/resume', { threadId: initial });
            expect(record(resumed.thread).turns).toEqual([]); expect(modelRequests).toHaveLength(0);
            expect(state.sessions.size).toBe(1);
            const web = state.sessions.get(sessionId)!;
            expect(web.state.requests).toEqual({});
            expect(web.state.completedRequests?.['old-worker']?.status).toBe('canceled');
            web.user?.({ content: { text: 'HELLO FROM WEB' } }, 'web-1');
            await vi.waitFor(() => expect(web.consumed).toContain('web-1'), { timeout: 15_000 });
            await vi.waitFor(() => expect(web.messages).toContainEqual(expect.objectContaining({ type: 'message', message: 'MOCK ANSWER' })), { timeout: 15_000 });
            const newResponse = record(await first.request('thread/start', { cwd }));
            const next = String(record(newResponse.thread).id); roots.push(next);
            expect(next).not.toBe(initial); expect(state.sessions.size).toBe(2);
            const nextSession = [...state.sessions.values()].find(session => session.metadata.codexSessionId === next)!;
            expect(web.metadata.codexSessionId).toBe(initial);
            expect(process.env.HAPI_SESSION_ID).toBe('parent-must-not-leak');
            await first.request('thread/archive', { threadId: next }); roots = [initial];
            await vi.waitFor(() => expect(nextSession.dead).toBe(true)); expect(web.dead).toBe(false);
            await second.request('turn/start', { threadId: initial, model: 'second-mock', input: [{ type: 'text', text: 'HELLO FROM TERMINAL', text_elements: [] }], clientUserMessageId: 'native-1' });
            await vi.waitFor(() => expect(web.messages).toContainEqual({ user: 'HELLO FROM TERMINAL' }), { timeout: 15_000 });
            await vi.waitFor(() => {
                const usageModels = web.messages.map(record).filter(body => body.type === 'token_count').map(body => body.model);
                expect(usageModels).toContain('mock-model');
                expect(usageModels).toContain('second-mock');
            }, { timeout: 15_000 });
            await first.disconnect(); expect(web.dead).toBe(false);
            await second.request('thread/archive', { threadId: initial }); roots = [];
            await running;
        } finally {
            for (const threadId of roots) {
                await clients.at(-1)?.request('thread/archive', { threadId }).catch(() => {});
            }
            abort.abort(); await running.catch(() => {});
            await Promise.all(clients.map(client => client.disconnect()));
            await new Promise<void>(resolve => http.close(() => resolve()));
            await rm(home, { recursive: true, force: true, maxRetries: 3 });
        }
    }, 60_000);
});
