package app.hapi.protocol.markdown

import org.commonmark.node.Code
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.Node
import org.commonmark.node.Text
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MarkdownMathTest {
    private fun nodes(source: String): List<Node> = buildList {
        fun visit(node: Node) {
            add(node)
            var child = node.firstChild
            while (child != null) { visit(child); child = child.next }
        }
        visit(MarkdownMath.parse(source))
    }

    @Test fun `screenshot objectives retain TeX escapes scripts and absolute value bars`() {
        val latex = """J(\theta)=\frac{\sum_k\sum_{d\in V_k}\mathrm{PnL}(d;M_k,\theta)}{\sum_k |V_k|}"""
        val formula = nodes("\$\$ $latex \$\$").filterIsInstance<DisplayMath>().single()
        assertEquals(latex, formula.latex)
        assertTrue(formula.closed)
        val inline = nodes("参数 \\(\\theta\\) 与 \\(M_{\\mathrm{full}}\\)")
            .filterIsInstance<InlineMath>()
        assertEquals(listOf("\\theta", "M_{\\mathrm{full}}"), inline.map { it.latex })
    }

    @Test fun `multiline display math accepts dollar and bracket fences`() {
        for ((open, close) in listOf("\$\$" to "\$\$", "\\[" to "\\]")) {
            val source = "$open\n\\begin{aligned}\na&=b\\\\\nc&=d\n\\end{aligned}\n$close"
            val formula = nodes(source).filterIsInstance<DisplayMath>().single()
            assertTrue(formula.closed)
            assertEquals(source, formula.source)
            assertTrue(formula.latex.contains("a&=b\\\\\nc&=d"))
        }
    }

    @Test fun `inline parsing protects TeX from emphasis link and code syntax`() {
        val math = nodes("A \$x_i^2 + \\alpha\$ and \\(\\text{a_b} + [x]\\)").filterIsInstance<InlineMath>()
        assertEquals(listOf("x_i^2 + \\alpha", "\\text{a_b} + [x]"), math.map { it.latex })
    }

    @Test fun `code fences code spans escaped dollars and paired currency stay literal`() {
        val parsed = nodes("`\\(x\\) \$y\$`\n\n```python\nprint('\\[x\\] \$y\$')\n```\n\nPrice \\$5 and \\$10. More \$5 and \$10.")
        assertTrue(parsed.none { it is InlineMath || it is DisplayMath })
        assertEquals("\\(x\\) \$y\$", parsed.filterIsInstance<Code>().single().literal)
        assertTrue(parsed.filterIsInstance<FencedCodeBlock>().single().literal.contains("\\[x\\]"))
    }

    @Test fun `unclosed math remains source and streaming completion restores later Markdown blocks`() {
        val pending = nodes("\$\$\n\\frac{1}{2}").filterIsInstance<DisplayMath>().single()
        assertFalse(pending.closed)
        assertEquals("\$\$\n\\frac{1}{2}", pending.source)
        val complete = nodes("\$\$\n\\frac{1}{2}\n\$\$\n\n**After**")
        assertTrue(complete.filterIsInstance<DisplayMath>().single().closed)
        assertTrue(complete.filterIsInstance<Text>().any { it.literal == "After" })
        assertTrue(nodes("Unfinished \\(x").none { it is InlineMath })
    }

    @Test fun `math inside lists quotes tables and link labels retains its container`() {
        for (source in listOf("- value \\(x_i\\)", "> value \\(x_i\\)", "[value \\(x_i\\)](https://example.com)", "| Value |\n| --- |\n| \\(x_i\\) |")) {
            assertEquals("x_i", nodes(source).filterIsInstance<InlineMath>().single().latex)
        }
        val quote = nodes("> \\[\n> x_i^2\n> \\]\n\nAfter").filterIsInstance<DisplayMath>().single()
        assertEquals("x_i^2", quote.latex)
    }

    @Test fun `prisoner waiting-time display equations render inside ordered items without blank lines`() {
        val source = """1. 等其中一人被选中并开灯。每天成功概率为 \(k/100\)，等待时间
   \[
   X_k\sim\operatorname{Geom}(k/100),\qquad \mathbb E[X_k]=\frac{100}{k}.
   \]
2. 等计数者被选中并关灯。每天成功概率为 \(1/100\)，等待时间
   \[
   Y_k\sim\operatorname{Geom}(1/100),\qquad \mathbb E[Y_k]=100.
   \]"""
        val formulas = nodes(source).filterIsInstance<DisplayMath>()
        assertEquals(2, formulas.size, nodes(source).joinToString { it.toString() })
        assertTrue(formulas.all { it.closed }, formulas.joinToString { it.source })
        assertEquals("X_k\\sim\\operatorname{Geom}(k/100),\\qquad \\mathbb E[X_k]=\\frac{100}{k}.", formulas[0].latex)
        assertEquals("Y_k\\sim\\operatorname{Geom}(1/100),\\qquad \\mathbb E[Y_k]=100.", formulas[1].latex)
    }

    @Test fun `math does not consume URL destinations or display trailing prose`() {
        val parsed = nodes("[docs](https://example.com/\$path\$) and \$\$x^2\$\$ after")
        assertEquals(1, parsed.filterIsInstance<InlineMath>().size)
        assertTrue(parsed.none { it is DisplayMath })
        assertTrue(parsed.filterIsInstance<Text>().any { it.literal.contains(" after") })
    }

    @Test fun `escaped bracket opener and TeX line break before closing delimiter are distinct`() {
        assertTrue(nodes("Literal \\\\(x\\)").none { it is InlineMath })
        val math = nodes("Value \\(x\\\\\\)").filterIsInstance<InlineMath>().single()
        assertEquals("x\\\\", math.latex)
    }
}
