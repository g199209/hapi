package app.hapi.companion.feature.chat

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.hapi.companion.R
import app.hapi.protocol.wire.CodexRestoreProgress
import kotlinx.coroutines.delay

internal fun restoreFraction(progress: CodexRestoreProgress): Float? {
    val total = progress.total ?: return null
    val completed = progress.completed ?: return null
    if (progress.phase !in listOf("projecting", "children") || total <= 0 || completed !in 0..total) return null
    return completed.toFloat() / total
}

@Composable
internal fun SessionRestoreBanner(progress: CodexRestoreProgress) {
    var elapsed by remember(progress.startedAt) { mutableLongStateOf(0) }
    LaunchedEffect(progress.startedAt) {
        while (progress.startedAt > 0) {
            elapsed = ((System.currentTimeMillis() - progress.startedAt) / 1000).coerceAtLeast(0)
            delay(1_000)
        }
    }
    val label = stringResource(when (progress.phase) {
        "history" -> R.string.chat_restore_history
        "projecting" -> R.string.chat_restore_projecting
        "children" -> R.string.chat_restore_children
        "syncing" -> R.string.chat_restore_syncing
        else -> R.string.chat_status_restoring
    })
    val completed = progress.completed
    val count = when {
        restoreFraction(progress) != null -> "${progress.completed} / ${progress.total}"
        progress.phase == "history" && completed != null -> stringResource(R.string.chat_restore_turns, completed)
        else -> null
    }
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp).testTag("session-restore")) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f)
                    .semantics { liveRegion = LiveRegionMode.Polite })
                count?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                if (elapsed > 0) Text(stringResource(R.string.chat_restore_seconds, elapsed), style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            val fraction = restoreFraction(progress)
            if (fraction == null) LinearProgressIndicator(Modifier.fillMaxWidth().testTag("restore-indeterminate"))
            else LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth().testTag("restore-progress"))
        }
    }
}
