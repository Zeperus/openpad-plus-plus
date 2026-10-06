package io.github.zeperus.openpad.markdown

/**
 * The openPad++ document model: what a Markdown file *means*, independent of the parser library, of Compose and
 * of any particular spelling (`*x*` vs `_x_`, `-` vs `*` bullets, ...). Pure immutable values, so structural
 * equality (`==`) is semantic equality - which is what the round-trip tests rely on.
 *
 * Anything the structured model cannot represent is kept verbatim as [Block.Raw] / [Inline.Raw] instead of being
 * dropped: data safety beats formatting everything.
 */
data class OpenPadDocument(val blocks: List<Block> = emptyList())

sealed interface Block {
    data class Paragraph(val inlines: List<Inline>) : Block

    data class Heading(val level: Int, val inlines: List<Inline>) : Block {
        init { require(level in 1..6) { "heading level $level" } }
    }

    /**
     * A list. Items with `checked != null` are task-list items (`- [ ]` / `- [x]`); a list whose items all are
     * is a *task list* ([isTaskList]). [tight] is CommonMark's tight/loose distinction (no blank lines between items).
     */
    data class ListBlock(val kind: ListKind, val items: List<ListItem>, val tight: Boolean = true) : Block {
        val isTaskList: Boolean get() = items.isNotEmpty() && items.all { it.checked != null }
    }

    data class Quote(val blocks: List<Block>) : Block

    /** [code] is the content without the trailing line break. */
    data class CodeBlock(val code: String, val info: String? = null, val style: CodeStyle = CodeStyle.Fenced) : Block

    data object Rule : Block

    /** Markdown the model does not interpret (HTML blocks, tables, link reference definitions, ...), kept verbatim. */
    data class Raw(val markdown: String) : Block
}

enum class CodeStyle { Fenced, Indented }

sealed interface ListKind {
    /** [marker] is `-`, `*` or `+`. */
    data class Bullet(val marker: Char = '-') : ListKind

    /** [delimiter] is `.` or `)`. Only [start] matters for numbering; items are numbered consecutively. */
    data class Ordered(val start: Int = 1, val delimiter: Char = '.') : ListKind
}

data class ListItem(val blocks: List<Block>, val checked: Boolean? = null)

sealed interface Inline {
    data class Text(val text: String) : Inline

    /** A line break inside a paragraph that renders as a space. */
    data object SoftBreak : Inline

    /** A forced line break. */
    data object HardBreak : Inline

    data class Emphasis(val children: List<Inline>) : Inline // italic

    data class Strong(val children: List<Inline>) : Inline // bold

    data class Strikethrough(val children: List<Inline>) : Inline

    data class Code(val code: String) : Inline

    data class Link(val children: List<Inline>, val destination: String, val title: String? = null) : Inline

    /** Inline Markdown/HTML the model does not interpret (images, inline HTML), kept verbatim. */
    data class Raw(val markdown: String) : Inline
}

/** The plain text of inline content without any formatting (line breaks become `\n`, raw/code kept as written). */
fun List<Inline>.plainText(): String = buildString { appendPlain(this@plainText) }

private fun StringBuilder.appendPlain(inlines: List<Inline>) {
    for (i in inlines) when (i) {
        is Inline.Text -> append(i.text)
        Inline.SoftBreak, Inline.HardBreak -> append('\n')
        is Inline.Emphasis -> appendPlain(i.children)
        is Inline.Strong -> appendPlain(i.children)
        is Inline.Strikethrough -> appendPlain(i.children)
        is Inline.Code -> append(i.code)
        is Inline.Link -> appendPlain(i.children)
        is Inline.Raw -> append(i.markdown)
    }
}
