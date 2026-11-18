import WebSocket from 'ws'
import { readFile, readdir } from 'node:fs/promises'
import { homedir } from 'node:os'
import { join } from 'node:path'
import { z } from 'zod'

const HttpUrl = z.string().url().refine(s => ['http:', 'https:'].includes(new URL(s).protocol))
export const DshDescriptorSchema = z.object({
    schemaVersion: z.literal(1), instanceId: z.string().min(1), pid: z.number().int().positive(),
    dshVersion: z.string().min(1), profile: z.string().min(1), dshHome: z.string().min(1), baseUrl: HttpUrl,
    auth: z.object({ type: z.literal('dsh-launch-token-exchange'), connectUrl: HttpUrl }),
    endpoints: z.object({ apiBaseUrl: HttpUrl.optional(), muxUrl: HttpUrl.optional(), eventsStream: z.string().optional(), interactionReplyEndpoint: z.string().optional() }).optional(),
    publishedAt: z.string(),
})
export type DshDescriptor = z.infer<typeof DshDescriptorSchema>

export async function dshDescriptors(): Promise<DshDescriptor[]> {
    const directory = process.env.HAPI_DSH_REGISTRY_DIR ?? join(process.env.DSH_HOME ?? join(homedir(), '.dsh'), 'run', 'hosts')
    const files = await readdir(directory).catch((error: NodeJS.ErrnoException) => { if (error.code === 'ENOENT') return []; throw error })
    const descriptors: DshDescriptor[] = []
    for (const file of files.filter(f => f.endsWith('.json') && !f.startsWith('.'))) {
        try { descriptors.push(DshDescriptorSchema.parse(JSON.parse(await readFile(join(directory, file), 'utf8')))) } catch { /* Withdrawn/foreign descriptors are skipped. */ }
    }
    return descriptors.sort((a, b) => Date.parse(b.publishedAt) - Date.parse(a.publishedAt))
}

function checkAuthority(base: string, target: string): void {
    const origin = new URL(base)
    if (!['127.0.0.1', 'localhost', '[::1]'].includes(origin.hostname) || new URL(target).origin !== origin.origin) throw new Error('DSH descriptor must address one local Host')
}

/** An API Peer owns exactly one socket; closing this client never closes a Host. */
export class DshConnection {
    private cookie = ''
    private socket?: WebSocket
    private serial = 0
    private streams = new Map<string, { item: (value: any) => void; error: (error: Error) => void }>()
    private closing = false
    private abort = new AbortController()
    clientId = ''
    onEvent: (value: any) => void = () => {}
    onDisconnect: (error: Error) => void = () => {}
    constructor(readonly descriptor: DshDescriptor) {}

    async open(): Promise<void> {
        const d = this.descriptor
        checkAuthority(d.baseUrl, d.auth.connectUrl)
        checkAuthority(d.baseUrl, d.endpoints?.apiBaseUrl ?? `${d.baseUrl}/api`)
        const url = new URL(d.auth.connectUrl)
        if (url.searchParams.has('interactions')) throw new Error('DSH descriptor already declares interactions')
        url.searchParams.set('interactions', 'answer')
        const response = await fetch(url, { redirect: 'manual', signal: AbortSignal.timeout(10_000) })
        if (response.status !== 302) throw new Error(`DSH Host exchange HTTP ${response.status}`)
        this.cookie = response.headers.getSetCookie().map(v => v.split(';')[0]).join('; ')
        const location = response.headers.get('location')
        if (!this.cookie || !location) throw new Error('DSH Host exchange did not return a Peer')
        const destination = new URL(location, url)
        checkAuthority(d.baseUrl, destination.href)
        destination.protocol = destination.protocol === 'https:' ? 'wss:' : 'ws:'
        const socket = new WebSocket(destination, { headers: { Cookie: this.cookie }, handshakeTimeout: 10_000 })
        this.socket = socket
        socket.on('message', bytes => {
            try {
                const frame = JSON.parse(bytes.toString())
                const stream = this.streams.get(frame.streamId)
                if (frame.type === 'item') stream?.item(frame.value)
                else if (frame.type === 'error') { this.streams.delete(frame.streamId); stream?.error(new Error(`${frame.error?.code}: ${frame.error?.message}`)) }
                else if (frame.type === 'end') { this.streams.delete(frame.streamId); stream?.error(new Error('DSH observation stream ended')) }
            } catch { this.fail(new Error('Invalid DSH stream frame')) }
        })
        socket.on('close', () => this.fail(new Error('DSH Host disconnected')))
        socket.on('error', () => this.fail(new Error('DSH Host connection failed')))
        await new Promise<void>((resolve, reject) => { socket.once('open', resolve); socket.once('error', reject) })
        await new Promise<void>((resolve, reject) => {
            const timer = setTimeout(() => reject(new Error('DSH Host readiness timed out')), 15_000)
            this.stream(d.endpoints?.eventsStream ?? '$events', {}, value => {
                if (value.type === 'ready') { this.clientId = value.clientId; clearTimeout(timer); resolve() }
                else this.onEvent(value)
            }, error => { clearTimeout(timer); reject(error); this.onDisconnect(error) })
        })
    }
    stream(endpoint: string, args: object, item: (value: any) => void, error = this.onDisconnect): () => void {
        const id = `hapi-${++this.serial}`
        this.streams.set(id, { item, error })
        this.socket!.send(JSON.stringify({ type: 'open', streamId: id, endpoint, payload: { args } }))
        return () => { if (this.socket?.readyState === WebSocket.OPEN) this.socket.send(JSON.stringify({ type: 'cancel', streamId: id })); this.streams.delete(id) }
    }
    async call(endpoint: string, args: object = {}): Promise<any> {
        const rpcId = `hapi-${++this.serial}`
        const response = await fetch(`${this.descriptor.endpoints?.apiBaseUrl ?? `${this.descriptor.baseUrl}/api`}/${endpoint}`, {
            method: 'POST', headers: { 'content-type': 'application/json', Cookie: this.cookie }, signal: AbortSignal.any([this.abort.signal, AbortSignal.timeout(30_000)]),
            body: JSON.stringify({ type: 'client-request', rpcId, method: endpoint, payload: { args } }),
        })
        if (!response.ok) throw new Error(`DSH ${endpoint} HTTP ${response.status}`)
        const body = await response.json() as any
        if (body.rpcId !== rpcId || body.result?.ok !== true) throw new Error(`DSH ${endpoint}: ${body.result?.error?.code ?? 'invalid_response'}: ${body.result?.error?.message ?? ''}`)
        return body.result.value
    }
    reply(eventId: string, outcome: unknown): Promise<unknown> {
        return this.call(this.descriptor.endpoints?.interactionReplyEndpoint ?? '$events/result', { clientId: this.clientId, eventId, outcome })
    }
    private fail(error: Error): void {
        if (this.closing) return
        this.closing = true
        this.abort.abort()
        this.cookie = ''
        for (const stream of this.streams.values()) stream.error(error)
        this.streams.clear()
        this.socket?.terminate()
        this.onDisconnect(error)
    }
    close(): void { this.closing = true; this.abort.abort(); this.cookie = ''; this.streams.clear(); this.socket?.close(); setTimeout(() => this.socket?.terminate(), 1000).unref() }
}
