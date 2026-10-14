import type { Database } from 'bun:sqlite'
import { hasConversationMessageContent } from '@hapi/protocol/messages'

/** The unread clock belongs to conversation messages, never runtime metadata. */
export function recordMessageActivity(db: Database, sessionId: string, content: unknown, at: number, historical = false): void {
    if (!hasConversationMessageContent(content)) return
    db.prepare(`UPDATE sessions SET last_message_at = MAX(last_message_at ${historical ? '' : '+ 1'}, @at)
        WHERE id = @id`).run({ id: sessionId, at })
}
