package app.hapi.companion.feature.chat

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember

/** Keep index changes off the list until they can be placed without taking over a gesture. */
@Composable
internal fun AnchoredTranscript(
    incoming: TranscriptSnapshot,
    list: LazyListState,
    reading: TranscriptReadingState,
    content: @Composable (TranscriptSnapshot) -> Unit,
) {
    val presentation = remember(incoming.state.sessionId, list) { TranscriptPresentation(incoming) }
    val previous = presentation.state
    val membershipChanged = remember(previous.rows, incoming.rows) {
        previous.rows.map { it.id } != incoming.rows.map { it.id }
    }
    // Observe only the start/end Boolean in composition, never per-frame geometry.
    // Keep the current rows for the entire drag/fling; newer snapshots coalesce.
    val scrolling = list.isScrollInProgress
    val next = if (scrolling && membershipChanged) previous else incoming
    content(next)
    SideEffect {
        if (next != previous) {
            val anchor = list.readingAnchor(previous)
            val exact = anchor?.let { saved -> next.rows.indexOfFirst { it.id == saved.row.id } } ?: -1
            val target = if (exact >= 0) exact else anchor?.let { saved ->
                val sourceIds = saved.row.sourceIds.toSet()
                next.rows.indexOfFirst { row -> row.sourceIds.any { it in sourceIds } }
            } ?: -1
            val index = target.takeIf { it >= 0 }?.plus(1)
            if (!reading.followsTail && !scrolling && anchor != null && index != null &&
                (index != anchor.index || exact < 0)) {
                // The new item provider is now installed, but has not been measured.
                // Requesting before composition would first scroll the OLD provider,
                // clamping large indices and double-compensating small prepends.
                // A tall tool/reasoning card becoming a thin divider cannot
                // retain an offset deep inside the former card.
                val sameDivider = anchor.row is TranscriptRow.ActivityDivider &&
                    next.rows[target] is TranscriptRow.ActivityDivider
                list.requestScrollToItem(index, if (exact >= 0 || sameDivider) anchor.scrollOffset else 0)
            }
            presentation.state = next
        }
    }
}

private class TranscriptPresentation(var state: TranscriptSnapshot)
private data class ReadingAnchor(val row: TranscriptRow, val index: Int, val scrollOffset: Int)

private fun LazyListState.readingAnchor(state: TranscriptSnapshot): ReadingAnchor? {
    // The fixed history control is an affordance, never a reading anchor.
    val item = layoutInfo.visibleItemsInfo.firstOrNull { it.index >= firstVisibleItemIndex && it.index > 0 }
        ?: return null
    val row = state.rows.getOrNull(item.index - 1) ?: return null
    if (item.key != row.id) return null
    return ReadingAnchor(row, item.index, -item.offset)
}
