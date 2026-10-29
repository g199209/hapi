package app.hapi.companion.feature.chat.attachments

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.hapi.companion.R
import app.hapi.companion.feature.chat.LocalChatMedia
import app.hapi.companion.ui.components.ZoomableImage
import app.hapi.companion.ui.components.ImageZoomState
import app.hapi.companion.ui.components.ImageCopyAction
import app.hapi.companion.ui.components.ClipboardImage
import app.hapi.companion.ui.components.asClipboardImage
import androidx.compose.ui.graphics.asAndroidBitmap
import app.hapi.protocol.chat.ChatAttachment
import java.util.Base64
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Session-owned state outlives recycled transcript rows and Activity rotation. */
@Stable
internal class AttachmentImagePreview {
    var attachment by mutableStateOf<ChatAttachment?>(null)
        private set
    val zoom = ImageZoomState()
    fun open(image: ChatAttachment) { zoom.reset(); attachment = image }
    fun dismiss() { attachment = null }
}

internal val LocalOpenAttachmentImage = staticCompositionLocalOf<((ChatAttachment) -> Unit)?> { null }

/** Load originals only on demand, on the session's machine. Stored previews
 * remain useful when the session is offline or its original upload is gone. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AttachmentImageViewer(attachment: ChatAttachment, zoom: ImageZoomState, onDismiss: () -> Unit) {
    val reader = LocalChatMedia.current.readAttachment
    var attempt by remember { mutableIntStateOf(0) }
    val original by produceState<PreviewImage>(PreviewImage.Loading, attachment.path, reader, attempt) {
        value = PreviewImage.Loading
        value = try {
            val response = reader?.invoke(attachment.path)
            val ready = if (response?.success == true && !response.content.isNullOrEmpty()) {
                withContext(Dispatchers.Default) {
                    val bytes = Base64.getMimeDecoder().decode(response.content)
                    decodeAttachmentImage(bytes)?.let { PreviewImage.Ready(it, bytes) }
                }
            } else null
            ready ?: PreviewImage.Unavailable
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            PreviewImage.Unavailable
        }
    }
    // Web previews may contain full-resolution bytes. Do not reuse the 512px
    // bubble decode for the viewer, even when the original cannot be read.
    val preview by rememberPreviewImage(attachment.previewUrl, maxDimension = Int.MAX_VALUE)
    val bitmap = (original as? PreviewImage.Ready)?.bitmap ?: (preview as? PreviewImage.Ready)?.bitmap
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Scaffold(
            modifier = Modifier.fillMaxSize().testTag("attachment-image-viewer"),
            topBar = {
                TopAppBar(
                    title = { Text(attachment.filename, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    navigationIcon = {
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.Default.Close, stringResource(R.string.image_close_preview))
                        }
                    },
                    actions = {
                        ImageCopyAction(attachment.path, enabled = bitmap != null && original != PreviewImage.Loading) {
                            val bytes = (original as? PreviewImage.Ready)?.originalBytes
                            if (bytes != null) ClipboardImage(bytes, attachment.mimeType)
                            else requireNotNull(bitmap).asAndroidBitmap().asClipboardImage()
                        }
                    },
                )
            },
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding)) {
                if (bitmap != null) {
                    ZoomableImage(bitmap, attachment.filename, zoom, Modifier.fillMaxSize())
                } else if (original == PreviewImage.Unavailable && preview == PreviewImage.Unavailable) {
                    Column(Modifier.align(Alignment.Center).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(stringResource(R.string.image_load_failed))
                        TextButton(onClick = { attempt++ }) { Text(stringResource(R.string.chat_retry)) }
                    }
                } else CircularProgressIndicator(Modifier.align(Alignment.Center))

                if (original == PreviewImage.Loading && bitmap != null) {
                    LinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.TopCenter))
                } else if (original == PreviewImage.Unavailable && bitmap != null) {
                    Surface(modifier = Modifier.align(Alignment.TopCenter).padding(8.dp),
                        shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.surface.copy(alpha = .92f)) {
                        Text(stringResource(R.string.image_preview_fallback), style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
                    }
                }
            }
        }
    }
}
