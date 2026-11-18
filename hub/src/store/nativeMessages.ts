import type { Database } from 'bun:sqlite'
import { randomUUID, createHash } from 'node:crypto'
import { isDeepStrictEqual } from 'node:util'
import type { NativeMessageBatch } from '@hapi/protocol'
import { bumpMessageEpoch, getNewestMessagePosition, toStoredMessage } from './messages'
import { decodeMessageContent, encodeMessageContent, truncateOversizedMessageContent } from './contentCodec'
import { recordMessageActivity } from './messageActivity'
import type { StoredMessage } from './types'

type DbRow = Parameters<typeof toStoredMessage>[0]

export function createNativeMessageSchema(db: Database): void {
    db.exec(`CREATE TABLE IF NOT EXISTS native_message_bindings (
        session_id TEXT NOT NULL REFERENCES sessions(id) ON DELETE CASCADE,
        source TEXT NOT NULL, native_id TEXT NOT NULL,
        message_id TEXT NOT NULL REFERENCES messages(id) ON DELETE CASCADE,
        content_hash TEXT NOT NULL DEFAULT '',
        PRIMARY KEY (session_id, source, native_id)
    )`)
}

/** Stable native bindings preserve optimistic input, mutable stream identity,
 * and canonical ordering. Native timestamps own activity across reconnects. */
export function applyNativeMessageBatch(db: Database, batch: NativeMessageBatch): {
    changed: StoredMessage[]; consumed: string[]; consumedPositions: Record<string, number>; structural: boolean
} {
    return db.transaction(() => {
        const changed: StoredMessage[] = [], consumed: string[] = []
        const consumedPositions: Record<string, number> = {}
        let structural = false
        const find = db.query(`SELECT m.* FROM native_message_bindings b JOIN messages m ON m.id=b.message_id
            WHERE b.session_id=? AND b.source=? AND b.native_id=?`)
        const getRow = db.query('SELECT * FROM messages WHERE id=?')
        for (const id of batch.removed) {
            const row = find.get(batch.sid, batch.source, id) as DbRow | null
            if (row) {
                db.query('DELETE FROM messages WHERE id=?').run(row.id)
                structural = true
            }
        }
        let head = getNewestMessagePosition(db, batch.sid)?.at ?? 0
        for (const entry of batch.messages) {
            const hash = createHash('sha256').update(JSON.stringify(entry)).digest('hex')
            const binding = db.query('SELECT content_hash FROM native_message_bindings WHERE session_id=? AND source=? AND native_id=?')
                .get(batch.sid, batch.source, entry.id) as {content_hash:string}|null
            if (binding?.content_hash === hash) continue
            const alias = entry.inputLocalId
            let row = find.get(batch.sid, batch.source, entry.id) as DbRow | null
            if (!row) {
                row = db.query('SELECT * FROM messages WHERE session_id=? AND local_id=?')
                    .get(batch.sid, alias ?? `${batch.source}:${entry.id}`) as DbRow | null
                if (row && alias && (decodeMessageContent(row.content) as { role?: string })?.role !== 'user') {
                    throw new Error('Native input alias must reference a user message')
                }
            }
            let content = truncateOversizedMessageContent(entry.content)
            if (row && alias) {
                const prior = decodeMessageContent(row.content) as { content?: object; meta?: object }
                const incoming = content as { content?: object; meta?: object }
                content = { ...prior, ...incoming,
                    content: { ...prior.content, ...incoming.content },
                    meta: { ...incoming.meta, ...prior.meta } }
                const attachmentBody = prior.content as {attachments?:unknown[];text?:string}|undefined
                if(attachmentBody?.attachments?.length){
                    (content as {content:{text?:string}}).content.text=attachmentBody.text
                }
            }
            let updated = false
            if (!row) {
                const id = randomUUID()
                const nextSeq = (db.query('SELECT COALESCE(MAX(seq),0)+1 AS seq FROM messages WHERE session_id=?')
                    .get(batch.sid) as { seq: number }).seq
                db.query(`INSERT INTO messages(id,session_id,content,created_at,seq,local_id,invoked_at,scheduled_at,delivery_state)
                    VALUES(?,?,?,?,?,?,?,NULL,'queued')`)
                    .run(id, batch.sid, encodeMessageContent(content), entry.createdAt ?? entry.at, nextSeq,
                        alias ?? `${batch.source}:${entry.id}`, entry.at)
                row = getRow.get(id) as DbRow
                structural ||= entry.at < head
                head = Math.max(head, entry.at)
                updated = true
            } else if (row.invoked_at !== entry.at || !isDeepStrictEqual(decodeMessageContent(row.content), content)) {
                structural ||= row.invoked_at !== entry.at
                db.query(`UPDATE messages SET content=?,invoked_at=?,scheduled_at=NULL,delivery_state='queued' WHERE id=?`)
                    .run(encodeMessageContent(content), entry.at, row.id)
                row = getRow.get(row.id) as DbRow
                updated = true
            }
            if (updated) {
                changed.push(toStoredMessage(row))
                recordMessageActivity(db, batch.sid, content, entry.updatedAt ?? entry.createdAt ?? entry.at, true)
            }
            db.query(`INSERT INTO native_message_bindings(session_id,source,native_id,message_id,content_hash) VALUES(?,?,?,?,?)
                ON CONFLICT(session_id,source,native_id) DO UPDATE SET message_id=excluded.message_id,content_hash=excluded.content_hash`)
                .run(batch.sid, batch.source, entry.id, row.id, hash)
            if (alias && updated) { consumed.push(alias); consumedPositions[alias] = entry.at }
        }
        if (batch.reconcile) {
            const retained = new Set(batch.reconcile)
            const bindings = db.query('SELECT native_id,message_id FROM native_message_bindings WHERE session_id=? AND source=?')
                .all(batch.sid, batch.source) as Array<{native_id: string; message_id: string}>
            for (const binding of bindings) if (!retained.has(binding.native_id)) {
                db.query('DELETE FROM messages WHERE id=?').run(binding.message_id)
                structural = true
            }
        }
        if (structural) bumpMessageEpoch(db, batch.sid)
        return { changed, consumed, consumedPositions, structural }
    })()
}
