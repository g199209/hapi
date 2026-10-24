package app.hapi.companion.feature.chat.attachments

import android.graphics.BitmapFactory
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Async decode outcome for a wire `previewUrl` thumbnail. */
sealed interface PreviewImage {
    /** Decode still running — render a neutral placeholder. */
    data object Loading : PreviewImage

    data class Ready(val bitmap: ImageBitmap) : PreviewImage

    /** Not a data URL / undecodable — fall back to the filename chip. */
    data object Unavailable : PreviewImage
}

/**
 * Decode an `AttachmentMetadata.previewUrl` data URL into a bubble thumbnail.
 *
 * Android-authored previews are ≤ 512 px JPEGs, but web-authored ones embed
 * the full original (up to 5 MB), so the decode downsamples to [maxDimension]
 * and runs off the main thread.
 */
@Composable
fun rememberPreviewImage(previewUrl: String?, maxDimension: Int = 512): State<PreviewImage> =
    produceState<PreviewImage>(initialValue = PreviewImage.Loading, previewUrl, maxDimension) {
        value = PreviewImage.Loading
        if (previewUrl == null) {
            value = PreviewImage.Unavailable
            return@produceState
        }
        value = withContext(Dispatchers.Default) {
            val bytes = AttachmentPolicy.bytesFromDataUrl(previewUrl)
            val bitmap = bytes?.let { decodeAttachmentImage(it, maxDimension) }
            if (bitmap != null) PreviewImage.Ready(bitmap) else PreviewImage.Unavailable
        }
    }

/**
 * Synchronous decode for composer chip thumbs — [ComposerAttachmentUi.previewBytes]
 * are preparer-made ≤ 512 px JPEGs, cheap enough to decode inline.
 */
@Composable
fun rememberChipThumbnail(previewBytes: ByteArray?): ImageBitmap? = remember(previewBytes) {
    previewBytes?.let { decodeAttachmentImage(it, AttachmentPolicy.PREVIEW_MAX_DIMENSION) }
}

internal fun decodeAttachmentImage(bytes: ByteArray, maxDimension: Int = Int.MAX_VALUE): ImageBitmap? {
    return try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val options = BitmapFactory.Options().apply {
            inSampleSize = AttachmentPolicy.sampleSizeFor(bounds.outWidth, bounds.outHeight, maxDimension)
        }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)?.asImageBitmap()
    } catch (_: Exception) {
        null
    } catch (_: OutOfMemoryError) {
        null
    }
}
