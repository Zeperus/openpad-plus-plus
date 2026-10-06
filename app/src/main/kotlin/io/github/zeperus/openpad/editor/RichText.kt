package io.github.zeperus.openpad.editor

import io.github.zeperus.openpad.markdown.Inline
import io.github.zeperus.openpad.markdown.InlineNormalizer
import io.github.zeperus.openpad.markdown.MarkdownSerializer

enum class SpanKind { Bold, Italic, Strike, Code, Link, Raw, HardBreak }

/** A formatted range `[start, end)` of a [RichText]. [href]/[title] are only used by [SpanKind.Link]. */
data class Span(val kind: SpanKind, val start: Int, val end: Int, val href: String? = null, val title: String? = null)

/**
 * The text of one editor row with its formatting as spans, the form a text field can edit directly. A line break inside
 * the text is `\n` (a [SpanKind.HardBreak] span marks a forced break, otherwise it is a soft break).
 *
 * Immutable and always normalized: spans are inside the text, non-empty, and overlapping/adjacent spans of the same
 * kind (and the same link target) are merged.
 */
class RichText private constructor(val text: String, val spans: List<Span>) {
    constructor(text: String) : this(text, emptyList())

    val length: Int get() = text.length
    val isEmpty: Boolean get() = text.isEmpty()

    /** The kinds that apply to the character at [index]. */
    fun kindsAt(index: Int): Set<SpanKind> =
        spans.filter { index >= it.start && index < it.end }.mapTo(HashSet()) { it.kind }

    fun linkAt(index: Int): Span? = spans.firstOrNull { it.kind == SpanKind.Link && index >= it.start && index < it.end }

    /** The link at the cursor: also when the cursor sits right after the last character of the link. */
    fun linkAtCursor(offset: Int): Span? = linkAt(offset) ?: linkAt(offset - 1)

    fun hasKind(kind: SpanKind, start: Int, end: Int): Boolean =
        end > start && (start until end).all { kind in kindsAt(it) }

    fun substring(start: Int, end: Int): RichText = slice(start, end)

    // ---- Editing ---------------------------------------------------------------------------------------------

    /**
     * Replaces `[start, end)` with [insert]. Formatting of the surrounding text is kept; the inserted text takes
     * [style] if given, otherwise the bold/italic/strike formatting of the character before it (typing continues the
     * style). Code and links are not continued at their right edge, so they can be left by simply typing on.
     */
    fun replace(start: Int, end: Int, insert: String, style: Set<SpanKind>? = null): RichText {
        val s = start.coerceIn(0, length)
        val e = end.coerceIn(s, length)
        val inherited = style ?: inheritedAt(s)
        val len = insert.length
        val delta = len - (e - s)
        val moved = ArrayList<Span>()
        for (sp in spans) {
            val a = sp.start
            val b = sp.end
            when {
                b <= s -> moved += sp // before the replaced range (a span ending exactly at the cursor does not grow)
                a >= e -> moved += sp.copy(start = a + delta, end = b + delta) // after it
                else -> { // overlaps the replaced range
                    // starts before: keeps its start; starts inside: begins after the inserted text
                    val newStart = if (a < s) a else s + len
                    // reaches past the range: keeps its end (moved); ends inside: ends where the range began
                    val newEnd = if (b > e) b + delta else s
                    if (newEnd > newStart) moved += sp.copy(start = newStart, end = newEnd)
                }
            }
        }
        val text = this.text.substring(0, s) + insert + this.text.substring(e)
        val withInsert = ArrayList(moved)
        if (len > 0) for (kind in inherited) {
            if (kind == SpanKind.Link || kind == SpanKind.HardBreak || kind == SpanKind.Code && style == null) continue
            withInsert += Span(kind, s, s + len)
        }
        return of(text, withInsert)
    }

    private fun inheritedAt(index: Int): Set<SpanKind> {
        if (index <= 0) return emptySet()
        val before = kindsAt(index - 1)
        return before.filterTo(HashSet()) { it == SpanKind.Bold || it == SpanKind.Italic || it == SpanKind.Strike }
    }

    /** Adds [kind] to the whole range if some of it lacks it, otherwise removes it from the range. */
    fun toggle(kind: SpanKind, start: Int, end: Int): RichText {
        if (end <= start) return this
        return if (hasKind(kind, start, end)) remove(kind, start, end) else add(kind, start, end)
    }

    fun add(kind: SpanKind, start: Int, end: Int, href: String? = null, title: String? = null): RichText {
        val s = start.coerceIn(0, length)
        val e = end.coerceIn(s, length)
        if (e <= s) return this
        val base = if (kind == SpanKind.Link) remove(SpanKind.Link, s, e) else this
        return of(text, base.spans + Span(kind, s, e, href, title))
    }

    fun remove(kind: SpanKind, start: Int, end: Int): RichText {
        val out = ArrayList<Span>()
        for (sp in spans) {
            if (sp.kind != kind || sp.end <= start || sp.start >= end) { out += sp; continue }
            if (sp.start < start) out += sp.copy(end = start)
            if (sp.end > end) out += sp.copy(start = end)
        }
        return of(text, out)
    }

    /** Splits at [offset] into the text before and after it; spans crossing the offset are cut. */
    fun split(offset: Int): Pair<RichText, RichText> = slice(0, offset) to slice(offset, length)

    fun plus(other: RichText): RichText =
        of(text + other.text, spans + other.spans.map { it.copy(start = it.start + length, end = it.end + length) })

    private fun slice(start: Int, end: Int): RichText {
        val s = start.coerceIn(0, length)
        val e = end.coerceIn(s, length)
        val cut = spans.mapNotNull { sp ->
            val a = maxOf(sp.start, s)
            val b = minOf(sp.end, e)
            if (b > a) sp.copy(start = a - s, end = b - s) else null
        }
        return of(text.substring(s, e), cut)
    }

    /** The same text with all formatting removed. */
    fun plain(): RichText = RichText(text)

    override fun equals(other: Any?) = other is RichText && other.text == text && other.spans == spans
    override fun hashCode() = text.hashCode() * 31 + spans.hashCode()
    override fun toString() = "RichText(${text.replace("\n", "⏎")}, $spans)"

    // ---- Conversion to and from the document model -----------------------------------------------------------

    /** The inline content this text stands for, in the canonical form of [InlineNormalizer]. */
    fun toInlines(): List<Inline> {
        if (text.isEmpty()) return emptyList()
        val runs = ArrayList<Run>()
        for (i in text.indices) {
            val key = keyAt(i)
            val last = runs.lastOrNull()
            if (last != null && last.key == key && text[i] != '\n' && last.text[0] != '\n') last.text.append(text[i]) else runs += Run(StringBuilder().append(text[i]), key)
        }
        return InlineNormalizer.block(build(runs, emptySet()))
    }

    private class Key(val bold: Boolean, val italic: Boolean, val strike: Boolean, val code: Boolean, val raw: Boolean, val hard: Boolean, val link: Span?) {
        override fun equals(other: Any?) = other is Key && bold == other.bold && italic == other.italic && strike == other.strike &&
            code == other.code && raw == other.raw && hard == other.hard && link?.href == other.link?.href && link?.title == other.link?.title &&
            (link == null) == (other.link == null)
        override fun hashCode() = listOf(bold, italic, strike, code, raw, hard, link?.href, link?.title).hashCode()
    }

    private class Run(val text: StringBuilder, val key: Key)

    private fun keyAt(i: Int): Key {
        var bold = false; var italic = false; var strike = false; var code = false; var raw = false; var hard = false; var link: Span? = null
        for (sp in spans) if (i >= sp.start && i < sp.end) when (sp.kind) {
            SpanKind.Bold -> bold = true
            SpanKind.Italic -> italic = true
            SpanKind.Strike -> strike = true
            SpanKind.Code -> code = true
            SpanKind.Raw -> raw = true
            SpanKind.HardBreak -> hard = true
            SpanKind.Link -> link = sp
        }
        return Key(bold, italic, strike, code, raw, hard, link)
    }

    // Styles that can wrap text. Priority decides the order when two cover exactly the same range: link outermost, then
    // strike, italic, bold (italic around bold is what `***x***` parses to).
    private sealed interface Style { val priority: Int }
    private data class LinkStyle(val href: String?, val title: String?) : Style { override val priority get() = 0 }
    private data object StrikeStyle : Style { override val priority get() = 1 }
    private data object ItalicStyle : Style { override val priority get() = 2 }
    private data object BoldStyle : Style { override val priority get() = 3 }

    private fun stylesOf(k: Key): List<Style> = buildList {
        k.link?.let { add(LinkStyle(it.href, it.title)) }
        if (k.strike) add(StrikeStyle)
        if (k.italic) add(ItalicStyle)
        if (k.bold) add(BoldStyle)
    }

    /**
     * Builds the inline tree: at each position the style that stays on the longest wins the outer position, so
     * `**bold *italic* bold**` stays one bold span with an italic part inside it instead of three pieces.
     */
    private fun build(runs: List<Run>, excluded: Set<Style>): List<Inline> {
        val out = ArrayList<Inline>()
        var i = 0
        while (i < runs.size) {
            val candidates = stylesOf(runs[i].key).filter { it !in excluded }
            if (candidates.isEmpty()) {
                out.addAll(leaf(runs[i]))
                i++
                continue
            }
            var best = candidates.first()
            var bestEnd = i + 1
            for (c in candidates) {
                var j = i + 1
                while (j < runs.size && c in stylesOf(runs[j].key)) j++
                if (j > bestEnd || (j == bestEnd && c.priority < best.priority)) { best = c; bestEnd = j }
            }
            val inner = build(runs.subList(i, bestEnd), excluded + best)
            out.add(
                when (best) {
                    is LinkStyle -> Inline.Link(inner, best.href.orEmpty(), best.title)
                    StrikeStyle -> Inline.Strikethrough(inner)
                    ItalicStyle -> Inline.Emphasis(inner)
                    BoldStyle -> Inline.Strong(inner)
                },
            )
            i = bestEnd
        }
        return out
    }

    private fun leaf(run: Run): List<Inline> {
        val s = run.text.toString()
        return when {
            s == "\n" -> listOf(if (run.key.hard) Inline.HardBreak else Inline.SoftBreak)
            run.key.raw -> listOf(Inline.Raw(s))
            run.key.code -> listOf(Inline.Code(s))
            else -> listOf(Inline.Text(s))
        }
    }

    companion object {
        /** Normalizes: spans inside the text, no empty ones, merged where they touch. */
        fun of(text: String, spans: List<Span>): RichText {
            val clean = spans.mapNotNull { sp ->
                var a = sp.start.coerceIn(0, text.length)
                var b = sp.end.coerceIn(a, text.length)
                if (sp.kind == SpanKind.Bold || sp.kind == SpanKind.Italic || sp.kind == SpanKind.Strike) {
                    // formatting on a line break at the edge of a span means nothing (and is written outside the markers)
                    while (a < b && text[a] == '\n') a++
                    while (b > a && text[b - 1] == '\n') b--
                }
                if (b > a) sp.copy(start = a, end = b) else null
            }
            return RichText(text, merge(clean))
        }

        private fun merge(spans: List<Span>): List<Span> {
            val out = ArrayList<Span>()
            for (kind in SpanKind.entries) {
                val same = spans.filter { it.kind == kind }.sortedWith(compareBy({ it.start }, { it.end }))
                var cur: Span? = null
                for (sp in same) {
                    val c = cur
                    cur = when {
                        c == null -> sp
                        // hard breaks are single characters and stay separate
                        kind == SpanKind.HardBreak -> { out += c; sp }
                        sp.start <= c.end && sp.href == c.href && sp.title == c.title -> c.copy(end = maxOf(c.end, sp.end))
                        else -> { out += c; sp }
                    }
                }
                cur?.let { out += it }
            }
            return out.sortedWith(compareBy({ it.start }, { it.kind.ordinal }, { it.end }))
        }

        /** Flattens inline content into text plus spans. Raw HTML/images become [SpanKind.Raw] text. */
        fun fromInlines(inlines: List<Inline>): RichText {
            val sb = StringBuilder()
            val spans = ArrayList<Span>()
            fun walk(list: List<Inline>) {
                for (inline in list) when (inline) {
                    is Inline.Text -> sb.append(inline.text)
                    Inline.SoftBreak -> sb.append('\n')
                    Inline.HardBreak -> { spans += Span(SpanKind.HardBreak, sb.length, sb.length + 1); sb.append('\n') }
                    is Inline.Code -> { val s = sb.length; sb.append(inline.code); spans += Span(SpanKind.Code, s, sb.length) }
                    is Inline.Raw -> { val s = sb.length; sb.append(inline.markdown); spans += Span(SpanKind.Raw, s, sb.length) }
                    is Inline.Strong -> { val s = sb.length; walk(inline.children); spans += Span(SpanKind.Bold, s, sb.length) }
                    is Inline.Emphasis -> { val s = sb.length; walk(inline.children); spans += Span(SpanKind.Italic, s, sb.length) }
                    is Inline.Strikethrough -> { val s = sb.length; walk(inline.children); spans += Span(SpanKind.Strike, s, sb.length) }
                    is Inline.Link -> {
                        val s = sb.length
                        walk(inline.children)
                        if (sb.length > s) {
                            spans += Span(SpanKind.Link, s, sb.length, inline.destination, inline.title)
                        } else { // a link without text has nothing to attach to: keep it as the Markdown it is
                            sb.append("[](").append(MarkdownSerializer.linkTarget(inline.destination, inline.title)).append(')')
                            spans += Span(SpanKind.Raw, s, sb.length)
                        }
                    }
                }
            }
            walk(inlines)
            return of(sb.toString(), spans)
        }
    }
}
