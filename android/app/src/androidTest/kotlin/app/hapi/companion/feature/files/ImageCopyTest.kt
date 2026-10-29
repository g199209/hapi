package app.hapi.companion.feature.files

import android.content.ClipboardManager
import android.content.ClipData
import android.content.ComponentName
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Process
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.runtime.*
import androidx.compose.material3.Text
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import app.hapi.companion.feature.chat.ChatMedia
import app.hapi.companion.feature.chat.LocalChatMedia
import app.hapi.companion.feature.chat.attachments.*
import app.hapi.companion.feature.chat.blocks.GeneratedImageBlockView
import app.hapi.companion.testing.ImagePasteActivity
import app.hapi.companion.ui.components.*
import app.hapi.companion.ui.theme.HapiTheme
import app.hapi.protocol.chat.ChatAttachment
import app.hapi.protocol.chat.GeneratedImageBlock
import app.hapi.protocol.wire.*
import coil.ImageLoader
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.Base64
import kotlinx.coroutines.CompletableDeferred
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ImageCopyTest {
    @get:Rule val compose = createComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val clipboard get() = context.getSystemService(ClipboardManager::class.java)
    private fun png(width: Int = 1600, color: Int = Color.GREEN) = ByteArrayOutputStream().also {
        Bitmap.createBitmap(width, width / 2, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
            .compress(Bitmap.CompressFormat.PNG, 100, it)
    }.toByteArray()
    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun copy(): ClipData {
        val before = clipboard.primaryClip?.getItemAt(0)?.uri
        compose.onNodeWithTag("image-copy").assertIsEnabled().performClick()
        compose.waitUntil(10_000) { clipboard.primaryClip?.getItemAt(0)?.uri?.let { it != before } == true }
        compose.waitUntil(10_000) { compose.onNodeWithTag("image-copy").isEnabled() }
        return clipboard.primaryClip!!
    }
    private fun read(clip: ClipData): ByteArray {
        assertTrue(clip.description.hasMimeType("image/*"))
        assertNull(clip.getItemAt(0).text)
        assertEquals("content", clip.getItemAt(0).uri.scheme)
        return context.contentResolver.openInputStream(clip.getItemAt(0).uri)!!.use { it.readBytes() }
    }

    @Test fun fileViewerCopiesExactOriginalAndASeparateAppCanPasteAfterTheViewerCloses() {
        val bytes = png()
        var show by mutableStateOf(true)
        compose.setContent {
            val scope = rememberCoroutineScope()
            val model = remember { FileViewerViewModel("s", "/Share/图表.png", null, ViewerMode.FILE, null, ImageGateway(bytes), scope) }
            HapiTheme { if (show) FileViewerScreen(model, { show = false }) else Text("Closed") }
        }
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("image-copy").fetchSemanticsNodes().isNotEmpty() }
        val clip = copy()
        assertArrayEquals(bytes, read(clip)); assertEquals("image/png", context.contentResolver.getType(clip.getItemAt(0).uri))
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithText("Closed").assertIsDisplayed()
        // The receiving package is launched without a URI extra or explicit grant.
        context.startActivity(Intent().setComponent(ComponentName(instrumentation.context.packageName, ImagePasteActivity::class.java.name)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val deadline = SystemClock.uptimeMillis() + 10_000
        var paste: AccessibilityNodeInfo? = null
        while (paste == null && SystemClock.uptimeMillis() < deadline) {
            val window = instrumentation.uiAutomation.rootInActiveWindow
            if (window?.packageName?.toString() == instrumentation.context.packageName) {
                paste = window.findAccessibilityNodeInfosByText("Paste image").firstOrNull { it.isVisibleToUser && it.isEnabled }
            }
            if (paste == null) SystemClock.sleep(50)
        }
        val bounds = android.graphics.Rect().also { requireNotNull(paste).getBoundsInScreen(it) }
        val down = SystemClock.uptimeMillis()
        for (action in listOf(android.view.MotionEvent.ACTION_DOWN, android.view.MotionEvent.ACTION_UP)) {
            val event = android.view.MotionEvent.obtain(down, SystemClock.uptimeMillis(), action,
                bounds.exactCenterX(), bounds.exactCenterY(), 0).apply { source = android.view.InputDevice.SOURCE_TOUCHSCREEN }
            assertTrue(instrumentation.uiAutomation.injectInputEvent(event, true)); event.recycle()
            SystemClock.sleep(50)
        }
        var result: String? = null
        while (result == null && SystemClock.uptimeMillis() < deadline) {
            fun find(n: AccessibilityNodeInfo): String? {
                if (n.contentDescription?.toString() == "HAPI_IMAGE_PASTE_RESULT" && !n.text.isNullOrEmpty()) return n.text.toString()
                return (0 until n.childCount).firstNotNullOfOrNull { n.getChild(it)?.let(::find) }
            }
            result = instrumentation.uiAutomation.rootInActiveWindow?.let(::find)
            if (result == null) SystemClock.sleep(50)
        }
        val received = JSONObject(requireNotNull(result))
        assertNotEquals(Process.myUid(), received.getInt("uid"))
        assertEquals("image/png", received.getString("mime")); assertTrue(received.getBoolean("imageMime"))
        assertEquals(hash(bytes), received.getString("sha256")); assertEquals(1600, received.getInt("width"))
        File(context.cacheDir, "image-clipboard-native-proof.json").writeText(received.toString())
        instrumentation.uiAutomation.rootInActiveWindow!!.findAccessibilityNodeInfosByText("Return to HAPI")
            .first().performAction(AccessibilityNodeInfo.ACTION_CLICK)
        val returnDeadline = SystemClock.uptimeMillis() + 5000
        while (instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString() != context.packageName && SystemClock.uptimeMillis() < returnDeadline) {
            SystemClock.sleep(50)
        }
        instrumentation.uiAutomation.waitForIdle(300, 3000)
        compose.onNodeWithText("Closed").assertIsDisplayed()
    }

    @Test fun userAttachmentCopiesOriginalBytesInsteadOfTheSmallPreview() {
        val bytes = png()
        val image = ChatAttachment("a", "photo.png", "image/png", bytes.size.toDouble(), "/original.png", AttachmentPolicy.dataUrl("image/png", png(100, Color.BLUE)))
        compose.setContent { HapiTheme {
            val zoom = remember { ImageZoomState() }
            CompositionLocalProvider(LocalChatMedia provides ChatMedia(null, { FileReadResponse(true, Base64.getEncoder().encodeToString(bytes)) }) { null }) {
                AttachmentImageViewer(image, zoom, {})
            }
        } }
        compose.waitUntil(10_000) { compose.onNodeWithTag("image-copy").isEnabled() }
        assertArrayEquals(bytes, read(copy()))
    }

    @Test fun missingOriginalCopiesTheDisplayedPreview() {
        val preview = png(100, Color.BLUE)
        val image = ChatAttachment("a", "gone.jpg", "image/jpeg", 0.0, "/gone.jpg", AttachmentPolicy.dataUrl("image/png", preview))
        compose.setContent { HapiTheme {
            val zoom = remember { ImageZoomState() }
            CompositionLocalProvider(LocalChatMedia provides ChatMedia(null, { FileReadResponse(false) }) { null }) { AttachmentImageViewer(image, zoom, {}) }
        } }
        compose.waitUntil(10_000) { compose.onNodeWithTag("image-copy").isEnabled() }
        val clip = copy(); val bytes = read(clip)
        val bitmap = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        assertEquals(100, bitmap.width); assertEquals(Color.BLUE, bitmap.getPixel(50, 25))
        assertEquals("image/png", context.contentResolver.getType(clip.getItemAt(0).uri))
    }

    @Test fun loadingAndUnavailableImagesCannotCopy() {
        val loaded = CompletableDeferred<FileReadResponse>()
        val image = ChatAttachment("a", "missing.png", "image/png", 0.0, "/missing.png", null)
        compose.setContent { HapiTheme {
            val zoom = remember { ImageZoomState() }
            CompositionLocalProvider(LocalChatMedia provides ChatMedia(null, { loaded.await() }) { null }) { AttachmentImageViewer(image, zoom, {}) }
        } }
        compose.onNodeWithTag("image-copy").assertIsNotEnabled()
        compose.runOnIdle { loaded.complete(FileReadResponse(false)) }
        compose.waitForIdle(); compose.onNodeWithTag("image-copy").assertIsNotEnabled()
    }

    @Test fun failurePreservesThePreviousClipboardAndAllowsRetry() {
        compose.runOnUiThread { clipboard.setPrimaryClip(ClipData.newPlainText("old", "keep me")) }
        val bytes = png()
        var fail by mutableStateOf(true)
        compose.setContent { HapiTheme { ImageCopyAction("test") { if (fail) error("Unavailable") else ClipboardImage(bytes, "image/png") } } }
        compose.onNodeWithTag("image-copy").performClick(); compose.waitForIdle()
        assertEquals("keep me", clipboard.primaryClip!!.getItemAt(0).text.toString())
        compose.runOnIdle { fail = false }; assertArrayEquals(bytes, read(copy()))
    }

    @Test fun laterCopiesKeepRecentUrisImmutableAndExpireDayOldFiles() {
        val first = png()
        val second = png(800, Color.RED)
        var current by mutableStateOf(first)
        compose.setContent { HapiTheme { ImageCopyAction(current) { ClipboardImage(current, "image/png") } } }
        val old = copy()
        val expired = File(context.cacheDir, "image-clipboard/expired.png").apply { writeBytes(first); setLastModified(System.currentTimeMillis() - 172_800_000) }
        compose.runOnIdle { current = second }
        val fresh = copy()
        assertArrayEquals(first, read(old)); assertArrayEquals(second, read(fresh)); assertFalse(expired.exists())
    }

    @Test fun generatedImagesCopyFullResolutionUsingTheExistingLoader() {
        val file = File(context.cacheDir, "generated-copy-test.png").apply { writeBytes(png()) }
        val loader = ImageLoader.Builder(context).build()
        val block = GeneratedImageBlock("g", null, 0, null, "image", "generated.png", "image/png", null, null)
        compose.setContent { HapiTheme {
            CompositionLocalProvider(LocalChatMedia provides ChatMedia(loader) { file.toURI().toString() }) { GeneratedImageBlockView(block) }
        } }
        compose.onNodeWithContentDescription("generated.png").performClick()
        val bytes = read(copy())
        val bitmap = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        assertEquals(1600, bitmap.width); assertEquals(Color.GREEN, bitmap.getPixel(800, 400))
        loader.shutdown(); file.delete()
    }

    private fun SemanticsNodeInteraction.isEnabled(): Boolean = runCatching { assertIsEnabled(); true }.getOrDefault(false)
    private class ImageGateway(private val bytes: ByteArray) : FilesGateway {
        override suspend fun readFile(sessionId: String, path: String) = FileReadResponse(true, Base64.getEncoder().encodeToString(bytes))
        override suspend fun gitDiffFile(sessionId: String, path: String, staged: Boolean?) = GitCommandResponse(true, stdout = "")
        override suspend fun gitStatus(sessionId: String): GitCommandResponse = error("unused")
        override suspend fun gitDiffNumstat(sessionId: String, staged: Boolean): GitCommandResponse = error("unused")
        override suspend fun searchFiles(sessionId: String, query: String, limit: Int): FileSearchResponse = error("unused")
        override suspend fun listDirectory(sessionId: String, path: String?): ListDirectoryResponse = error("unused")
    }
}
