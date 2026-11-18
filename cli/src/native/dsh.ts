import { DshConnection, dshDescriptors, type DshDescriptor } from './dshConnection'
import { projectDshEvent, nativeBlocks, canonicalMessages, DshLiveBlocks, contentText } from './projection'
import { array, object, text, type NativeRuntime, type NativeView, type NativeRequest, type NativeReply } from './types'
import type { NativeSessionSummary } from '@hapi/protocol'
import type { AttachmentMetadata } from '@hapi/protocol'
import { readFile } from 'node:fs/promises'
import { formatMessageWithAttachments } from '@/utils/attachmentFormatter'

export class DshRuntime implements NativeRuntime {
    readonly flavor = 'dsh' as const
    get runtime(): string { return this.connection.descriptor.instanceId }
    get profile(): string { return this.connection.descriptor.profile }
    changed = () => {}
    disconnected = (_error: Error) => {}
    private id = ''
    private events = new Map<number, any>()
    private pending = new Map<string, any>()
    private cursor = 0
    private header: any = {}
    private projections: Record<string, any> = {}
    private projectionClocks = new Map<string, number>()
    private initial?: Promise<void>
    private stops: Array<() => void> = []
    private live?: { attempt: string; prefix: string; after: number; next: number; at: number; updatedAt: number; blocks: DshLiveBlocks }
    private constructor(readonly connection: DshConnection) {
        connection.onDisconnect = error => this.disconnected(error)
        connection.onEvent = event => {
            if (event.type === 'waterfall') {
                if (!this.id) { this.pending.set(event.eventId, event); return }
                if (event.agentId === this.id) this.pending.set(event.eventId, event)
                else void connection.reply(event.eventId, { kind: 'next' }).catch(() => {})
            } else if (event.type === 'cancel') this.pending.delete(event.eventId)
            this.changed()
        }
    }
    static async connectDescriptor(descriptor: DshDescriptor): Promise<DshRuntime> {
        const connection = new DshConnection(descriptor)
        const client = new DshRuntime(connection)
        try { await connection.open(); return client }
        catch (error) { connection.close(); throw error }
    }
    static async connect(runtime?: string, profile?: string, sessionId?: string): Promise<DshRuntime> {
        const descriptors = await dshDescriptors()
        const candidates = descriptors.filter(d => sessionId || !profile || d.profile === profile)
            .sort((a,b) => Number(b.instanceId === runtime) - Number(a.instanceId === runtime) || Number(b.profile === 'desktop') - Number(a.profile === 'desktop'))
        let last = 'No running DSH Host. Start Desktop/Web with the Host discovery plugin.'
        let fallback: DshRuntime | undefined
        for (const descriptor of candidates) {
            let client: DshRuntime | undefined
            try {
                client = await this.connectDescriptor(descriptor)
                if (!sessionId) return client
                const session = (await client.list()).find(s => s.id === sessionId)
                if (session?.active) { await fallback?.close(); const selected = client; client = undefined; return selected }
                if (session && !fallback && (!profile || profile === descriptor.profile)) { fallback = client; client = undefined }
            } catch (error) { last = error instanceof Error ? error.message : last }
            finally { if (client && sessionId) await client.close() }
        }
        if (fallback) return fallback
        throw new Error(last)
    }
    async list(): Promise<NativeSessionSummary[]> {
        const rows: any[] = [], seen = new Set<string>()
        let cursor: string | undefined
        do {
            const page = await this.connection.call('session/list', { _request: { ...(cursor ? { cursor } : {}) } })
            rows.push(...array(page.items)); cursor = page.nextCursor ?? page.cursor
            if (cursor && (typeof cursor !== 'string' || seen.has(cursor))) throw new Error('DSH catalog repeated a cursor')
            if (cursor) seen.add(cursor)
        } while (cursor)
        return rows.filter(s => s.cwd && !s.parentSessionId && s.origin !== 'subagent').map(s => ({
            id: s.sessionId, cwd: s.cwd, title: text(s.projections?.values?.title?.title ?? s.projections?.values?.title ?? s.title) || s.sessionId,
            modifiedAt: Number(s.updatedAt) || 0, runtime: this.runtime, profile: this.profile, active: s.agentAvailable === true,
        }))
    }
    async create(cwd: string): Promise<NativeSessionSummary> {
        const created = await this.connection.call('session/create', { request: { cwd } })
        return { id: created.sessionId, cwd, title: 'DeepSeek Harness', modifiedAt: Date.now(), runtime: this.runtime, profile: this.profile }
    }
    async bind(id: string): Promise<void> {
        this.id = id
        for (const [key,event] of this.pending) if (event.agentId !== id) { this.pending.delete(key); await this.connection.reply(key, { kind: 'next' }) }
        this.initial = new Promise<void>((resolve, reject) => {
            this.stops.push(this.connection.stream('session/follow', { request: { address: { kind: 'session', sessionId: id }, assistantStream: true, maxMessages: 100 } }, frame => {
                if (frame.type === 'snapshot') {
                    this.header = frame.header; this.cursor = frame.cursor
                    this.applyProjections(frame.projections?.values, frame.projections?.asOfSeq ?? frame.cursor)
                    for (const record of array(frame.records)) this.events.set(record.event.seq, record.event)
                    const attempt = frame.assistantStream?.activeAttempt
                    if (attempt) {
                        const blocks = new DshLiveBlocks(); blocks.seed(attempt.stream)
                        const times=array(attempt.stream).map(item=>Number(item.time??item.time0??0)+array(item.dt).reduce((a,b)=>a+Number(b),0))
                        const at=Number(this.events.get(attempt.startedAfterSeq)?.time)||Number(frame.header?.createdAt)||0
                        this.live = { attempt: attempt.attemptId, prefix: `assistant:${attempt.turn}:${attempt.step}`, after: attempt.startedAfterSeq, next: attempt.nextIndex, at, updatedAt:Math.max(at,...times), blocks }
                    }
                    void this.backfill(frame.hasMore).then(resolve,reject)
                } else if (frame.type === 'event') {
                    const event = frame.event
                    if (event.seq <= this.cursor) return
                    if (event.seq !== this.cursor + 1) { this.disconnected(new Error('DSH history sequence gap')); return }
                    this.cursor = event.seq; this.events.set(event.seq, event); this.changed()
                } else if (frame.type === 'assistant-stream') {
                    const value = frame.frame
                    if (value.type === 'start') {const at=Number(this.events.get(value.startedAfterSeq)?.time)||Number(this.header.createdAt)||0;this.live = { attempt: value.attemptId, prefix: `assistant:${value.turn}:${value.step}`, after: value.startedAfterSeq, next: 0, at, updatedAt:at, blocks: new DshLiveBlocks() }}
                    else if (this.live && this.live.attempt === value.attemptId) {
                        if (value.index !== this.live.next) { this.disconnected(new Error('DSH assistant sequence gap')); return }
                        if (value.type === 'chunk') { this.live.blocks.apply(value.chunk); this.live.updatedAt=value.time; this.live.next++ }
                        else this.live = undefined
                    }
                    this.changed()
                }
            }, error => { reject(error); this.disconnected(error) }))
        })
        this.stops.push(this.connection.stream('session/control', {}, frame => {
            if (frame.type === 'baseline') {
                const baseline = frame.value?.projections?.[this.id]
                this.applyProjections(baseline?.values, baseline?.asOfSeq ?? 0)
            } else if (frame.type === 'projection' && frame.sessionId === this.id) {
                this.applyProjections(Object.fromEntries(array(frame.changes).map(change => [change.key, change.value])), frame.seq)
            } else return
            this.changed()
        }))
        await this.initial
    }
    private applyProjections(values: unknown, at: number): void {
        for (const [key, value] of Object.entries(object(values))) {
            if (at < (this.projectionClocks.get(key) ?? -1)) continue
            this.projections[key] = value
            this.projectionClocks.set(key, at)
        }
    }
    private async backfill(hasMore: boolean): Promise<void> {
        while (hasMore) {
            const before = Math.min(...this.events.keys())
            const page = await this.connection.call('session/events', { request: { address: { kind: 'session', sessionId: this.id }, beforeSeq: before, throughSeq: this.cursor, maxMessages: 100 } })
            const records = array(page.records)
            if (!records.length || Math.min(...records.map(r => r.event.seq)) >= before) throw new Error('DSH history pagination made no progress')
            for (const record of records) this.events.set(record.event.seq, record.event)
            hasMore = page.hasMore === true
        }
    }
    async view(): Promise<NativeView> {
        const state = await this.connection.call('session/state', { request: { sessionId: this.id } })
        const ordered=[...this.events.values()].sort((a,b)=>a.seq-b.seq)
        const settled=this.live&&ordered.some(event=>event.type==='assistant/message'&&`assistant:${event.data?.turn}:${event.data?.step}`===this.live!.prefix)
        const live=this.live&&!settled?nativeBlocks(this.live.prefix,this.live.blocks.values(),Number(this.events.get(this.live.after)?.time)||this.live.at,true)
            .map(message=>({...message,updatedAt:this.live!.updatedAt})):[]
        const messages=[]
        let inserted=false
        for(const event of ordered){
            if(this.live&&!inserted&&event.seq>this.live.after){messages.push(...live);inserted=true}
            messages.push(...projectDshEvent(event,this.id))
        }
        if(!inserted)messages.push(...live)
        const requests: NativeRequest[] = [...this.pending.values()].filter(e=>e.agentId===this.id).map(e => ({
            id: e.eventId, tool: e.event === 'user-questions/request' ? 'request_user_input' : text(e.request.toolName) || 'Tool',
            toolCallId: e.request.callId ?? e.request.wait?.callId,
            arguments: e.event === 'user-questions/request' ? { questions: array(e.request.questions).map(q=>({ ...q, options: array(q.options) })) } : e.request,
        }))
        const queue = [...array(state.queue), ...array(state.pendingInput)].filter(item => !item.source?.kind || item.source.kind === 'user')
            .map(item => ({ id: text(item.id), localId: text(item.source?.rpcId).startsWith('hapi:') ? item.source.rpcId.slice(5) : undefined, text: contentText(item.content) })).filter(q=>q.id&&q.text)
        const title = [...this.events.values()].filter(e=>e.type==='session/title').sort((a,b)=>b.seq-a.seq)[0]?.data?.title
        const selection = object(this.projections.modelSelection?.pending ?? this.projections.modelSelection?.lastUsed ?? this.projections.modelSelection)
        return { messages:canonicalMessages(messages), queue, running: state.running === true, requests,
            title: text(this.projections.title?.title ?? this.projections.title ?? title) || undefined,
            ...(selection.provider && selection.model ? {model: `${selection.provider}/${selection.model}`, effort: selection.reasoningEffort ?? null} : {}) }
    }
    async submit(localId: string, content: string, delivery: 'queue' | 'steer', attachments: AttachmentMetadata[] = []): Promise<void> {
        const images=attachments.filter(file=>['image/png','image/jpeg','image/webp','image/gif'].includes(file.mimeType))
        const formatted=formatMessageWithAttachments(content,attachments.filter(file=>!images.includes(file)))
        const parts:object[]=formatted?[{type:'text',text:formatted}]:[]
        for(const image of images)parts.push({type:'image',mediaType:image.mimeType,name:image.filename,data:(await readFile(image.path)).toString('base64')})
        await this.connection.call('session/prompt', { request: { requestId: `hapi:${localId}`, sessionId: this.id, mode: delivery, content: parts } })
    }
    async cancel(): Promise<void> { await this.connection.call('session/cancel',{request:{sessionId:this.id}}) }
    async cancelQueued(id: string): Promise<void> { await this.connection.call('session/updateQueue',{request:{sessionId:this.id,itemId:id,action:{kind:'remove'}}}) }
    async steerQueued(id: string): Promise<void> { await this.connection.call('session/updateQueue',{request:{sessionId:this.id,itemId:id,action:{kind:'steer'}}}) }
    async reply(id: string, reply: NativeReply): Promise<void> {
        const event = this.pending.get(id)
        if (!event) throw new Error('Permission request not found or already resolved')
        const value = event.event === 'approval/request' ? reply.approved ? 'allowed-once' : 'rejected'
            : { answers: array(event.request.questions).map(q=>{
                const values=array(Array.isArray(reply.answers?.[q.id]) ? reply.answers?.[q.id] : object(reply.answers?.[q.id]).answers)
                const options=new Set(array(q.options).map(option=>option.label))
                const custom=values.filter(value=>!options.has(value)).join('\n')
                return { id:q.id,selected:values.filter(value=>options.has(value)),...(custom?{custom}:{}) }
            }) }
        await this.connection.reply(id, {kind:'result',value})
        this.pending.delete(id)
        this.changed()
    }
    async close(): Promise<void> { for(const stop of this.stops)stop();this.connection.close() }
}
