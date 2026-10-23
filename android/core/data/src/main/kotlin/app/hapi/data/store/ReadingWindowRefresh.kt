package app.hapi.data.store

import app.hapi.data.api.MessagesApi
import app.hapi.data.api.MessagesQuery
import app.hapi.protocol.window.MessagePosition
import app.hapi.protocol.window.MessageViewMode
import app.hapi.protocol.window.MessageWindowLogic
import app.hapi.protocol.window.MessageWindowState
import app.hapi.protocol.window.PAGE_SIZE
import app.hapi.protocol.window.asWindowMessage

/** The existing before API can re-read a historical range without traversing the live tail. */
internal suspend fun reloadReadingWindow(
    sessionId: String,
    api: MessagesApi,
    initial: MessageWindowState,
    isCurrent: () -> Boolean,
): MessageWindowState? {
    val regular = initial.messages.filter { it.seq != null && !it.isOptimistic && !it.isQueuedForInvocation }
    val oldest = regular.minOfOrNull { MessagePosition(it.positionAt, it.seq!!) } ?: return null
    val newest = regular.maxOf { MessagePosition(it.positionAt, it.seq!!) }
    val known = regular.associateBy { it.id }
    var before = MessagePosition(newest.at, Math.addExact(newest.seq, 1))
    var epoch: Long? = null
    var result = MessageWindowState(sessionId, viewMode = MessageViewMode.History,
        historyRetentionLimit = initial.historyRetentionLimit)
    while (true) {
        val response = api.getMessages(sessionId, MessagesQuery.Before(before.at, before.seq, PAGE_SIZE))
        if (!isCurrent()) return result // Caller rejects the superseded generation.
        check(response.page.direction == "before") { "Unexpected history refresh page" }
        check(epoch == null || epoch == response.page.epoch) { "History changed during refresh; retry" }
        epoch = response.page.epoch
        // Reuse unchanged payloads and pipeline cache entries instead of retaining
        // a second copy of every large tool result in the reading window.
        val rows = response.messages.map { wire ->
            known[wire.id]?.takeIf { it.status == null && it.wire == wire } ?: wire.asWindowMessage()
        }
        val retained = rows.filter { it.seq != null && MessagePosition(it.positionAt, it.seq!!) >= oldest }
        result = MessageWindowLogic.applyOlderResponse(result, retained, response.page, 0)
        val next = MessageWindowLogic.pagePosition(response.page.nextBeforeAt, response.page.nextBeforeSeq)
        if (!response.page.hasMore || (next != null && next <= oldest)) {
            val first = result.messages.firstOrNull { it.seq != null }
            val last = result.messages.lastOrNull { it.seq != null }
            return result.copy(
                hasMore = response.page.hasMore || rows.size != retained.size,
                oldestPosition = first?.let { MessagePosition(it.positionAt, it.seq!!) } ?: next,
                newestPosition = last?.let { MessagePosition(it.positionAt, it.seq!!) },
                // A bounded history refresh never claims to have caught up with the live tail.
                requiresLatestReset = true,
            )
        }
        check(next != null && next < before) { "Message history cursor did not advance" }
        before = next
    }
}
