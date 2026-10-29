package app.hapi.companion.feature.files

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import app.hapi.companion.R
import java.io.ByteArrayInputStream
import java.net.URI
import java.net.URLConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Only packaged viewer assets and this original PDF are available to the browser. */
internal object PdfViewerResources {
    const val ORIGIN = "https://hapi-pdf.invalid"
    const val VIEWER = "$ORIGIN/web/viewer.html?file=%2Fdocument.pdf"

    fun path(url: String): String? = try {
        val uri = URI(url)
        val path = uri.path
        if (uri.scheme != "https" || uri.rawAuthority != "hapi-pdf.invalid" ||
            path.split('/').any { it == ".." || it == "." } ||
            !(path == "/document.pdf" || path.startsWith("/web/") || path.startsWith("/build/"))) null
        else path
    } catch (_: java.net.URISyntaxException) { null }
}

/** Packaged viewer callbacks only; PDF JavaScript is disabled in hapi.js. */
internal class PdfViewerBridge(
    private val post: (() -> Unit) -> Unit,
    private val ready: () -> Unit,
    private val error: () -> Unit,
    private val state: (String) -> Unit,
    private val download: () -> Unit,
) {
    @JavascriptInterface fun onReady() = post(ready)
    @JavascriptInterface fun onError() = post(error)
    @JavascriptInterface fun onViewState(location: String) {
        if (location.startsWith("#page=") && location.length < 512) post { state(location) }
    }
    @JavascriptInterface fun onDownload() = post(download)
}

/** Full PDF.js reader, with selectable text, find, continuous pages and vector re-rendering. */
@SuppressLint("SetJavaScriptEnabled")
@Composable
internal fun PdfFilePreview(
    content: FileContentUiState.Pdf,
    path: String,
    modifier: Modifier = Modifier,
    onOpenUrl: (String) -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val documentKey = remember(path, content) { "$path:${content.bytes.contentHashCode()}" }
    val saved = rememberSaveable(documentKey) { Bundle() }
    val latestUrl by rememberUpdatedState(onOpenUrl)
    val latestBytes by rememberUpdatedState(content.bytes)
    val saveFailed = stringResource(R.string.files_pdf_save_failed)
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        if (uri != null) scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { requireNotNull(context.contentResolver.openOutputStream(uri)).use { it.write(latestBytes) } }
            }
            if (result.isFailure) Toast.makeText(context, saveFailed, Toast.LENGTH_LONG).show()
        }
    }
    var ready by remember(content) { mutableStateOf(false) }
    var failed by remember(content) { mutableStateOf(false) }
    Box(modifier.testTag(if (ready) "pdf-preview-ready" else "pdf-preview-loading")) {
        key(content) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { browserContext ->
                    WebView(browserContext).apply {
                        layoutParams = ViewGroup.LayoutParams(-1, -1)
                        settings.apply {
                            javaScriptEnabled = true
                            allowFileAccess = false
                            allowContentAccess = false
                            domStorageEnabled = true
                            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                            cacheMode = WebSettings.LOAD_NO_CACHE
                            // PDF.js owns pinch zoom and re-renders the document.
                            setSupportZoom(false)
                        }
                        addJavascriptInterface(PdfViewerBridge(
                            post = { action -> post { action() } },
                            ready = { ready = true }, error = { failed = true },
                            state = { saved.putString("location", it) },
                            download = { save.launch(path.substringAfterLast('/')) },
                        ), "HapiPdf")
                        webViewClient = object : WebViewClient() {
                            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse {
                                val resource = PdfViewerResources.path(request.url.toString())
                                if (request.method != "GET" || resource == null) return pdfResponse(null)
                                if (resource == "/document.pdf") return pdfResponse(content.bytes, "application/pdf")
                                return try {
                                    val mime = when (resource.substringAfterLast('.')) {
                                        "mjs", "js" -> "application/javascript"
                                        "css" -> "text/css"
                                        "wasm" -> "application/wasm"
                                        "ftl" -> "text/plain"
                                        else -> URLConnection.guessContentTypeFromName(resource) ?: "application/octet-stream"
                                    }
                                    val asset = if (resource.startsWith("/build/")) "/lib/" + resource.removePrefix("/build/") else resource
                                    pdfResponse(context.assets.open("pdfjs$asset").use { it.readBytes() }, mime)
                                } catch (_: java.io.IOException) { pdfResponse(null) }
                            }
                            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                                val url = request.url.toString()
                                if (url.substringBefore('#') == PdfViewerResources.VIEWER) return false
                                if (request.hasGesture()) latestUrl(url)
                                return true
                            }
                            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                                failed = true
                                return true
                            }
                        }
                        loadUrl(PdfViewerResources.VIEWER + (saved.getString("location") ?: "#zoom=page-width"))
                    }
                },
                onRelease = { view ->
                    view.removeJavascriptInterface("HapiPdf")
                    view.stopLoading()
                    view.destroy()
                },
            )
        }
        if (!ready && !failed) CircularProgressIndicator(Modifier.align(Alignment.Center))
        if (failed) Text(stringResource(R.string.files_pdf_failed),
            color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp).testTag("pdf-preview-error"))
    }
}

private fun pdfResponse(bytes: ByteArray?, mime: String = "text/plain") = WebResourceResponse(
    mime, "UTF-8", if (bytes == null) 404 else 200, if (bytes == null) "Not Found" else "OK",
    mapOf("Cache-Control" to "no-store"), ByteArrayInputStream(bytes ?: byteArrayOf()),
)
