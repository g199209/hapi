package app.hapi.protocol.markdown

import org.commonmark.node.CustomBlock
import org.commonmark.node.CustomNode
import org.commonmark.node.Node
import org.commonmark.parser.Parser
import org.commonmark.parser.SourceLine
import org.commonmark.parser.beta.InlineContentParser
import org.commonmark.parser.beta.InlineContentParserFactory
import org.commonmark.parser.beta.InlineParserState
import org.commonmark.parser.beta.ParsedInline
import org.commonmark.parser.beta.Scanner
import org.commonmark.parser.block.AbstractBlockParser
import org.commonmark.parser.block.AbstractBlockParserFactory
import org.commonmark.parser.block.BlockContinue
import org.commonmark.parser.block.BlockStart
import org.commonmark.parser.block.MatchedBlockParser
import org.commonmark.parser.block.ParserState

class InlineMath(val latex: String, val source: String, val display: Boolean) : CustomNode()

class DisplayMath : CustomBlock() {
    var latex: String = ""
        internal set
    var source: String = ""
        internal set
    var closed: Boolean = false
        internal set
}

/** Parse TeX before CommonMark can consume its escapes, underscores or brackets.
 * CommonMark still owns code spans/fences, links and other opaque constructs. */
object MarkdownMath {
    private val parser = MarkdownTransforms.newParser(listOf(object : Parser.ParserExtension {
        override fun extend(builder: Parser.Builder) {
            builder.customInlineContentParserFactory(MathInlineFactory())
            builder.customBlockParserFactory(MathBlockFactory())
        }
    }))

    fun parse(source: String): Node = parser.parse(MarkdownTransforms.repairTables(source))
}

private class MathInlineFactory : InlineContentParserFactory {
    override fun getTriggerCharacters(): Set<Char> = setOf('$', '\\')
    override fun create(): InlineContentParser = object : InlineContentParser {
        override fun tryParse(state: InlineParserState): ParsedInline? {
            val scanner = state.scanner()
            val start = scanner.position()
            val dollar = scanner.peek() == '$'
            scanner.next()
            val display: Boolean
            val close: String
            if (dollar) {
                display = scanner.next('$')
                close = if (display) "$$" else "$"
                if (!display && (scanner.peek().isWhitespace() || scanner.peek() == Scanner.END)) return null
            } else {
                display = scanner.peek() == '['
                if (!display && scanner.peek() != '(') return null
                scanner.next()
                close = if (display) "\\]" else "\\)"
            }
            val content = scanner.position()
            var escaped = false
            while (scanner.hasNext()) {
                val before = scanner.position()
                val previous = scanner.peekPreviousCodePoint().toChar()
                if (!escaped && scanner.next(close)) {
                    if (dollar && !display && (previous.isWhitespace() || scanner.peek().isDigit())) continue
                    val latex = scanner.getSource(content, before).content
                    if (latex.isBlank()) return null
                    return ParsedInline.of(InlineMath(latex, scanner.getSource(start, scanner.position()).content, display), scanner.position())
                }
                if (!display && scanner.peek() == '\n') return null
                escaped = if (scanner.peek() == '\\') !escaped else false
                scanner.next()
            }
            return null
        }
    }
}

private class MathBlockFactory : AbstractBlockParserFactory() {
    override fun tryStart(state: ParserState, matchedBlockParser: MatchedBlockParser): BlockStart? {
        val line = state.line.content.toString().substring(state.nextNonSpaceIndex)
        val open = when {
            line.startsWith("$$") -> "$$"
            line.startsWith("\\[") -> "\\["
            else -> return null
        }
        val close = if (open == "$$") "$$" else "\\]"
        val rest = line.drop(open.length)
        val end = closingIndex(rest, close)
        if (end >= 0 && rest.substring(end + close.length).isNotBlank()) return null
        return BlockStart.of(MathBlockParser(line, rest, close, end)).atIndex(state.line.content.length)
    }
}

private fun closingIndex(text: String, close: String): Int {
    var at = text.indexOf(close)
    while (at >= 0) {
        var backslashes = 0
        var before = at - 1
        while (before >= 0 && text[before--] == '\\') backslashes++
        if (backslashes % 2 == 0) return at
        at = text.indexOf(close, at + close.length)
    }
    return -1
}

private class MathBlockParser(first: String, rest: String, private val close: String, end: Int) : AbstractBlockParser() {
    private val math = DisplayMath()
    private val raw = StringBuilder(first)
    private val body = StringBuilder(if (end >= 0) rest.take(end) else rest)
    private var opening = true

    init { math.closed = end >= 0 }
    override fun getBlock(): DisplayMath = math

    override fun tryContinue(state: ParserState): BlockContinue? {
        if (math.closed) return null
        val line = state.line.content.toString().substring(state.index)
        val end = closingIndex(line, close)
        if (end >= 0 && line.substring(end + close.length).isBlank()) {
            raw.append('\n').append(line)
            body.append('\n').append(line.take(end))
            math.closed = true
            return BlockContinue.finished()
        }
        return BlockContinue.atIndex(state.index)
    }

    override fun addLine(line: SourceLine) {
        if (opening) { opening = false; return }
        raw.append('\n').append(line.content)
        body.append('\n').append(line.content)
    }

    override fun closeBlock() {
        math.source = raw.toString()
        math.latex = body.toString().trim()
    }
}
