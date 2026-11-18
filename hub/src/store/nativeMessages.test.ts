import { describe, expect, it } from 'bun:test'
import { Store } from './index'
import { AGENT_MESSAGE_PAYLOAD_TYPE, type NativeMessageBatch } from '@hapi/protocol'

function setup() {
    const store = new Store(':memory:')
    const session = store.sessions.getOrCreateSession('native-test', { path: '/tmp/native' }, null, 'default')
    const batch = (messages: NativeMessageBatch['messages'], removed: string[] = [], source = 'opencode:thread') =>
        store.messages.applyNativeMessageBatch({ sid: session.id, source, messages, removed })
    const clock = () => store.sessions.getSession(session.id)!.lastMessageAt
    return { store, session, batch, clock }
}
const user = (text: string) => ({ role: 'user', content: { type: 'text', text } })
const agent = (text: string) => ({ role: 'agent', content: { type: AGENT_MESSAGE_PAYLOAD_TYPE, data: { type: 'message', message: text, id: 'answer' } } })

describe('native source projection', () => {
    it('uses native activity time and replays without changing rows, epoch or unread clock', () => {
        const f = setup()
        const entries = [{ id: 'user', at: 100, content: user('hello') }, { id: 'agent', at: 200, content: agent('reply') }]
        f.batch(entries)
        const before = f.store.messages.getAllMessages(f.session.id)
        const epoch = f.store.messages.getMessageEpoch(f.session.id)
        expect(f.clock()).toBe(200)
        expect(f.batch(entries)).toMatchObject({ changed: [], consumed: [], structural: false })
        expect(f.store.messages.getAllMessages(f.session.id)).toEqual(before)
        expect(f.store.messages.getMessageEpoch(f.session.id)).toBe(epoch)
        expect(f.clock()).toBe(200)
    })

    it('updates an assistant snapshot in place without structurally invalidating the window', () => {
        const f = setup()
        const first = f.batch([{ id: 'assistant', at: 100, content: agent('par') }]).changed[0]
        const result = f.batch([{ id: 'assistant', at: 100, updatedAt: 250, content: agent('partial complete') }])
        expect(result.changed[0].id).toBe(first.id)
        expect(result.changed[0].seq).toBe(first.seq)
        expect(result.structural).toBe(false)
        expect(f.store.messages.getAllMessages(f.session.id)).toHaveLength(1)
        expect(f.clock()).toBe(250)
    })

    it('reconciles native acknowledgement with the optimistic input and preserves attachments', () => {
        const f = setup()
        const original = f.store.messages.addMessage(f.session.id, {
            ...user('hello'), content: { type: 'text', text: 'hello', attachments: [{ name: 'image.png' }] }, meta: { sentFrom: 'web' },
        }, 'local-input')
        const result = f.batch([{ id: 'native-input', at: 100, inputLocalId: 'local-input', content: user('hello') }])
        expect(result.consumed).toEqual(['local-input'])
        expect(result.changed[0]).toMatchObject({ id: original.id, localId: 'local-input', invokedAt: 100,
            content: { content: { attachments: [{ name: 'image.png' }] }, meta: { sentFrom: 'web' } } })
        expect(f.store.messages.getAllMessages(f.session.id)).toHaveLength(1)
        expect(f.batch([{ id: 'native-input', at: 100, inputLocalId: 'local-input', content: user('hello') }]).changed).toEqual([])
    })

    it('invalidates on canonical reordering and deletes only the selected source bindings', () => {
        const f = setup()
        f.batch([{ id: 'first', at: 100, content: agent('first') }, { id: 'last', at: 300, content: agent('last') }])
        f.batch([{ id: 'other', at: 200, content: agent('other') }], [], 'dsh:other')
        const epoch = f.store.messages.getMessageEpoch(f.session.id)
        expect(f.batch([{ id: 'first', at: 250, content: agent('first') }]).structural).toBe(true)
        expect(f.store.messages.getMessageEpoch(f.session.id)).toBeGreaterThan(epoch)
        f.batch([], ['other'])
        expect(f.store.messages.getAllMessages(f.session.id)).toHaveLength(3)
        f.batch([], ['first'])
        expect(f.store.messages.getAllMessages(f.session.id)).toHaveLength(2)
    })

    it('rolls back a whole batch when an input aliases an agent message', () => {
        const f = setup()
        f.store.messages.addMessage(f.session.id, agent('private'), 'not-a-user')
        expect(() => f.batch([
            { id: 'one', at: 100, content: user('new') },
            { id: 'two', at: 200, inputLocalId: 'not-a-user', content: user('invalid') },
        ])).toThrow('user message')
        expect(f.store.messages.getAllMessages(f.session.id)).toHaveLength(1)
    })

    it('withdraws a stale live message only after its source baseline completes', () => {
        const f=setup()
        f.batch([{id:'live',at:100,content:agent('unfinished')}])
        f.batch([{id:'other',at:150,content:agent('other source')}],[],'dsh:other')
        f.store.messages.applyNativeMessageBatch({sid:f.session.id,source:'opencode:thread',
            messages:[{id:'final',at:200,content:agent('completed')}],removed:[],reconcile:['final']})
        expect(f.store.messages.getAllMessages(f.session.id).map(m=>m.localId)).toEqual(['dsh:other:other','opencode:thread:final'])
    })
})
