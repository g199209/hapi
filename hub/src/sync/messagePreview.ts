import { extractAssistantPlainText, stripNotifySummaryFooter, unwrapRoleWrappedRecordEnvelope } from '@hapi/protocol/messages'
import { isObject } from '@hapi/protocol'
import type { Store } from '../store'

/** Ordinary conversation text only; summaries/titles and tool traffic are not previews. */
export function extractMessagePreview(content: unknown): string | null {
    const envelope = unwrapRoleWrappedRecordEnvelope(content)
    if (!envelope || !isObject(envelope.content)) return null
    let text: string | null = null
    if (envelope.role === 'user' && envelope.content.type === 'text') {
        text = typeof envelope.content.text === 'string' ? envelope.content.text : null
        if (!text?.trim() && Array.isArray(envelope.content.attachments)) {
            text = envelope.content.attachments.flatMap(a => isObject(a) && typeof a.filename === 'string' ? [a.filename] : []).join(', ')
        }
    } else if (envelope.role === 'agent') {
        text = extractAssistantPlainText(envelope.content)
    }
    if (!text?.trim() || text.trim() === 'No response requested.') return null
    text = stripNotifySummaryFooter(text)
    const plain = text.replace(/!?(\[([^\]]+)\])\([^\n)]+\)/g, '$2')
        .replace(/(\*\*|__|`)(.+?)\1/g, '$2')
        .replace(/^\s{0,3}#{1,6}\s+/gm, '')
        .replace(/\s+/g, ' ').trim()
    if (!plain) return null
    const chars = Array.from(plain)
    return chars.length > 180 ? `${chars.slice(0, 179).join('')}…` : plain
}

/** Walk the indexed display order, including beyond a tail full of tool/thought events. */
export function latestMessagePreview(store: Store, sessionId: string): string {
    let before: { at: number; seq: number } | undefined
    for (;;) {
        const page = store.messages.getMessagesByPosition(sessionId, 32, before)
        for (let i = page.length - 1; i >= 0; i--) {
            const message = page[i]
            // A scheduled message becomes conversation content only when invoked.
            if (message.scheduledAt !== null && message.invokedAt === null) continue
            const preview = extractMessagePreview(message.content)
            if (preview) return preview
        }
        if (page.length < 32) return ''
        const first = page[0]
        before = { at: first.invokedAt ?? first.createdAt, seq: first.seq }
    }
}
