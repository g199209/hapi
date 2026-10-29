package app.hapi.companion.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.*
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import app.hapi.companion.R
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal class ClipboardImage(val bytes: ByteArray, val mimeType: String)

internal fun Bitmap.asClipboardImage(): ClipboardImage = ClipboardImage(
    ByteArrayOutputStream().also { check(compress(Bitmap.CompressFormat.PNG, 100, it)) }.toByteArray(),
    "image/png",
)

/** Publish a URI to complete, immutable image bytes. Android grants clipboard
 * readers access through FileProvider; no image payload crosses Binder. */
internal suspend fun copyImageToClipboard(context: Context, load: suspend () -> ClipboardImage) {
    val file = withContext(Dispatchers.IO) {
        val image = load()
        val extension = requireNotNull(MimeTypeMap.getSingleton().getExtensionFromMimeType(image.mimeType))
        val directory = File(context.cacheDir, "image-clipboard").apply { mkdirs() }
        File.createTempFile("image-", ".$extension", directory).apply { writeBytes(image.bytes) }
    }
    val uri = FileProvider.getUriForFile(context, context.packageName + ".attachments", file)
    context.getSystemService(ClipboardManager::class.java)
        .setPrimaryClip(ClipData.newUri(context.contentResolver, "HAPI image", uri))
    // Keep recent copies readable after closing the viewer. Expire old files
    // only after a successful replacement, so failure preserves the old clip.
    withContext(Dispatchers.IO) {
        val cutoff = System.currentTimeMillis() - 86_400_000
        file.parentFile?.listFiles()?.filter { it != file && it.lastModified() < cutoff }?.forEach { it.delete() }
    }
}

@Composable
internal fun ImageCopyAction(imageKey: Any, enabled: Boolean = true, load: suspend () -> ClipboardImage) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val latestLoad by rememberUpdatedState(load)
    var busy by remember(imageKey) { mutableStateOf(false) }
    var copied by remember(imageKey) { mutableStateOf(false) }
    val failed = stringResource(R.string.image_copy_failed)
    LaunchedEffect(copied) { if (copied) { delay(1800); copied = false } }
    IconButton(
        enabled = enabled && !busy,
        modifier = Modifier.testTag("image-copy"),
        onClick = {
            busy = true
            scope.launch(Dispatchers.Main.immediate) {
                try {
                    copyImageToClipboard(context, latestLoad)
                    copied = true
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (_: Exception) {
                    Toast.makeText(context, failed, Toast.LENGTH_SHORT).show()
                } finally { busy = false }
            }
        },
    ) {
        val label = stringResource(if (copied) R.string.image_copied else R.string.image_copy)
        when {
            busy -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            copied -> Icon(Icons.Default.Check, label)
            else -> Icon(painterResource(R.drawable.ic_content_copy), label)
        }
    }
}
