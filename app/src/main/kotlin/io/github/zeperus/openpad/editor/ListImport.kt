package io.github.zeperus.openpad.editor

/**
 * Turning text that is a *list of things* into list items: the rules behind "convert to list" and "Paste as Checklist". Pure
 * functions on strings, tested without Android.
 *
 * Two levels of cleaning (see docs/editor.md, "Lists from several lines"):
 *  - **markers** (conversions and Paste as Checklist): a leading list marker (`- * + • – —`), a Markdown task marker (`[ ]`, `[x]`,
 *    `[X]`) or a box glyph (`☐ ☑`) is removed from the *start of a line*; a task marker also says whether the item is done.
 *  - **messenger headers** (Paste as Checklist only): a chat message line such as `[10.10., 12:42] Sybille: - Bier` loses its
 *    timestamp and sender. A header is recognised **only** when a real timestamp opens the line - never from "something before
 *    a colon" - so `Note: buy milk` or `URL: https://example.com` are never touched.
 */
object ListImport {
    /** A cleaned line: [start] until [end] of the original line is the item's text; [checked] is set when the line had a task marker. */
    data class Cleaned(val start: Int, val end: Int, val checked: Boolean?)

    /** A line of an import: its text and whether it said it is done. */
    data class Item(val text: String, val checked: Boolean?)

    /** CRLF and lone CR are line breaks like LF; the rest of the text is not touched. */
    fun normalizeNewlines(text: String): String = if ('\r' !in text) text else text.replace("\r\n", "\n").replace('\r', '\n')

    /** The logical lines of [text]: a final line break gives a final empty line (so nothing a user copied is lost). */
    fun lines(text: String): List<String> = normalizeNewlines(text).split('\n')

    private const val BULLETS = "-*+•–—"

    // directional marks and the like that chat exports put in front of lines
    private fun isInvisible(c: Char) = c == '\u200E' || c == '\u200F' || c == '\uFEFF' || c == '\u200B' || c in '\u202A'..'\u202E'

    private const val DATE = """\d{1,4}[./-]\d{1,2}(?:[./-]\d{1,4})?\.?"""
    private const val TIME = """\d{1,2}:\d{2}(?::\d{2})?(?:\s?[AaPp]\.?[Mm]\.?)?"""

    /** The sender of a message: no colon, no brackets, not endless. Followed by `:` and a space (or the end of the line). */
    private const val SENDER = """[^:\[\]\n]{1,60}?"""

    // [12:42] Sybille: ...      [10.10., 12:42] Sybille: ...      [10/10/2025, 12:42:07 PM] Sybille: ...
    private val bracketHeader = Regex("""^\[\s*(?:$DATE(?:\s*,\s*|\s+))?$TIME\s*]\s+($SENDER):(?=\s|$)\s*""")

    // 10.10.25, 12:42 - Sybille: ...   (the format of a WhatsApp text export)
    private val exportHeader = Regex("""^$DATE\s*,\s*$TIME\s+[-–]\s+($SENDER):(?=\s|$)\s*""")

    /** Where the message starts, if [line] opens with a timestamp header (otherwise null). */
    fun messageStart(line: String, from: Int = 0): Int? {
        val rest = line.substring(from)
        val m = bracketHeader.find(rest) ?: exportHeader.find(rest) ?: return null
        if (m.groupValues[1].isBlank()) return null
        return from + m.range.last + 1
    }

    /**
     * How many characters at the start of [s] are a list marker (leading blanks, the marker, the blanks after it) and a task
     * marker, and whether the task is done. Zero if there is neither. A marker needs a blank (or the end of the line) after it: `-5 degrees` stays.
     */
    fun markerLength(s: String): Pair<Int, Boolean?> {
        var i = 0
        while (i < s.length && s[i].isWhitespace()) i++
        if (i < s.length && s[i] in BULLETS && (i + 1 == s.length || s[i + 1].isWhitespace())) {
            i++
            while (i < s.length && s[i].isWhitespace()) i++
        }
        var checked: Boolean? = null
        if (i + 2 < s.length && s[i] == '[' && s[i + 2] == ']' && s[i + 1] in " xX" && (i + 3 == s.length || s[i + 3].isWhitespace())) {
            checked = s[i + 1] != ' '
            i += 3
        } else if (i < s.length && (s[i] == '☐' || s[i] == '☑') && (i + 1 == s.length || s[i + 1].isWhitespace())) {
            checked = s[i] == '☑'
            i++
        }
        while (i < s.length && s[i].isWhitespace()) i++
        return i to checked
    }

    /** True if [text] starts with something [markerLength] would remove (a marker, not just blanks). */
    fun hasMarker(text: String): Boolean {
        val (n, checked) = markerLength(text)
        return checked != null || text.substring(0, n).any { !it.isWhitespace() }
    }

    /**
     * One line cleaned for a list import: the marker(s) at its start, the blanks around the text. With [messenger] a timestamp
     * header is removed first. Null if no text is left (a blank line, `- `, a header without a message).
     */
    fun clean(line: String, messenger: Boolean = false): Cleaned? {
        var start = 0
        while (start < line.length && (isInvisible(line[start]))) start++
        if (messenger) messageStart(line, start)?.let { start = it }
        val (n, checked) = markerLength(line.substring(start))
        start += n
        var end = line.length
        while (end > start && (line[end - 1].isWhitespace() || isInvisible(line[end - 1]))) end--
        return if (end > start) Cleaned(start, end, checked) else null
    }

    /** The items of a Paste as Checklist: every meaningful line of [text] once, in the order of the text. */
    fun checklistItems(text: String): List<Item> =
        lines(text).mapNotNull { line -> clean(line, messenger = true)?.let { Item(line.substring(it.start, it.end), it.checked) } }
}
