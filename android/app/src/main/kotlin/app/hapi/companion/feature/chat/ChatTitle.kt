package app.hapi.companion.feature.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.hapi.companion.R
import app.hapi.companion.ui.theme.HapiTypography
import app.hapi.companion.ui.theme.hapi
import app.hapi.protocol.wire.SummaryPatching

internal enum class ChatSessionStatus {
    Loading, LoadFailed, Reconnecting, Working, NeedsInput, NeedsApproval,
    NeedsAttention, Background, Idle, Inactive, Archived,
}

/** Prefer connection truth over cached activity, and pending actions over busy. */
internal fun chatSessionStatus(header: ChatHeaderUi, reconnecting: Boolean, loadFailed: Boolean = false): ChatSessionStatus = when {
    reconnecting -> ChatSessionStatus.Reconnecting
    !header.hasSessionInfo -> if (loadFailed) ChatSessionStatus.LoadFailed else ChatSessionStatus.Loading
    !header.active -> if (header.lifecycleState == "archived") ChatSessionStatus.Archived else ChatSessionStatus.Inactive
    header.pendingRequestKinds.containsAll(listOf(SummaryPatching.KIND_PERMISSION, SummaryPatching.KIND_INPUT)) -> ChatSessionStatus.NeedsAttention
    SummaryPatching.KIND_PERMISSION in header.pendingRequestKinds -> ChatSessionStatus.NeedsApproval
    SummaryPatching.KIND_INPUT in header.pendingRequestKinds -> ChatSessionStatus.NeedsInput
    header.thinking -> ChatSessionStatus.Working
    header.backgroundTaskCount > 0 -> ChatSessionStatus.Background
    else -> ChatSessionStatus.Idle
}

@Composable
internal fun ChatTitle(header: ChatHeaderUi, reconnecting: Boolean, retry: () -> Unit, loadFailed: Boolean = false) {
    val status = chatSessionStatus(header, reconnecting, loadFailed)
    val label = stringResource(when (status) {
        ChatSessionStatus.Loading -> R.string.chat_status_loading
        ChatSessionStatus.LoadFailed -> R.string.chat_status_load_failed
        ChatSessionStatus.Reconnecting -> R.string.chat_reconnecting_retry
        ChatSessionStatus.Working -> R.string.chat_status_working
        ChatSessionStatus.NeedsInput -> R.string.chat_status_needs_input
        ChatSessionStatus.NeedsApproval -> R.string.chat_status_needs_approval
        ChatSessionStatus.NeedsAttention -> R.string.chat_status_needs_attention
        ChatSessionStatus.Background -> R.string.chat_status_background
        ChatSessionStatus.Idle -> R.string.chat_status_idle
        ChatSessionStatus.Inactive -> R.string.chat_status_inactive
        ChatSessionStatus.Archived -> R.string.chat_status_archived
    })
    val retryable = status == ChatSessionStatus.Reconnecting || status == ChatSessionStatus.LoadFailed
    val spinning = status in setOf(ChatSessionStatus.Working, ChatSessionStatus.Background, ChatSessionStatus.Loading, ChatSessionStatus.Reconnecting)
    val color = when (status) {
        ChatSessionStatus.Working, ChatSessionStatus.Background, ChatSessionStatus.Idle ->
            if (MaterialTheme.hapi.isDark) Color(0xFF6CDB86) else Color(0xFF16783D)
        ChatSessionStatus.NeedsApproval, ChatSessionStatus.NeedsAttention ->
            if (MaterialTheme.hapi.isDark) Color(0xFFFFD166) else Color(0xFF995700)
        ChatSessionStatus.NeedsInput -> MaterialTheme.colorScheme.primary
        ChatSessionStatus.Reconnecting, ChatSessionStatus.LoadFailed -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Column(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("chat-header")
            .then(if (retryable) Modifier.clickable(onClick = retry) else Modifier),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(header.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        // Append to the existing subtitle, keeping the same two-line header.
        // Reserve status width so long machine names cannot hide the activity.
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (!retryable && !header.subtitle.isNullOrBlank()) {
                Text(header.subtitle, modifier = Modifier.weight(1f, fill = false), style = HapiTypography.caption,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (!retryable && !header.subtitle.isNullOrBlank()) {
                    Text("·", style = HapiTypography.caption, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Box(Modifier.size(12.dp).clearAndSetSemantics {}, contentAlignment = Alignment.Center) {
                    if (spinning) CircularProgressIndicator(Modifier.size(11.dp), color = color, strokeWidth = 1.5.dp)
                    else Box(Modifier.size(6.dp).background(color, CircleShape))
                }
                Text(label, style = HapiTypography.caption, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.testTag("chat-session-status").semantics { liveRegion = LiveRegionMode.Polite })
            }
        }
    }
}
