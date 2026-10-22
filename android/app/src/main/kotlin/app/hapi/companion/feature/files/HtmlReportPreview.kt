package app.hapi.companion.feature.files

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.hapi.protocol.wire.FileReadResponse
import java.io.ByteArrayInputStream
import java.net.URLConnection
import java.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/** A full-height browser owns pan/pinch/selection; no Compose scroll parent. */
@SuppressLint("SetJavaScriptEnabled")
@Composable
internal fun HtmlReportPreview(
    path: String,
    html: String,
    readResource: suspend (String) -> FileReadResponse,
    onOpenFile: (String) -> Unit = {},
    onOpenUrl: (String) -> Unit = {},
    modifier: Modifier = Modifier,
    savedState: Bundle? = null,
) {
    val document = remember(path, html) { HtmlReportDocument(path, html) }
    val fallbackState = rememberSaveable(path, html.hashCode()) { Bundle() }
    val saved = savedState ?: fallbackState
    val latestRead by rememberUpdatedState(readResource)
    val latestFile by rememberUpdatedState(onOpenFile)
    val latestUrl by rememberUpdatedState(onOpenUrl)
    val requests = remember(document) { SupervisorJob() }
    var browser by remember(document) { mutableStateOf<WebView?>(null) }
    var ready by remember(document) { mutableStateOf(false) }
    var error by remember(document) { mutableStateOf<String?>(null) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, browser) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) browser?.saveState(saved)
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    DisposableEffect(requests) { onDispose { requests.cancel() } }

    Box(modifier.testTag(if (ready) "html-report-ready" else "html-report-loading")) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context ->
                WebView(context).apply {
                    layoutParams = ViewGroup.LayoutParams(-1, -1)
                    settings.apply {
                        javaScriptEnabled = true
                        allowFileAccess = false
                        allowContentAccess = false
                        domStorageEnabled = false
                        mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                        cacheMode = WebSettings.LOAD_NO_CACHE
                        setSupportZoom(true)
                        builtInZoomControls = true
                        displayZoomControls = false
                        useWideViewPort = true
                        loadWithOverviewMode = true
                    }
                    webViewClient = object : WebViewClient() {
                        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse {
                            val resourceUrl = request.url.toString()
                            val resource = document.resourcePath(resourceUrl)
                            if (request.method != "GET" || resource == null) return response(null)
                            if (resource == document.resourcePath(document.url)) {
                                return response(document.content.toByteArray(), "text/html")
                            }
                            return try {
                                val result = runBlocking(requests + Dispatchers.IO) {
                                    withTimeout(15_000) { latestRead(resource) }
                                }
                                val bytes = if (result.success && result.content != null) Base64.getDecoder().decode(result.content) else null
                                val mime = when (resource.substringAfterLast('.').lowercase()) {
                                    "css" -> "text/css"
                                    "js", "mjs" -> "application/javascript"
                                    "svg" -> "image/svg+xml"
                                    else -> URLConnection.guessContentTypeFromName(resource) ?: "application/octet-stream"
                                }
                                response(bytes, mime)
                            } catch (_: Exception) { response(null) }
                        }
                        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                            val target = request.url.toString()
                            if (target.substringBefore('#') == document.url) return false
                            if (request.hasGesture()) {
                                val file = document.resourcePath(target)
                                if (file != null) latestFile(file) else latestUrl(target)
                            }
                            return true
                        }
                        override fun onPageFinished(view: WebView, url: String) {
                            view.postVisualStateCallback(1, object : WebView.VisualStateCallback() {
                                override fun onComplete(requestId: Long) { ready = true }
                            })
                        }
                        override fun onReceivedError(view: WebView, request: WebResourceRequest, failure: WebResourceError) {
                            if (request.isForMainFrame) error = failure.description.toString()
                        }
                        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                            error = "HTML renderer stopped"
                            return true
                        }
                    }
                    browser = this
                    if (restoreState(saved) == null) loadUrl(document.url)
                }
            },
            onRelease = { view ->
                view.saveState(saved)
                view.stopLoading()
                view.destroy()
                browser = null
            },
        )
        if (!ready && error == null) CircularProgressIndicator(Modifier.align(Alignment.Center))
        error?.let { Text(it, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error) }
    }
}

private fun response(bytes: ByteArray?, mime: String = "text/plain") = WebResourceResponse(
    mime, "UTF-8", if (bytes == null) 404 else 200, if (bytes == null) "Not Found" else "OK",
    mapOf("Cache-Control" to "no-store"), ByteArrayInputStream(bytes ?: byteArrayOf()),
)
