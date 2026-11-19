import { describe, expect, it, vi } from 'vitest'
import { RPC_METHODS } from '@hapi/protocol'
import type { ApiSessionClient } from '@/api/apiSession'
import { NativeSessionBridge } from './run'
import type { NativeRuntime } from './types'

const { connect } = vi.hoisted(() => ({ connect: vi.fn() }))
vi.mock('./opencode', () => ({ OpencodeRuntime: { connect } }))

describe('shared native session configuration', () => {
    it('acknowledges applied fields in the Hub contract and propagates native rejection', async () => {
        const handlers = new Map<string, (raw: unknown) => Promise<unknown>>()
        let metadata = { opencodeSessionId: 'native-id', capabilities: { concurrentClients: true } }
        let state = {}
        const ready = vi.fn()
        const configure = vi.fn(async (_config: Record<string, unknown>) => {})
        const runtime = {
            runtime: 'service', bind: vi.fn(), close: vi.fn(), configure,
            view: async () => ({ messages: [], queue: [], requests: [], running: false, model: 'provider/model' }),
        } as unknown as NativeRuntime
        connect.mockResolvedValue(runtime)
        const session = {
            getMetadata: () => metadata,
            updateMetadata: (update: (value: typeof metadata) => typeof metadata) => { metadata = update(metadata) },
            getAgentState: () => state,
            updateAgentState: (update: (value: object) => object) => { state = update(state) },
            rpcHandlerManager: { registerHandler: (name: string, handler: (raw: unknown) => Promise<unknown>) => handlers.set(name, handler) },
            onUserMessage: vi.fn(), onRetryQueuedMessage: vi.fn(), onCancelQueuedMessage: vi.fn(), on: vi.fn(),
            flush: async () => true, syncNativeMessageBatch: async () => {}, keepAlive: vi.fn(),
            emitSessionReady: ready, sendSessionDeath: vi.fn(), close: async () => {},
        } as unknown as ApiSessionClient
        const bridge = new NativeSessionBridge(session, 'opencode', { resumeSessionId: 'native-id' })
        const running = bridge.run()
        try {
            await vi.waitFor(() => expect(ready).toHaveBeenCalled())
            const apply = handlers.get(RPC_METHODS.SetSessionConfig)!
            expect(await apply({ model: 'provider/new-model' })).toEqual({ applied: { model: 'provider/new-model' } })
            expect(configure).toHaveBeenLastCalledWith({ model: 'provider/new-model' })
            expect(await apply({ modelReasoningEffort: null })).toEqual({ applied: { modelReasoningEffort: null } })
            configure.mockRejectedValueOnce(new Error('Native model rejected'))
            await expect(apply({ model: 'provider/missing' })).rejects.toThrow('Native model rejected')
        } finally {
            await bridge.close()
            await running
        }
    })
})
