package io.github.zeperus.openpad.markdown

import org.commonmark.ext.gfm.strikethrough.Strikethrough
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.ext.task.list.items.TaskListItemMarker
import org.commonmark.ext.task.list.items.TaskListItemsExtension
import org.commonmark.node.BlockQuote
import org.commonmark.node.BulletList
import org.commonmark.node.Code
import org.commonmark.node.Document
import org.commonmark.node.Emphasis
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.HardLineBreak
import org.commonmark.node.Heading
import org.commonmark.node.HtmlInline
import org.commonmark.node.Image
import org.commonmark.node.IndentedCodeBlock
import org.commonmark.node.Link
import org.commonmark.node.Node
import org.commonmark.node.OrderedList
import org.commonmark.node.Paragraph
import org.commonmark.node.SoftLineBreak
import org.commonmark.node.SourceSpan
import org.commonmark.node.StrongEmphasis
import org.commonmark.node.Text
import org.commonmark.node.ThematicBreak
import org.commonmark.parser.IncludeSourceSpans
import org.commonmark.parser.Parser
import org.commonmark.node.ListItem as CmListItem

/** One top-level block together with exactly the text it came from. */
data class ParsedBlock(
    val block: Block,
    /** The original text of this block (whole lines, without the final line break). */
    val source: String,
    /** The original text between this block and the next one (or the end): line break plus blank lines. */
    val gapAfter: String,
)

/**
 * A parsed `.md` file: the structured [document] plus enough of the original to write unchanged parts back
 * byte-for-byte. `leading + blocks.joinToString { source + gapAfter } + trailing` is always exactly the input
 * (when [layoutValid]); that is what lets the editor change one paragraph without reformatting the whole file.
 */
data class ParsedDocument(
    val blocks: List<ParsedBlock>,
    val leading: String,
    val eol: String,
    /** False only if the parser's source positions could not be trusted; then everything is serialized afresh. */
    val layoutValid: Boolean = true,
) {
    val document: OpenPadDocument get() = OpenPadDocument(blocks.map { it.block })

    /** The exact original text, if [layoutValid]. */
    fun original(): String = leading + blocks.joinToString("") { it.source + it.gapAfter }
}

object MarkdownParser {
    // CommonMark plus GFM strikethrough (`~~x~~`, two tildes only), task lists and tables. Tables are only
    // *recognized* so that they stay intact as raw blocks instead of being mangled as paragraphs.
    private val parser: Parser = Parser.builder()
        .extensions(
            listOf(
                StrikethroughExtension.builder().requireTwoTildes(true).build(),
                TaskListItemsExtension.create(),
                TablesExtension.create(),
            ),
        )
        .includeSourceSpans(IncludeSourceSpans.BLOCKS)
        .maxOpenBlockParsers(MAX_NESTING)
        .maxInlineNesting(MAX_NESTING)
        .build()

    private const val MAX_NESTING = 24

    /**
     * Parses any text. Never throws: if the library fails for some pathological input the whole file is kept as one
     * raw block, so opening a strange file can neither crash the app nor lose content.
     */
    fun parse(text: String): ParsedDocument = try {
        parseUnsafe(text)
    } catch (e: Exception) {
        wholeFileRaw(text)
    } catch (e: StackOverflowError) {
        wholeFileRaw(text)
    }

    fun parseDocument(text: String): OpenPadDocument = parse(text).document

    private fun wholeFileRaw(text: String): ParsedDocument {
        val eol = detectEol(text)
        return ParsedDocument(listOf(ParsedBlock(Block.Raw(text), text, "")), "", eol)
    }

    private fun parseUnsafe(text: String): ParsedDocument {
        val eol = detectEol(text)
        val lines = Lines(text)
        val root = parser.parse(text) as Document
        val mapper = Mapper(lines)

        val tops = generateSequence(root.firstChild) { it.next }.toList()
        val ranges = tops.map { node -> lines.lineRange(node) }
        // Positions must exist, be ascending and not overlap, otherwise we cannot cut the text safely.
        var valid = ranges.none { it == null }
        if (valid) {
            var previousEnd = -1
            for (r in ranges) {
                if (r!!.first <= previousEnd || r.last < r.first) { valid = false; break }
                previousEnd = r.last
            }
        }
        val blocks = tops.map { mapper.block(it, topLevelSourceOf = if (valid) lines else null) }
        if (!valid) {
            return ParsedDocument(blocks.map { ParsedBlock(it, "", "") }, "", eol, layoutValid = false)
        }

        val parsed = ArrayList<ParsedBlock>(blocks.size)
        for ((i, block) in blocks.withIndex()) {
            val range = ranges[i]!!
            val start = lines.start(range.first)
            val end = lines.endExclusive(range.last)
            val nextStart = if (i + 1 < blocks.size) lines.start(ranges[i + 1]!!.first) else text.length
            parsed += ParsedBlock(block, text.substring(start, end), text.substring(end, nextStart))
        }
        val leading = if (blocks.isEmpty()) text else text.substring(0, lines.start(ranges[0]!!.first))
        return ParsedDocument(parsed, leading, eol)
    }

    private fun detectEol(text: String): String {
        var crlf = 0
        var lf = 0
        var i = 0
        while (i < text.length) {
            when (text[i]) {
                '\r' -> if (i + 1 < text.length && text[i + 1] == '\n') { crlf++; i++ }
                '\n' -> lf++
            }
            i++
        }
        return if (crlf > 0 && crlf >= lf) "\r\n" else "\n"
    }

    /** Line table using CommonMark's line-break rules (`\n`, `\r\n`, `\r`). */
    private class Lines(private val text: String) {
        private val starts = ArrayList<Int>()
        private val ends = ArrayList<Int>() // exclusive of the line break

        init {
            var start = 0
            var i = 0
            while (i < text.length) {
                val c = text[i]
                if (c == '\n' || c == '\r') {
                    starts += start; ends += i
                    if (c == '\r' && i + 1 < text.length && text[i + 1] == '\n') i++
                    start = i + 1
                }
                i++
            }
            starts += start; ends += text.length
        }

        fun start(line: Int) = starts[line]
        fun endExclusive(line: Int) = ends[line]
        fun line(index: Int): String = text.substring(starts[index], ends[index])

        /** First and last line of a node, or null if it has no source position. */
        fun lineRange(node: Node): IntRange? {
            val spans = node.sourceSpans
            if (spans.isEmpty()) return null
            val first = spans.minOf { it.lineIndex }
            val last = spans.maxOf { it.lineIndex }
            return if (first in starts.indices && last in starts.indices) first..last else null
        }

        /** The node's own text without container prefixes (`> `, list indentation). */
        fun content(spans: List<SourceSpan>): String =
            spans.joinToString("\n") { span ->
                val l = line(span.lineIndex)
                val from = span.columnIndex.coerceIn(0, l.length)
                l.substring(from, (from + span.length).coerceIn(from, l.length))
            }

        /** Whole lines, for top-level blocks. */
        fun wholeLines(range: IntRange): String = text.substring(starts[range.first], ends[range.last])
    }

    private class Mapper(private val lines: Lines) {
        fun block(node: Node, topLevelSourceOf: Lines?): Block = when (node) {
            is Paragraph -> Block.Paragraph(inlines(node))
            is Heading -> Block.Heading(node.level.coerceIn(1, 6), inlines(node))
            is ThematicBreak -> Block.Rule
            is FencedCodeBlock -> Block.CodeBlock(
                code = node.literal.orEmpty().removeSuffix("\n"),
                info = node.info?.takeIf { it.isNotBlank() },
                style = CodeStyle.Fenced,
            )
            is IndentedCodeBlock -> Block.CodeBlock(node.literal.orEmpty().removeSuffix("\n"), null, CodeStyle.Indented)
            is BlockQuote -> Block.Quote(children(node))
            is BulletList -> Block.ListBlock(
                ListKind.Bullet(node.bulletMarker.takeIf { it in "-*+" } ?: '-'),
                items(node), node.isTight,
            )
            is OrderedList -> Block.ListBlock(
                ListKind.Ordered(node.markerStartNumber ?: 1, if (node.markerDelimiter == ")") ')' else '.'),
                items(node), node.isTight,
            )
            else -> raw(node, topLevelSourceOf) // HTML blocks, tables, link reference definitions, ...
        }

        private fun raw(node: Node, top: Lines?): Block.Raw {
            val range = lines.lineRange(node)
            val markdown = if (top != null && node.parent is Document && range != null) lines.wholeLines(range)
            else lines.content(node.sourceSpans)
            return Block.Raw(markdown)
        }

        private fun children(parent: Node): List<Block> =
            generateSequence(parent.firstChild) { it.next }.map { block(it, null) }.toList()

        private fun items(list: Node): List<ListItem> =
            generateSequence(list.firstChild) { it.next }.filterIsInstance<CmListItem>().map { item(it) }.toList()

        private fun item(item: CmListItem): ListItem {
            val blocks = ArrayList<Block>()
            var checked: Boolean? = null
            for (child in generateSequence(item.firstChild) { it.next }) {
                if (child is TaskListItemMarker) { // commonmark-java puts the marker in the item, before the paragraph
                    checked = child.isChecked
                    continue
                }
                if (child is Paragraph && checked == null && blocks.isEmpty()) {
                    val marker = child.firstChild as? TaskListItemMarker
                    if (marker != null) {
                        checked = marker.isChecked
                        blocks += Block.Paragraph(inlines(child, skipFirst = true))
                        continue
                    }
                }
                blocks += block(child, null)
            }
            return ListItem(blocks, checked)
        }

        fun inlines(parent: Node, skipFirst: Boolean = false): List<Inline> {
            val out = ArrayList<Inline>()
            var node = if (skipFirst) parent.firstChild?.next else parent.firstChild
            while (node != null) {
                inline(node, out)
                node = node.next
            }
            // `<strong>x</strong>` etc. (also what the serializer writes for spans CommonMark cannot express) is the style it means
            return InlineNormalizer.block(HtmlSpans.foldInlines(InlineNormalizer.block(out)))
        }

        private fun nested(parent: Node): List<Inline> {
            val out = ArrayList<Inline>()
            var node = parent.firstChild
            while (node != null) { inline(node, out); node = node.next }
            return out
        }

        private fun inline(node: Node, out: MutableList<Inline>) {
            when (node) {
                is Text -> out += Inline.Text(node.literal.orEmpty())
                is SoftLineBreak -> out += Inline.SoftBreak
                is HardLineBreak -> out += Inline.HardBreak
                is Emphasis -> out += Inline.Emphasis(nested(node))
                is StrongEmphasis -> out += Inline.Strong(nested(node))
                is Strikethrough -> out += Inline.Strikethrough(nested(node))
                is Code -> out += Inline.Code(node.literal.orEmpty())
                is Link -> out += Inline.Link(nested(node), node.destination.orEmpty(), node.title)
                is Image -> out += Inline.Raw(MarkdownSerializer.imageMarkdown(nested(node), node.destination.orEmpty(), node.title))
                is HtmlInline -> out += Inline.Raw(node.literal.orEmpty())
                is TaskListItemMarker -> Unit // consumed by the list item
                else -> out += nested(node) // unknown inline node: keep its text
            }
        }
    }
}
