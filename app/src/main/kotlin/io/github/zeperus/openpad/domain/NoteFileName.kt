package io.github.zeperus.openpad.domain

/**
 * Turns user-visible note titles into safe `.md` file names and back.
 * Pure Kotlin so it can be unit tested without Android.
 */
object NoteFileName {
    const val EXTENSION = ".md"
    const val DEFAULT_TITLE = "Untitled"
    // File systems limit names to 255 bytes; leave room for " 123.md" suffixes and multi-byte characters.
    private const val MAX_BASE_BYTES = 120

    // Characters that are illegal or troublesome on common file systems / document providers.
    private val forbidden = Regex("""[\\/:*?"<>|\u0000-\u001F]""")
    private val reservedWindowsNames = setOf(
        "CON", "PRN", "AUX", "NUL",
        "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
        "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9",
    )

    /** Returns a sanitized base name (without extension); never empty. */
    fun sanitize(title: String): String = sanitizeOrNull(title) ?: DEFAULT_TITLE

    /** Like [sanitize] but returns null when nothing usable is left (blank, only dots/illegal characters). */
    fun sanitizeOrNull(title: String): String? {
        var base = title.replace(forbidden, " ")
            .replace(Regex("\\s+"), " ")
            .trim()
            .trim('.')
            .trim()
        if (base.endsWith(EXTENSION, ignoreCase = true)) {
            base = base.dropLast(EXTENSION.length).trim().trim('.').trim()
        }
        base = truncateUtf8(base, MAX_BASE_BYTES).trim().trim('.').trim()
        if (base.isEmpty()) return null
        if (base.uppercase() in reservedWindowsNames) base = "$base-"
        return base
    }

    /** True if [name] can be used as a single path segment without escaping its directory. */
    fun isSafeFileName(name: String): Boolean =
        name.isNotEmpty() && name != "." && name != ".." &&
            name.none { it == '/' || it == '\\' || it == '\u0000' } &&
            name.toByteArray(Charsets.UTF_8).size <= 255

    private fun truncateUtf8(text: String, maxBytes: Int): String {
        var bytes = 0
        var end = 0
        while (end < text.length) {
            val cp = text.codePointAt(end)
            val size = String(Character.toChars(cp)).toByteArray(Charsets.UTF_8).size
            if (bytes + size > maxBytes) break
            bytes += size
            end += Character.charCount(cp)
        }
        return text.substring(0, end)
    }

    fun toFileName(title: String): String = sanitize(title) + EXTENSION

    /** Display title for a file name: strips a trailing `.md` (case-insensitive). */
    fun titleOf(fileName: String): String =
        if (fileName.endsWith(EXTENSION, ignoreCase = true)) fileName.dropLast(EXTENSION.length) else fileName

    /**
     * Picks a file name based on [title] that does not collide with [existing] (compared case-insensitively):
     * `Shopping.md`, `Shopping 2.md`, `Shopping 3.md`, ...
     */
    fun unique(title: String, existing: Collection<String>): String {
        val taken = existing.mapTo(HashSet()) { it.lowercase() }
        val base = sanitize(title)
        var candidate = base + EXTENSION
        var n = 2
        while (candidate.lowercase() in taken) {
            candidate = "$base $n$EXTENSION"
            n++
        }
        return candidate
    }
}
