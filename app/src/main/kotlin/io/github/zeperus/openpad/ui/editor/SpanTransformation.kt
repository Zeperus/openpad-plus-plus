package io.github.zeperus.openpad.ui.editor

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import io.github.zeperus.openpad.editor.RichText
import io.github.zeperus.openpad.editor.SpanKind

class SpanColors(val link: Color, val codeBackground: Color, val muted: Color, val highlight: Color = Color.Transparent, val match: Color = Color.Transparent)

internal fun spanStyleFor(kinds: Set<SpanKind>, colors: SpanColors): SpanStyle {
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

/** The formatted text of [this] as an AnnotatedString (for places that show text without a text field: table cells, HTML). */
internal fun RichText.toAnnotated(colors: SpanColors): AnnotatedString {
    val points = sortedSetOf(0, length)
    for (s in spans) { points += s.start; points += s.end }
    val builder = AnnotatedString.Builder(text)
    val cuts = points.toList()
    for (i in 0 until cuts.size - 1) {
        val kinds = kindsAt(cuts[i])
        if (kinds.isNotEmpty()) builder.addStyle(spanStyleFor(kinds, colors), cuts[i], cuts[i + 1])
    }
    return builder.toAnnotatedString()
}
