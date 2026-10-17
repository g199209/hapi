package app.hapi.companion.ui.markdown

import android.content.ClipboardManager
import android.webkit.WebView
import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import android.util.Log
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import app.hapi.companion.R
import app.hapi.companion.ui.theme.HapiTheme
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONTokener
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

@SdkSuppress(minSdkVersion = 29)
class MermaidRenderingTest {
    @get:Rule val compose = createComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val screenshot = """flowchart LR
    P[Python API] -->|提交 native command| R[Rust Client / Tokio]
    R --> Q[Native completion queue]
    Q -->|eventfd notification| E[Python event loop]
    E -->|批量消费| F[完成 Future / 执行 callback]"""

    private fun mount(code: String, dark: Boolean = false) {
        compose.setContent {
            HapiTheme(darkTheme = dark, dynamicColor = false) {
                Surface {
                    Column(Modifier.width(320.dp).verticalScroll(rememberScrollState())) {
                        Markdown("Before\n\n```mermaid\n$code\n```\n\nAfter", selectable = true)
                    }
                }
            }
        }
    }

    private fun awaitDiagram() {
        compose.waitUntil(45_000) {
            compose.onAllNodesWithTag("mermaid-rendered").fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun evaluate(script: String): String {
        val result = AtomicReference<String?>(null)
        compose.runOnUiThread {
            fun webViews(view: View): List<WebView> = when (view) {
                is WebView -> listOf(view)
                is ViewGroup -> (0 until view.childCount).flatMap { webViews(view.getChildAt(it)) }
                else -> emptyList()
            }
            val view = WindowInspector.getGlobalWindowViews().flatMap(::webViews)
                .single { it.isShown && it.hasWindowFocus() }
            view.evaluateJavascript(script) { result.set(it) }
        }
        compose.waitUntil(10_000) { result.get() != null }
        return result.get()!!
    }

    private fun awaitVisualState() {
        val complete = java.util.concurrent.atomic.AtomicBoolean(false)
        compose.runOnUiThread {
            fun webViews(view: View): List<WebView> = when (view) {
                is WebView -> listOf(view)
                is ViewGroup -> (0 until view.childCount).flatMap { webViews(view.getChildAt(it)) }
                else -> emptyList()
            }
            WindowInspector.getGlobalWindowViews().flatMap(::webViews).single { it.isShown && it.hasWindowFocus() }
                .postVisualStateCallback(1, object : WebView.VisualStateCallback() {
                    override fun onComplete(requestId: Long) { complete.set(true) }
                })
        }
        compose.waitUntil(10_000) { complete.get() }
    }

    @Test fun screenshotFlowchartRendersChineseNodesAndEdgesOffline() {
        mount(screenshot)
        awaitDiagram()
        assertEquals("5", evaluate("document.querySelectorAll('svg .node').length"))
        assertEquals("4", evaluate("document.querySelectorAll('svg .edgePaths path').length"))
        val labels = JSONTokener(evaluate("document.querySelector('svg').textContent")).nextValue().toString()
        assertTrue(labels.contains("Python API"))
        assertTrue(labels.contains("提交 native command"))
        assertTrue(labels.contains("批量消费"))
        File(context.filesDir, "mermaid-raw.svg").writeText(requireNotNull(MermaidCache.get(screenshot, false)))
        awaitVisualState()
        assertTrue(evaluate("document.querySelector('svg').getBoundingClientRect().height").toDouble() > 20)
        Log.d("HapiMermaidTest", evaluate("JSON.stringify({svg:document.querySelector('svg').getBoundingClientRect().toJSON(),width:innerWidth,height:innerHeight,style:document.querySelector('svg').getAttribute('style'),viewBox:document.querySelector('svg').getAttribute('viewBox'),computed:getComputedStyle(document.querySelector('svg')).height})"))
        InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot().let { bitmap ->
            File(context.filesDir, "mermaid-preview.png").outputStream().use {
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
        }
    }

    @Test fun sourceToggleKeepsOriginalCopyAndReturnsToCachedDiagram() {
        val code = "flowchart TD\nA[Copy original source] --> B[Done]"
        mount(code)
        awaitDiagram()
        compose.onNodeWithText(context.getString(R.string.mermaid_source)).performClick()
        compose.onNodeWithText(code, substring = true).assertExists()
        compose.onNodeWithContentDescription(context.getString(R.string.chat_copy_full_content)).performClick()
        compose.runOnIdle {
            assertEquals(code, context.getSystemService(ClipboardManager::class.java).primaryClip!!.getItemAt(0).text.toString())
        }
        compose.onNodeWithText(context.getString(R.string.mermaid_diagram)).performClick()
        awaitDiagram()
        assertEquals("\"undefined\"", evaluate("typeof mermaid"))
        assertTrue(evaluate("document.querySelector('svg').getBoundingClientRect().height").toDouble() > 20)
    }

    @Test fun fullscreenUsesCachedSVGEnablesZoomAndClosesBackToSameMessage() {
        mount("sequenceDiagram\nparticipant P as Python\nparticipant R as Rust\nP->>R: command\nR-->>P: completion")
        awaitDiagram()
        compose.onNodeWithContentDescription(context.getString(R.string.mermaid_fullscreen)).performClick()
        compose.onNodeWithTag("mermaid-viewer").assertIsDisplayed()
        compose.waitUntil(20_000) { compose.onAllNodesWithTag("mermaid-rendered").fetchSemanticsNodes().size == 2 }
        assertEquals("\"undefined\"", evaluate("typeof mermaid"))
        assertTrue(evaluate("document.querySelector('meta[name=viewport]').content").contains("user-scalable=yes"))
        val initialScale = evaluate("window.visualViewport.scale").toDouble()
        compose.onNode(hasTestTag("mermaid-rendered") and hasAnyAncestor(hasTestTag("mermaid-viewer")))
            .performTouchInput {
                pinch(
                    start0 = Offset(center.x * 0.8f, center.y * 0.8f),
                    end0 = Offset(center.x * 0.2f, center.y * 0.5f),
                    start1 = Offset(center.x * 1.2f, center.y * 1.2f),
                    end1 = Offset(center.x * 1.8f, center.y * 1.5f),
                )
            }
        awaitVisualState()
        assertTrue(evaluate("window.visualViewport.scale").toDouble() > initialScale)
        compose.onNodeWithContentDescription(context.getString(R.string.mermaid_close)).performClick()
        compose.onNodeWithTag("mermaid-viewer").assertDoesNotExist()
        compose.onNodeWithText("After").assertExists()
    }

    @Test fun invalidStreamingSourceRecoversWhenTheCodeChanges() {
        val code = mutableStateOf("flowchart LR\nA -->")
        compose.setContent { HapiTheme { Surface { Markdown("```mermaid\n${code.value}\n```", selectable = true) } } }
        compose.waitUntil(45_000) { compose.onAllNodesWithText(context.getString(R.string.mermaid_error)).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(code.value, substring = true).assertExists()
        compose.runOnIdle { code.value = "flowchart LR\nA[Recovered] --> B[Latest]" }
        awaitDiagram()
        compose.onNodeWithText(context.getString(R.string.mermaid_error)).assertDoesNotExist()
        assertTrue(evaluate("document.querySelector('svg').textContent").contains("Latest"))
    }

    @Test fun darkThemeAndDiagramTextCannotEscapeIntoHTML() {
        mount("flowchart TD\nA[\"中文 &lt;script&gt; body\"] --> B[Dark diagram]", dark = true)
        awaitDiagram()
        assertEquals("true", evaluate("document.querySelector('svg') !== null"))
        assertEquals("null", evaluate("document.querySelector('script:not([src]):not(:last-child)')"))
    }
}
