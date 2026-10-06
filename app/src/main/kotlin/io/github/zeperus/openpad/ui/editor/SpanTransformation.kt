package io.github.zeperus.openpad.ui.editor

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextDecoration
import io.github.zeperus.openpad.editor.RichText
import io.github.zeperus.openpad.editor.SpanKind

class SpanColors(val link: Color, val codeBackground: Color, val muted: Color, val highlight: Color = Color.Transparent, val match: Color = Color.Transparent)

/**
 * Draws a row's formatting on top of its plain text: bold, italic, strikethrough, inline code, links. The field text is the
 * row's text behind one invisible character, so every offset is shifted by one; the text itself is not changed, which keeps
 * the keyboard's composing text and the selection exactly as they are.
 */
class SpanTransformation(
    private val rich: RichText,
    private val colors: SpanColors,
    /** Characters shown as selected by a selection across rows (our own highlight; the field's native selection is collapsed then). */
    private val highlight: IntRange? = null,
    /** Matches of Find in note, and the current one. */
    private val matches: List<IntRange> = emptyList(),
    private val currentMatch: IntRange? = null,
) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val plain = rich.spans.isEmpty() && highlight == null && matches.isEmpty()
        if (plain || text.text.length != rich.length + 1) return TransformedText(text, OffsetMapping.Identity)
        val points = sortedSetOf(0, rich.length)
        for (s in rich.spans) { points += s.start; points += s.end }
        highlight?.let { points += it.first.coerceIn(0, rich.length); points += (it.last + 1).coerceIn(0, rich.length) }
        for (m in matches) { points += m.first.coerceIn(0, rich.length); points += (m.last + 1).coerceIn(0, rich.length) }
        val builder = AnnotatedString.Builder(text.text)
        val cuts = points.toList()
        for (i in 0 until cuts.size - 1) {
            val from = cuts[i]
            val to = cuts[i + 1]
            var style = rich.kindsAt(from).takeIf { it.isNotEmpty() }?.let { styleFor(it) } ?: SpanStyle()
            if (matches.any { from in it }) style = style.copy(background = if (currentMatch != null && from in currentMatch) colors.highlight else colors.match)
            if (highlight != null && from in highlight) style = style.copy(background = colors.highlight)
            if (style != SpanStyle()) builder.addStyle(style, from + 1, to + 1)
        }
        return TransformedText(builder.toAnnotatedString(), OffsetMapping.Identity)
    }

    private fun styleFor(kinds: Set<SpanKind>): SpanStyle {
        var style = SpanStyle()
        val decorations = ArrayList<TextDecoration>()
        if (SpanKind.Bold in kinds) style = style.copy(fontWeight = FontWeight.Bold)
        if (SpanKind.Italic in kinds) style = style.copy(fontStyle = FontStyle.Italic)
        if (SpanKind.Strike in kinds) decorations += TextDecoration.LineThrough
        if (SpanKind.Code in kinds) style = style.copy(fontFamily = FontFamily.Monospace, background = colors.codeBackground)
        if (SpanKind.Raw in kinds) style = style.copy(fontFamily = FontFamily.Monospace, color = colors.muted)
        if (SpanKind.Link in kinds) {
            style = style.copy(color = colors.link)
            decorations += TextDecoration.Underline
        }
        if (decorations.isNotEmpty()) style = style.copy(textDecoration = TextDecoration.combine(decorations))
        return style
    }

    // Equal transformations for equal text and colours: the field is not re-laid-out when nothing changed.
    override fun equals(other: Any?) = other is SpanTransformation && other.rich === rich && other.colors.link == colors.link &&
        other.highlight == highlight && other.matches == matches && other.currentMatch == currentMatch
    override fun hashCode() = System.identityHashCode(rich)
}
