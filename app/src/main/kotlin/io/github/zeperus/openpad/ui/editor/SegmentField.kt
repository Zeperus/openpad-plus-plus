package io.github.zeperus.openpad.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import android.os.SystemClock
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.contextmenu.builder.item
import androidx.compose.foundation.text.contextmenu.data.TextContextMenuKeys
import androidx.compose.foundation.text.contextmenu.modifier.appendTextContextMenuComponents
import androidx.compose.foundation.text.contextmenu.modifier.filterTextContextMenuComponents
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.OutputTransformation
import androidx.compose.foundation.text.input.TextFieldBuffer
import androidx.compose.foundation.text.input.TextFieldDecorator
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.selectAll
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.layout.layout
import androidx.compose.ui.graphics.graphicsLayer
import io.github.zeperus.openpad.editor.EditorTypography
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.editableText
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.ParagraphStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.zeperus.openpad.R
import io.github.zeperus.openpad.editor.DocumentPosition
import io.github.zeperus.openpad.editor.EditorRow
import io.github.zeperus.openpad.editor.FindMatch
import io.github.zeperus.openpad.editor.Numbering
import io.github.zeperus.openpad.editor.RawBlocks
import io.github.zeperus.openpad.editor.RowKind
import io.github.zeperus.openpad.editor.Segment
import io.github.zeperus.openpad.editor.SegmentEditing
import io.github.zeperus.openpad.editor.SegmentOp
import io.github.zeperus.openpad.editor.Segments
import io.github.zeperus.openpad.editor.SpanKind
import io.github.zeperus.openpad.ui.FocusRequest
import io.github.zeperus.openpad.ui.NotesViewModel
import kotlin.math.max
import kotlin.math.min

/**
 * One text field for a run of consecutive rows (paragraphs, headings, list items, quotes, code): their texts joined by line
 * breaks. This is what makes the selection native across blocks - the handles, the magnifier and the system toolbar are those of
 * an ordinary Android text field, and a long press can be dragged from one paragraph into the next.
 *
 * The **document model stays the single truth**. The field only holds text:
 *  - what the user does in the field (typing, Enter, Backspace at the start of a list item, deleting across paragraphs) is turned
 *    into a document operation *before it is committed* ([SegmentInput]); if the model decided something other than the plain
 *    text change (Enter on an empty bullet leaves the list), the field is made to show the model's text instead;
 *  - what the model does on its own (Undo, the formatting bar, a ticked checkbox that moves an item) is written into the field as
 *    a minimal text change ([SegmentController.syncFromModel]), the caret following its row;
 *  - formatting, list markers, checkboxes, quote bars and code backgrounds are *drawn* from the rows ([SegmentOutput], the
 *    overlays) - none of it is text in the field, so no Markdown punctuation is ever visible.
 */
/**
 * Invisible first character of every field. With the caret at the very start of the field there is nothing before it to delete, so a
 * soft keyboard reports nothing when Backspace is pressed there - yet that is exactly "Backspace at the start of the first row"
 * (leave the list, join the block above). The keyboard *does* delete a character if there is one: this one, and that is how the
 * press is noticed. (The same trick as in Alpha 2, proven on a real phone; rows after the first have their line break instead.)
 * All offsets in the field are one more than the segment's; the caret never stays in front of it.
 */
private val replacedMenuKeys = setOf(TextContextMenuKeys.CutKey, TextContextMenuKeys.CopyKey, TextContextMenuKeys.PasteKey, TextContextMenuKeys.SelectAllKey)

/** A caret that appears this soon after a touch on the field comes from that touch. */
private const val TAP_WINDOW_MS = 400L

internal const val FIELD_PREFIX = "\u200B"

internal fun fieldTextOf(segment: Segment) = FIELD_PREFIX + segment.text

internal class DecorationInput(
    val segment: Segment,
    val numbers: Map<Long, Int>,
    val matches: Map<Long, List<IntRange>>,
    val currentMatch: FindMatch?,
)

/** The state one segment field shares between the composition, the input and output transformations and the view model. */
internal class SegmentController(
    val vm: NotesViewModel,
    val sourceRows: () -> Set<Long>,
    first: Segment,
) {
    /** The segment as the model had it when this field last looked (to keep the caret on its row when text moves). */
    var lastSegment: Segment = first
    var appliedEpoch = -1
    val decoration = mutableStateOf<DecorationInput?>(null)
    var layout by mutableStateOf<(() -> TextLayoutResult?)?>(null)

    /** The last touch on the field (in the field's coordinates) and when it happened: the caret a tap produces is checked against it. */
    var lastTouch: Offset? = null
    var lastTouchAt = 0L

    /**
     * Where a *collapsed caret that a tap has just placed* belongs. The text layout puts a tap to the right of a row's last line after
     * the invisible character that ends the row (see `SegmentOutput`) - that is the start of the *next* row. A tap belongs to the row
     * whose line it is on, so the caret may not go beyond the end of that row; a tap to the right of a wrapped line stays on that
     * line (the layout handles it), left of the text is the start of the line (also the layout). One-shot: it only looks at the first
     * caret that follows a touch.
     */
    fun correctedTapOffset(offset: Int): Int {
        val tap = lastTouch ?: return offset
        lastTouch = null
        if (SystemClock.uptimeMillis() - lastTouchAt > TAP_WINDOW_MS) return offset
        return clampToTappedRow(offset, tap.y)
    }

    /** [offset] limited to the row that owns the line at height [y] of the field. */
    fun clampToTappedRow(offset: Int, y: Float): Int {
        val l = layout?.invoke() ?: return offset
        val seg = current() ?: return offset
        if (l.layoutInput.text.length != seg.displayLength) return offset
        val line = l.getLineForVerticalPosition(y).coerceIn(0, l.lineCount - 1)
        return seg.clampTapToRowOfLine(offset, l.getLineStart(line))
    }

    /** The model's version of this field, found through any of its rows. */
    fun current(): Segment? {
        val doc = vm.ui.doc
        val id = lastSegment.ids.firstOrNull { doc.row(it) != null } ?: return null
        return Segments.containing(doc, id, sourceRows())
    }

    fun publishDecoration(segment: Segment, matches: Map<Long, List<IntRange>>, currentMatch: FindMatch?) {
        decoration.value = DecorationInput(segment, Numbering.of(segment.rows), matches, currentMatch)
    }

    /** Writes the model's text into the field if it differs, keeping the caret on the same logical place. */
    fun syncFromModel(state: TextFieldState, segment: Segment, epoch: Int) {
        val wanted = fieldTextOf(segment)
        if (state.text.toString() != wanted) {
            val logical = lastSegment.let { old -> positionOf(old, state.selection) }
            state.edit {
                replaceMinimal(this, wanted)
                val a = logical.first?.let { segment.absolute(it) }
                val b = logical.second?.let { segment.absolute(it) }
                selection = if (a != null && b != null) TextRange(a + 1, b + 1) else TextRange(min(max(1, selection.start), wanted.length), min(max(1, selection.end), wanted.length))
            }
        }
        lastSegment = segment
        if (epoch != appliedEpoch) {
            appliedEpoch = epoch
            val c = vm.ui.cursor
            if (c != null && c.rowId in segment.ids) {
                val a = segment.absolute(DocumentPosition(c.rowId, c.start))
                val b = if (c.start != c.end) segment.absolute(DocumentPosition(c.rowId, c.end)) else a
                if (a != null && b != null && state.selection != TextRange(a + 1, b + 1)) state.edit { selection = TextRange(a + 1, b + 1) }
            }
        }
    }

    private fun positionOf(segment: Segment, selection: TextRange): Pair<DocumentPosition?, DocumentPosition?> {
        if (selection.max > segment.text.length + 1) return null to null
        return segment.position(max(0, selection.min - 1)) to segment.position(max(0, selection.max - 1))
    }

    companion object {
        /** Replaces only the part of the text that differs (the composing text of a keyboard is left alone where possible). */
        fun replaceMinimal(buffer: TextFieldBuffer, wanted: String) {
            val old = buffer.asCharSequence()
            var p = 0
            val m = min(old.length, wanted.length)
            while (p < m && old[p] == wanted[p]) p++
            var s = 0
            while (s < m - p && old[old.length - 1 - s] == wanted[wanted.length - 1 - s]) s++
            buffer.replace(p, old.length - s, wanted.substring(p, wanted.length - s))
        }
    }
}

/** Turns every user edit of the field into a document operation, before it is committed. */
internal class SegmentInput(private val controller: SegmentController) : InputTransformation {
    override fun TextFieldBuffer.transformInput() {
        val oldField = originalText.toString()
        val newField = asCharSequence().toString()
        if (oldField == newField) { // only the caret or the keyboard's composing text moved: the caret never goes in front of the marker
            if (selection.min < 1) selection = TextRange(max(1, selection.start), max(1, selection.end))
            if (selection.collapsed && selection != originalSelection) { // a tap placed the caret: it belongs to the row that was tapped
                val fixed = controller.correctedTapOffset(selection.start)
                if (fixed != selection.start) selection = TextRange(fixed)
            }
            return
        }
        val segment = controller.current()
        if (segment == null || oldField != fieldTextOf(segment)) { // the field and the model have drifted apart: show the model's text
            revertAllChanges()
            return
        }
        val before = originalSelection
        val markerGone = !newField.startsWith(FIELD_PREFIX)
        val oldBody = segment.text
        val newBody = if (markerGone) newField else newField.substring(1)
        val beforeStart = max(0, before.min - 1)
        val beforeEnd = max(0, before.max - 1)
        val op = if (markerGone && newBody == oldBody) {
            SegmentOp.Backspace(segment.rows.first().id) // Backspace at the very start of the first row
        } else {
            SegmentEditing.interpret(segment, oldBody, newBody, beforeStart, beforeEnd, max(0, selection.max - (if (markerGone) 0 else 1)))
        }
        if (op == SegmentOp.None || !controller.vm.applyFieldOp(op)) {
            // nothing changed in the document (Backspace at the very start of the note, a refused paste, a read-only note): keep the old text
            replace(0, length, oldField)
            selection = before
            return
        }
        // what the model now says this field contains
        val doc = controller.vm.ui.doc
        val anchor = controller.vm.ui.cursor?.rowId?.takeIf { doc.row(it) != null } ?: segment.ids.first { doc.row(it) != null }
        val next = Segments.containing(doc, anchor, controller.sourceRows()) ?: return
        val wanted = fieldTextOf(next)
        if (wanted != asCharSequence().toString()) SegmentController.replaceMinimal(this, wanted)
        val cursor = controller.vm.ui.cursor
        val a = cursor?.let { next.absolute(DocumentPosition(it.rowId, it.start)) }
        val b = cursor?.let { if (it.start != it.end) next.absolute(DocumentPosition(it.rowId, it.end)) else a }
        selection = if (a != null && b != null) TextRange(a + 1, b + 1) else TextRange(min(max(1, selection.start), wanted.length), min(max(1, selection.end), wanted.length))
        controller.lastSegment = next
        controller.publishDecoration(next, controller.decoration.value?.matches.orEmpty(), controller.decoration.value?.currentMatch)
    }
}

/** What each kind of row looks like, in the field's text (no characters are added; everything is style). */
internal class RowStyles(
    private val colors: androidx.compose.material3.ColorScheme,
    private val density: Density,
    private val spans: SpanColors,
    val typography: EditorTypography,
) {
    private fun sp(dp: Float) = with(density) { dp.dp.toSp() }

    /** The marker column (bullet / number / checkbox) in front of list text, in dp: 32 dp, or 2 x the text size if that is more (large type, large system font). */
    val markerIndentDp: Float = with(density) { max(32.dp.toPx(), typography.markerColumn.sp.toPx()) / this.density }

    /** One nesting level, in dp: 22 dp, or 1.375 x the text size. */
    val depthIndentDp: Float = with(density) { max(22.dp.toPx(), typography.depthIndent.sp.toPx()) / this.density }

    /** Ordinary text sits 1.5 lines apart like in a notepad; headings get what their larger type needs (see [EditorTypography]). */
    fun lineHeight(row: EditorRow) = when (val k = row.kind) {
        is RowKind.Heading -> typography.headingLineHeight(k.level).sp
        is RowKind.Code, RowKind.Raw -> typography.codeLineHeight.sp
        else -> typography.bodyLineHeight.sp
    }

    fun paragraph(row: EditorRow): ParagraphStyle {
        val indent = when (val k = row.kind) {
            is RowKind.ListItem -> sp(markerIndentDp + depthIndentDp * row.depth)
            RowKind.Quote -> sp(16f)
            is RowKind.Code, RowKind.Raw -> sp(10f)
            else -> 0.sp
        }
        return ParagraphStyle(
            lineHeight = lineHeight(row),
            lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Bottom, LineHeightStyle.Trim.None),
            textIndent = TextIndent(indent, indent),
        )
    }

    fun span(row: EditorRow): SpanStyle? = when (val k = row.kind) {
        is RowKind.Heading -> SpanStyle(
            fontSize = typography.headingSize(k.level).sp,
            fontWeight = FontWeight.Bold,
        )
        RowKind.Quote -> SpanStyle(color = colors.onSurfaceVariant)
        is RowKind.Code -> SpanStyle(fontFamily = FontFamily.Monospace, fontSize = typography.code.sp)
        RowKind.Raw -> SpanStyle(fontFamily = FontFamily.Monospace, fontSize = typography.code.sp, color = colors.onSurfaceVariant)
        is RowKind.ListItem -> if (k.checked == true) SpanStyle(color = colors.onSurfaceVariant, textDecoration = TextDecoration.LineThrough) else null
        else -> null
    }

    val spanColors get() = spans
}

/** The formatting of the rows, drawn as styles over the field's text. */
internal class SegmentOutput(private val input: State<DecorationInput?>, private val styles: RowStyles, private val paletteMatch: androidx.compose.ui.graphics.Color, private val paletteCurrent: androidx.compose.ui.graphics.Color) : OutputTransformation {
    override fun TextFieldBuffer.transformOutput() {
        val d = input.value ?: return
        val seg = d.segment
        if (length != seg.text.length + 1) return // a frame between the field and the model: no styling rather than the wrong one
        // A paragraph style that ends in a line break makes the layout add an empty line after it - a gap between every two rows. So
        // the line breaks between the rows are *drawn* as invisible characters (same length: every offset stays valid) and the
        // paragraph styles end there instead; the field's own text (what the keyboard and the clipboard see) keeps its line breaks.
        for (i in 0 until seg.rows.lastIndex) replace(seg.end(i) + 1, seg.end(i) + 2, DRAWN_BREAK)
        if (seg.rows.last().text.isEmpty) append(DRAWN_BREAK) // an empty last row still needs a character to carry its paragraph style
        for ((i, row) in seg.rows.withIndex()) {
            val s = seg.start(i) + 1 // everything is one further on: the field starts with the invisible marker
            val e = seg.end(i) + 1
            val paragraphStart = if (i == 0) 0 else s
            val paragraphEnd = if (i < seg.rows.lastIndex || e == s) e + 1 else e
            if (paragraphEnd > paragraphStart) addStyle(styles.paragraph(row), paragraphStart, paragraphEnd)
            if (e > s) {
                styles.span(row)?.let { addStyle(it, s, e) }
                // inline formatting
                val points = sortedSetOf(0, row.text.length)
                for (sp in row.text.spans) { points += sp.start; points += sp.end }
                val cuts = points.toList()
                for (c in 0 until cuts.size - 1) {
                    val kinds = row.text.kindsAt(cuts[c])
                    if (kinds.isNotEmpty()) addStyle(spanStyleFor(kinds, styles.spanColors), s + cuts[c], s + cuts[c + 1])
                }
                for (m in d.matches[row.id].orEmpty()) {
                    val current = d.currentMatch?.let { it.rowId == row.id && it.start == m.first } == true
                    addStyle(SpanStyle(background = if (current) paletteCurrent else paletteMatch), s + m.first, s + m.last + 1)
                }
            }
        }
    }
}

/** What the line break between two rows is drawn as: nothing visible, but a character, so that the row's paragraph ends before the next one starts. */
private const val DRAWN_BREAK = "\u200B"

/** Hardware keyboard: Ctrl+B / Ctrl+I / Ctrl+Z / Ctrl+Y and Tab / Shift+Tab (indent / outdent). */
private fun shortcut(event: androidx.compose.ui.input.key.KeyEvent, vm: NotesViewModel): Boolean {
    if (event.type != KeyEventType.KeyDown) return false
    if (event.isCtrlPressed) {
        when (event.key) {
            Key.B -> vm.toggleStyle(SpanKind.Bold)
            Key.I -> vm.toggleStyle(SpanKind.Italic)
            Key.Z -> if (event.isShiftPressed) vm.redo() else vm.undo()
            Key.Y -> vm.redo()
            else -> return false
        }
        return true
    }
    if (event.key == Key.Tab) {
        if (event.isShiftPressed) vm.outdent() else vm.indent()
        return true
    }
    return false
}

@Composable
internal fun SegmentField(
    vm: NotesViewModel,
    segment: Segment,
    sourceRows: Set<Long>,
    readOnly: Boolean,
    showHint: Boolean,
    matches: Map<Long, List<IntRange>>,
    currentMatch: FindMatch?,
    focusRequest: FocusRequest?,
    onLayoutProvider: ((() -> TextLayoutResult?)?) -> Unit,
    onSourceDone: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val density = LocalDensity.current
    val sourceNow by rememberUpdatedState(sourceRows)
    val controller = remember { SegmentController(vm, { sourceNow }, segment) }
    val state = remember {
        val c = vm.ui.cursor
        val a = c?.let { segment.absolute(DocumentPosition(it.rowId, it.start)) }
        val b = c?.let { if (it.start != it.end) segment.absolute(DocumentPosition(it.rowId, it.end)) else a }
        TextFieldState(fieldTextOf(segment), if (a != null && b != null) TextRange(a + 1, b + 1) else TextRange(segment.text.length + 1))
    }
    val spanColors = remember(colors) {
        SpanColors(link = colors.primary, codeBackground = colors.surfaceVariant, muted = colors.onSurfaceVariant, highlight = colors.tertiaryContainer, match = colors.secondaryContainer)
    }
    val typography = LocalEditorTypography.current
    val styles = remember(colors, density, typography.base) { RowStyles(colors, density, spanColors, typography) }

    // the model's text and decorations go into the field before the next frame is drawn
    SideEffect {
        controller.syncFromModel(state, segment, vm.cursorEpoch)
        controller.publishDecoration(segment, matches, currentMatch)
        onLayoutProvider(controller.layout)
    }
    DisposableEffect(Unit) {
        EditorDiagnostics.fieldCreated()
        onDispose { EditorDiagnostics.fieldDisposed() }
    }

    // where the user moves the caret or the selection (also across rows) is told to the model
    LaunchedEffect(state) {
        snapshotFlow { state.selection }.collect { selection ->
            // a caret from a tap that got past the input transformation is corrected here (the same check, once per touch)
            if (selection.collapsed) {
                val fixed = controller.correctedTapOffset(selection.start)
                if (fixed != selection.start) {
                    state.edit { this.selection = TextRange(fixed) }
                    return@collect
                }
            }
            val seg = controller.current() ?: return@collect
            if (state.text.length == seg.text.length + 1) vm.onFieldSelection(seg, max(0, selection.start - 1), max(0, selection.end - 1))
        }
    }

    val focus = remember { FocusRequester() }
    LaunchedEffect(focusRequest) {
        if (focusRequest != null) {
            try { focus.requestFocus() } catch (_: IllegalStateException) { /* not attached yet: the next request retries */ }
            vm.focusHandled(focusRequest)
        }
    }

    val input = remember(controller) { SegmentInput(controller) }
    val output = remember(controller, styles, colors) { SegmentOutput(controller.decoration, styles, colors.secondaryContainer, colors.tertiaryContainer) }
    val textStyle = TextStyle(
        color = colors.onBackground, fontSize = typography.body.sp, lineHeight = typography.bodyLineHeight.sp,
        lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Bottom, LineHeightStyle.Trim.None),
    )
    val primary = colors.primary
    val codeBackground = colors.surfaceVariant
    val rowsNow by rememberUpdatedState(segment)
    val numbersNow = remember(segment) { Numbering.of(segment.rows) }
    val copyLabel = stringResource(R.string.selection_copy)
    val cutLabel = stringResource(R.string.selection_cut)
    val markdownLabel = stringResource(R.string.selection_copy_markdown)
    val pasteLabel = stringResource(R.string.selection_paste)
    val pasteChecklistLabel = stringResource(R.string.action_paste_checklist)
    val selectAllLabel = stringResource(R.string.selection_all)
    val clipboard = rememberAppClipboard()
    val appName = stringResource(R.string.app_name)
    val hint = stringResource(R.string.editor_hint)

    val marginH = with(density) { 16.dp.toPx() }
    val marginV = with(density) { 4.dp.toPx() }
    Box(
        modifier
            .fillMaxWidth()
            // the page margin around the field belongs to the row too: a tap there puts the caret at the nearest place of the row
            .pointerInput(controller) {
                detectTapGestures { tap ->
                    val outside = tap.x < marginH || tap.x > size.width - marginH || tap.y < marginV || tap.y > size.height - marginV
                    val l = controller.layout?.invoke()
                    if (outside && l != null && l.layoutInput.text.length == rowsNow.displayLength) {
                        val x = (tap.x - marginH).coerceIn(0f, l.size.width.toFloat())
                        val y = (tap.y - marginV).coerceIn(0f, max(0f, l.size.height - 1f))
                        val offset = max(1, controller.clampToTappedRow(l.getOffsetForPosition(Offset(x, y)), y))
                        state.edit { selection = TextRange(offset) } // first the caret, then the focus: the caret is kept
                        try { focus.requestFocus() } catch (_: IllegalStateException) { }
                    }
                }
            }
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .drawBehind {
                val layout = controller.layout?.invoke() ?: return@drawBehind
                val seg = rowsNow
                if (layout.layoutInput.text.length != seg.displayLength) return@drawBehind
                for ((i, row) in seg.rows.withIndex()) {
                    val s = seg.start(i) + 1
                    val e = seg.end(i) + 1
                    val first = layout.getLineForOffset(s.coerceAtMost(max(0, layout.layoutInput.text.length)))
                    val last = layout.getLineForOffset(if (e > s) e - 1 else s)
                    val top = layout.getLineTop(first)
                    val bottom = layout.getLineBottom(last)
                    when (val k = row.kind) {
                        RowKind.Quote -> drawRect(primary, Offset(0f, top), Size(3.dp.toPx(), bottom - top))
                        is RowKind.Code, RowKind.Raw -> drawRoundRect(
                            codeBackground.copy(alpha = if (k is RowKind.Raw) 0.5f else 1f),
                            topLeft = Offset(-8.dp.toPx(), top), size = Size(size.width + 16.dp.toPx(), bottom - top), cornerRadius = CornerRadius(6.dp.toPx()),
                        )
                        is RowKind.ListItem -> Unit // the markers are text overlays (below), so they are semantic and scale with the font
                        else -> Unit
                    }
                }
            },
    ) {
        BasicTextField(
            state = state,
            modifier = Modifier
                .fillMaxWidth()
                // watches the touches (without consuming them) so that the caret a tap produces can be checked against where it was
                .pointerInput(controller) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            val change = event.changes.firstOrNull() ?: continue
                            if (event.type == PointerEventType.Press || event.type == PointerEventType.Release) {
                                controller.lastTouch = change.position
                                controller.lastTouchAt = SystemClock.uptimeMillis() // our own clock: injected test events carry the test clock's times
                            }
                        }
                    }
                }
                .testTag("segment")
                // what is *drawn* has an invisible character where the rows break (see SegmentOutput); what assistive technology and
                // the tests read is the field's own text, with its real line breaks
                .semantics { editableText = androidx.compose.ui.text.AnnotatedString(state.text.toString()) }
                .focusRequester(focus)
                .onPreviewKeyEvent { shortcut(it, vm) }
                .appendTextContextMenuComponents {
                    val hasSelection = !state.selection.collapsed
                    if (hasSelection && !readOnly) item(key = "op-cut", label = cutLabel) {
                        vm.cutSelection()?.let { clipboard.copy(appName, it) }
                        close()
                    }
                    if (hasSelection) {
                        item(key = "op-copy", label = copyLabel) { vm.selectedText(markdown = false)?.let { clipboard.copy(appName, it) }; close() }
                        item(key = "op-copy-markdown", label = markdownLabel) { vm.selectedText(markdown = true)?.let { clipboard.copy("Markdown", it) }; close() }
                    }
                    if (!readOnly) item(key = "op-paste", label = pasteLabel) { clipboard.text()?.let { vm.pasteText(it) }; close() }
                    if (!readOnly) item(key = "op-paste-checklist", label = pasteChecklistLabel) { clipboard.text()?.let { vm.pasteAsChecklist(it) }; close() }
                    item(key = "op-select-all", label = selectAllLabel) { state.edit { selectAll() }; close() }
                }
                // the system's cut/copy/paste/select-all go (ours replace them: they know about the invisible marker, the Markdown copy and
                // the undo step); anything else the system offers stays. Filtering by key cannot remove our own items, whatever the order.
                .filterTextContextMenuComponents { it.key !in replacedMenuKeys },
            enabled = true,
            readOnly = readOnly,
            inputTransformation = input,
            outputTransformation = output,
            textStyle = textStyle,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            lineLimits = TextFieldLineLimits.MultiLine(),
            onTextLayout = { getResult -> controller.layout = getResult },
            cursorBrush = SolidColor(colors.primary),
            decorator = TextFieldDecorator { inner ->
                Box {
                    if (showHint && state.text.length <= 1) Text(hint, style = textStyle.copy(color = colors.onSurfaceVariant))
                    inner()
                }
            },
        )
        // "Done" for a table / image / HTML block that is open as source: back to the formatted view
        for (row in segment.rows) {
            if (row.kind != RowKind.Raw || row.id !in sourceRows || !RawBlocks.classify(row.text.text).isRendered()) continue
            androidx.compose.runtime.key(row.id) {
                androidx.compose.material3.TextButton(
                    onClick = { onSourceDone(row.id) },
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .offset {
                            val layout = controller.layout?.invoke()
                            val seg = rowsNow
                            val i = seg.indexOf(row.id)
                            if (layout == null || i < 0 || layout.layoutInput.text.length != seg.displayLength) IntOffset(-1000, -1000)
                            else IntOffset(0, layout.getLineTop(layout.getLineForOffset(seg.start(i) + 1)).toInt() - 14.dp.roundToPx())
                        }
                        .testTag("source-done"),
                ) { Text(stringResource(R.string.raw_done)) }
            }
        }
        // bullets and numbers in front of the first line of the other list items
        for (row in segment.rows) {
            val item = (row.kind as? RowKind.ListItem)?.takeIf { it.checked == null } ?: continue
            androidx.compose.runtime.key(row.id) {
                val marker = if (item.list.ordered) "${numbersNow[row.id] ?: 1}${if (item.list.marker == ')') ")" else "."}" else BULLETS[row.depth.coerceAtLeast(0) % BULLETS.size]
                Text(
                    text = marker,
                    style = textStyle,
                    modifier = Modifier
                        .offset {
                            val layout = controller.layout?.invoke()
                            val seg = rowsNow
                            val i = seg.indexOf(row.id)
                            if (layout == null || i < 0 || layout.layoutInput.text.length != seg.displayLength) IntOffset(-1000, -1000) else {
                                val line = layout.getLineForOffset(seg.start(i) + 1)
                                val top = layout.getLineTop(line)
                                IntOffset((styles.depthIndentDp.dp.toPx() * row.depth).toInt(), top.toInt())
                            }
                        }
                        .testTag("marker"),
                )
            }
        }
        // Real checkboxes over the first line of every task item. The box is drawn 24 dp (the lines are close together, like in a
        // notepad) but its touch target is 48 dp wide and one line tall (never overlapping the neighbours): visual density and
        // touch target are separate things. The toggle belongs to the target, the Checkbox inside only draws.
        for (row in segment.rows) {
            val task = (row.kind as? RowKind.ListItem)?.takeIf { it.checked != null } ?: continue
            androidx.compose.runtime.key(row.id) {
                val label = stringResource(if (task.checked == true) R.string.task_done_description else R.string.task_open_description, row.text.text.take(60))
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .offset {
                            val l = controller.layout?.invoke()
                            val seg = rowsNow
                            val i = seg.indexOf(row.id)
                            if (l == null || i < 0 || l.layoutInput.text.length != seg.displayLength) IntOffset(-1000, -1000)
                            else IntOffset((styles.depthIndentDp * row.depth + CHECKBOX_TARGET_LEFT).dp.roundToPx(), l.getLineTop(l.getLineForOffset(seg.start(i) + 1)).toInt())
                        }
                        .layout { measurable, _ ->
                            val l = controller.layout?.invoke()
                            val seg = rowsNow
                            val i = seg.indexOf(row.id)
                            val height = if (l == null || i < 0 || l.layoutInput.text.length != seg.displayLength) 1
                            else l.getLineForOffset(seg.start(i) + 1).let { (l.getLineBottom(it) - l.getLineTop(it)).toInt().coerceAtLeast(1) }
                            val width = (styles.markerIndentDp - CHECKBOX_TARGET_LEFT).dp.roundToPx()
                            val placeable = measurable.measure(androidx.compose.ui.unit.Constraints.fixed(width, height))
                            layout(width, height) { placeable.place(0, 0) }
                        }
                        .toggleable(
                            value = task.checked == true,
                            enabled = !readOnly,
                            role = androidx.compose.ui.semantics.Role.Checkbox,
                            onValueChange = { vm.setChecked(row.id, it) },
                        )
                        .semantics { contentDescription = label }
                        .testTag("checkbox"),
                ) {
                    Checkbox(
                        checked = task.checked == true,
                        onCheckedChange = null,
                        enabled = !readOnly,
                        modifier = Modifier.graphicsLayer(scaleX = typography.checkboxScale, scaleY = typography.checkboxScale),
                    )
                }
            }
        }
    }
}

/** The touch target of a task's checkbox: from [CHECKBOX_TARGET_LEFT] (into the page margin) up to the start of the text - 48 dp wide at the default size, wider with larger text. */
private const val CHECKBOX_TARGET_LEFT = -16

internal val BULLETS = listOf("•", "◦", "▪")
