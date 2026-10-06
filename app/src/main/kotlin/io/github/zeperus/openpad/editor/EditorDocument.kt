package io.github.zeperus.openpad.editor

import io.github.zeperus.openpad.markdown.Block
import io.github.zeperus.openpad.markdown.CodeStyle
import io.github.zeperus.openpad.markdown.ListItem
import io.github.zeperus.openpad.markdown.ListKind
import io.github.zeperus.openpad.markdown.MarkdownParser
import io.github.zeperus.openpad.markdown.MarkdownSerializer
import io.github.zeperus.openpad.markdown.ParsedDocument

/** One list (not one item): rows with the same id at the same depth belong to the same list. */
data class ListInfo(
    val id: Long,
    val ordered: Boolean,
    /** First number of an ordered list. */
    val start: Int = 1,
    /** Bullet character (`-`, `*`, `+`) or ordered delimiter (`.`, `)`): kept so an untouched list keeps its spelling. */
    val marker: Char = '-',
    val tight: Boolean = true,
)

sealed interface RowKind {
    data object Paragraph : RowKind
    data class Heading(val level: Int) : RowKind
    data object Quote : RowKind

    /** [checked] is null for a plain item and true/false for a task item. */
    data class ListItem(val list: ListInfo, val checked: Boolean?) : RowKind
    data class Code(val info: String?, val style: CodeStyle) : RowKind
    data object Rule : RowKind

    /** Markdown the editor does not format; its text *is* the Markdown source and stays editable as such. */
    data object Raw : RowKind
}

/** Where a row came from: block [block] of the parsed file, row [ordinal] of [count] rows that block was split into. */
data class Origin(val block: Int, val ordinal: Int, val count: Int)

/**
 * One editable line-level unit. [id] is stable for the lifetime of the row (UI state attaches to it). Rows that were not
 * [touched] and still form their complete original group are written back exactly as they were read.
 */
data class EditorRow(
    val id: Long,
    val kind: RowKind,
    val text: RichText,
    val depth: Int = 0,
    val origin: Origin? = null,
    val touched: Boolean = false,
) {
    val isTextual: Boolean get() = kind !is RowKind.Rule
    val isListItem: Boolean get() = kind is RowKind.ListItem
}

/**
 * The document as the editor sees it: a flat list of [EditorRow]s. Immutable; every edit produces a new document that
 * shares the unchanged rows. See docs/editor.md for how rows map to Markdown blocks.
 */
class EditorDocument internal constructor(
    val rows: List<EditorRow>,
    internal val parsed: ParsedDocument?,
    private val originalText: String?,
    internal val nextId: Long,
    /** True until the first edit: the document is then written back as the original text, byte for byte. */
    val pristine: Boolean,
) {
    fun indexOf(rowId: Long): Int = rows.indexOfFirst { it.id == rowId }

    fun row(rowId: Long): EditorRow? = rows.firstOrNull { it.id == rowId }

    /** The number shown for a numbered list item (null for other rows). */
    fun numberOf(index: Int): Int? {
        val row = rows.getOrNull(index) ?: return null
        val kind = row.kind as? RowKind.ListItem ?: return null
        if (!kind.list.ordered) return null
        var n = kind.list.start
        var j = index - 1
        while (j >= 0) {
            val r = rows[j]
            val k = r.kind as? RowKind.ListItem ?: break
            if (r.depth < row.depth) break
            if (r.depth == row.depth) { if (k.list.id != kind.list.id) break; n++ }
            j--
        }
        return n
    }

    /** The Markdown for this document. Unchanged blocks keep their exact original text. */
    fun toMarkdown(): String {
        if (pristine && originalText != null) return originalText
        val (blocks, origins) = toBlocks()
        return MarkdownSerializer.serializeIncremental(parsed, blocks, origins)
    }

    /** The top-level blocks and, for each, the index of the parsed block it still is (null if changed or new). */
    fun toBlocks(): Pair<List<Block>, List<Int?>> {
        val blocks = ArrayList<Block>()
        val origins = ArrayList<Int?>()
        var i = 0
        while (i < rows.size) {
            val start = i
            val block: Block
            when (val kind = rows[i].kind) {
                RowKind.Paragraph -> { block = Block.Paragraph(rows[i].text.toInlines()); i++ }
                is RowKind.Heading -> { block = Block.Heading(kind.level, rows[i].text.toInlines()); i++ }
                RowKind.Rule -> { block = Block.Rule; i++ }
                is RowKind.Code -> { block = Block.CodeBlock(rows[i].text.text, kind.info, kind.style); i++ }
                RowKind.Raw -> { block = Block.Raw(rows[i].text.text); i++ }
                RowKind.Quote -> {
                    // quote lines belong together, except that two quotes parsed from separate blocks stay separate
                    val firstOrigin = rows[i].origin?.block
                    i++
                    while (i < rows.size && rows[i].kind == RowKind.Quote &&
                        (rows[i].origin == null || firstOrigin == null || rows[i].origin?.block == firstOrigin)
                    ) i++
                    block = Block.Quote(rows.subList(start, i).map { Block.Paragraph(it.text.toInlines()) })
                }
                is RowKind.ListItem -> {
                    // a top-level list: all following list rows up to the first row of a different list at depth 0
                    val firstList = kind.list.id
                    i++
                    while (i < rows.size) {
                        val r = rows[i]
                        val k = r.kind as? RowKind.ListItem ?: break
                        if (r.depth == 0 && k.list.id != firstList) break
                        i++
                    }
                    block = buildList(rows.subList(start, i))
                }
            }
            val group = rows.subList(start, i)
            val original = originalBlockFor(group)
            blocks += if (original != null) parsed!!.blocks[original].block else block
            // a group made only of rows of one parsed block is offered to the serializer as that block: if edits made it
            // equal to the original again it is written verbatim, otherwise it is regenerated
            origins += original ?: group.map { it.origin?.block }.distinct().singleOrNull()
        }
        return blocks to origins
    }

    /** The parsed block this group of rows still is exactly, if untouched and complete. */
    private fun originalBlockFor(group: List<EditorRow>): Int? {
        val parsed = parsed ?: return null
        val first = group.firstOrNull()?.origin ?: return null
        if (group.size != first.count) return null
        for ((n, r) in group.withIndex()) {
            val o = r.origin ?: return null
            if (r.touched || o.block != first.block || o.ordinal != n || o.count != first.count) return null
        }
        return first.block.takeIf { it in parsed.blocks.indices }
    }

    private fun buildList(listRows: List<EditorRow>): Block.ListBlock {
        class Frame(val info: ListInfo, val depth: Int) {
            val items = ArrayList<MutableItem>()
        }
        var root: Block.ListBlock? = null
        val stack = ArrayList<Frame>()

        fun close(frame: Frame): Block.ListBlock = Block.ListBlock(
            kind = if (frame.info.ordered) ListKind.Ordered(frame.info.start, frame.info.marker) else ListKind.Bullet(frame.info.marker),
            items = frame.items.map { it.toItem() },
            tight = frame.info.tight,
        )

        fun pop() {
            val top = stack.removeAt(stack.lastIndex)
            val list = close(top)
            val parent = stack.lastOrNull()
            if (parent != null) parent.items.lastOrNull()?.nested?.add(list) else root = list
        }

        for (row in listRows) {
            val kind = row.kind as RowKind.ListItem
            val depth = row.depth.coerceAtLeast(0)
            while (stack.isNotEmpty() && stack.last().depth > depth) pop()
            if (stack.isNotEmpty() && stack.last().depth == depth && stack.last().info.id != kind.list.id) {
                if (stack.size == 1) break // a different list at the top level starts the next block (cannot happen here)
                pop()
            }
            val parentDepth = stack.lastOrNull()?.depth ?: -1
            if (stack.isEmpty() || parentDepth < depth) {
                // an item deeper than its parent allows is attached one level below it
                stack += Frame(kind.list, if (stack.isEmpty()) 0 else minOf(depth, parentDepth + 1))
            }
            stack.last().items += MutableItem(row)
        }
        while (stack.isNotEmpty()) pop()
        return root ?: Block.ListBlock(ListKind.Bullet(), emptyList())
    }

    private class MutableItem(val row: EditorRow) {
        val nested = ArrayList<Block>()
        fun toItem(): ListItem {
            val kind = row.kind as RowKind.ListItem
            val inlines = row.text.toInlines()
            val blocks = ArrayList<Block>()
            if (inlines.isNotEmpty()) blocks += Block.Paragraph(inlines)
            blocks += nested
            return ListItem(blocks, kind.checked)
        }
    }

    // ---- Construction ----------------------------------------------------------------------------------------

    /** A copy with new rows; marks the document as changed. */
    internal fun withRows(newRows: List<EditorRow>, nextId: Long = this.nextId): EditorDocument {
        val (finalRows, next) = ensureTrailingParagraph(normalizeDepths(newRows), nextId)
        return EditorDocument(finalRows, parsed, originalText, next, pristine = false)
    }

    companion object {
        fun empty(): EditorDocument = fromMarkdown("")

        /** Parses [markdown] into rows. Never throws (the parser degrades to a raw block instead). */
        fun fromMarkdown(markdown: String): EditorDocument {
            val parsed = MarkdownParser.parse(markdown)
            var nextId = 1L
            val rows = ArrayList<EditorRow>()
            for ((index, pb) in parsed.blocks.withIndex()) {
                val flat = ArrayList<EditorRow>()
                val ctx = Ctx(nextId)
                if (!flatten(pb.block, 0, ctx, flat)) {
                    flat.clear()
                    flat += EditorRow(ctx.take(), RowKind.Raw, RichText(pb.source))
                }
                nextId = ctx.next
                for ((n, r) in flat.withIndex()) rows += r.copy(origin = Origin(index, n, flat.size))
            }
            val (finalRows, next) = ensureTrailingParagraph(rows, nextId)
            return EditorDocument(finalRows, parsed, markdown, next, pristine = true)
        }

        internal class Ctx(var next: Long) {
            fun take(): Long = next++
        }

        /** A list item can be at most one level deeper than the list row before it; the first one is at depth 0. */
        private fun normalizeDepths(rows: List<EditorRow>): List<EditorRow> {
            var previous = -1
            return rows.map { r ->
                if (r.kind !is RowKind.ListItem) {
                    previous = -1
                    r
                } else {
                    val depth = r.depth.coerceIn(0, previous + 1)
                    previous = depth
                    if (depth == r.depth) r else r.copy(depth = depth, touched = true)
                }
            }
        }

        /** There is always a paragraph to type into, also after a code block, rule or raw block. */
        private fun ensureTrailingParagraph(rows: List<EditorRow>, nextId: Long): Pair<List<EditorRow>, Long> {
            val last = rows.lastOrNull()
            val needs = last == null || last.kind is RowKind.Code || last.kind == RowKind.Rule || last.kind == RowKind.Raw
            return if (needs) (rows + EditorRow(nextId, RowKind.Paragraph, RichText(""))) to nextId + 1 else rows to nextId
        }

        /** Adds the rows for [block]; false if the editor cannot represent it (then the caller keeps it as a raw row). */
        private fun flatten(block: Block, depth: Int, ctx: Ctx, out: MutableList<EditorRow>): Boolean {
            when (block) {
                is Block.Paragraph -> out += EditorRow(ctx.take(), RowKind.Paragraph, RichText.fromInlines(block.inlines))
                is Block.Heading -> out += EditorRow(ctx.take(), RowKind.Heading(block.level), RichText.fromInlines(block.inlines))
                Block.Rule -> out += EditorRow(ctx.take(), RowKind.Rule, RichText(""))
                is Block.CodeBlock -> out += EditorRow(ctx.take(), RowKind.Code(block.info, block.style), RichText(block.code))
                is Block.Raw -> out += EditorRow(ctx.take(), RowKind.Raw, RichText(block.markdown))
                is Block.Quote -> {
                    if (block.blocks.any { it !is Block.Paragraph }) return false
                    if (block.blocks.isEmpty()) out += EditorRow(ctx.take(), RowKind.Quote, RichText(""))
                    for (p in block.blocks) out += EditorRow(ctx.take(), RowKind.Quote, RichText.fromInlines((p as Block.Paragraph).inlines))
                }
                is Block.ListBlock -> return flattenList(block, depth, ctx, out)
            }
            return true
        }

        private fun flattenList(list: Block.ListBlock, depth: Int, ctx: Ctx, out: MutableList<EditorRow>): Boolean {
            // every item must be: optional paragraph, then nested lists only
            for (item in list.items) {
                val rest = if (item.blocks.firstOrNull() is Block.Paragraph) item.blocks.drop(1) else item.blocks
                if (rest.any { it !is Block.ListBlock }) return false
                if (rest.any { nested -> !canFlattenList(nested as Block.ListBlock) }) return false
            }
            val info = when (val k = list.kind) {
                is ListKind.Bullet -> ListInfo(ctx.take(), false, 1, k.marker, list.tight)
                is ListKind.Ordered -> ListInfo(ctx.take(), true, k.start, k.delimiter, list.tight)
            }
            for (item in list.items) {
                val para = item.blocks.firstOrNull() as? Block.Paragraph
                val text = para?.let { RichText.fromInlines(it.inlines) } ?: RichText("")
                out += EditorRow(ctx.take(), RowKind.ListItem(info, item.checked), text, depth)
                for (nested in item.blocks.drop(if (para != null) 1 else 0)) flattenList(nested as Block.ListBlock, depth + 1, ctx, out)
            }
            return true
        }

        private fun canFlattenList(list: Block.ListBlock): Boolean = list.items.all { item ->
            val rest = if (item.blocks.firstOrNull() is Block.Paragraph) item.blocks.drop(1) else item.blocks
            rest.all { it is Block.ListBlock && canFlattenList(it) }
        }
    }
}
