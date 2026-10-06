package io.github.zeperus.openpad.markdown

/**
 * The serializer writes a bold/italic/strike/link span as an HTML tag when CommonMark's delimiter rules cannot express
 * it in its position (for example `a**!**`). Reading it back yields raw inline tags; this folds them into the styles
 * they mean, so "did it survive?" can be answered by comparing models.
 */
internal object HtmlSpans {
    private val open = mapOf("<strong>" to "strong", "<em>" to "em", "<del>" to "del")
    private val close = mapOf("</strong>" to "strong", "</em>" to "em", "</del>" to "del")
    private val anchor = Regex("^<a href=\"([^\"]*)\"(?: title=\"([^\"]*)\")?>$")

    private fun unescape(v: String) = v.replace("&quot;", "\"").replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&")

    fun fold(blocks: List<Block>): List<Block> = blocks.map { fold(it) }

    private fun fold(b: Block): Block = when (b) {
        is Block.Paragraph -> Block.Paragraph(foldInlines(b.inlines))
        is Block.Heading -> Block.Heading(b.level, foldInlines(b.inlines))
        is Block.Quote -> Block.Quote(b.blocks.map { fold(it) })
        is Block.ListBlock -> b.copy(items = b.items.map { it.copy(blocks = it.blocks.map { x -> fold(x) }) })
        else -> b
    }

    private class Frame(val kind: String?, val href: String? = null, val title: String? = null) {
        val items = ArrayList<Inline>()
    }

    fun foldInlines(inlines: List<Inline>): List<Inline> {
        val stack = ArrayList<Frame>().apply { add(Frame(null)) }
        for (raw in inlines) {
            val inline = when (raw) {
                is Inline.Strong -> Inline.Strong(foldInlines(raw.children))
                is Inline.Emphasis -> Inline.Emphasis(foldInlines(raw.children))
                is Inline.Strikethrough -> Inline.Strikethrough(foldInlines(raw.children))
                is Inline.Link -> raw.copy(children = foldInlines(raw.children))
                else -> raw
            }
            val tag = (inline as? Inline.Raw)?.markdown
            val anchorMatch = tag?.let { anchor.find(it) }
            when {
                tag != null && tag in open -> stack += Frame(open.getValue(tag))
                anchorMatch != null -> stack += Frame("a", unescape(anchorMatch.groupValues[1]), anchorMatch.groups[2]?.value?.let { unescape(it) })
                tag == "</a>" && stack.size > 1 && stack.last().kind == "a" -> {
                    val done = stack.removeAt(stack.lastIndex)
                    stack.last().items += Inline.Link(done.items, done.href.orEmpty(), done.title)
                }
                tag != null && tag in close && stack.size > 1 && stack.last().kind == close.getValue(tag) -> {
                    val done = stack.removeAt(stack.lastIndex)
                    stack.last().items += when (done.kind) {
                        "strong" -> Inline.Strong(done.items)
                        "em" -> Inline.Emphasis(done.items)
                        else -> Inline.Strikethrough(done.items)
                    }
                }
                else -> stack.last().items += inline
            }
        }
        while (stack.size > 1) { // an opening tag that never closed: it was real raw HTML, keep it as such
            val unclosed = stack.removeAt(stack.lastIndex)
            val text = when (unclosed.kind) {
                "a" -> "<a href=\"${unclosed.href}\">"
                else -> "<${unclosed.kind}>"
            }
            stack.last().items += Inline.Raw(text)
            stack.last().items += unclosed.items
        }
        return stack[0].items
    }
}
