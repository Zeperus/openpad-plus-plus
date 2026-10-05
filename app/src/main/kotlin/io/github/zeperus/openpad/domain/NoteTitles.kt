package io.github.zeperus.openpad.domain

object NoteTitles {
    private const val MAX_LENGTH = 60

    // Block markers that should not become part of a title: headings, quotes, bullets, task boxes, numbering.
    private val blockPrefix = Regex("""^(?:#{1,6}(?:\s+|$)|>\s*|[-*+](?:\s+|$)(?:\[[ xX]](?:\s+|$))?|\d+[.)](?:\s+|$))+""")

    /** A title taken from the first non-blank line of [text], or null if there is nothing meaningful. */
    fun derive(text: String): String? {
        val line = text.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: return null
        val stripped = blockPrefix.replace(line, "").trim()
        if (stripped.isEmpty()) return null
        return stripped.take(MAX_LENGTH).trim().ifEmpty { null }
    }
}
