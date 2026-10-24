package app.hapi.companion.feature.chat.blocks

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.hapi.companion.R
import app.hapi.companion.feature.chat.LocalChatInteractions
import app.hapi.companion.feature.chat.LocalChatInspection
import app.hapi.companion.feature.chat.messagePreview
import app.hapi.companion.ui.markdown.Markdown
import app.hapi.companion.ui.theme.HapiTypography
import app.hapi.companion.feature.chat.attachments.PreviewImage
import app.hapi.companion.feature.chat.attachments.rememberPreviewImage
import app.hapi.companion.feature.chat.attachments.LocalOpenAttachmentImage
import app.hapi.companion.ui.theme.HapiTheme
import app.hapi.protocol.chat.ChatAttachment
import app.hapi.protocol.chat.UserTextBlock

/**
 * Operator prompt: right-aligned, selectable Markdown bubble with input line
 * breaks preserved. Attachments render as
 * image thumbnails (decoded from the wire `previewUrl` data URL — both
 * Android- and web-sent messages carry one, and optimistic rows do too, so
 * thumbnails appear instantly on send) or filename chips, and a failed-send
 * tap-to-retry hint (B-M3f upgrades the former chips-only rendering).
 */
@Composable
fun UserTextBlockView(block: UserTextBlock, modifier: Modifier = Modifier) {
    val annotated = remember(block.text) { app.hapi.companion.feature.chat.annotations.ResponseAnnotations.parse(block.text) }
    val replies = remember(block.text) { QuestionReplies.parse(block.text) }
    val request = if (replies != null) "" else annotated?.request ?: block.text
    val preview = remember(request) { messagePreview(request) }
    val inspection = LocalChatInspection.current
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val maxBubbleWidth = maxWidth * 0.85f
        Row(modifier = Modifier.fillMaxWidth()) {
            Spacer(modifier = Modifier.width(48.dp).weight(1f))
            Column(horizontalAlignment = Alignment.End) {
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomStart = 16.dp, bottomEnd = 4.dp),
                    modifier = Modifier.widthIn(max = maxBubbleWidth),
                ) {
                    Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                        if (replies != null) Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            replies.forEachIndexed { index, reply -> QuestionReplyView(reply, block.id, index) }
                        }
                        annotated?.annotations?.forEachIndexed { index, value ->
                            app.hapi.companion.feature.chat.annotations.AnnotationCard(value, index, Modifier.padding(bottom = 6.dp))
                        }
                        if (replies == null && preview.text.isNotBlank()) Markdown(
                            text = preview.text,
                            textStyle = HapiTypography.body,
                            selectable = true,
                            preserveLineBreaks = true,
                        )
                        if (replies == null && preview.end < request.length) {
                            TextButton(onClick = { inspection?.openMessage(block.id) }, enabled = inspection != null) {
                                Text(stringResource(R.string.chat_view_full_message))
                            }
                        }
                        block.attachments?.takeIf { it.isNotEmpty() }?.let { attachments ->
                            Column(
                                modifier = Modifier.padding(top = 8.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                                horizontalAlignment = Alignment.End,
                            ) {
                                attachments.forEach { attachment ->
                                    key(attachment.id, attachment.path) { AttachmentView(attachment) }
                                }
                            }
                        }
                    }
                }
                app.hapi.companion.feature.chat.history.MessageHistoryActions(block)
                if (block.status == "failed") {
                    val interactions = LocalChatInteractions.current
                    val retryLocalId = block.localId
                    val retryModifier = if (interactions != null && retryLocalId != null) {
                        Modifier.clickable { interactions.retryFailedMessage(retryLocalId) }
                    } else {
                        Modifier
                    }
                    Text(
                        text = stringResource(
                            if (interactions != null && retryLocalId != null) {
                                R.string.chat_not_delivered_retry
                            } else {
                                R.string.chat_not_delivered
                            },
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = retryModifier.padding(top = 2.dp, end = 4.dp),
                    )
                }
            }
        }
    }
}

/**
 * One bubble attachment: image mimes with a decodable `previewUrl` render a
 * thumbnail (web `MessageAttachments` split); everything else — plus decode
 * failures — falls back to the filename chip. Both image forms open the
 * full-screen viewer; ordinary file chips retain their existing behavior.
 */
@Composable
private fun AttachmentView(attachment: ChatAttachment) {
    val isImage = attachment.mimeType.startsWith("image/")
    val openImage = LocalOpenAttachmentImage.current
    val imageClick = if (isImage && openImage != null) Modifier
        .testTag("attachment-image-${attachment.id}")
        .clickable(role = Role.Button, onClickLabel = stringResource(R.string.image_open_preview)) { openImage(attachment) }
        else Modifier
    if (!isImage || attachment.previewUrl == null) {
        AttachmentChip(attachment, imageClick)
        return
    }
    val preview by rememberPreviewImage(attachment.previewUrl)
    when (val state = preview) {
        is PreviewImage.Ready -> Image(
            bitmap = state.bitmap,
            contentDescription = attachment.filename,
            contentScale = ContentScale.Fit,
            alignment = Alignment.CenterEnd,
            modifier = Modifier
                .heightIn(max = 180.dp)
                .clip(RoundedCornerShape(10.dp))
                .then(imageClick),
        )
        // Sized placeholder while decoding keeps the bubble from jumping.
        PreviewImage.Loading -> Surface(
            modifier = imageClick,
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.4f),
            shape = RoundedCornerShape(10.dp),
        ) {
            Spacer(modifier = Modifier.size(width = 120.dp, height = 90.dp))
        }
        PreviewImage.Unavailable -> AttachmentChip(attachment, imageClick)
    }
}

@Composable
private fun AttachmentChip(attachment: ChatAttachment, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.55f),
        shape = RoundedCornerShape(8.dp),
    ) {
        Text(
            text = "${if (attachment.mimeType.startsWith("image/")) "🖼" else "📎"} ${attachment.filename}",
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun UserTextBlockPreview() {
    HapiTheme {
        Surface {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            UserTextBlockView(
                UserTextBlock(
                    id = "u1",
                    localId = null,
                    createdAt = 0,
                    invokedAt = null,
                    text = "Fix the failing pagination test and explain the root cause",
                    attachments = null,
                    status = null,
                    originalText = null,
                    meta = null,
                ),
            )
            UserTextBlockView(
                UserTextBlock(
                    id = "u2",
                    localId = null,
                    createdAt = 0,
                    invokedAt = null,
                    text = "Here is the screenshot",
                    attachments = listOf(
                        ChatAttachment(
                            id = "a1",
                            filename = "screenshot.png",
                            mimeType = "image/png",
                            size = 1024.0,
                            path = "/uploads/screenshot.png",
                        ),
                    ),
                    status = "failed",
                    originalText = null,
                    meta = null,
                ),
            )
            }
        }
    }
}
