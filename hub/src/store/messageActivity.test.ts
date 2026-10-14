import { describe, expect, it, spyOn } from 'bun:test'
import { mkdtempSync, rmSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { Store } from './index'
const user = (text: string) => ({ role: 'user', content: { type: 'text', text } })
const ready = { role: 'agent', content: { type: 'event', data: { type: 'ready' } } }

describe('persisted conversation unread clock', () => {
    it('survives changed PID/version/title/state and replay, then advances for real messages', () => {
        const store = new Store(':memory:'); const clock = spyOn(Date, 'now').mockReturnValue(1000)
        try {
            const s = store.sessions.getOrCreateSession('clock', { path: '/tmp', host: 'test', hostPid: 1, version: 'old' }, null, 'default')
            store.messages.addMessage(s.id, user('Original'), 'user')
            const seen = store.sessions.getSession(s.id)!.lastMessageAt!
            clock.mockReturnValue(5000)
            store.sessions.updateSessionMetadata(s.id, { path: '/tmp', host: 'test', hostPid: 2, version: 'new', name: 'Hydrated native title' }, s.metadataVersion, 'default')
            store.sessions.updateSessionAgentState(s.id, { steeringActive: false, codexPlanProposalId: null, requests: {}, completedRequests: {} }, s.agentStateVersion, 'default')
            store.messages.addMessage(s.id, ready, 'ready')
            store.messages.addProjectedMessage(s.id, user('Original'), 'user', 1000)
            expect(store.sessions.getSession(s.id)?.updatedAt).toBe(5000)
            expect(store.sessions.getSession(s.id)?.lastMessageAt).toBe(seen)
            store.messages.addMessage(s.id, { role: 'agent', content: { type: 'codex', data: { type: 'message', message: 'Real new reply' } } }, 'reply')
            expect(store.sessions.getSession(s.id)?.lastMessageAt).toBeGreaterThan(seen)
            const replyAt = store.sessions.getSession(s.id)!.lastMessageAt!
            store.messages.addMessage(s.id, user('Another real message'), 'another')
            expect(store.sessions.getSession(s.id)?.lastMessageAt).toBeGreaterThan(replyAt)
            const now = store.sessions.getSession(s.id)!.lastMessageAt!
            store.messages.addMessage(s.id, user('Future'), 'future', 7000)
            expect(store.sessions.getSession(s.id)?.lastMessageAt).toBe(now)
            clock.mockReturnValue(8000); store.messages.markMessagesInvoked(s.id, ['future'], 8000)
            expect(store.sessions.getSession(s.id)?.lastMessageAt).toBe(8000)
            clock.mockReturnValue(9000); store.messages.markMessagesInvoked(s.id, ['future'], 9000)
            expect(store.sessions.getSession(s.id)?.lastMessageAt).toBe(8000)
        } finally { clock.mockRestore(); store.close() }
    })
    it('backfills compressed history from message time, excluding ready and uninvoked schedules', () => {
        const dir = mkdtempSync(join(tmpdir(), 'hapi-clock-')); const path = join(dir, 'test.db')
        const clock = spyOn(Date, 'now').mockReturnValue(1000)
        let store = new Store(path)
        try {
            const s = store.sessions.getOrCreateSession('migration', { path: '/tmp', host: 'test' }, null, 'default')
            store.messages.addMessage(s.id, user('History '.repeat(1000)), 'history')
            clock.mockReturnValue(5000); store.messages.addMessage(s.id, ready, 'ready')
            store.messages.addMessage(s.id, user('Future'), 'future', 9000)
            store.sessions.updateSessionMetadata(s.id, { path: '/tmp', host: 'test', hostPid: 999 }, s.metadataVersion, 'default')
            const db = (store as unknown as { db: import('bun:sqlite').Database }).db
            db.exec('ALTER TABLE sessions DROP COLUMN last_message_at; PRAGMA user_version = 26')
            store.close(); store = new Store(path)
            expect(store.sessions.getSession(s.id)?.lastMessageAt).toBe(1000)
            expect(store.sessions.getSession(s.id)?.updatedAt).toBe(5000)
            store.close(); store = new Store(path)
            expect(store.sessions.getSession(s.id)?.lastMessageAt).toBe(1000)
        } finally { clock.mockRestore(); store.close(); rmSync(dir, { recursive: true, force: true }) }
    })
})
