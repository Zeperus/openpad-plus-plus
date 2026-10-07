package io.github.zeperus.openpad.domain

/**
 * The editor's body text size in `sp` (a global app preference; never stored in a note). `sp` means Android's system font scale
 * applies on top, so 18 here plus a large system font is larger than 18 on screen - nothing is converted to pixels.
 */
object EditorFontSize {
    const val MIN = 12
    const val MAX = 28
    const val DEFAULT = 16
    const val STEP = 1

    fun clamp(value: Int): Int = value.coerceIn(MIN, MAX)

    fun isValid(value: Int): Boolean = value in MIN..MAX

    /** A stored value, or the default if there is none or it is out of range (a corrupted or foreign value is never "repaired" to a limit). */
    fun fromStored(value: Int?): Int = value?.takeIf(::isValid) ?: DEFAULT

    fun increased(value: Int): Int = clamp(value + STEP)

    fun decreased(value: Int): Int = clamp(value - STEP)
}
