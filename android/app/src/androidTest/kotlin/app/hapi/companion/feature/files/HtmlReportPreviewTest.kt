package app.hapi.companion.feature.files

import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import android.webkit.WebView
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.filters.SdkSuppress
import app.hapi.companion.ui.markdown.Markdown
import app.hapi.companion.ui.markdown.LocalMarkdownLinkHandler
import app.hapi.companion.ui.markdown.MarkdownLinkHandler
import app.hapi.companion.ui.theme.HapiTheme
import app.hapi.protocol.markdown.HrefDecision
import app.hapi.protocol.wire.FileReadResponse
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

@SdkSuppress(minSdkVersion = 29)
class HtmlReportPreviewTest {
    @get:Rule val compose = createComposeRule()
    private val requests = CopyOnWriteArrayList<String>()
    private fun mount() {
        val html = """<!doctype html><html><head><link rel="stylesheet" href="style.css"></head><body>
            <h1 id="title">Optimizer convergence 中文</h1><img id="plot" src="curve.svg">
            <script src="report.js"></script><div style="height:3000px">Results</div></body></html>"""
        compose.setContent { HapiTheme { HtmlReportPreview("/Share/reports/report.html", html, readResource = { path ->
            requests += path
            val text = when (path.substringAfterLast('/')) {
                "style.css" -> "h1 { color: rgb(1, 2, 3) }"
                "curve.svg" -> "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"120\" height=\"80\"><rect width=\"120\" height=\"80\" fill=\"blue\"/></svg>"
                "report.js" -> "window.reportReady = true;"
                else -> error("Unexpected resource $path")
            }
            FileReadResponse(success = true, content = Base64.getEncoder().encodeToString(text.toByteArray()))
        }, modifier = Modifier.fillMaxSize()) } }
        compose.waitUntil(30_000) { compose.onAllNodesWithTag("html-report-ready").fetchSemanticsNodes().isNotEmpty() }
    }
    private fun webView(): WebView {
        fun find(view: View): List<WebView> = when (view) {
            is WebView -> listOf(view)
            is ViewGroup -> (0 until view.childCount).flatMap { find(view.getChildAt(it)) }
            else -> emptyList()
        }
        return WindowInspector.getGlobalWindowViews().flatMap(::find).single { it.isShown }
    }
    private fun evaluate(script: String): String {
        val result = AtomicReference<String?>(null)
        compose.runOnUiThread { webView().evaluateJavascript(script) { result.set(it) } }
        compose.waitUntil(10_000) { result.get() != null }
        return result.get()!!
    }
    @Test fun rendersRemoteResourcesAndChineseWithBrowserScrollingAndZoom() {
        mount()
        assertTrue(requests.containsAll(listOf("/Share/reports/style.css", "/Share/reports/curve.svg", "/Share/reports/report.js")))
        assertEquals("120", evaluate("document.getElementById('plot').naturalWidth"))
        assertEquals("true", evaluate("window.reportReady === true"))
        assertEquals("\"rgb(1, 2, 3)\"", evaluate("getComputedStyle(document.getElementById('title')).color"))
        assertTrue(evaluate("document.body.textContent").contains("中文"))
        compose.runOnUiThread { assertTrue(webView().settings.builtInZoomControls) }
        evaluate("scrollTo(0, 600)")
        compose.waitUntil(5_000) { evaluate("scrollY").toInt() > 0 }
        compose.runOnUiThread { assertTrue(webView().zoomIn()) }
    }
    @Test fun reportHasNoHubCredentialsOrNativeFileAccess() {
        mount()
        assertEquals("\"hapi-report.invalid\"", evaluate("location.host"))
        assertEquals("\"\"", evaluate("document.cookie"))
        compose.runOnUiThread {
            assertFalse(webView().settings.allowFileAccess)
            assertFalse(webView().settings.allowContentAccess)
        }
    }
    @Test fun originalAbsoluteMarkdownLinkDispatchesToTheSessionFileViewer() {
        var opened: String? = null
        val path = "/Share/mingfei/diagnostics/optimizer_convergence_20261022/report.html"
        val handler = object : MarkdownLinkHandler {
            override fun onFilePath(path: String, line: Int?) { opened = path }
            override fun onUrl(url: String, decision: HrefDecision) { fail("File must route to the session Runner") }
        }
        compose.setContent { HapiTheme {
            CompositionLocalProvider(LocalMarkdownLinkHandler provides handler) { Markdown("[查看完整收敛曲线和分段结果]($path)", selectable = true) }
        } }
        val node = compose.onNodeWithText("查看完整收敛曲线和分段结果")
        val layouts = mutableListOf<TextLayoutResult>()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        node.performTouchInput { click(layouts.single().getBoundingBox(3).center) }
        compose.runOnIdle { assertEquals(path, opened) }
    }
}
