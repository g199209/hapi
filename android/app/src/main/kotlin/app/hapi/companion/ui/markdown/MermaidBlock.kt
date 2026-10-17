package app.hapi.companion.ui.markdown

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebChromeClient
import android.webkit.ConsoleMessage
import android.webkit.RenderProcessGoneDetail
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.hapi.companion.R
import app.hapi.companion.ui.components.FullTextAction
import app.hapi.companion.ui.theme.hapi
import java.io.ByteArrayInputStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.delay

internal class MermaidBridge(
    private val onRendered: (String, Float, Float) -> Unit,
    private val onError: (String) -> Unit,
) {
    private val active = AtomicBoolean(true)
    private val main = Handler(Looper.getMainLooper())

    @JavascriptInterface
    fun rendered(svg: String, width: Double, height: Double) {
        main.post {
            if (active.get()) {
                if (svg.isNotBlank() && width.isFinite() && height.isFinite() && width > 0 && height > 0) {
                    onRendered(svg, width.toFloat(), height.toFloat())
                } else onError("Diagram produced invalid dimensions")
            }
        }
    }

    @JavascriptInterface
    fun failed(message: String) {
        Log.w("HapiMermaid", message.take(400))
        main.post { if (active.get()) onError(message.take(400)) }
    }

    fun close() { active.set(false); main.removeCallbacksAndMessages(null) }
}

/** Only this APK's one script may be loaded. Diagram views have no Hub token,
 * file/content access or external navigation, including in the fullscreen view. */
internal class MermaidAssetClient(
    private val document: String,
    private val openAsset: () -> java.io.InputStream,
    private val onFailure: (String) -> Unit,
) : WebViewClient() {
    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse {
        val url = request.url.toString()
        Log.d("HapiMermaid", "Local resource: $url")
        return when {
            request.method == "GET" && url == "$MERMAID_ORIGIN/mermaid/" ->
                WebResourceResponse("text/html", "UTF-8", 200, "OK", mapOf("Cache-Control" to "no-store"), ByteArrayInputStream(document.toByteArray(Charsets.UTF_8)))
            request.method == "GET" && url == MERMAID_ORIGIN + MERMAID_ASSET ->
                WebResourceResponse("application/javascript", "UTF-8", 200, "OK", mapOf("Cache-Control" to "no-store"), openAsset())
            else -> WebResourceResponse("text/plain", "UTF-8", 403, "Blocked", emptyMap(), ByteArrayInputStream(byteArrayOf()))
        }
    }

    override fun onPageFinished(view: WebView, url: String) {
        Log.d("HapiMermaid", "Local document loaded")
        view.evaluateJavascript("JSON.stringify({state:document.readyState,engine:typeof mermaid,bridge:typeof NativeMermaid,svg:!!document.querySelector('svg')})") {
            Log.d("HapiMermaid", "Document state: $it")
        }
    }

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = true

    override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
        onFailure("Diagram renderer stopped")
        return true
    }
}

@Composable
internal fun MermaidBlock(code: String, selectionSuffix: String) {
    val colors = MaterialTheme.hapi
    var sourceView by rememberSaveable(code) { mutableStateOf(false) }
    var fullscreen by rememberSaveable(code) { mutableStateOf(false) }
    DisableSelection {
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(colors.codeBackground)) {
            Row(
                Modifier.fillMaxWidth().background(colors.codeHeaderBackground).padding(start = 12.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("MERMAID", Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, color = colors.codeHeaderForeground)
                TextButton(onClick = { sourceView = !sourceView }) {
                    Text(stringResource(if (sourceView) R.string.mermaid_diagram else R.string.mermaid_source))
                }
                IconButton(onClick = { fullscreen = true }) {
                    Icon(painterResource(R.drawable.ic_diagram_fullscreen), stringResource(R.string.mermaid_fullscreen))
                }
                FullTextAction(code, compact = true)
            }
            if (sourceView) {
                MermaidSource(code, selectionSuffix)
            } else {
                MermaidPreview(code, onOpen = { fullscreen = true })
            }
        }
    }
    if (fullscreen) MermaidViewer(code, onDismiss = { fullscreen = false })
}

@Composable
private fun MermaidSource(code: String, suffix: String = "") {
    androidx.compose.foundation.text.selection.SelectionContainer {
        Box(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
            MarkdownSelectionText(
                AnnotatedString(code), suffix = suffix, softWrap = false,
                style = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, fontSize = 14.sp, lineHeight = 20.sp),
                modifier = Modifier.padding(12.dp),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MermaidViewer(code: String, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Scaffold(
            modifier = Modifier.fillMaxSize().testTag("mermaid-viewer"),
            topBar = {
                TopAppBar(
                    title = { Text(stringResource(R.string.mermaid_diagram)) },
                    navigationIcon = {
                        IconButton(onClick = onDismiss) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.mermaid_close)) }
                    },
                    actions = { FullTextAction(code, compact = true) },
                )
            },
        ) { padding -> MermaidPreview(code, Modifier.fillMaxSize().padding(padding), expanded = true) }
    }
}

@Composable
private fun MermaidPreview(code: String, modifier: Modifier = Modifier, expanded: Boolean = false, onOpen: () -> Unit = {}) {
    val dark = MaterialTheme.hapi.isDark
    val available = remember { runCatching { WebView.getCurrentWebViewPackage() != null }.getOrDefault(false) }
    var ready by remember(code, dark) { mutableStateOf(false) }
    var error by remember(code, dark) { mutableStateOf(if (available) null else "Android WebView is unavailable") }
    var diagramWidth by remember(code, dark) { mutableFloatStateOf(0f) }
    var diagramHeight by remember(code, dark) { mutableFloatStateOf(0f) }
    var settled by remember(code, dark) { mutableStateOf(false) }
    LaunchedEffect(code, dark) {
        if (MermaidCache.get(code, dark) == null) delay(300)
        settled = true
    }
    LaunchedEffect(code, dark, settled) {
        if (settled) {
            delay(20_000)
            if (!ready && error == null) error = "Diagram rendering timed out"
        }
    }
    if (error != null) {
        Column(if (expanded) modifier.verticalScroll(rememberScrollState()) else modifier) {
            Text(stringResource(R.string.mermaid_error), Modifier.padding(12.dp), color = MaterialTheme.colorScheme.error)
            Text(error.orEmpty(), Modifier.padding(horizontal = 12.dp), style = MaterialTheme.typography.bodySmall)
            MermaidSource(code)
        }
        return
    }
    BoxWithConstraints(modifier.fillMaxWidth()) {
        // Keep inline labels readable and let Compose handle horizontal/vertical
        // gestures. Fullscreen uses the browser's pinch zoom and pan instead.
        val previewWidth = maxOf(maxWidth.value, (diagramWidth * 0.75f + 16).coerceAtMost(2000f))
        val previewHeight = if (diagramWidth > 0) (diagramHeight * (previewWidth - 16) / diagramWidth + 16).coerceIn(96f, 436f) else 140f
        val container = if (expanded) Modifier.fillMaxSize() else Modifier
            .fillMaxWidth().height(previewHeight.dp).testTag("mermaid-preview").horizontalScroll(rememberScrollState()).clickable(onClick = onOpen)
        Box(container) {
            if (settled) key(code, dark, expanded) {
                MermaidWebView(
                    code, dark, expanded,
                    modifier = (if (expanded) Modifier.fillMaxSize() else Modifier.width(previewWidth.dp).fillMaxHeight())
                        .testTag(if (ready) "mermaid-rendered" else "mermaid-loading"),
                    onRendered = { svg, width, height ->
                        MermaidCache.put(code, dark, svg)
                        diagramWidth = width
                        diagramHeight = height
                        ready = true
                    },
                    onError = { error = it },
                )
            }
            if (!ready) CircularProgressIndicator(Modifier.align(Alignment.Center).padding(20.dp).size(24.dp))
        }
    }
}

@SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
@Composable
private fun MermaidWebView(
    code: String, dark: Boolean, expanded: Boolean, modifier: Modifier,
    onRendered: (String, Float, Float) -> Unit, onError: (String) -> Unit,
) {
    val context = LocalContext.current
    val background = MaterialTheme.hapi.codeBackground.toArgb()
    AndroidView(
        modifier = modifier,
        factory = {
            object : WebView(context) {
                override fun onTouchEvent(event: MotionEvent): Boolean = if (expanded) super.onTouchEvent(event) else false
            }.apply {
                val bridge = MermaidBridge(onRendered, onError)
                tag = bridge
                setBackgroundColor(background)
                settings.javaScriptEnabled = true
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                settings.textZoom = 100
                settings.setSupportZoom(expanded)
                settings.builtInZoomControls = expanded
                settings.displayZoomControls = false
                settings.useWideViewPort = true
                settings.loadWithOverviewMode = true
                isVerticalScrollBarEnabled = expanded
                isHorizontalScrollBarEnabled = expanded
                isLongClickable = false
                webViewClient = MermaidAssetClient(
                    mermaidDocument(code, dark, expanded, MermaidCache.get(code, dark)),
                    { context.assets.open("mermaid/mermaid.min.js") }, bridge::failed,
                )
                webChromeClient = object : WebChromeClient() {
                    override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                        if (message.messageLevel() == ConsoleMessage.MessageLevel.ERROR) Log.w("HapiMermaid", message.message())
                        return true
                    }
                }
                addJavascriptInterface(bridge, "NativeMermaid")
                loadUrl("$MERMAID_ORIGIN/mermaid/")
            }
        },
        onRelease = {
            (it.tag as? MermaidBridge)?.close()
            it.stopLoading()
            it.removeJavascriptInterface("NativeMermaid")
            it.destroy()
        },
    )
}
