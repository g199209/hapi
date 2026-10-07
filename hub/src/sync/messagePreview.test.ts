import { describe, expect, it } from 'bun:test'
import { Store } from '../store'
import { extractMessagePreview, latestMessagePreview } from './messagePreview'
import { SessionCache } from './sessionCache'
import type { EventPublisher } from './eventPublisher'
import type { SyncEvent } from '@hapi/protocol/types'

const user = (text: string) => ({ role: 'user', content: { type: 'text', text } })
const agent = (text: string, type = 'message') => ({ role: 'agent', content: { type: 'codex', data: { type, message: text } } })
function fixture() {
    const store = new Store(':memory:'); const events: SyncEvent[] = []
    const publisher = { emit: (event: SyncEvent) => events.push(event) } as unknown as EventPublisher
    const cache = new SessionCache(store, publisher)
    const session = cache.getOrCreateSession('preview', { path: '/test', host: 'test', summary: { text: 'Original title', updatedAt: 1 } }, null, 'default')
    return { store, cache, events, id: session.id, publisher }
}

describe('latest conversation preview', () => {
    it('extracts user/assistant text but ignores reasoning, tools and session events', () => {
        expect(extractMessagePreview(user('Hi\nthere'))).toBe('Hi there')
        expect(extractMessagePreview(agent('**Done** with `my_code` and [docs](https://example.com)'))).toBe('Done with my_code and docs')
        expect(extractMessagePreview(agent('private thought', 'reasoning'))).toBeNull()
        expect(extractMessagePreview(agent('tool log', 'tool-call-result'))).toBeNull()
        expect(extractMessagePreview({ role: 'agent', content: { type: 'event', data: { type: 'message', message: 'ready' } } })).toBeNull()
        expect(extractMessagePreview({ role: 'agent', content: { type: 'output', data: { type: 'assistant', message: { content: [{ type: 'text', text: 'Claude reply' }, { type: 'tool_use', name: 'Read' }] } } } })).toBe('Claude reply')
    })

    it('handles attachments, blank text, markdown headings and Unicode limits', () => {
        expect(extractMessagePreview({ role: 'user', content: { type: 'text', text: '', attachments: [{ filename: 'photo.jpg' }] } })).toBe('photo.jpg')
        expect(extractMessagePreview(user(' \n '))).toBeNull()
        expect(extractMessagePreview(agent('# Heading\n\nBody'))).toBe('Heading Body')
        expect(Array.from(extractMessagePreview(user('🙂'.repeat(181)))!)).toHaveLength(180)
    })

    it('recovers older prose beyond a long tool tail and does not promote replayed old messages', () => {
        const f = fixture()
        f.store.messages.addMessage(f.id, agent('Latest answer'), 'new', null, 2000)
        for (let i = 0; i < 70; i++) f.store.messages.addMessage(f.id, agent('tool output', 'tool-call-result'), `tool-${i}`, null, 3000 + i)
        f.store.messages.addMessage(f.id, user('Old history'), 'old', null, 1000)
        expect(latestMessagePreview(f.store, f.id)).toBe('Latest answer')
        expect(new SessionCache(f.store, f.publisher).refreshSession(f.id)?.lastMessagePreview).toBe('Latest answer')
    })

    it('keeps name and stored summary unchanged and broadcasts only preview changes', () => {
        const f = fixture(); f.events.length = 0
        f.store.messages.addMessage(f.id, user('New question'))
        f.cache.refreshMessagePreview(f.id); f.cache.refreshMessagePreview(f.id)
        expect(f.events).toEqual([{ type: 'session-updated', sessionId: f.id, namespace: 'default', data: { lastMessagePreview: 'New question' } }])
        expect(f.store.sessions.getSession(f.id)?.metadata).toMatchObject({ summary: { text: 'Original title', updatedAt: 1 } })
        expect(f.cache.getSession(f.id)?.metadata?.name).toBeUndefined()
        f.store.messages.addMessage(f.id, agent('New response')); f.cache.refreshMessagePreview(f.id)
        expect(f.cache.getSession(f.id)?.lastMessagePreview).toBe('New response')
    })

    it('hides scheduled messages until invocation and refreshes appended imports', () => {
        const f = fixture()
        f.store.messages.addMessage(f.id, user('Visible'), 'visible', null, 1000)
        f.store.messages.addMessage(f.id, user('Scheduled'), 'scheduled', Date.now() + 100000, 2000)
        expect(latestMessagePreview(f.store, f.id)).toBe('Visible')
        f.store.messages.addImportedMessage(f.id, agent('Imported response'), 'import', 3000)
        expect(f.cache.refreshSession(f.id)?.lastMessagePreview).toBe('Imported response')
        f.store.messages.markMessagesInvoked(f.id, ['scheduled'], 4000)
        f.cache.refreshMessagePreview(f.id)
        expect(f.cache.getSession(f.id)?.lastMessagePreview).toBe('Scheduled')
    })

    it('falls back after cancellation and clears when no conversation text remains', () => {
        const f = fixture()
        const first = f.store.messages.addMessage(f.id, user('First'), 'first', null, 1000)
        const second = f.store.messages.addMessage(f.id, user('Second'), 'second', null, 2000)
        f.cache.refreshMessagePreview(f.id)
        expect(f.cache.getSession(f.id)?.lastMessagePreview).toBe('Second')
        f.store.messages.deleteQueuedMessageById(f.id, second.id); f.cache.refreshMessagePreview(f.id)
        expect(f.cache.getSession(f.id)?.lastMessagePreview).toBe('First')
        f.store.messages.deleteQueuedMessageById(f.id, first.id); f.cache.refreshMessagePreview(f.id)
        expect(f.cache.getSession(f.id)?.lastMessagePreview).toBe('')
    })
})
