package app.hapi.companion.feature.files

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import app.hapi.companion.ui.components.*
import app.hapi.companion.ui.theme.HapiTheme
import app.hapi.protocol.wire.*
import java.io.ByteArrayOutputStream
import java.util.Base64
import kotlinx.coroutines.CoroutineScope
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ImageZoomTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var zoom: ImageZoomState
    private val key = mutableStateOf("image-a")
    private val landscape = mutableStateOf(false)
    private fun bitmap() = Bitmap.createBitmap(1200, 900, Bitmap.Config.ARGB_8888).apply {
        Canvas(this).apply {
            drawColor(Color.WHITE)
            val paint = Paint().apply { color = Color.BLUE }
            drawRect(80f, 80f, 500f, 400f, paint)
            paint.color = Color.RED
            drawRect(650f, 500f, 1100f, 800f, paint)
        }
    }
    private fun mount(restoration: StateRestorationTester? = null) {
        val image = bitmap().asImageBitmap()
        val content: @Composable () -> Unit = {
            zoom = rememberImageZoomState(key.value)
            HapiTheme {
                ZoomableImage(image, "chart", zoom, if (landscape.value) Modifier.width(500.dp).height(300.dp)
                    else Modifier.width(350.dp).height(550.dp))
            }
        }
        if (restoration == null) compose.setContent(content) else restoration.setContent(content)
        compose.waitForIdle()
    }

    @Test fun doubleTapZoomPanAndFitControlWork() {
        mount()
        val image = compose.onNodeWithTag("image-zoom-viewport")
        val beforePixels = image.captureToImage().toPixelMap()
        val sampleX = (beforePixels.width * .2f).toInt()
        val sampleY = (beforePixels.height * .5f - beforePixels.width * .75f * .15f).toInt()
        assertTrue(beforePixels[sampleX, sampleY].blue > .9f && beforePixels[sampleX, sampleY].red < .1f)
        image.performTouchInput { doubleClick(center) }
        compose.runOnIdle { assertEquals(3f, zoom.zoom.scale, .01f) }
        val afterPixels = image.captureToImage().toPixelMap()
        assertTrue("Zoom must change the painted bitmap, not only its state", afterPixels[sampleX, sampleY].red > .9f)
        image.performTouchInput { swipe(center, center + Offset(100f, 0f), 400) }
        compose.runOnIdle { assertTrue(zoom.zoom.x > 0f) }
        compose.onNodeWithTag("image-zoom-fit").performClick()
        compose.runOnIdle { assertEquals(ImageZoom(), zoom.zoom) }
        image.performTouchInput { doubleClick(center); advanceEventTime(400); doubleClick(center) }
        compose.runOnIdle { assertEquals(1f, zoom.zoom.scale, .01f) }
    }

    @Test fun aRealTwoFingerPinchChangesScaleAndKeepsPanningInsideTheImage() {
        mount()
        val image = compose.onNodeWithTag("image-zoom-viewport")
        image.performTouchInput {
            val left = center - Offset(60f, 0f); val right = center + Offset(60f, 0f)
            down(0, left); down(1, right)
            for (step in 1..10) {
                moveTo(0, left - Offset(step * 12f, 0f))
                moveTo(1, right + Offset(step * 12f, 0f)); move(delayMillis = 20)
            }
            up(0); up(1)
        }
        compose.runOnIdle { assertTrue("Pinch must zoom the real image", zoom.zoom.scale > 1.5f) }
        image.performTouchInput { swipe(center, center + Offset(350f, 250f), 500) }
        compose.runOnIdle { assertTrue(zoom.zoom.x in -.5f.. .5f); assertTrue(zoom.zoom.y in -.5f.. .5f) }
    }

    @Test fun zoomAndFocalPointSurviveSavedStateAndViewportResize() {
        val restoration = StateRestorationTester(compose)
        mount(restoration)
        compose.onNodeWithTag("image-zoom-viewport").performTouchInput { doubleClick(center) }
        compose.onNodeWithTag("image-zoom-viewport").performTouchInput { swipe(center, center + Offset(50f, 15f), 400) }
        val before = compose.runOnIdle { zoom.zoom }
        restoration.emulateSavedInstanceStateRestore()
        compose.runOnIdle { assertEquals(before, zoom.zoom); landscape.value = true }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(before, zoom.zoom) }
        compose.onNodeWithTag("image-zoom-fit").assertIsDisplayed()
    }

    @Test fun aDifferentImageStartsAtFit() {
        mount()
        compose.onNodeWithTag("image-zoom-viewport").performTouchInput { doubleClick(center) }
        compose.runOnIdle { assertTrue(zoom.zoom.scale > 1); key.value = "image-b" }
        compose.runOnIdle { assertEquals(ImageZoom(), zoom.zoom) }
    }

    @Test fun theRealFileViewerUsesTheZoomViewportAndKeepsItsToolbar() {
        val bytes = ByteArrayOutputStream().also { bitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        var back = false
        compose.setContent {
            val scope = rememberCoroutineScope()
            val model = remember { FileViewerViewModel("session", "/Share/reports/chart.png", null, ViewerMode.FILE, null, ImageGateway(bytes), scope) }
            HapiTheme { FileViewerScreen(model, { back = true }) }
        }
        compose.waitUntil(10000) { compose.onAllNodesWithTag("image-zoom-viewport").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("image-zoom-viewport").performTouchInput { doubleClick(center) }
        compose.onNodeWithTag("image-zoom-fit").assertIsDisplayed()
        compose.onNodeWithContentDescription("Refresh").assertIsDisplayed()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.runOnIdle { assertTrue(back) }
    }

    @Test fun theFileViewerRestoresZoomAfterAsyncImageDecodeOnRecreation() {
        val bytes = ByteArrayOutputStream().also { bitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            val scope = rememberCoroutineScope()
            val model = remember { FileViewerViewModel("session", "/Share/reports/chart.png", null, ViewerMode.FILE, null, ImageGateway(bytes), scope) }
            HapiTheme { FileViewerScreen(model, {}) }
        }
        compose.waitUntil(10000) { compose.onAllNodesWithTag("image-zoom-viewport").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("image-zoom-viewport").performTouchInput { doubleClick(center) }
        compose.onNodeWithTag("image-zoom-viewport").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Zoom 300%"))
        restoration.emulateSavedInstanceStateRestore()
        compose.waitUntil(10000) { compose.onAllNodesWithTag("image-zoom-viewport").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("image-zoom-viewport").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Zoom 300%"))
    }

    private class ImageGateway(private val bytes: ByteArray) : FilesGateway {
        override suspend fun readFile(sessionId: String, path: String) = FileReadResponse(true, content = Base64.getEncoder().encodeToString(bytes))
        override suspend fun gitDiffFile(sessionId: String, path: String, staged: Boolean?) = GitCommandResponse(true, stdout = "")
        override suspend fun gitStatus(sessionId: String): GitCommandResponse = error("not used")
        override suspend fun gitDiffNumstat(sessionId: String, staged: Boolean): GitCommandResponse = error("not used")
        override suspend fun searchFiles(sessionId: String, query: String, limit: Int): FileSearchResponse = error("not used")
        override suspend fun listDirectory(sessionId: String, path: String?): ListDirectoryResponse = error("not used")
    }
}
