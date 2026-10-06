package io.github.zeperus.openpad.editor

/** What a raw block (Markdown the editor does not edit as text) is, so the UI can show something better than its source. */
sealed interface RawBlock {
    enum class Align { Start, Center, End }

    /** A GFM table: [header] cells, one alignment per column, then the body [rows] (always as wide as the header). */
    data class Table(val header: List<String>, val aligns: List<Align>, val rows: List<List<String>>) : RawBlock

    /** A paragraph that is just one image. [title] is the optional title. Nothing is loaded here. */
    data class Image(val alt: String, val source: String, val title: String?) : RawBlock

    /** An HTML block. [simple] is the safe rendition if every tag is on the allowlist, else null (shown as source). */
    data class Html(val simple: List<HtmlRun>?) : RawBlock

    data object Other : RawBlock
}

/** A piece of text from simple HTML with its style. A line break is part of the text (`\n`). */
data class HtmlRun(val text: String, val bold: Boolean = false, val italic: Boolean = false, val code: Boolean = false)

object RawBlocks {
    fun classify(markdown: String): RawBlock {
        val text = markdown.trim('\n', '\r')
        table(text)?.let { return it }
        image(text)?.let { return it }
        if (text.trimStart().startsWith("<")) return RawBlock.Html(HtmlSimple.parse(text))
        return RawBlock.Other
    }

    // ---- Tables ------------------------------------------------------------------------------------------------

    private val delimiterCell = Regex("^:?-+:?$")

    private fun table(text: String): RawBlock.Table? {
        val lines = text.split(Regex("\r\n|\n|\r")).filter { it.isNotBlank() }
        if (lines.size < 2) return null
        val header = cells(lines[0])
        val delimiters = cells(lines[1])
        if (header.isEmpty() || delimiters.size != header.size || !delimiters.all { delimiterCell.matches(it) }) return null
        val aligns = delimiters.map { d ->
            when {
                d.startsWith(":") && d.endsWith(":") -> RawBlock.Align.Center
                d.endsWith(":") -> RawBlock.Align.End
                else -> RawBlock.Align.Start
            }
        }
        val rows = lines.drop(2).map { line -> cells(line).let { c -> List(header.size) { c.getOrElse(it) { "" } } } }
        return RawBlock.Table(header, aligns, rows)
    }

    /** Splits a table row at unescaped pipes (outside code spans); a leading and trailing pipe are optional. */
    internal fun cells(line: String): List<String> {
        val out = ArrayList<String>()
        val cell = StringBuilder()
        var inCode = false
        var i = 0
        val t = line.trim()
        while (i < t.length) {
            val c = t[i]
            when {
                c == '\\' && i + 1 < t.length && t[i + 1] == '|' -> { cell.append('|'); i++ }
                c == '`' -> { inCode = !inCode; cell.append(c) }
                c == '|' && !inCode -> { out += cell.toString().trim(); cell.setLength(0) }
                else -> cell.append(c)
            }
            i++
        }
        out += cell.toString().trim()
        if (t.startsWith("|") && out.isNotEmpty()) out.removeAt(0)
        if (t.endsWith("|") && !t.endsWith("\\|") && out.isNotEmpty()) out.removeAt(out.lastIndex)
        return out
    }

    // ---- Images ------------------------------------------------------------------------------------------------

    private val imagePattern = Regex("""^!\[((?:[^\[\]\\]|\\.)*)]\(\s*(<[^>]*>|[^\s)]*)(?:\s+"((?:[^"\\]|\\.)*)")?\s*\)$""")

    private fun image(text: String): RawBlock.Image? {
        val m = imagePattern.matchEntire(text.trim()) ?: return null
        val src = m.groupValues[2].removePrefix("<").removeSuffix(">")
        return RawBlock.Image(m.groupValues[1].replace(Regex("""\\(.)"""), "$1"), src, m.groups[3]?.value)
    }
}

/**
 * The tiny, safe HTML subset that is shown as formatted text: `p`, `br`, `strong`/`b`, `em`/`i`, `code`, `pre`. Anything else - any
 * attribute, `script`, `style`, `a`, `img`, `div`, comments, unknown tags - makes the whole block "complex": it is then shown as
 * source and nothing of it is interpreted. Nothing here loads, runs or follows anything.
 */
object HtmlSimple {
    private val tag = Regex("""<(/?)([a-zA-Z][a-zA-Z0-9]*)\s*(/?)>""")
    private val any = Regex("<[^>]*>")

    fun parse(html: String): List<HtmlRun>? {
        // anything that looks like a tag but is not a plain allowlisted tag (attributes, comments, doctype, unknown) -> not simple
        for (m in any.findAll(html)) {
            val t = tag.matchEntire(m.value) ?: return null
            if (t.groupValues[2].lowercase() !in ALLOWED) return null
        }
        if (html.contains('<') && any.findAll(html).count() == 0) return null // a lone "<": not HTML we understand
        val runs = ArrayList<HtmlRun>()
        var bold = 0; var italic = 0; var code = 0; var pre = 0
        var last = 0
        val text = StringBuilder()

        fun flush() {
            if (text.isEmpty()) return
            runs += HtmlRun(decode(text.toString(), pre > 0), bold > 0, italic > 0, code > 0 || pre > 0)
            text.setLength(0)
        }

        for (m in tag.findAll(html)) {
            text.append(html, last, m.range.first)
            last = m.range.last + 1
            val closing = m.groupValues[1] == "/"
            when (m.groupValues[2].lowercase()) {
                "br" -> text.append(BREAK)
                "p" -> { flush(); if (runs.isNotEmpty() && !runs.last().text.endsWith("\n\n")) runs += HtmlRun("\n\n") }
                "strong", "b" -> { flush(); bold += if (closing) -1 else 1 }
                "em", "i" -> { flush(); italic += if (closing) -1 else 1 }
                "code" -> { flush(); code += if (closing) -1 else 1 }
                "pre" -> { flush(); pre += if (closing) -1 else 1 }
            }
            if (bold < 0 || italic < 0 || code < 0 || pre < 0) return null // unbalanced
        }
        text.append(html, last, html.length)
        flush()
        if (bold != 0 || italic != 0 || code != 0 || pre != 0) return null
        val trimmed = runs.dropWhile { it.text.isBlank() }.dropLastWhile { it.text.isBlank() }
        return trimmed.takeIf { it.isNotEmpty() }
    }

    private const val BREAK = '\u0001' // a <br> while the text is still being collected (source line breaks are only spaces)

    private val ALLOWED = setOf("p", "br", "strong", "b", "em", "i", "code", "pre")

    private fun decode(s: String, keepWhitespace: Boolean): String {
        val spaced = if (keepWhitespace) s else s.replace(Regex("[ \\t]*\\n[ \\t]*|[ \\t]+"), " ")
        return spaced.replace(BREAK, '\n').replace("&nbsp;", " ").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'").replace("&amp;", "&")
    }
}
