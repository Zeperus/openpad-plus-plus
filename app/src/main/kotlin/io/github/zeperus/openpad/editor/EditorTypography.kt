package io.github.zeperus.openpad.editor

/**
 * All sizes of the editor's text, derived from ONE number: the body size in `sp` the user chose. Plain numbers (sp), no Compose, so the
 * rules are testable. Line heights are ratios too, so a larger text never clips and a smaller one is never cramped or double spaced.
 */
class EditorTypography(val base: Float) {
    /** Paragraphs, list items, quotes. */
    val body: Float get() = base

    /** 1.5 x the text size: the notepad-like spacing of Alpha 5/6 (24 sp at the default 16 sp). */
    val bodyLineHeight: Float get() = base * BODY_LINE_RATIO

    fun headingSize(level: Int): Float = base * when (level) {
        1 -> 1.8f
        2 -> 1.55f
        3 -> 1.35f
        4 -> 1.2f
        5 -> 1.1f
        else -> 1.0f
    }

    /** A heading's line is 1.25 x its size, but never less than a body line (so H5/H6 sit like body text). */
    fun headingLineHeight(level: Int): Float = maxOf(headingSize(level) * 1.25f, bodyLineHeight)

    /** Code and raw/source rows: monospace, a little smaller than the body (14 sp at 16 sp). */
    val code: Float get() = base * 0.875f
    val codeLineHeight: Float get() = base * 1.25f

    /** Table cells (14 sp at 16 sp). */
    val table: Float get() = base * 0.875f

    /** Captions: where an image comes from, the "HTML" label. */
    val caption: Float get() = base * 0.75f

    /** The width of the marker column (bullet, number, checkbox) in front of list text, in `sp`; the layout never makes it narrower than 32 dp. */
    val markerColumn: Float get() = base * 2f

    /** The indent of one nesting level, in `sp` (22 at 16); never narrower than 22 dp. */
    val depthIndent: Float get() = base * 1.375f

    /** How much larger or smaller than the default size the checkbox is drawn (its touch target does not shrink). */
    val checkboxScale: Float get() = (base / DEFAULT_BASE).coerceIn(0.75f, 1.75f)

    companion object {
        const val BODY_LINE_RATIO = 1.5f
        const val DEFAULT_BASE = 16f
    }
}
