package app.hapi.companion.feature.chat.annotations

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class AnnotationSource(
    val messageId: String,
    val startOffset: Int? = null,
    val endOffset: Int? = null,
)

@Serializable
data class ResponseAnnotation(val text: String, val annotation: String = "", val source: AnnotationSource? = null)

data class AnnotatedPrompt(val request: String, val annotations: List<ResponseAnnotation>)

/** The Codex App envelope is ordinary user text, shared by drafts and sends. */
object ResponseAnnotations {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }
    const val instructions = "Each item contains text selected from an earlier Codex response and may include a user comment. Treat items as Annotation 1, Annotation 2, and so on in array order. Use every selection as context and address every comment. For every annotation you address, include its inline directive `:codex-annotation{index=\"N\"}`, where N is its one-based array position (for example, `:codex-annotation{index=\"1\"}`). Do not use unstructured annotation labels."
    private const val heading = "# Response annotations:"
    private const val opening = "<response-annotations>\n"
    private const val closing = "\n</response-annotations>\n\n## My request:\n"

    /** Shared Codex projection IDs contain the original App message ID. */
    fun nativeMessageId(blockId: String): String? = Regex("(?:^|:)(msg_[^:]+)(?=:|$)").find(blockId)?.groupValues?.get(1)

    fun encode(request: String, annotations: List<ResponseAnnotation>): String {
        if (annotations.isEmpty()) return request
        // Keep a quoted closing tag inside a JSON string from becoming envelope syntax.
        val array = json.encodeToString(annotations).replace("<", "\\u003c")
        return "\n$heading\n$instructions\n$opening$array$closing$request"
    }

    fun parse(text: String): AnnotatedPrompt? {
        val body = text.trimStart('\n', '\r')
        if (!body.startsWith("$heading\n")) return null
        val start = body.indexOf(opening).takeIf { it >= 0 }?.plus(opening.length) ?: return null
        val end = body.indexOf(closing, start).takeIf { it >= start } ?: return null
        val annotations = runCatching { json.decodeFromString<List<ResponseAnnotation>>(body.substring(start, end)) }.getOrNull() ?: return null
        if (annotations.isEmpty() || annotations.any { it.text.isBlank() }) return null
        return AnnotatedPrompt(body.substring(end + closing.length), annotations)
    }

    fun displayPrompt(text: String): String {
        val prompt = parse(text) ?: return text
        val quotes = prompt.annotations.mapIndexed { index, item ->
            "〔${index + 1}〕 ${item.text}" + if (item.annotation.isNotBlank()) "\n${item.annotation}" else ""
        }.joinToString("\n\n")
        return listOf(quotes, prompt.request).filter { it.isNotBlank() }.joinToString("\n\n")
    }

    /** Render reply references without exposing Codex's UI directive syntax.
     * Fences and inline code retain literal examples of that syntax. */
    fun displayReply(text: String): String {
        val directive = Regex(":codex-annotation\\{index=\"([1-9][0-9]*)\"\\}")
        val result = StringBuilder()
        var fence: String? = null
        var ticks = 0
        text.splitToSequence('\n').forEachIndexed { lineIndex, line ->
            if (lineIndex > 0) result.append('\n')
            val marker = Regex("^ {0,3}(`{3,}|~{3,})").find(line)?.groupValues?.get(1)
            if (marker != null && ticks == 0) {
                val previous = fence
                if (previous == null) fence = marker
                else if (marker[0] == previous[0] && marker.length >= previous.length) fence = null
                result.append(line)
            } else if (fence != null) result.append(line)
            else {
                var i = 0
                while (i < line.length) {
                    if (line[i] == '`') {
                        var end = i + 1
                        while (end < line.length && line[end] == '`') end++
                        val count = end - i
                        if (ticks == 0) ticks = count else if (ticks == count) ticks = 0
                        result.append(line.substring(i, end)); i = end
                    } else {
                        val match = if (ticks == 0 && (i == 0 || line[i - 1] != '\\')) directive.matchAt(line, i) else null
                        if (match != null) { result.append("〔${match.groupValues[1]}〕"); i += match.value.length }
                        else result.append(line[i++])
                    }
                }
            }
        }
        return result.toString()
    }
}
