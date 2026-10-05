package io.github.zeperus.openpad.domain

/**
 * Turns user-visible note titles into safe `.md` file names and back.
 * Pure Kotlin so it can be unit tested without Android.
 */
object NoteFileName {
    const val EXTENSION = ".md"
    const val DEFAULT_TITLE = "Untitled"
    private const val MAX_BASE_LENGTH = 100

    // Characters that are illegal or troublesome on common file systems / document providers.
    private val forbidden = Regex("""[\\/:*?"<>|\u0000-\u001F]""")
    private val reservedWindowsNames = setOf(
        "CON", "PRN", "AUX", "NUL",
        "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
        "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9",
    )

    /** Returns a sanitized base name (without extension); never empty. */
    fun sanitize(title: String): String {
        var base = title.replace(forbidden, " ")
            .replace(Regex("\\s+"), " ")
            .trim()
            .trim('.')
            .trim()
        if (base.endsWith(EXTENSION, ignoreCase = true)) {
            base = base.dropLast(EXTENSION.length).trim().trim('.').trim()
        }
        if (base.length > MAX_BASE_LENGTH) base = base.take(MAX_BASE_LENGTH).trim()
        if (base.isEmpty()) base = DEFAULT_TITLE
        if (base.uppercase() in reservedWindowsNames) base = "$base-"
        return base
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
