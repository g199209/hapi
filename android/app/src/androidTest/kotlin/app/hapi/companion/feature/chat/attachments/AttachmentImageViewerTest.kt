package app.hapi.companion.feature.chat.attachments

import android.graphics.Bitmap
import android.graphics.Color
import android.accessibilityservice.AccessibilityService
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import app.hapi.companion.R
import app.hapi.companion.feature.chat.ChatMedia
import app.hapi.companion.feature.chat.LocalChatMedia
import app.hapi.companion.feature.chat.blocks.UserTextBlockView
import app.hapi.companion.ui.theme.HapiTheme
import app.hapi.protocol.chat.ChatAttachment
import app.hapi.protocol.chat.UserTextBlock
import app.hapi.protocol.wire.FileReadResponse
import java.io.ByteArrayOutputStream
import java.util.Base64
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class AttachmentImageViewerTest {
    @get:Rule val compose = createComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val viewer = AttachmentImagePreview()
    private val messageMounted = mutableStateOf(true)
    private fun png(color: Int, width: Int = 1200): ByteArray = ByteArrayOutputStream().also {
        Bitmap.createBitmap(width, width / 2, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
            .compress(Bitmap.CompressFormat.PNG, 100, it)
    }.toByteArray()
    private fun response(bytes: ByteArray) = FileReadResponse(true, Base64.getEncoder().encodeToString(bytes))
    private fun attachment(preview: String? = AttachmentPolicy.dataUrl("image/png", png(Color.BLUE, 100))) =
        ChatAttachment("a", "中文 screenshot.png", "image/png", 1000.0, "/tmp/hapi-blobs/session/original.png", preview)
    private fun mount(attachment: ChatAttachment = attachment(), restoration: StateRestorationTester? = null,
        reader: suspend (String) -> FileReadResponse) {
        val media = ChatMedia(null, readAttachment = reader) { null }
        val content: @Composable () -> Unit = {
            HapiTheme { CompositionLocalProvider(LocalChatMedia provides media, LocalOpenAttachmentImage provides viewer::open) {
                if (messageMounted.value) UserTextBlockView(UserTextBlock("message", null, 0, null, "Original user message", listOf(attachment), null, null, null))
                viewer.attachment?.let { AttachmentImageViewer(it, viewer.zoom, viewer::dismiss) }
            } }
        }
        if (restoration == null) compose.setContent(content) else restoration.setContent(content)
        compose.waitForIdle()
    }
    private fun open() { compose.onNodeWithTag("attachment-image-a").performClick() }
    private fun pressBack() {
        assertTrue(InstrumentationRegistry.getInstrumentation().uiAutomation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK))
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("attachment-image-viewer").fetchSemanticsNodes().isEmpty() }
    }
    private fun viewport() = compose.onNodeWithTag("image-zoom-viewport")
    private fun waitForImage() = compose.waitUntil(10_000) {
        compose.onAllNodesWithTag("image-zoom-viewport").fetchSemanticsNodes().isNotEmpty()
    }
    private fun assertZoomed() = viewport().assert(SemanticsMatcher.expectValue(
        SemanticsProperties.StateDescription, context.getString(R.string.image_zoom_level, 300)))

    @Test fun tappingTheUserThumbnailReadsTheOriginalOnlyOnDemandAndBackKeepsTheMessage() {
        val loaded = CompletableDeferred<FileReadResponse>()
        val paths = mutableListOf<String>()
        mount { path -> paths.add(path); loaded.await() }
        compose.runOnIdle { assertTrue(paths.isEmpty()) }
        open()
        waitForImage() // Stored thumbnail is already usable while the file request is pending.
        compose.runOnIdle { assertEquals(listOf(attachment().path), paths) }
        val full = png(Color.GREEN)
        // Original decoding keeps pixels beyond the bubble's 512px ceiling.
        assertEquals(1200, decodeAttachmentImage(full)!!.width)
        compose.runOnIdle { loaded.complete(response(full)) }
        compose.waitUntil(10_000) {
            viewport().captureToImage().let { image ->
                val pixels = image.toPixelMap()
                pixels[pixels.width / 2, pixels.height / 2].green > .9f
            }
        }
        viewport().performTouchInput { doubleClick(center) }
        assertZoomed()
        compose.onNodeWithTag("image-zoom-fit").performClick()
        pressBack()
        compose.onNodeWithTag("attachment-image-viewer").assertDoesNotExist()
        compose.onNodeWithText("Original user message").assertIsDisplayed()
    }

    @Test fun missingOriginalKeepsThePreviewZoomableAndCloseReturnsToChat() {
        mount { FileReadResponse(false, error = "No such file") }
        open(); waitForImage()
        compose.onNodeWithText(context.getString(R.string.image_preview_fallback)).assertIsDisplayed()
        viewport().performTouchInput { doubleClick(center) }
        assertZoomed()
        compose.onNodeWithContentDescription(context.getString(R.string.image_close_preview)).performClick()
        compose.onNodeWithText("Original user message").assertIsDisplayed()
    }

    @Test fun anImageWithoutPreviewCanOpenTheOriginalFromItsFilename() {
        mount(attachment(null)) { response(png(Color.GREEN)) }
        compose.onNodeWithText("🖼 中文 screenshot.png").assertIsDisplayed()
        open(); waitForImage()
        viewport().performTouchInput { doubleClick(center) }
        assertZoomed()
    }

    @Test fun openViewerAndZoomSurviveRecreationWhileTheOriginalReloads() {
        val restoration = StateRestorationTester(compose)
        var loaded = CompletableDeferred<FileReadResponse>().apply { complete(response(png(Color.GREEN))) }
        mount(restoration = restoration) { loaded.await() }
        open(); waitForImage()
        compose.waitForIdle()
        viewport().performTouchInput { doubleClick(center) }
        assertZoomed()
        compose.runOnIdle { loaded = CompletableDeferred() }
        restoration.emulateSavedInstanceStateRestore()
        waitForImage()
        assertZoomed()
        compose.runOnIdle { loaded.complete(response(png(Color.GREEN))) }
        compose.waitForIdle()
        assertZoomed()
    }

    @Test fun dismissingWhileLoadingCancelsTheFileRequest() {
        var cancelled = false
        var requested = false
        mount { requested = true; try { awaitCancellation() } finally { cancelled = true } }
        open()
        compose.runOnIdle { assertTrue(requested) }
        compose.onNodeWithContentDescription(context.getString(R.string.image_close_preview)).performClick()
        compose.runOnIdle { assertTrue(cancelled) }
        compose.onNodeWithTag("attachment-image-viewer").assertDoesNotExist()
    }

    @Test fun recyclingTheUserRowDoesNotDismissOrResetTheSessionImageViewer() {
        mount { response(png(Color.GREEN)) }
        open(); waitForImage()
        viewport().performTouchInput { doubleClick(center) }
        compose.runOnIdle { messageMounted.value = false }
        assertZoomed()
        compose.runOnIdle { messageMounted.value = true }
        assertZoomed()
        pressBack()
        compose.onNodeWithText("Original user message").assertIsDisplayed()
    }

    @Test fun brokenPreviewAndOriginalOfferRetryWithoutLosingTheMessage() {
        var requests = 0
        mount(attachment("data:image/png;base64,broken")) {
            requests++
            if (requests == 1) FileReadResponse(true, content = "not base64!") else response(png(Color.GREEN))
        }
        open()
        compose.waitUntil(10_000) { compose.onAllNodesWithText(context.getString(R.string.image_load_failed)).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(context.getString(R.string.chat_retry)).performClick()
        waitForImage()
        compose.runOnIdle { assertEquals(2, requests) }
        pressBack()
        compose.onNodeWithText("Original user message").assertIsDisplayed()
    }
}
