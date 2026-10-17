package app.hapi.companion.ui.markdown

import java.io.File
import java.security.MessageDigest
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MermaidDocumentTest {
    @Test fun `diagram text is UTF8 encoded outside executable HTML`() {
        val source = "flowchart LR\nA[中文 \" '</script><script>danger()</script> ` $ &] --> B"
        val document = mermaidDocument(source, dark = false, expanded = false, cachedSvg = null)
        val encoded = Regex("atob\\('([^']+)'\\)").find(document)!!.groupValues[1]
        assertEquals(source, String(Base64.getDecoder().decode(encoded), Charsets.UTF_8))
        assertFalse(document.contains("danger()"))
        assertTrue(document.contains("$MERMAID_ORIGIN$MERMAID_ASSET"))
        assertTrue(document.contains("securityLevel:'strict'"))
    }

    @Test fun `cached SVG opens without Mermaid engine and fullscreen enables zoom`() {
        val svg = "<svg><text>中文</text></svg>"
        val document = mermaidDocument("ignored source", dark = true, expanded = true, cachedSvg = svg)
        assertFalse(document.contains("<script src="))
        assertTrue(document.contains("user-scalable=yes"))
        val encoded = Regex("atob\\('([^']+)'\\)").find(document)!!.groupValues[1]
        assertEquals(svg, String(Base64.getDecoder().decode(encoded), Charsets.UTF_8))
    }

    @Test fun `cache distinguishes exact source and theme including hash collisions`() {
        assertEquals("Aa".hashCode(), "BB".hashCode())
        MermaidCache.put("Aa", false, "svg-a")
        MermaidCache.put("BB", false, "svg-b")
        MermaidCache.put("Aa", true, "svg-dark")
        assertEquals("svg-a", MermaidCache.get("Aa", false))
        assertEquals("svg-b", MermaidCache.get("BB", false))
        assertEquals("svg-dark", MermaidCache.get("Aa", true))
    }

    @Test fun `cache evicts old diagrams and rejects oversized SVG`() {
        for (index in 0..50) MermaidCache.put("bounded-$index", false, "svg-$index")
        assertNull(MermaidCache.get("bounded-0", false))
        assertEquals("svg-50", MermaidCache.get("bounded-50", false))
        MermaidCache.put("oversized", false, "x".repeat(2_000_001))
        assertNull(MermaidCache.get("oversized", false))
    }

    @Test fun `packaged engine matches pinned version and provenance hash`() {
        val root = File(requireNotNull(System.getProperty("hapi.fixtures.dir"))).parentFile!!.parentFile!!
        val assets = File(root, "android/app/src/main/assets/mermaid")
        val info = File(assets, "vendor-info.json").readText()
        assertTrue(info.contains("\"version\": \"11.14.0\""))
        val digest = MessageDigest.getInstance("SHA-256").digest(File(assets, "mermaid.min.js").readBytes())
            .joinToString("") { "%02x".format(it) }
        assertTrue(info.contains(digest))
    }
}
