package app.hapi.companion.feature.chat.history

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import app.hapi.companion.R
import app.hapi.protocol.chat.UserTextBlock

/** Separate from text selection: history actions never consume prose long-press gestures. */
data class HistoryInteractions(
    val availability: HistoryAvailability,
    val pending: Boolean,
    val request: (HistoryKind, String?, String) -> Unit,
)
val LocalConversationHistory = compositionLocalOf<HistoryInteractions?> { null }

@Composable
fun MessageHistoryActions(block: UserTextBlock) {
    val interactions = LocalConversationHistory.current ?: return
    val localId = block.localId ?: return
    val history = interactions.availability
    val fork = history.supports(HistoryKind.ForkBefore, localId)
    val rewind = history.supports(HistoryKind.RewindBefore, localId)
    if (!fork && !rewind) return
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        if (fork) TextButton(
            onClick = { interactions.request(HistoryKind.ForkBefore, localId, block.text) },
            enabled = history.ready && !interactions.pending,
            modifier = Modifier.testTag("history-fork-$localId"),
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
        ) { Text(stringResource(R.string.history_fork), style = MaterialTheme.typography.labelMedium) }
        if (rewind) TextButton(
            onClick = { interactions.request(HistoryKind.RewindBefore, localId, block.text) },
            enabled = history.ready && !interactions.pending,
            modifier = Modifier.testTag("history-rewind-$localId"),
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
        ) { Text(stringResource(R.string.history_rewind), style = MaterialTheme.typography.labelMedium) }
    }
}

@Composable
fun ConversationHistoryDialog(
    state: HistoryUi,
    onConfirm: () -> Unit,
    onFallback: () -> Unit,
    onDismiss: () -> Unit,
) {
    val request = state.request ?: return
    val rewind = request.kind == HistoryKind.RewindBefore
    val title = when {
        state.offerFork -> R.string.history_fallback_title
        state.refreshOnly -> R.string.history_refresh_title
        rewind -> R.string.history_rewind_title
        request.kind == HistoryKind.ForkBefore -> R.string.history_fork_before_title
        else -> R.string.history_fork_current
    }
    AlertDialog(
        onDismissRequest = { if (!state.pending) onDismiss() },
        title = { Text(stringResource(title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(when {
                    state.offerFork -> R.string.history_fallback_body
                    state.refreshOnly -> R.string.history_refresh_body
                    rewind -> R.string.history_rewind_body
                    request.kind == HistoryKind.ForkBefore -> R.string.history_fork_before_body
                    else -> R.string.history_fork_current_body
                }))
                if (request.preview.isNotBlank()) Text(request.preview, style = MaterialTheme.typography.bodySmall, maxLines = 5)
                state.error?.takeUnless { state.offerFork }?.let { error ->
                    Text(error.detail ?: stringResource(if (error.kind == HistoryErrorKind.Stale) R.string.history_stale else R.string.history_failed),
                        color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                if (state.pending) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            TextButton(
                onClick = if (state.offerFork) onFallback else onConfirm,
                enabled = !state.pending && state.error?.kind != HistoryErrorKind.Stale,
                modifier = Modifier.testTag("history-confirm"),
            ) { Text(stringResource(when {
                state.pending -> R.string.history_working
                state.offerFork -> R.string.history_fork
                state.refreshOnly -> R.string.history_refresh
                rewind -> R.string.history_rewind
                else -> R.string.history_fork
            })) }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !state.pending, modifier = Modifier.testTag("history-cancel")) { Text(stringResource(R.string.feature_cancel)) } },
    )
}
