package app.hapi.companion.feature.files

import java.net.URI

/** Synthetic origin maps resources back to this session's Runner, never the Hub. */
internal class HtmlReportDocument(val path: String, html: String) {
    private val relativePrefix = "/_hapi_workspace_/"
    private val normalized = path.replace('\\', '/')
    private val absolute = normalized.startsWith('/') || Regex("^[A-Za-z]:/").containsMatchIn(normalized)
    val url: String = URI("https", "hapi-report.invalid",
        if (absolute) "/${normalized.trimStart('/')}" else relativePrefix + normalized, null, null).toASCIIString()
    val content: String = withViewport(html)

    fun resourcePath(url: String): String? = try {
        val uri = URI(url)
        if (uri.scheme != "https" || uri.rawAuthority != "hapi-report.invalid") null
        else {
            val target = uri.path
            when {
                target.startsWith(relativePrefix) -> target.removePrefix(relativePrefix)
                Regex("^/[A-Za-z]:/").containsMatchIn(target) -> target.removePrefix("/")
                else -> target
            }.takeIf { it.isNotBlank() }
        }
    } catch (_: java.net.URISyntaxException) { null }

    private fun withViewport(html: String): String {
        if (Regex("<meta\\b[^>]*\\bname\\s*=\\s*['\"]?viewport\\b", RegexOption.IGNORE_CASE).containsMatchIn(html)) return html
        val meta = "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">"
        val head = Regex("<head\\b[^>]*>", RegexOption.IGNORE_CASE).find(html)
            ?: Regex("<!doctype[^>]*>", RegexOption.IGNORE_CASE).find(html)
        val position = head?.range?.last?.plus(1) ?: 0
        return html.substring(0, position) + meta + html.substring(position)
    }
}
