package app.hapi.companion.feature.chat.blocks

import kotlin.test.*

class QuestionRepliesTest {
    private val payload = """[{"questionItemId":"[\"request_user_input_async\",\"call_test\",0]","question":"Model search 的 common optimizer 已经是 TPE；“默认 optimizer 改为 TPE”是否指 Feature climb 和 Strategy search 也一起统一？","answer":"仅 Model，其他阶段保持原设置"}]"""
    private fun envelope(json: String = payload) = "<send_user_message_question_reply>\n$json\n</send_user_message_question_reply>\n"

    @Test fun `native envelope displays question and answer without internal metadata`() {
        val source = envelope()
        val reply = QuestionReplies.parse(source)!!.single()
        assertEquals("仅 Model，其他阶段保持原设置", reply.answer)
        assertTrue(reply.question.startsWith("Model search"))
        val displayed = QuestionReplies.displayPrompt(source)
        assertEquals("${reply.question}\n${reply.answer}", displayed)
        assertFalse(displayed.contains("questionItemId")); assertFalse(displayed.contains("call_test"))
        assertEquals(source, envelope())
    }

    @Test fun `multiple answers keep their order unicode and entered line breaks`() {
        val json = """[{"question":"第一题😀","answer":"一\n二"},{"question":"第二题","answer":"`TPE`","futureField":{"value":42}}]"""
        assertEquals(listOf(QuestionReply("第一题😀", "一\n二"), QuestionReply("第二题", "`TPE`")), QuestionReplies.parse(envelope(json)))
        assertEquals("第一题😀\n一\n二\n\n第二题\n`TPE`", QuestionReplies.displayPrompt(envelope(json)))
    }

    @Test fun `wrapper whitespace and CRLF do not affect recognition`() {
        assertEquals(QuestionReplies.parse(envelope()), QuestionReplies.parse(" \r\n" + envelope().replace("\n", "\r\n") + "  "))
    }

    @Test fun `literal examples ordinary text and partial envelopes stay unchanged`() {
        listOf("普通消息", "```xml\n${envelope()}\n```", "Example: ${envelope()}", envelope() + "还有补充", "<send_user_message_question_reply>\n$payload").forEach {
            assertNull(QuestionReplies.parse(it)); assertEquals(it, QuestionReplies.displayPrompt(it))
        }
    }

    @Test fun `malformed or incomplete lists fall back without hiding any content`() {
        listOf("broken", "[]", "{}", "null", "[null]", """[{"question":"q"}]""", """[{"question":"q","answer":42}]""", """[{"question":" ","answer":"a"}]""", """[{"question":"q","answer":""}]""", """[{"question":"q","answer":"a"},{"question":"missing answer"}]""").forEach { value ->
            val source = envelope(value)
            assertNull(QuestionReplies.parse(source)); assertEquals(source, QuestionReplies.displayPrompt(source))
        }
    }

    @Test fun `long answers and tag text inside JSON are preserved in full reader text`() {
        val question = "<send_user_message_question_reply> and </send_user_message_question_reply>"
        val answer = "a".repeat(20_000)
        val source = envelope("""[{"question":"$question","answer":"$answer"}]""")
        assertEquals("$question\n$answer", QuestionReplies.displayPrompt(source))
    }
}
