package app.hapi.companion.feature.files

import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.platform.app.InstrumentationRegistry
import app.hapi.companion.ui.markdown.LocalMarkdownLinkHandler
import app.hapi.companion.ui.markdown.Markdown
import app.hapi.companion.ui.markdown.MarkdownLinkHandler
import app.hapi.companion.ui.theme.HapiTheme
import app.hapi.protocol.markdown.HrefDecision
import java.io.ByteArrayOutputStream
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

@androidx.test.filters.SdkSuppress(minSdkVersion = 29)
class DocumentLinksTest {
    @get:Rule val compose = createComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val directory = "/home/mingfei/.codex/worktrees/09d4/Arbistrix/build.dev/key_charts"

    @Test fun pdfAndAllFourMarkdownImagesDispatchTheirOriginalSessionPaths() {
        val opened = mutableListOf<String>()
        val names = listOf("01_population_quality.png", "02_fill_selection.png", "03_daily_pnl_relationship.png", "04_holding_horizon.png")
        val labels = listOf("Population quality", "Fill selection", "Daily PnL relationship", "Holding horizon")
        val markdown = "[完整 PDF（4 页）]($directory/key_charts.pdf)\n\n" +
            names.zip(labels).joinToString("\n\n") { (file, label) -> "![$label]($directory/$file)" }
        val handler = object : MarkdownLinkHandler {
            override fun onFilePath(path: String, line: Int?) { opened += path }
            override fun onUrl(url: String, decision: HrefDecision) { fail("Local files must use the owning session Runner: $url") }
        }
        compose.setContent { HapiTheme {
            CompositionLocalProvider(LocalMarkdownLinkHandler provides handler) { Markdown(markdown, selectable = true) }
        } }
        (listOf("完整 PDF（4 页）") + labels).forEach(::clickText)
        compose.runOnIdle { assertEquals(listOf("$directory/key_charts.pdf") + names.map { "$directory/$it" }, opened) }
    }

    @Test fun linkedImagesKeepTheirOuterUrlAndBlockedImageSchemesRemainInert() {
        var opened: String? = null
        val handler = object : MarkdownLinkHandler {
            override fun onFilePath(path: String, line: Int?) { fail("Outer link owns the destination") }
            override fun onUrl(url: String, decision: HrefDecision) { opened = url }
        }
        compose.setContent { HapiTheme {
            CompositionLocalProvider(LocalMarkdownLinkHandler provides handler) {
                Markdown("[![Plot]($directory/plot.png)](https://example.com/report)\n\n![Blocked](javascript:bad)")
            }
        } }
        clickText("Plot")
        compose.runOnIdle { assertEquals("https://example.com/report", opened); opened = null }
        clickText("Blocked")
        compose.runOnIdle { assertNull(opened) }
    }

    private fun clickText(label: String) {
        val node = compose.onNodeWithText(label, substring = true)
        val layouts = mutableListOf<TextLayoutResult>()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        node.performTouchInput { click(layouts.single().getBoundingBox(1).center) }
    }

    private fun pdf(): ByteArray {
        val document = PdfDocument()
        try {
            for ((index, color) in listOf(Color.BLUE, Color.RED, Color.GREEN, Color.YELLOW).withIndex()) {
                val page = document.startPage(PdfDocument.PageInfo.Builder(600, 400, index + 1).create())
                page.canvas.drawColor(color)
                page.canvas.drawText("Page ${index + 1}", 50f, 80f, Paint().apply { this.color = Color.WHITE; textSize = 32f })
                document.finishPage(page)
            }
            return ByteArrayOutputStream().also(document::writeTo).toByteArray()
        } finally {
            document.close()
        }
    }

    @Test fun viewerHasSelectableTextSearchContinuousPagesAndDocumentZoom() {
        mountPdf()
        assertEquals("4", evaluate("PDFViewerApplication.pdfDocument.numPages"))
        compose.waitUntil(10_000) { evaluate("!!document.querySelector('.textLayer span')") == "true" }
        assertTrue(evaluate("document.querySelector('.textLayer').textContent").contains("Page 1"))
        val x = evaluate("(() => { const r=document.querySelector('.textLayer span').getBoundingClientRect(); return (r.left+r.width*.25)*devicePixelRatio; })()").toFloat()
        val y = evaluate("(() => { const r=document.querySelector('.textLayer span').getBoundingClientRect(); return (r.top+r.height/2)*devicePixelRatio; })()").toFloat()
        // WebView's native long-press timer uses the real clock, while Compose's
        // longClick helper advances synthetic event time without holding down.
        compose.onNodeWithTag("pdf-preview-ready").performTouchInput { down(androidx.compose.ui.geometry.Offset(x,y)) }
        android.os.SystemClock.sleep(800)
        compose.onNodeWithTag("pdf-preview-ready").performTouchInput { up() }
        compose.waitUntil(10_000) { evaluate("getSelection().toString().length").toInt() > 0 }

        assertTrue(evaluate("(() => { const r = document.createRange(); r.selectNodeContents(document.querySelector('.textLayer')); const s = getSelection(); s.removeAllRanges(); s.addRange(r); return s.toString(); })()").contains("Page 1"))
        evaluate("getSelection().removeAllRanges(); PDFViewerApplication.eventBus.dispatch('find', {source:window, type:'', query:'Page', phraseSearch:true, caseSensitive:false, entireWord:false, highlightAll:true, findPrevious:false}); true")
        compose.waitUntil(10_000) { evaluate("PDFViewerApplication.findController.pageMatches.reduce((n,p)=>n+p.length,0)").toInt() == 4 }
        val before = evaluate("PDFViewerApplication.pdfViewer.currentScale").toFloat()
        evaluate("PDFViewerApplication.pdfViewer.currentScale = " + (before * 2))
        compose.waitUntil(10_000) { evaluate("PDFViewerApplication.pdfViewer.currentScale").toFloat() > before * 1.9f }
        evaluate("PDFViewerApplication.page = 4")
        compose.waitUntil(10_000) { evaluate("PDFViewerApplication.page") == "4" }
        assertEquals("4", evaluate("document.querySelectorAll('.page').length"))
        compose.runOnUiThread { assertFalse(webView().settings.allowFileAccess); assertFalse(webView().settings.allowContentAccess) }
        assertEquals("false", evaluate("PDFViewerApplicationOptions.get('enableScripting')"))
    }

    @Test fun pdfPageZoomAndPositionSurviveRecreation() {
        val content = FileContentUiState.Pdf(pdf())
        val restoration = StateRestorationTester(compose)
        restoration.setContent { HapiTheme { PdfFilePreview(content, "$directory/key_charts.pdf") } }
        waitForPage()
        evaluate("PDFViewerApplication.pdfViewer.currentScale = 1.75; PDFViewerApplication.page = 3; true")
        compose.waitUntil(10_000) { evaluate("PDFViewerApplication.page") == "3" }
        // updateviewarea is debounced by the viewer.
        compose.waitUntil(10_000) { evaluate("PDFViewerApplication.pdfViewer._location.pdfOpenParams").contains("page=3") }
        restoration.emulateSavedInstanceStateRestore()
        waitForPage()
        compose.waitUntil(10_000) { evaluate("PDFViewerApplication.page") == "3" }
        assertEquals(1.75f, evaluate("PDFViewerApplication.pdfViewer.currentScale").toFloat(), .03f)
    }

    @Test fun malformedPdfShowsAnErrorInsteadOfCrashing() {
        compose.setContent { HapiTheme { PdfFilePreview(FileContentUiState.Pdf("not a PDF".toByteArray()), "broken.pdf") } }
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("pdf-preview-error").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("pdf-preview-error").assertIsDisplayed()
    }

    private fun mountPdf() {
        val content = FileContentUiState.Pdf(pdf())
        compose.setContent { HapiTheme { PdfFilePreview(content, "$directory/key_charts.pdf") } }
        waitForPage()
    }
    private fun webView(): android.webkit.WebView {
        fun find(view: android.view.View): List<android.webkit.WebView> = when(view) {
            is android.webkit.WebView -> listOf(view)
            is android.view.ViewGroup -> (0 until view.childCount).flatMap { find(view.getChildAt(it)) }
            else -> emptyList()
        }
        return android.view.inspector.WindowInspector.getGlobalWindowViews().flatMap(::find).single { it.isShown }
    }
    private fun evaluate(script: String): String {
        val result = java.util.concurrent.atomic.AtomicReference<String?>(null)
        compose.runOnUiThread { webView().evaluateJavascript(script) { result.set(it) } }
        compose.waitUntil(10_000) { result.get() != null }
        return result.get()!!
    }

    private fun waitForPage() {
        compose.waitUntil(30_000) { compose.onAllNodesWithTag("pdf-preview-ready").fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
    }
}
