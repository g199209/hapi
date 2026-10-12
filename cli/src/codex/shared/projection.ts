import { createHash } from 'node:crypto';
import type { ApiSessionClient } from '@/api/apiSession';
import { normalizeSessionDisplayTitle } from '@/agent/sessionDisplayRename';
import { registerGeneratedImageFromPath } from '@/modules/common/generatedImages';
import { AppServerEventConverter } from '../utils/appServerEventConverter';
import { record, string } from './gateway';
import { codexPlanProposalId } from './plan';
import { asyncQuestionsInput } from './asyncQuestions';

export function inputText(input: unknown): string {
    if (!Array.isArray(input)) return '';
    return input.map(part => {
        const value = record(part);
        return string(value.text) ?? (value.type === 'mention' ? `@"${value.path}"` : value.type === 'skill' ? `$${value.name}`
            : value.type === 'localImage' ? `[Image: ${value.path}]` : value.type === 'image' ? '[Image]' : '');
    }).filter(Boolean).join('\n');
}

function requestedTitle(item: Record<string, unknown>): string | undefined {
    if (item.type !== 'mcpToolCall' || item.server !== 'hapi' || item.tool !== 'change_title') return;
    return normalizeSessionDisplayTitle(string(record(item.arguments).title)) ?? undefined;
}

function successfulTitle(item: Record<string, unknown>, pending?: string): string | undefined {
    if (item.type !== 'mcpToolCall' || (item.status !== undefined && item.status !== 'completed')
        || item.error != null || item.result == null) return;
    const result = record(item.result);
    if ('Err' in result || result.isError === true || record(result.Ok).isError === true) return;
    return requestedTitle(item) ?? pending;
}

function metadataHasDisplayTitle(metadata: { name?: string; summary?: { text: string } } | null | undefined): boolean {
    return Boolean(metadata?.name?.trim() || metadata?.summary?.text?.trim());
}

/** Canonical V2 stream only. Stable message IDs also deduplicate snapshot replay at the hub. */
export class SharedCodexProjection {
    private converter = new AppServerEventConverter();
    private readonly emitted = new Map<string, number | undefined>();
    private position?: number;
    private readonly itemPositions = new Map<string, number>();
    private readonly turnStarts = new Map<string, number>();
    private readonly itemCounts = new Map<string, number>();
    private readonly lastPositions = new Map<string, number>();
    private readonly userAliases = new Map<string, string>();
    private readonly userSlots = new Map<string, Array<{ id: string; text: string; completed: boolean }>>();
    private readonly turns = new Map<string, string>();
    private readonly turnModels = new Map<string, string>();
    // Unlike transcript emission, title side effects survive reset/replay.
    private readonly pendingTitles = new Map<string, string>();
    private readonly completedTitles = new Set<string>();
    private titleRevision = 0;
    private excludedHistoryReasoning?: Set<string>;
    private readonly hadHistory: boolean;
    constructor(private readonly session: ApiSessionClient, readonly threadId: string,
        private readonly committed: (id: string) => Promise<void>, private readonly parentThreadId?: string) {
        const metadata = session.getMetadata();
        this.hadHistory = Object.keys(metadata?.conversationHistoryTurns ?? {}).length > 0;
        if (metadata?.codexReasoningReplayExcluded) this.excludedHistoryReasoning = new Set(metadata.codexReasoningReplayExcluded);
        if (!parentThreadId) for (const [id, turn] of Object.entries(metadata?.conversationHistoryTurns ?? {})) this.turns.set(id, turn);
    }

    turnFor(id: string): string | undefined { return this.turns.get(id); }
    reset(): void { this.converter = new AppServerEventConverter(); this.emitted.clear(); }
    private send(body: Record<string, unknown>, key: string): void {
        const at = this.position === undefined ? undefined : this.position + (String(body.type).endsWith('result') ? 1 : 0);
        if (this.emitted.has(key) && this.emitted.get(key) === at) return;
        this.emitted.set(key, at);
        const id = `codex:${this.threadId}:${key}`;
        this.session.sendAgentMessage(this.parentThreadId && !String(body.type).startsWith('agent-run-') ? {
            type: 'agent-run-trace', agentId: this.threadId, cardId: `codex-agent:${this.threadId}`, message: { ...body, id }, id,
            scope: { role: 'child', threadId: this.threadId, parentThreadId: this.parentThreadId }, scope_role: 'child'
        } : { ...body, id }, id, ...(at === undefined ? [] : [at]));
    }

    private applyDisplayRename(title: string, revision: number): void {
        this.session.updateMetadata(metadata => {
            if (revision !== this.titleRevision) return metadata;
            const normalized = normalizeSessionDisplayTitle(title);
            if (!normalized || metadata.name?.trim() === normalized) return metadata;
            return { ...metadata, name: normalized };
        });
    }

    async notification(method: string, params: unknown, modelAtReceipt?: string): Promise<void> {
        const p = record(params);
        const item = record(p.item);
        if (!this.parentThreadId && p.threadId === this.threadId && string(item.id)) {
            const key = `${string(p.turnId) ?? 'thread'}:${item.id}`;
            if (!this.completedTitles.has(key)) {
                const title = requestedTitle(item);
                if (method === 'item/started' && title) this.pendingTitles.set(key, title);
                if (method === 'item/completed') {
                    const completedTitle = successfulTitle(item, this.pendingTitles.get(key));
                    this.pendingTitles.delete(key);
                    if (completedTitle) {
                        this.completedTitles.add(key);
                        const revision = ++this.titleRevision;
                        this.applyDisplayRename(completedTitle, revision);
                    }
                }
            }
        }
        await this.project(method, params, modelAtReceipt);
    }

    private async project(method: string, params: unknown, modelAtReceipt?: string): Promise<void> {
        if (method.startsWith('codex/event/')) return;
        const p = record(params);
        const item = record(p.item);
        const turnId = string(p.turnId) ?? string(record(p.turn).id);
        const nativeTurn = record(p.turn);
        if (turnId && typeof nativeTurn.startedAt === 'number') this.turnStarts.set(turnId, nativeTurn.startedAt * 1000);
        const itemIdForPosition = string(item.id) ?? string(p.itemId);
        const positionKey = `${turnId}:${itemIdForPosition}`;
        if (typeof p.projectionAt === 'number') this.itemPositions.set(positionKey, p.projectionAt);
        else if (turnId && itemIdForPosition && !this.itemPositions.has(positionKey)) {
            const base = this.turnStarts.get(turnId) ?? (/^[a-f0-9]{8}-[a-f0-9]{4}-7/.test(turnId)
                ? parseInt(turnId.replaceAll('-', '').slice(0, 12), 16) : undefined);
            if (base !== undefined) {
                const index = this.itemCounts.get(turnId) ?? 0;
                this.itemCounts.set(turnId, index + 1);
                // Live item starts carry their own position. A delayed final
                // text stays at its start, while new output appends after the
                // bridge's ready/usage events. Reconnect snapshots repair gaps.
                const at = Math.max(Date.now(), (this.lastPositions.get(turnId) ?? base) + 2);
                this.itemPositions.set(positionKey, at);
                this.lastPositions.set(turnId, at);
            }
        }
        this.position = this.itemPositions.get(positionKey);
        // Settings may already describe the next turn by the time queued
        // notifications are projected. Never attribute usage to that model.
        if (turnId && method === 'turn/started' && modelAtReceipt && !this.turnModels.has(turnId)) {
            this.turnModels.set(turnId, modelAtReceipt);
        }
        if (turnId && method === 'model/rerouted' && string(p.toModel)) {
            this.turnModels.set(turnId, string(p.toModel)!);
        }
        const itemId = string(item.id) ?? string(p.itemId);
        // Root async questions are published as interactive cards by the
        // question bridge. Do not also emit their plain-text fallback.
        if (!this.parentThreadId && asyncQuestionsInput(item)) return;
        if (!this.parentThreadId && (method === 'item/started' || method === 'item/completed') && item.type === 'userMessage') {
            const text = inputText(item.content);
            const explicitId = string(item.clientId ?? item.clientUserMessageId);
            const alias = `${turnId}:${itemId}`;
            const slots = this.userSlots.get(turnId ?? '') ?? [];
            let slot = typeof p.userIndex === 'number' ? slots[p.userIndex] : undefined;
            let id = explicitId ?? this.userAliases.get(alias) ?? slot?.id;
            if (!id && turnId) {
                // A live item's placeholder id can be replaced in a later snapshot.
                // Match that pending occurrence only, never all equal prompt text.
                slot = slots.find(value => value.text === text && !value.completed)
                    ?? (method === 'item/completed' ? [...slots].reverse().find(value => value.text === text) : undefined);
                if (slot) id = slot.id;
                else {
                    const index = typeof p.userIndex === 'number' ? p.userIndex : slots.length;
                    id = `codex:${this.threadId}:user:${turnId}:${index}`;
                    slot = { id, text, completed: false };
                    slots[index] = slot;
                }
            }
            if (id) this.userAliases.set(alias, id);
            if (slot && method === 'item/completed') slot.completed = true;
            if (turnId) this.userSlots.set(turnId, slots);
            if (id) {
                const firstInTurn = turnId ? [...this.turns].find(([, value]) => value === turnId)?.[0] : undefined;
                if (turnId) this.turns.set(id, turnId);
                await this.committed(id);
                if (text) this.session.sendUserMessage(text, undefined, id, ...(this.position === undefined ? [] : [this.position]));
                this.session.updateMetadata(metadata => ({ ...metadata, conversationHistoryTurns: Object.fromEntries(this.turns),
                    ...(turnId && (!firstInTurn || firstInTurn === id) ? { conversationHistoryPoints: { ...metadata.conversationHistoryPoints, [id]: true } } : {})
                }));
            }
        }
        if (this.parentThreadId && (method === 'turn/started' || method === 'turn/completed')) {
            this.send({ type: 'agent-run-update', agentId: this.threadId, cardId: `codex-agent:${this.threadId}`,
                status: method === 'turn/started' ? 'running' : record(p.turn).status === 'completed' ? 'completed' : 'failed'
            }, `lifecycle:${turnId}:${method}`);
        }
        const events = this.converter.handleNotification(method, params);
        for (const event of events) {
            const callId = string(event.call_id);
            const key = `${turnId ?? 'thread'}:${itemId ?? callId ?? createHash('sha256').update(JSON.stringify(event)).digest('hex')}:${event.type}`;
            if (event.type === 'agent_message') this.send({ type: 'message', message: event.message }, key);
            else if (event.type === 'agent_reasoning') this.send({ type: 'reasoning', message: event.text }, key);
            else if (event.type === 'exec_command_begin' && callId) {
                this.send({ type: 'tool-call', name: 'CodexBash', callId, input: event }, key);
            } else if (event.type === 'exec_command_end' && callId) {
                this.send({ type: 'tool-call-result', callId, output: { ...event, stdout: event.output } }, key);
            } else if (event.type === 'patch_apply_begin' && callId) {
                this.send({ type: 'tool-call', name: 'CodexPatch', callId, input: { changes: event.changes, auto_approved: event.auto_approved } }, key);
            } else if (event.type === 'patch_apply_end' && callId) {
                this.send({ type: 'tool-call-result', callId, output: { stdout: event.stdout, stderr: event.stderr, success: event.success } }, key);
            } else if (event.type === 'mcp_tool_call_begin' && callId) {
                const invocation = record(event.invocation);
                this.send({ type: 'tool-call', name: `mcp__${invocation.server}__${invocation.tool}`, callId, input: invocation.arguments ?? {} }, key);
            } else if (event.type === 'mcp_tool_call_end' && callId) {
                const result = record(event.result);
                this.send({ type: 'tool-call-result', callId, output: result.Ok ?? result.Err ?? event.result, is_error: 'Err' in result }, key);
            } else if (event.type === 'codex_tool_call_begin' && callId) {
                this.send({ type: 'tool-call', name: event.name, callId, input: event.input ?? event.arguments }, key);
            } else if (event.type === 'codex_tool_call_end' && callId) {
                this.send({ type: 'tool-call-result', callId, output: event.output, is_error: event.is_error }, key);
            } else if (event.type === 'token_count' || event.type === 'context_compacted' || event.type.startsWith('thread_goal_')) {
                const model = event.type === 'token_count' && turnId ? this.turnModels.get(turnId) : undefined;
                this.send({ ...event, ...(model ? { model } : {}), flavor: 'codex', scope: { role: 'parent', threadId: this.threadId }, scope_role: 'parent', thread_id: this.threadId }, key);
            } else if (event.type === 'proposed_plan' && turnId && itemId) {
                const planId = codexPlanProposalId(this.threadId, turnId, itemId);
                this.send({ type: 'tool-call', name: 'ExitPlanMode', callId: planId, input: { plan: event.plan } }, key);
                // A proposal is durable content, not a native approval request.
                this.send({ type: 'tool-call-result', callId: planId, output: null }, `${key}:result`);
            } else if (event.type === 'plan_update') {
                this.send({ type: 'tool-call', name: 'update_plan', callId: 'codex-plan-state', input: { plan: event.plan, source: 'codex' } }, key);
                this.send({ type: 'tool-call-result', callId: 'codex-plan-state', output: { plan: event.plan, source: 'codex', status: 'updated' } }, `${key}:result`);
            } else if (event.type === 'generated_image' && typeof event.saved_path === 'string') {
                const image = await registerGeneratedImageFromPath({ path: event.saved_path, id: createHash('sha256').update(`${this.threadId}:${key}`).digest('hex'), fileName: string(event.file_name) });
                if (image) this.send({ type: 'generated-image', imageId: image.id, fileName: image.fileName, mimeType: image.mimeType }, key);
            } else if (event.type === 'task_failed') {
                this.send({ type: 'message', message: `Codex error: ${event.error ?? event.message ?? 'Turn failed'}` }, key);
            }
        }
        if (item.type === 'collabAgentToolCall') {
            const states = record(item.agentsStates);
            for (const [agentId, state] of Object.entries(states)) {
                const status = string(record(state).status) ?? 'running';
                this.send({ type: 'agent-run-update', agentId, cardId: `codex-agent:${agentId}`,
                    status: status === 'completed' ? 'completed' : status === 'errored' ? 'failed' : 'running',
                    summary: record(state).message, input: item, scope: { role: 'child', threadId: agentId, parentThreadId: this.threadId }, scope_role: 'child', thread_id: agentId
                }, `agent:${agentId}:${itemId}:${method}:${JSON.stringify(state)}`);
            }
        }
    }

    async history(thread: unknown): Promise<void> {
        const turns = record(thread).turns;
        if (!Array.isArray(turns)) return;
        if (!this.parentThreadId && !this.excludedHistoryReasoning) {
            const excluded = this.hadHistory ? turns.flatMap(value => {
                const items = record(value).items;
                return Array.isArray(items) ? items.flatMap(item => {
                    const value = record(item);
                    return value.type === 'reasoning' && typeof value.id === 'string' ? [value.id] : [];
                }) : [];
            }) : [];
            this.excludedHistoryReasoning = new Set(excluded);
            this.session.updateMetadata(metadata => ({ ...metadata,
                codexReasoningReplayExcluded: metadata.codexReasoningReplayExcluded ?? excluded }));
        }
        const titleRevision = this.titleRevision;
        let latestTitle: string | undefined;
        let previousPosition = 0;
        for (const value of turns) {
            const turn = record(value);
            if (!Array.isArray(turn.items)) continue;
            const nativeBase = typeof turn.startedAt === 'number' ? turn.startedAt * 1000
                : typeof turn.id === 'string' && /^[a-f0-9]{8}-[a-f0-9]{4}-7/.test(turn.id)
                    ? parseInt(turn.id.replaceAll('-', '').slice(0, 12), 16) : undefined;
            const base = nativeBase === undefined ? undefined : Math.max(nativeBase, previousPosition);
            if (base !== undefined) {
                this.turnStarts.set(String(turn.id), base);
                previousPosition = base + turn.items.length * 2;
                this.lastPositions.set(String(turn.id), previousPosition);
            }
            this.itemCounts.set(String(turn.id), turn.items.length);
            let userIndex = 0;
            for (const [index, item] of turn.items.entries()) {
                if (!this.parentThreadId && record(item).type === 'reasoning'
                    && this.excludedHistoryReasoning?.has(String(record(item).id))) continue;
                const params = { threadId: this.threadId, turnId: turn.id, item,
                    ...(base === undefined ? {} : { projectionAt: base + index * 2 }),
                    ...(record(item).type === 'userMessage' ? { userIndex: userIndex++ } : {}) };
                const titleKey = `${string(turn.id) ?? 'thread'}:${record(item).id}`;
                const pendingTitle = requestedTitle(record(item));
                if (!this.parentThreadId && string(record(item).id) && pendingTitle && !this.completedTitles.has(titleKey)) {
                    this.pendingTitles.set(titleKey, pendingTitle);
                }
                await this.project('item/started', params);
                // Active snapshots can contain partial assistant text. Do not
                // settle it under the final stable id and suppress completion.
                if (turn.status !== 'inProgress' || record(item).status === 'completed' || record(item).type === 'userMessage'
                    || (record(item).type === 'agentMessage' && index < turn.items.length - 1)) {
                    if (!this.parentThreadId) {
                        const title = successfulTitle(record(item));
                        if (title && string(record(item).id)) {
                            latestTitle = title;
                            this.completedTitles.add(titleKey);
                        }
                        this.pendingTitles.delete(titleKey);
                    }
                    await this.project('item/completed', params);
                }
            }
        }
        // Repair sessions created while remote title projection was missing.
        // Recheck inside the metadata lock: live updates may still be queued,
        // and a replay must never replace an existing or newer display title.
        if (latestTitle && titleRevision === this.titleRevision && !metadataHasDisplayTitle(this.session.getMetadata())) {
            const title = latestTitle;
            this.session.updateMetadata(metadata => {
                if (titleRevision !== this.titleRevision || metadataHasDisplayTitle(metadata)) {
                    return metadata;
                }
                const normalized = normalizeSessionDisplayTitle(title);
                if (!normalized) return metadata;
                return { ...metadata, name: normalized };
            });
        }
    }
}
