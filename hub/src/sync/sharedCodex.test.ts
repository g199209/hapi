import { describe, expect, it } from 'bun:test'
import type { Metadata } from '@hapi/protocol/types'
import { Store } from '../store'
import { RpcRegistry } from '../socket/rpcRegistry'
import { SyncEngine } from './syncEngine'
import type { RpcGateway } from './rpcGateway'

function fixture() {
    const store = new Store(':memory:')
    const engine = new SyncEngine(store, {} as never, new RpcRegistry(), { broadcast() {} } as never)
    const metadata: Metadata = { path: '/tmp/work', host: 'test', machineId: 'machine', hostPid: 42, flavor: 'codex',
        capabilities: { concurrentClients: true, conversationHistory: { forkCurrent: true, forkAtMessage: true } } }
    const create = (name: string, more: Partial<Metadata> = {}) => {
        const session = engine.getOrCreateSession(name, { ...metadata, codexSessionId: `native-${name}`, ...more }, { controlledByUser: false }, 'default')
        engine.handleSessionAlive({ sid: session.id, time: Date.now() })
        engine.handleSessionReady({ sid: session.id, time: Date.now() })
        return session
    }
    return { store, engine, create, rpc: (engine as unknown as { rpcGateway: RpcGateway }).rpcGateway }
}

describe('shared Codex hub binding', () => {
    it('guards plan actions by namespace and runtime, leaving stale-plan and retry validation to CLI', async () => {
        const { engine, create, rpc } = fixture()
        try {
            const source = create('source')
            const unsupported = create('other', { flavor: 'claude' })
            const calls: string[][] = []
            rpc.implementCodexPlan = async (...args) => { calls.push(args); return { ok: true } }
            expect(await engine.implementCodexPlan(source.id, 'other-namespace', 'plan')).toMatchObject({ ok: false })
            expect(await engine.implementCodexPlan(unsupported.id, 'default', 'plan')).toMatchObject({ ok: false })
            expect(calls).toHaveLength(0)
            // The hub can lag the native plan state, including after an accepted action's lost reply.
            expect(await engine.implementCodexPlan(source.id, 'default', 'plan')).toEqual({ ok: true })
            expect(calls).toEqual([[source.id, 'plan']])
        } finally { engine.stop() }
    })

    it('uses the already-bound fork child without spawning a second engine', async () => {
        const { engine, create, rpc } = fixture()
        try {
            const source = create('source'); const child = create('child', { forkedFrom: source.id })
            rpc.forkConversation = async () => ({ nativeSessionId: 'native-child', sessionId: child.id })
            expect(await engine.forkConversation(source.id, 'default')).toEqual({ type: 'success', sessionId: child.id })
            expect(engine.getSession(source.id)?.metadata?.codexSessionId).toBe('native-source')
        } finally { engine.stop() }
    })
    it('clear returns a new root without superseding other clients and mode switching is inapplicable', async () => {
        const { engine, create, rpc } = fixture()
        try {
            const source = create('source'); const child = create('child')
            rpc.clearConversation = async () => ({ sessionId: child.id })
            expect(await engine.clearConversation(source.id, 'default')).toEqual({ sessionId: child.id })
            expect(engine.getSession(source.id)?.metadata?.supersededBySessionId).toBeUndefined()
            await expect(engine.switchSession(source.id, 'remote')).rejects.toThrow('control_mode_not_applicable')
            await expect(engine.clearConversation(source.id, 'other-namespace')).rejects.toThrow()
        } finally { engine.stop() }
    })
})


it('renames active shared Codex through its native bridge and inactive through its machine', async () => {
    const { store, engine, create, rpc } = fixture()
    try {
        const session = create('rename')
        const calls: unknown[][] = []
        rpc.renameCodexSession = async (...args) => {
            calls.push(args)
            if (!args[2]) {
                const row = store.sessions.getSession(session.id)!
                store.sessions.updateSessionMetadata(session.id, { ...row.metadata as object, name: 'Native normalized' }, row.metadataVersion, 'default', { touchUpdatedAt: false })
            }
            return 'Native normalized'
        }
        await engine.renameSession(session.id, 'Requested')
        expect(engine.getSession(session.id)?.metadata?.name).toBe('Native normalized')
        expect(calls[0]).toEqual([session.id, 'Requested'])
        engine.handleSessionEnd({ sid: session.id, time: Date.now(), reason: 'terminated' })
        await engine.renameSession(session.id, 'Offline request')
        expect(calls[1]).toEqual([session.id, 'Offline request', { machineId: 'machine', nativeId: 'native-rename' }])
        expect(engine.getSession(session.id)?.metadata?.name).toBe('Native normalized')
        rpc.renameCodexSession = async () => { throw new Error('native unavailable') }
        await expect(engine.renameSession(session.id, 'Unconfirmed')).rejects.toThrow('native unavailable')
        expect(engine.getSession(session.id)?.metadata?.name).toBe('Native normalized')
        const other = create('claude', { flavor: 'claude' })
        await engine.renameSession(other.id, 'Claude local title')
        expect(engine.getSession(other.id)?.metadata?.name).toBe('Claude local title')
    } finally { engine.stop() }
})


it('preserves archive state instead of unarchiving a native thread just to rename it', async () => {
    const { store, engine, create, rpc } = fixture()
    try {
        const session = create('archived-title', { lifecycleState: 'archived' })
        let called = false
        rpc.renameCodexSession = async () => { called = true; return 'Unexpected' }
        await expect(engine.renameSession(session.id, 'New title')).rejects.toThrow('Reopen this Codex session')
        expect(called).toBe(false)
        expect(store.sessions.getSession(session.id)?.metadata).toMatchObject({ lifecycleState: 'archived' })
    } finally { engine.stop() }
})
