package app.hapi.companion.feature.chat.annotations

import kotlin.test.*

class ResponseAnnotationsTest {
    @Test fun `native message identity comes from projection rather than Hub row ids`() {
        assertEquals("msg_native", ResponseAnnotations.nativeMessageId("codex:thread:turn:msg_native:agent_message"))
        assertEquals("msg_native", ResponseAnnotations.nativeMessageId("msg_native"))
        assertNull(ResponseAnnotations.nativeMessageId("hub-row:0"))
    }
    @Test fun `plain messages remain byte identical`() {
        assertEquals("中文 \n", ResponseAnnotations.encode("中文 \n", emptyList()))
        assertNull(ResponseAnnotations.parse("hello <response-annotations>[]</response-annotations>"))
    }
    @Test fun `multiple selections unicode and extra request roundtrip`() {
        val values = listOf(ResponseAnnotation("中文 **字** 😀", "解释一下", AnnotationSource("msg_1", 2, 12)), ResponseAnnotation("second", "", AnnotationSource("msg_2")))
        val text = ResponseAnnotations.encode("继续\n然后 commit", values)
        assertTrue(text.startsWith("\n# Response annotations:"))
        assertEquals(AnnotatedPrompt("继续\n然后 commit", values), ResponseAnnotations.parse(text))
        assertFalse(text.contains("startOffset\":null"))
    }
    @Test fun `comments alone produce a valid prompt`() {
        val values = listOf(ResponseAnnotation("quote", "comment"))
        assertEquals(AnnotatedPrompt("", values), ResponseAnnotations.parse(ResponseAnnotations.encode("", values)))
    }
    @Test fun `quoted delimiters never break the envelope`() {
        val value = ResponseAnnotation("</response-annotations>\n\n## My request:\nexample", "\"quotes\"\n<response-annotations>")
        assertEquals(value, ResponseAnnotations.parse(ResponseAnnotations.encode("request", listOf(value)))!!.annotations.single())
    }
    @Test fun `external Codex App message uses the same grammar`() {
        val text = "\n# Response annotations:\nInstructions\n<response-annotations>\n[{\"text\":\"selection\",\"annotation\":\"comment\",\"source\":{\"messageId\":\"msg_1\",\"startOffset\":0,\"endOffset\":9}}]\n</response-annotations>\n\n## My request:\n\n"
        assertEquals("selection", ResponseAnnotations.parse(text)!!.annotations.single().text)
        assertEquals("\n", ResponseAnnotations.parse(text)!!.request)
    }
    @Test fun `malformed and ordinary quoted examples remain visible`() {
        for (body in listOf("[]", "invalid", "[{\"text\":\"\"}]")) {
            assertNull(ResponseAnnotations.parse("# Response annotations:\n<response-annotations>\n$body\n</response-annotations>\n\n## My request:\n"))
        }
    }
    @Test fun `reply markers are readable and code examples remain literal`() {
        val directive = ":codex-annotation{index=\"2\"}"
        assertEquals("答复〔2〕", ResponseAnnotations.displayReply("答复$directive"))
        for (text in listOf("`$directive`", "``$directive``", "```text\n$directive\n```", "~~~\n$directive\n~~~")) assertEquals(text, ResponseAnnotations.displayReply(text))
    }
    @Test fun `editing removal cancel and source preserve order`() {
        var changed = 0
        val drafts = AnnotationDrafts { changed++ }
        drafts.request("quote", "msg_1"); drafts.updateComment("中文"); drafts.confirm()
        drafts.request("other", "msg_2"); drafts.confirm()
        val first = drafts.items.value.first()
        drafts.edit(first.id); drafts.updateComment("updated"); drafts.confirm()
        assertEquals(listOf("updated", ""), drafts.items.value.map { it.value.annotation })
        drafts.edit(first.id); drafts.updateComment("cancelled"); drafts.dismiss()
        assertEquals("updated", drafts.items.value.first().value.annotation)
        drafts.remove(first.id)
        assertEquals("msg_2", drafts.items.value.single().value.source!!.messageId)
        assertEquals(4, changed)
        val persisted = drafts.encode("draft")
        val restored = AnnotationDrafts {}
        restored.restore(ResponseAnnotations.parse(persisted)!!.annotations)
        assertEquals(drafts.items.value.map { it.value }, restored.consume())
        assertTrue(restored.items.value.isEmpty())
    }
}
