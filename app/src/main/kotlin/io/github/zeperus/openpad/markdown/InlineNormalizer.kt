package io.github.zeperus.openpad.markdown

/**
 * Canonical form of inline content. Markdown has many spellings for the same meaning; both the parser output and
 * everything the editor produces go through here, so "same meaning" becomes plain `==` and the serializer only ever
 * sees well-formed input. Rules (idempotent):
 *
 *  - text is split at `\n` into [Inline.Text] / [Inline.SoftBreak]; `\r` is treated as a line break; NUL becomes U+FFFD
 *  - adjacent text merges; empty text and empty emphasis disappear
 *  - whitespace and line breaks at the edges of bold/italic/strike are moved *outside* of it (CommonMark cannot
 *    express `** a **`), formatting around nothing but whitespace disappears
 *  - a style that is already active is not applied again inside itself (bold in bold), links do not nest
 *  - `Strong` directly inside a sole `Emphasis` (and vice versa) has one canonical order (what `***x***` parses to)
 *  - spaces/tabs at the start and end of a line are dropped (they cannot be represented)
 *  - consecutive breaks collapse into one (a hard break wins)
 */
object InlineNormalizer {
    /** Normalizes the inline content of a paragraph, heading or list item: also trims its outer edges. */
    fun block(inlines: List<Inline>): List<Inline> = finish(normalize(inlines, emptySet()), outerEdges = true)

    /** Inline content of a heading: a heading is one line, so every line break (also inside links) becomes a space. */
    fun heading(inlines: List<Inline>): List<Inline> = block(flattenBreaks(inlines))

    private fun flattenBreaks(inlines: List<Inline>): List<Inline> = inlines.map {
        when (it) {
            Inline.SoftBreak, Inline.HardBreak -> Inline.Text(" ")
            is Inline.Strong -> Inline.Strong(flattenBreaks(it.children))
            is Inline.Emphasis -> Inline.Emphasis(flattenBreaks(it.children))
            is Inline.Strikethrough -> Inline.Strikethrough(flattenBreaks(it.children))
            is Inline.Link -> it.copy(children = flattenBreaks(it.children))
            else -> it
        }
    }

    /** Normalizes nested inline content (e.g. the children of a link) without trimming its edges. */
    fun nested(inlines: List<Inline>): List<Inline> = finish(normalize(inlines, emptySet()), outerEdges = false)

    private enum class Style { Strong, Emphasis, Strike, Link }

    private fun normalize(inlines: List<Inline>, active: Set<Style>): List<Inline> {
        val out = ArrayList<Inline>()
        for (inline in inlines) {
            when (inline) {
                is Inline.Text -> splitText(inline.text, out)
                Inline.SoftBreak, Inline.HardBreak -> out += inline
                is Inline.Code -> if (inline.code.isNotEmpty()) out += Inline.Code(inline.code.replace('\u0000', '�'))
                is Inline.Raw -> if (inline.markdown.isNotEmpty()) out += inline
                is Inline.Strong -> styled(Style.Strong, inline.children, active, out) { Inline.Strong(it) }
                is Inline.Emphasis -> styled(Style.Emphasis, inline.children, active, out) { Inline.Emphasis(it) }
                is Inline.Strikethrough -> styled(Style.Strike, inline.children, active, out) { Inline.Strikethrough(it) }
                is Inline.Link -> {
                    if (Style.Link in active) {
                        out += normalize(inline.children, active) // a link inside a link: keep only the text
                    } else {
                        out += Inline.Link(finish(normalize(inline.children, active + Style.Link), false), inline.destination, inline.title)
                    }
                }
            }
        }
        return mergeAdjacent(out)
    }

    private fun splitText(raw: String, out: MutableList<Inline>) {
        val text = raw.replace("\r\n", "\n").replace('\r', '\n').replace('\u0000', '�')
        var start = 0
        while (true) {
            val nl = text.indexOf('\n', start)
            if (nl < 0) break
            if (nl > start) out += Inline.Text(text.substring(start, nl))
            out += Inline.SoftBreak
            start = nl + 1
        }
        if (start < text.length) out += Inline.Text(text.substring(start))
    }

    private fun styled(
        style: Style,
        children: List<Inline>,
        active: Set<Style>,
        out: MutableList<Inline>,
        make: (List<Inline>) -> Inline,
    ) {
        if (style in active) { // already bold/italic/struck here: redundant, keep the content only
            out += normalize(children, active)
            return
        }
        var inner = finish(normalize(children, active + style), outerEdges = false)
        // move edge whitespace/breaks out of the delimiters
        val before = ArrayList<Inline>()
        val after = ArrayList<Inline>()
        inner = ArrayList(inner)
        while (inner.isNotEmpty()) {
            val first = inner.first()
            if (first is Inline.SoftBreak || first is Inline.HardBreak) { before += first; inner.removeAt(0); continue }
            if (first is Inline.Text) {
                val trimmed = first.text.trimStart(::isUnicodeWhitespace)
                if (trimmed.length != first.text.length) {
                    before += Inline.Text(first.text.substring(0, first.text.length - trimmed.length))
                    if (trimmed.isEmpty()) inner.removeAt(0) else inner[0] = Inline.Text(trimmed)
                    if (trimmed.isEmpty()) continue
                }
            }
            break
        }
        while (inner.isNotEmpty()) {
            val last = inner.last()
            if (last is Inline.SoftBreak || last is Inline.HardBreak) { after.add(0, last); inner.removeAt(inner.lastIndex); continue }
            if (last is Inline.Text) {
                val trimmed = last.text.trimEnd(::isUnicodeWhitespace)
                if (trimmed.length != last.text.length) {
                    after.add(0, Inline.Text(last.text.substring(trimmed.length)))
                    if (trimmed.isEmpty()) inner.removeAt(inner.lastIndex) else inner[inner.lastIndex] = Inline.Text(trimmed)
                    if (trimmed.isEmpty()) continue
                }
            }
            break
        }
        out += before
        // A style around nothing but another style (or a link) means the same with the two swapped. One canonical order,
        // outermost first: link, strike, italic, bold (italic around bold is also what `***x***` parses to). After the
        // swap the new inner part is normalized again, so chains of three settle completely.
        val only = inner.singleOrNull()
        val swapped: Inline? = when {
            only is Inline.Link && Style.Link !in active -> Inline.Link(listOf(make(only.children)), only.destination, only.title)
            only is Inline.Strikethrough && style != Style.Strike && Style.Strike !in active -> Inline.Strikethrough(listOf(make(only.children)))
            only is Inline.Emphasis && style == Style.Strong && Style.Emphasis !in active -> Inline.Emphasis(listOf(make(only.children)))
            else -> null
        }
        if (swapped != null) {
            out += normalize(listOf(swapped), active)
        } else if (inner.isNotEmpty()) {
            out += make(inner)
        }
        out += after
    }

    private fun mergeAdjacent(list: List<Inline>): List<Inline> {
        val out = ArrayList<Inline>(list.size)
        for (item in list) {
            val prev = out.lastOrNull()
            when {
                item is Inline.Text && prev is Inline.Text -> out[out.lastIndex] = Inline.Text(prev.text + item.text)
                // two code spans in a row would be read back as a single one
                item is Inline.Code && prev is Inline.Code -> out[out.lastIndex] = Inline.Code(prev.code + item.code)
                item is Inline.Strong && prev is Inline.Strong ->
                    out[out.lastIndex] = Inline.Strong(mergeAdjacent(prev.children + item.children))
                item is Inline.Emphasis && prev is Inline.Emphasis ->
                    out[out.lastIndex] = Inline.Emphasis(mergeAdjacent(prev.children + item.children))
                item is Inline.Strikethrough && prev is Inline.Strikethrough ->
                    out[out.lastIndex] = Inline.Strikethrough(mergeAdjacent(prev.children + item.children))
                else -> out += item
            }
        }
        return out
    }

    /** Line-edge cleanup: spaces around breaks, repeated breaks and (for block content) the outer edges. */
    private fun finish(list: List<Inline>, outerEdges: Boolean): List<Inline> {
        var cur: List<Inline> = list
        repeat(8) { // trimming can empty a text, which can make breaks adjacent: iterate to a fixed point
            val next = finishOnce(cur, outerEdges)
            if (next == cur) return next
            cur = next
        }
        return cur
    }

    private fun finishOnce(list: List<Inline>, outerEdges: Boolean): List<Inline> {
        val out = ArrayList<Inline>(list.size)
        for (item in list) { // collapse consecutive breaks
            val prev = out.lastOrNull()
            if ((item is Inline.SoftBreak || item is Inline.HardBreak) && (prev is Inline.SoftBreak || prev is Inline.HardBreak)) {
                if (item is Inline.HardBreak) out[out.lastIndex] = item
            } else {
                out += item
            }
        }
        for (i in out.indices) { // spaces/tabs that touch a line break
            val item = out[i]
            if (item is Inline.SoftBreak || item is Inline.HardBreak) {
                (out.getOrNull(i - 1) as? Inline.Text)?.let { out[i - 1] = Inline.Text(it.text.trimEnd(' ', '\t')) }
                (out.getOrNull(i + 1) as? Inline.Text)?.let { out[i + 1] = Inline.Text(it.text.trimStart(' ', '\t')) }
            }
        }
        if (outerEdges) {
            (out.firstOrNull() as? Inline.Text)?.let { out[0] = Inline.Text(it.text.trimStart(' ', '\t')) }
            (out.lastOrNull() as? Inline.Text)?.let { out[out.lastIndex] = Inline.Text(it.text.trimEnd(' ', '\t')) }
        }
        val cleaned = mergeAdjacent(out.filterNot { it is Inline.Text && it.text.isEmpty() }).toMutableList()
        if (outerEdges) {
            while (cleaned.isNotEmpty() && (cleaned.first() is Inline.SoftBreak || cleaned.first() is Inline.HardBreak)) cleaned.removeAt(0)
            while (cleaned.isNotEmpty() && (cleaned.last() is Inline.SoftBreak || cleaned.last() is Inline.HardBreak)) cleaned.removeAt(cleaned.lastIndex)
        }
        return cleaned
    }
}

/** CommonMark's "Unicode whitespace" for a code point. */
internal fun isUnicodeWhitespace(cp: Int): Boolean =
    cp == 32 || cp == 9 || cp == 10 || cp == 13 || cp == 12 || Character.getType(cp) == Character.SPACE_SEPARATOR.toInt()

/** CommonMark's "Unicode whitespace": tab, line feed, form feed, carriage return and any space separator. */
internal fun isUnicodeWhitespace(c: Char): Boolean =
    c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\u000C' || Character.getType(c) == Character.SPACE_SEPARATOR.toInt()
