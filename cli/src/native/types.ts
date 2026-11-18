import type { NativeMessage, NativeSessionSummary, SharedNativeFlavor, AttachmentMetadata } from '@hapi/protocol'
import { AGENT_MESSAGE_PAYLOAD_TYPE } from '@hapi/protocol'

export function object(value: unknown): Record<string, any> {
    return value && typeof value === 'object' && !Array.isArray(value) ? value as Record<string, any> : {}
}
export function array(value: unknown): any[] { return Array.isArray(value) ? value : [] }
export function text(value: unknown): string { return typeof value === 'string' ? value : '' }
export type NativeRequest = { id: string; tool: string; arguments: unknown; toolCallId?: string }
export type NativeQueueEntry = { id: string; localId?: string; text: string }
export type NativeView = {
    messages: NativeMessage[]; queue: NativeQueueEntry[]; running: boolean; compacting?: boolean;
    title?: string; model?: string | null; effort?: string | null; requests: NativeRequest[];
}
export type NativeReply = { approved: boolean; answers?: Record<string, string[] | { answers: string[] }>; reason?: string }
export interface NativeRuntime {
    readonly flavor: SharedNativeFlavor
    readonly runtime: string
    readonly profile?: string
    changed: () => void
    disconnected: (error: Error) => void
    list(): Promise<NativeSessionSummary[]>
    create(cwd: string, model?: string): Promise<NativeSessionSummary>
    bind(id: string): Promise<void>
    view(full?: boolean): Promise<NativeView>
    submit(localId: string, content: string, delivery: 'queue' | 'steer', attachments?: AttachmentMetadata[]): Promise<void>
    cancel(): Promise<void>
    cancelQueued(id: string): Promise<void>
    steerQueued(id: string): Promise<void>
    reply(id: string, reply: NativeReply): Promise<void>
    models?(): Promise<unknown>
    configure?(config: Record<string, unknown>): Promise<void>
    close(): Promise<void>
}

export function userContent(value: string) { return { role: 'user', content: { type: 'text', text: value }, meta: { sentFrom: 'cli' } } }
export function agentContent(value: unknown) { return { role: 'agent', content: { type: AGENT_MESSAGE_PAYLOAD_TYPE, data: value }, meta: { sentFrom: 'cli' } } }
