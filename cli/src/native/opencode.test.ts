import { createServer } from 'node:http'
import { mkdtemp, writeFile, rm } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { describe, expect, it } from 'vitest'
import { OpencodeRuntime } from './opencode'

describe('native OpenCode settings', () => {
    it('sets provider-qualified models, clears explicit effort and rejects unsupported changes', async () => {
        const received: unknown[] = []
        const server = createServer(async (req, res) => {
            if (req.url === '/api/event') {
                res.writeHead(200, { 'content-type': 'text/event-stream' })
                res.write(': ready\n\n')
                return
            }
            res.setHeader('content-type', 'application/json')
            if (req.method === 'POST') {
                let body = ''
                for await (const chunk of req) body += chunk
                received.push(JSON.parse(body))
                res.end(JSON.stringify({ data: {} }))
            } else res.end(JSON.stringify({ data: { location: { directory: tmpdir() }, model: { providerID: 'provider', id: 'old', variant: 'high' } } }))
        })
        await new Promise<void>(resolve => server.listen(0, '127.0.0.1', resolve))
        const port = (server.address() as { port: number }).port
        const directory = await mkdtemp(join(tmpdir(), 'hapi-opencode-model-'))
        const descriptor = join(directory, 'service.json')
        await writeFile(descriptor, JSON.stringify({ id: 'service', url: `http://127.0.0.1:${port}`, password: 'test-only', version: '2.3.2' }))
        const previous = process.env.HAPI_OPENCODE_SERVICE_FILE
        process.env.HAPI_OPENCODE_SERVICE_FILE = descriptor
        let runtime: OpencodeRuntime | undefined
        try {
            runtime = await OpencodeRuntime.connect()
            await runtime.bind('session')
            await runtime.configure({ model: 'other/new-model' })
            expect(received).toEqual([{ model: { providerID: 'other', id: 'new-model' } }])
            await runtime.configure({ modelReasoningEffort: null })
            expect(received[1]).toEqual({ model: { providerID: 'provider', id: 'old' } })
            await expect(runtime.configure({ model: null })).rejects.toThrow('explicit OpenCode provider/model')
            await expect(runtime.configure({ effort: 'high' })).rejects.toThrow('does not support setting effort')
            expect(received).toHaveLength(2)
        } finally {
            await runtime?.close()
            if (previous === undefined) delete process.env.HAPI_OPENCODE_SERVICE_FILE
            else process.env.HAPI_OPENCODE_SERVICE_FILE = previous
            server.closeAllConnections()
            await new Promise<void>(resolve => server.close(() => resolve()))
            await rm(directory, { recursive: true, force: true })
        }
    })
})
