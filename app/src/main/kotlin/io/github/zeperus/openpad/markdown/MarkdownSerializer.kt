package io.github.zeperus.openpad.markdown

/**
 * Writes the document model back as Markdown.
 *
 * Normalization rules (documented in docs/editor.md): bullets keep their marker, numbered lists are numbered
 * consecutively, `*italic*` / `**bold**` / `~~strike~~`, ATX headings (`# Title`), fenced code, `---` for rules,
 * a single blank line between blocks, backslash hard breaks, links as `[text](url "title")`.
 *
 * Safety nets:
 *  - text is escaped so that it can never turn into markup (including at the start of a line);
 *  - every block that is *regenerated* is parsed again and compared with the model; if some delimiter edge case
 *    (CommonMark's flanking rules) made it come out differently, alternative spellings are tried, the last resort
 *    being HTML tags, which any Markdown reader understands;
 *  - [Block.Raw] / [Inline.Raw] are written verbatim.
 */
object MarkdownSerializer {
    /** A whole document, regenerated from scratch. Ends with a line break unless it is empty. */
    fun serialize(document: OpenPadDocument, eol: String = "\n"): String {
        val parts = renderTopLevel(document.blocks)
        if (parts.isEmpty()) return ""
        return parts.joinToString("\n\n", postfix = "\n").withEol(eol)
    }

    /** One block without trailing line break (LF line endings). */
    fun serializeBlock(block: Block): String = renderVerified(block)

    /** The inline content of a paragraph as Markdown (LF line endings). */
    fun serializeInlines(inlines: List<Inline>): String =
        paragraphLines(InlineNormalizer.block(inlines), Strategy.Default).joinToString("\n")

    /**
     * For editing: writes [blocks] but keeps the original text of every block that is unchanged. [origins] says,
     * for each current block, the index of the [original] block it came from (or null for a new block); an origin
     * is only reused if the block is still equal to what was parsed. The result differs from the original only
     * where the user actually changed something.
     */
    fun serializeIncremental(original: ParsedDocument?, blocks: List<Block>, origins: List<Int?>): String {
        require(origins.size == blocks.size) { "one origin per block" }
        if (original == null || !original.layoutValid) return serialize(OpenPadDocument(blocks), original?.eol ?: "\n")
        val eol = original.eol
        class Piece(val text: String, val verbatim: Int?, val lineage: Int?) // lineage: the original block this one came from (also when rewritten)
        val pieces = ArrayList<Piece>()
        val regenerate = HashSet<Int>() // unchanged lists that have to be written again to keep their neighbours apart
        fun verbatim(i: Int): Int? = if (i in regenerate) null else origins[i]?.takeIf { it in original.blocks.indices && original.blocks[it].block == blocks[i] }
        var written: Written? = null // the list marker of the block just written (a list written next to it must differ)
        var listJustRewritten = false
        for ((i, block) in blocks.withIndex()) {
            // an indented code block right after a list that was written again would be read as part of that list
            if (listJustRewritten && block is Block.CodeBlock && block.style == CodeStyle.Indented) regenerate += i
            val o = verbatim(i)
            val text: String
            if (o != null) {
                text = original.blocks[o].source
                pieces += Piece(text, o, o)
            } else {
                // also keep clear of an unchanged list that follows: its text cannot be changed, ours can
                val next = (i + 1 until blocks.size).firstOrNull { BlockNormalizer.compareForm(blocks[it]) != null }
                var following = if (block is Block.ListBlock && next != null) verbatim(next)?.let { writtenFromText(original.blocks[it].source) } else null
                // numbered lists have only two delimiters: if both neighbours use different ones, the next list is written again
                if (following != null && written != null && !following.bullet && !written.bullet && following.marker != written.marker) {
                    regenerate += next!!
                    following = null
                }
                text = renderVerified(block, blocks.getOrNull(i - 1), written?.let { if (following != null) it.copy(other = following) else it } ?: following)
                if (text.isNotEmpty()) pieces += Piece(text.withEol(eol), null, origins[i]?.takeIf { it in original.blocks.indices })
            }
            if (text.isNotEmpty()) {
                written = if (block is Block.ListBlock) writtenFromText(text) else null
                listJustRewritten = block is Block.ListBlock && o == null
            }
        }
        if (pieces.isEmpty()) return if (blocks.isEmpty() && original.blocks.isEmpty()) original.leading else ""
        val out = StringBuilder()
        if (pieces.first().verbatim == 0) out.append(original.leading)
        for ((i, piece) in pieces.withIndex()) {
            out.append(piece.text)
            val o = piece.lineage
            val next = pieces.getOrNull(i + 1)
            when {
                next == null -> out.append(if (piece.verbatim != null && piece.verbatim == original.blocks.lastIndex) original.blocks[piece.verbatim].gapAfter else eol)
                // two untouched neighbours keep exactly the gap they had
                piece.verbatim != null && next.verbatim == piece.verbatim + 1 -> out.append(original.blocks[piece.verbatim].gapAfter)
                // next to a rewritten block the blank lines of the original are kept (a lone line break is not: the rewritten text
                // might not stay separate from its neighbour without a blank line)
                o != null && next.lineage == o + 1 && lineBreaks.findAll(original.blocks[o].gapAfter).count() >= 2 -> out.append(original.blocks[o].gapAfter)
                else -> out.append(eol).append(eol)
            }
        }
        return out.toString()
    }

    private val lineBreaks = Regex("\r\n|\n|\r")

    // ---- Blocks ----------------------------------------------------------------------------------------------

    private enum class Strategy { Default, UnderscoreEmphasis, Html }

    private val strategies = listOf(Strategy.Default, Strategy.UnderscoreEmphasis, Strategy.Html)

    private fun renderTopLevel(blocks: List<Block>): List<String> {
        val out = ArrayList<String>()
        var previous: Block? = null
        var written: Written? = null
        for (block in blocks) {
            val text = renderVerified(block, previous, written)
            if (text.isNotEmpty()) written = if (block is Block.ListBlock) writtenFromText(text) else null
            if (text.isNotEmpty()) {
                out += text
                previous = block
            }
        }
        return out
    }

    /** The list marker that was actually written for a list (it may differ from the model to keep lists apart). */
    private data class Written(val marker: Char, val bullet: Boolean, val other: Written? = null)

    private fun ListKind.markerChar(): Char = when (this) {
        is ListKind.Bullet -> marker
        is ListKind.Ordered -> delimiter
    }

    /** Renders a top-level block and checks that it parses back to the same meaning, trying other spellings if not. */
    private fun renderVerified(block: Block, previous: Block? = null, written: Written? = null): String {
        val wanted = BlockNormalizer.compareForm(block) ?: return ""
        if (block is Block.Raw) return block.markdown
        var last = ""
        for (strategy in strategies) {
            val lines = renderBlock(block, strategy, previous, written ?: previous?.let { writtenOf(it) })
            last = lines.joinToString("\n")
            if (strategy == Strategy.Html) break
            val reparsed = HtmlSpans.fold(MarkdownParser.parseDocument(last).blocks).mapNotNull { BlockNormalizer.compareForm(it) }
            if (reparsed == listOf(wanted)) return last
        }
        return last
    }

    private val markerPattern = Regex("^(?:([-*+])|\\d+([.)]))")

    /** The marker a rendered list actually starts with (it may differ from the model to keep lists apart). */
    private fun writtenFromText(text: String): Written? {
        val m = markerPattern.find(text) ?: return null
        return m.groups[1]?.let { Written(it.value.single(), true) } ?: Written(m.groups[2]!!.value.single(), false)
    }

    private fun writtenOf(block: Block): Written? =
        (block as? Block.ListBlock)?.let { Written(it.kind.markerChar(), it.kind is ListKind.Bullet) }

    private fun renderBlocks(blocks: List<Block>, strategy: Strategy, tight: Boolean = false): List<String> {
        val out = ArrayList<String>()
        var previous: Block? = null
        var written: Written? = null
        for (block in blocks) {
            val lines = renderBlock(block, strategy, previous, written)
            if (lines.isEmpty()) continue
            written = if (block is Block.ListBlock) writtenFromText(lines.first()) else null
            if (previous != null) {
                val adjacent = tight && canBeTightAfter(previous, block)
                if (!adjacent) out += ""
            }
            out += lines
            previous = block
        }
        return out
    }

    /**
     * Can [next] directly follow [previous] without a blank line? A list may only interrupt a paragraph if its first
     * item has content and, for numbered lists, starts at 1 (CommonMark); a fenced code block always may.
     */
    private fun canBeTightAfter(previous: Block, next: Block): Boolean = when {
        next is Block.CodeBlock -> !isWrittenIndented(next, previous)
        next is Block.ListBlock -> previous !is Block.Paragraph || (
            next.items.firstOrNull()?.blocks?.any { BlockNormalizer.compareForm(it) != null } == true &&
                (next.kind !is ListKind.Ordered || (next.kind as ListKind.Ordered).start == 1)
            )
        else -> false
    }

    private fun renderBlock(block: Block, strategy: Strategy, previous: Block?, written: Written?): List<String> = when (block) {
        is Block.Paragraph -> paragraphLines(InlineNormalizer.block(block.inlines), strategy)
        is Block.Heading -> listOf(heading(block, strategy))
        Block.Rule -> listOf("---")
        is Block.Raw -> if (block.markdown.isEmpty()) emptyList() else block.markdown.split("\n")
        is Block.CodeBlock -> codeBlock(block, previous)
        is Block.Quote -> {
            val inner = renderBlocks(block.blocks, strategy)
            if (inner.isEmpty()) listOf(">") else inner.map { if (it.isEmpty()) ">" else "> $it" }
        }
        is Block.ListBlock -> list(block, strategy, written)
    }

    private fun heading(block: Block.Heading, strategy: Strategy): String {
        val line = inlinesToString(InlineNormalizer.heading(block.inlines), strategy).replace("\n", " ").trim()
        // a trailing run of # would be taken as a closing sequence
        val safe = line.replace(Regex("(^|\\s)(#+)$")) { m -> m.groupValues[1] + "\\" + m.groupValues[2] }
        val first = if (safe.isNotEmpty()) safe.escapeLineStart() else safe
        return "#".repeat(block.level) + if (first.isEmpty()) "" else " $first"
    }

    /**
     * Indented code cannot carry an info string, begin or end with a blank line, directly follow a list (it would
     * become part of the last item) or follow another indented code block (the two would merge).
     */
    private fun isWrittenIndented(block: Block.CodeBlock, previous: Block?): Boolean {
        val lines = block.code.split("\n")
        return block.style == CodeStyle.Indented && block.info.isNullOrBlank() &&
            previous !is Block.ListBlock && !(previous is Block.CodeBlock && previous.style == CodeStyle.Indented) &&
            lines.first().isNotBlank() && lines.last().isNotBlank()
    }

    private fun codeBlock(block: Block.CodeBlock, previous: Block?): List<String> {
        val lines = block.code.split("\n")
        if (isWrittenIndented(block, previous)) return lines.map { if (it.isEmpty()) "" else "    $it" }
        val info = block.info?.trim()?.replace("\n", " ")
        val char = if (info != null && '`' in info) '~' else '`'
        val longest = Regex(Regex.escape(char.toString()) + "+").findAll(block.code).maxOfOrNull { it.value.length } ?: 0
        val fence = char.toString().repeat(maxOf(3, longest + 1))
        return listOf(fence + (info ?: "")) + (if (block.code.isEmpty()) emptyList() else lines) + fence
    }

    private fun list(block: Block.ListBlock, strategy: Strategy, written: Written?): List<String> {
        val kind = block.kind
        val tight = effectiveTight(block)
        val bulletChar = (kind as? ListKind.Bullet)?.let { bulletMarker(it, written) }
        val orderedChar = (kind as? ListKind.Ordered)?.let { orderedDelimiter(it, written) }
        val out = ArrayList<String>()
        for ((index, item) in block.items.withIndex()) {
            val marker = when (kind) {
                is ListKind.Bullet -> "$bulletChar "
                is ListKind.Ordered -> "${kind.start.coerceAtLeast(0) + index}$orderedChar "
            }
            val lines = ArrayList(renderBlocks(item.blocks, strategy, tight))
            // a task marker needs text after it; an empty "- [ ]" would be read back as the text "[ ]"
            if (hasTaskMarker(item) && lines.isNotEmpty()) {
                val prefix = if (item.checked == true) "[x]" else "[ ]"
                lines[0] = "$prefix ${lines[0]}"
            }
            if (!tight && index > 0) out += ""
            // "- ---" would be read as a thematic break made of the marker and the rule: use another rule character
            if (lines.firstOrNull() == "---" && bulletChar == '-') lines[0] = "***"
            if (lines.isEmpty()) {
                out += marker.trimEnd()
            } else {
                val indent = " ".repeat(marker.length)
                out += marker + lines[0]
                for (rest in lines.drop(1)) out += if (rest.isEmpty()) "" else indent + rest
            }
        }
        return out
    }

    /** A task marker needs a paragraph with text right behind it. */
    internal fun hasTaskMarker(item: ListItem): Boolean =
        item.checked != null && item.blocks.firstOrNull { BlockNormalizer.compareForm(it) != null } is Block.Paragraph

    // Two neighbouring lists with the same marker would be read back as one list: use another marker.
    private fun bulletMarker(kind: ListKind.Bullet, written: Written?): Char {
        val avoid = listOfNotNull(written, written?.other).filter { it.bullet }.map { it.marker }
        if (kind.marker !in avoid) return kind.marker
        return "-*+".first { it !in avoid }
    }

    private fun orderedDelimiter(kind: ListKind.Ordered, written: Written?): Char {
        val avoid = listOfNotNull(written, written?.other).filter { !it.bullet }.map { it.marker }
        if (kind.delimiter !in avoid) return kind.delimiter
        return if (kind.delimiter == '.') ')' else '.'
    }

    /**
     * True if the list is written without blank lines (CommonMark "tight"). A list can only be loose if there is
     * something to separate: several items, or blocks inside an item that cannot sit directly next to each other.
     */
    internal fun effectiveTight(block: Block.ListBlock): Boolean {
        val itemsAdjacent = block.items.all { item ->
            item.blocks.filter { BlockNormalizer.compareForm(it) != null }.zipWithNext().all { (prev, next) -> canBeTightAfter(prev, next) } &&
                item.blocks.none { it is Block.Raw && it.markdown.contains("\n\n") }
        }
        return itemsAdjacent && (block.tight || block.items.size <= 1)
    }

    // ---- Inlines ---------------------------------------------------------------------------------------------

    /** Lines of a paragraph, each protected against being read as a block start. */
    private fun paragraphLines(inlines: List<Inline>, strategy: Strategy): List<String> {
        if (inlines.isEmpty()) return emptyList()
        return inlinesToString(inlines, strategy).split("\n").map { it.escapeLineStart() }
    }

    /** [before]/[after] are the code points just outside this run (null = line edge); they decide delimiter flanking. */
    private fun inlinesToString(inlines: List<Inline>, strategy: Strategy, before: Int? = null, after: Int? = null): String {
        val sb = StringBuilder()
        for ((i, inline) in inlines.withIndex()) {
            if (inline is Inline.Link && inlines.getOrNull(i - 1).let { it is Inline.Text && it.text.endsWith('!') } && sb.endsWith('!')) {
                sb.setLength(sb.length - 1)
                sb.append("\\!") // "![" would start an image
            }
            val prev = if (sb.isNotEmpty()) sb.codePointBefore(sb.length) else before
            val next = inlines.getOrNull(i + 1)?.let { firstCodePointOf(it) } ?: after
            appendInline(sb, inline, strategy, prev, next)
        }
        return sb.toString()
    }

    /** A stand-in for the first code point an inline will produce: enough to classify it as space, punctuation or text. */
    private fun firstCodePointOf(inline: Inline): Int? = when (inline) {
        is Inline.Text -> if (inline.text.isEmpty()) ' '.code else inline.text.codePointAt(0)
        Inline.SoftBreak, Inline.HardBreak -> '\n'.code
        is Inline.Raw -> inline.markdown.takeIf { it.isNotEmpty() }?.codePointAt(0)
        else -> '*'.code // code span, link and styled text all begin with punctuation
    }

    private fun appendInline(sb: StringBuilder, inline: Inline, strategy: Strategy, prev: Int?, next: Int?) {
        when (inline) {
            is Inline.Text -> sb.append(escapeText(inline.text))
            Inline.SoftBreak -> sb.append('\n')
            Inline.HardBreak -> sb.append("\\\n")
            is Inline.Code -> sb.append(codeSpan(inline.code))
            is Inline.Raw -> sb.append(inline.markdown)
            is Inline.Strong -> styled(sb, inline.children, strategy, prev, next, "**", "<strong>", "</strong>", underscore = false)
            is Inline.Emphasis -> styled(
                sb, inline.children, strategy, prev, next,
                if (strategy == Strategy.UnderscoreEmphasis) "_" else "*", "<em>", "</em>",
                underscore = strategy == Strategy.UnderscoreEmphasis,
            )
            is Inline.Strikethrough -> styled(sb, inline.children, strategy, prev, next, "~~", "<del>", "</del>", underscore = false)
            is Inline.Link -> if (strategy == Strategy.Html) {
                // last resort, e.g. a line starting with "[`]:`](u)" would be read as a link reference definition
                sb.append("<a href=\"").append(htmlAttr(inline.destination)).append('"')
                inline.title?.let { sb.append(" title=\"").append(htmlAttr(it)).append('"') }
                sb.append('>').append(inlinesToString(inline.children, strategy, '>'.code, '<'.code)).append("</a>")
            } else {
                sb.append('[').append(inlinesToString(inline.children, strategy, '['.code, ']'.code)).append("](")
                    .append(linkTarget(inline.destination, inline.title)).append(')')
            }
        }
    }

    /**
     * Writes bold/italic/strike with Markdown delimiters when CommonMark's flanking rules allow them in this
     * position, and with an HTML tag only for exactly that span when they do not (e.g. `a**!**` is not bold).
     */
    private fun styled(
        sb: StringBuilder, children: List<Inline>, strategy: Strategy, prev: Int?, next: Int?,
        delimiter: String, htmlOpen: String, htmlClose: String, underscore: Boolean,
    ) {
        val inner = inlinesToString(children, strategy, delimiter.first().code, delimiter.first().code)
        val first = if (inner.isEmpty()) null else inner.codePointAt(0)
        val last = if (inner.isEmpty()) null else inner.codePointBefore(inner.length)
        // a lone "~" next to the "~~" delimiters would make a run of three, which is not strikethrough
        val tilde = '~'.code
        val tildeClash = delimiter == "~~" && (prev == tilde || next == tilde || first == tilde || last == tilde)
        val useHtml = strategy == Strategy.Html || tildeClash || !canOpen(prev, first, underscore) || !canClose(last, next, underscore)
        if (useHtml) sb.append(htmlOpen).append(inner).append(htmlClose) else sb.append(delimiter).append(inner).append(delimiter)
    }

    // CommonMark "flanking" rules on code points; null means start/end of the line, which counts as whitespace.
    private fun isWs(c: Int?) = c == null || isUnicodeWhitespace(c)

    private fun isPunct(c: Int?): Boolean {
        if (c == null) return false
        if (c < 128) return !Character.isLetterOrDigit(c) && !Character.isWhitespace(c) && !Character.isISOControl(c)
        return when (Character.getType(c).toByte()) {
            Character.CONNECTOR_PUNCTUATION, Character.DASH_PUNCTUATION, Character.START_PUNCTUATION, Character.END_PUNCTUATION,
            Character.INITIAL_QUOTE_PUNCTUATION, Character.FINAL_QUOTE_PUNCTUATION, Character.OTHER_PUNCTUATION,
            Character.MATH_SYMBOL, Character.CURRENCY_SYMBOL, Character.MODIFIER_SYMBOL, Character.OTHER_SYMBOL -> true
            else -> false
        }
    }

    private fun leftFlanking(before: Int?, after: Int?) =
        !isWs(after) && (!isPunct(after) || isWs(before) || isPunct(before))

    private fun rightFlanking(before: Int?, after: Int?) =
        !isWs(before) && (!isPunct(before) || isWs(after) || isPunct(after))

    private fun canOpen(before: Int?, innerFirst: Int?, underscore: Boolean): Boolean {
        val left = leftFlanking(before, innerFirst)
        return if (underscore) left && (!rightFlanking(before, innerFirst) || isPunct(before)) else left
    }

    private fun canClose(innerLast: Int?, after: Int?, underscore: Boolean): Boolean {
        val right = rightFlanking(innerLast, after)
        return if (underscore) right && (!leftFlanking(innerLast, after) || isPunct(after)) else right
    }

    private fun htmlAttr(value: String): String =
        value.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;").replace(">", "&gt;").replace(Regex("[\\r\\n]+"), " ")

    private fun codeSpan(code: String): String {
        val text = code.replace('\n', ' ')
        val longest = Regex("`+").findAll(text).maxOfOrNull { it.value.length } ?: 0
        val fence = "`".repeat(longest + 1)
        val pad = text.startsWith('`') || text.endsWith('`') || (text.startsWith(' ') && text.endsWith(' ') && text.isNotBlank())
        return if (pad) "$fence $text $fence" else "$fence$text$fence"
    }

    private val entityLike = Regex("&(#[0-9]{1,7}|#[xX][0-9a-fA-F]{1,6}|[A-Za-z][A-Za-z0-9]{1,31});")

    /** Escapes characters that would otherwise start inline markup. Block-level hazards are handled per line. */
    internal fun escapeText(text: String): String {
        val sb = StringBuilder(text.length + 8)
        for ((i, c) in text.withIndex()) {
            when (c) {
                '\\', '`', '*', '[', ']' -> sb.append('\\').append(c)
                '_' -> {
                    val intraword = text.getOrNull(i - 1)?.isLetterOrDigit() == true && text.getOrNull(i + 1)?.isLetterOrDigit() == true
                    if (intraword) sb.append(c) else sb.append('\\').append(c)
                }
                '<' -> {
                    val n = text.getOrNull(i + 1)
                    if (n != null && (n.isLetter() || n == '/' || n == '!' || n == '?')) sb.append('\\').append(c) else sb.append(c)
                }
                '&' -> if (entityLike.matchAt(text, i) != null) sb.append('\\').append(c) else sb.append(c)
                '~' -> if (text.getOrNull(i - 1) == '~' || text.getOrNull(i + 1) == '~') sb.append('\\').append(c) else sb.append(c)
                else -> sb.append(c)
            }
        }
        return sb.toString()
    }

    private val hrOrSetext = Regex("^(?:(?:-\\s*){3,}|(?:_\\s*){3,}|(?:=\\s*)+|(?:-\\s*)+)$")

    /** Protects a line of paragraph text from being read as a heading, quote, list item, rule, ... */
    private fun String.escapeLineStart(): String {
        if (isEmpty()) return this
        Regex("^(#{1,6})(?=\\s|$)").find(this)?.let { return "\\" + this }
        if (startsWith('>')) return "\\$this"
        if (Regex("^[-+](?=\\s|$)").containsMatchIn(this)) return "\\$this"
        Regex("^(\\d{1,9})([.)])(?=\\s|$)").find(this)?.let { m ->
            val digits = m.groupValues[1]
            return digits + "\\" + substring(digits.length)
        }
        if (hrOrSetext.matches(this)) return "\\$this"
        return this
    }

    internal fun linkTarget(destination: String, title: String?): String {
        val dest = escapeDestination(destination)
        val needsBrackets = destination.isEmpty() || destination.any { it.isWhitespace() || it.isISOControl() }
        val d = if (needsBrackets) "<" + dest.replace("<", "\\<").replace(">", "\\>").replace(Regex("[\\r\\n]"), "%20") + ">" else dest
        val t = title?.let { " \"" + it.replace("\\", "\\\\").replace("\"", "\\\"").replace(Regex("[\\r\\n]+"), " ").replace(entityLike) { m -> "\\" + m.value } + "\"" } ?: ""
        return d + t
    }

    private fun escapeDestination(dest: String): String =
        dest.replace("\\", "\\\\").replace("(", "\\(").replace(")", "\\)").replace(entityLike) { "\\" + it.value }

    internal fun imageMarkdown(alt: List<Inline>, destination: String, title: String?): String {
        val altText = alt.plainText().replace("\n", " ").replace("\\", "\\\\").replace("[", "\\[").replace("]", "\\]")
        return "![" + altText + "](" + linkTarget(destination, title) + ")"
    }

    private fun String.withEol(eol: String): String = if (eol == "\n") this else this.replace("\n", eol)
}

/** The comparison form of a block: everything that carries meaning, none of the spelling. */
internal object BlockNormalizer {
    private val taskLookalike = Regex("^\\[([ xX])\\](?:\\s+|$)")

    /**
     * Canonical list item. Task syntax is read from the *decoded* text (that is how the GFM task extension works),
     * so a paragraph that starts with `[ ] `/`[x] ` is a task item however it was written: such text is turned into
     * the explicit `checked` state, and a task marker without text in front of a paragraph does not exist.
     */
    fun taskForm(item: ListItem): ListItem {
        val blocks = item.blocks.mapNotNull { compareForm(it) }.toMutableList()
        var checked = item.checked.takeIf { MarkdownSerializer.hasTaskMarker(item) }
        val first = blocks.firstOrNull() as? Block.Paragraph
        val lead = first?.inlines?.firstOrNull() as? Inline.Text
        if (checked == null && lead != null) {
            taskLookalike.find(lead.text)?.let { m ->
                checked = m.groupValues[1] != " "
                val rest = lead.text.substring(m.value.length)
                val inlines = (if (rest.isEmpty()) emptyList() else listOf(Inline.Text(rest))) + first.inlines.drop(1)
                blocks[0] = Block.Paragraph(InlineNormalizer.block(inlines))
                if (inlines.isEmpty()) { blocks.removeAt(0); checked = null }
            }
        }
        return ListItem(blocks, checked)
    }

    /** Null for blocks that produce no output (empty paragraphs). */
    fun compareForm(block: Block): Block? = when (block) {
        is Block.Paragraph -> InlineNormalizer.block(block.inlines).takeIf { it.isNotEmpty() }?.let { Block.Paragraph(it) }
        is Block.Heading -> Block.Heading(block.level, InlineNormalizer.heading(block.inlines))
        Block.Rule -> Block.Rule
        is Block.Raw -> Block.Raw(block.markdown).takeIf { block.markdown.isNotEmpty() }
        is Block.CodeBlock -> Block.CodeBlock(block.code, block.info?.trim()?.takeIf { it.isNotEmpty() }, CodeStyle.Fenced)
        is Block.Quote -> Block.Quote(block.blocks.mapNotNull { compareForm(it) })
        is Block.ListBlock -> Block.ListBlock(
            kind = when (val k = block.kind) {
                is ListKind.Bullet -> ListKind.Bullet('-')
                is ListKind.Ordered -> ListKind.Ordered(k.start, '.')
            },
            items = block.items.map { item -> taskForm(item) },
            // tight/loose only changes blank lines when rendered; CommonMark reads it inconsistently for deeply nested
            // lists with empty items, so it is not part of the *meaning* compared here (see docs/editor.md)
            tight = true,
        )
    }
}

/** Test hook: the comparison form of a block (everything that carries meaning, none of the spelling). */
object BlockNormalizerAccess {
    fun form(block: Block): Block? = BlockNormalizer.compareForm(block)
}
