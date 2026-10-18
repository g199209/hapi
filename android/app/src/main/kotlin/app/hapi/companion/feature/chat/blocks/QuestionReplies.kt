package app.hapi.companion.feature.chat.blocks

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class QuestionReply(val question: String, val answer: String)

/** Codex App sends question answers as a standalone user-text envelope. */
object QuestionReplies {
    private const val opening = "<send_user_message_question_reply>"
    private const val closing = "</send_user_message_question_reply>"
    private val json = Json { ignoreUnknownKeys = true }

    fun parse(text: String): List<QuestionReply>? {
        val body = text.trim()
        if (!body.startsWith(opening) || !body.endsWith(closing)) return null
        val payload = body.substring(opening.length, body.length - closing.length).trim()
        val replies = runCatching { json.decodeFromString<List<QuestionReply>>(payload) }.getOrNull() ?: return null
        return replies.takeIf { it.isNotEmpty() && it.all { reply -> reply.question.isNotBlank() && reply.answer.isNotBlank() } }
    }

    /** Readable text for the full-message reader and queued preview. Wire text stays intact. */
    fun displayPrompt(text: String): String = parse(text)?.joinToString("\n\n") { "${it.question}\n${it.answer}" } ?: text
}
