package app.hapi.companion.feature.chat

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember

/** Keep index changes off the list until they can be placed without taking over a gesture. */
@Composable
internal fun AnchoredTranscript(
    incoming: ChatUiState,
    list: LazyListState,
    reading: TranscriptReadingState,
    content: @Composable (ChatUiState) -> Unit,
) {
    val presentation = remember(incoming.sessionId, list) { TranscriptPresentation(incoming) }
    val previous = presentation.state
    val membershipChanged = remember(previous.blocks, incoming.blocks) {
        previous.blocks.map { it.stableId } != incoming.blocks.map { it.stableId }
    }
    // Observe only the start/end Boolean in composition, never per-frame geometry.
    // Keep the current rows for the entire drag/fling; newer snapshots coalesce.
    val scrolling = list.isScrollInProgress
    val next = if (scrolling && membershipChanged) previous else incoming
    content(next)
    SideEffect {
        if (next != previous) {
            val anchor = list.readingAnchor(previous)
            val index = anchor?.let { saved -> next.blocks.indexOfFirst { it.stableId == saved.key } }
                ?.takeIf { it >= 0 }?.plus(1)
            if (!reading.followsTail && !scrolling && anchor != null && index != null && index != anchor.index) {
                // The new item provider is now installed, but has not been measured.
                // Requesting before composition would first scroll the OLD provider,
                // clamping large indices and double-compensating small prepends.
                list.requestScrollToItem(index, anchor.scrollOffset)
            }
            presentation.state = next
        }
    }
}

private class TranscriptPresentation(var state: ChatUiState)
private data class ReadingAnchor(val key: String, val index: Int, val scrollOffset: Int)

private fun LazyListState.readingAnchor(state: ChatUiState): ReadingAnchor? {
    // The fixed history control is an affordance, never a reading anchor.
    val item = layoutInfo.visibleItemsInfo.firstOrNull { it.index >= firstVisibleItemIndex && it.index > 0 }
        ?: return null
    val key = state.blocks.getOrNull(item.index - 1)?.stableId ?: return null
    if (item.key != key) return null
    return ReadingAnchor(key, item.index, -item.offset)
}
