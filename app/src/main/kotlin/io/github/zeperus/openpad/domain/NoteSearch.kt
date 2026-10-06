package io.github.zeperus.openpad.domain

/** A note that matches a search: [snippet] is the line (or part of it) around the first match in the text, null for title-only matches. */
data class SearchHit(val note: NoteInfo, val titleMatch: Boolean, val snippet: String?, val snippetMatch: IntRange?, val matches: Int)

/**
 * Local note search: no index, no service - the titles and the text are looked through directly. The match is case-insensitive
 * for all letters (umlauts included) and ignores the Markdown punctuation that happens to be at the start of a line in the snippet.
 */
object NoteSearch {
    fun search(query: String, notes: List<Pair<NoteInfo, String?>>): List<SearchHit> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        val hits = ArrayList<SearchHit>()
        for ((note, text) in notes) {
            val titleMatch = indexOf(note.title, q, 0) >= 0
            var count = 0
            var first = -1
            if (text != null) {
                var at = indexOf(text, q, 0)
                while (at >= 0) {
                    if (first < 0) first = at
                    count++
                    at = indexOf(text, q, at + maxOf(1, q.length))
                }
            }
            if (!titleMatch && count == 0) continue
            val snippet = if (first >= 0 && text != null) snippet(text, first, q.length) else null
            hits += SearchHit(note, titleMatch, snippet?.first, snippet?.second, count)
        }
        // title matches first; inside each group the order of [notes] (by title) is kept
        return hits.sortedBy { if (it.titleMatch) 0 else 1 }
    }

    /** Index of [needle] in [text] from [from], comparing letters case-insensitively; offsets are those of [text]. */
    fun indexOf(text: String, needle: String, from: Int): Int {
        if (needle.isEmpty()) return -1
        var i = from.coerceAtLeast(0)
        val last = text.length - needle.length
        while (i <= last) {
            if (text.regionMatches(i, needle, 0, needle.length, ignoreCase = true)) return i
            i++
        }
        return -1
    }

    private val marker = Regex("""^\s*(?:#{1,6}\s+|>\s*|[-*+]\s+(?:\[[ xX]]\s+)?|\d+[.)]\s+)+""")

    private fun snippet(text: String, at: Int, length: Int): Pair<String, IntRange> {
        val start = text.lastIndexOf('\n', at - 1) + 1
        val endBreak = text.indexOf('\n', at)
        val end = if (endBreak < 0) text.length else endBreak
        val line = text.substring(start, end).trimEnd('\r')
        val skip = marker.find(line)?.value?.length?.takeIf { it <= at - start } ?: 0
        var matchStart = at - start - skip
        var body = line.substring(skip)
        // a long line: show the neighbourhood of the match
        if (body.length > 90) {
            val from2 = (matchStart - 30).coerceAtLeast(0)
            val to2 = (from2 + 90).coerceAtMost(body.length)
            body = (if (from2 > 0) "…" else "") + body.substring(from2, to2) + (if (to2 < body.length) "…" else "")
            matchStart = matchStart - from2 + (if (from2 > 0) 1 else 0)
        }
        val range = matchStart.coerceAtLeast(0) until (matchStart + length).coerceAtMost(body.length)
        return body to range
    }
}
