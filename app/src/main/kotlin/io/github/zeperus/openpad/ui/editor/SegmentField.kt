package io.github.zeperus.openpad.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
        val wanted = segment.text
        if (state.text.toString() != wanted) {
            val logical = lastSegment.let { old -> positionOf(old, state.selection) }
            state.edit {
                replaceMinimal(this, wanted)
                val a = logical.first?.let { segment.absolute(it) }
                val b = logical.second?.let { segment.absolute(it) }
                selection = if (a != null && b != null) TextRange(a, b) else TextRange(min(selection.start, wanted.length), min(selection.end, wanted.length))
            }
        }
        lastSegment = segment
        if (epoch != appliedEpoch) {
            appliedEpoch = epoch
            val c = vm.ui.cursor
            if (c != null && c.rowId in segment.ids) {
                val a = segment.absolute(DocumentPosition(c.rowId, c.start))
                val b = if (c.start != c.end) segment.absolute(DocumentPosition(c.rowId, c.end)) else a
                if (a != null && b != null && state.selection != TextRange(a, b)) state.edit { selection = TextRange(a, b) }
            }
        }
    }

    private fun positionOf(segment: Segment, selection: TextRange): Pair<DocumentPosition?, DocumentPosition?> {
        if (selection.max > segment.text.length) return null to null
        return segment.position(selection.min) to segment.position(selection.max)
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
        val old = originalText.toString()
        val proposed = asCharSequence().toString()
        if (old == proposed) return // only the caret or the keyboard's composing text moved
        val segment = controller.current()
        if (segment == null || segment.text != old) { // the field and the model have drifted apart: show the model's text
            revertAllChanges()
            return
        }
        val before = originalSelection
        val op = SegmentEditing.interpret(segment, old, proposed, before.min, before.max, selection.max)
        if (op == SegmentOp.None || !controller.vm.applyFieldOp(op)) {
            // nothing changed in the document (Backspace at the very start, a refused paste, a read-only note): keep the old text
            replace(0, length, old)
            selection = before
            return
        }
        // what the model now says this field contains
        val doc = controller.vm.ui.doc
        val anchor = controller.vm.ui.cursor?.rowId?.takeIf { doc.row(it) != null } ?: segment.ids.first { doc.row(it) != null }
        val next = Segments.containing(doc, anchor, controller.sourceRows()) ?: return
        if (next.text != proposed) SegmentController.replaceMinimal(this, next.text)
        val cursor = controller.vm.ui.cursor
        val a = cursor?.let { next.absolute(DocumentPosition(it.rowId, it.start)) }
        val b = cursor?.let { if (it.start != it.end) next.absolute(DocumentPosition(it.rowId, it.end)) else a }
        selection = if (a != null && b != null) TextRange(a, b) else TextRange(min(selection.start, next.text.length), min(selection.end, next.text.length))
        controller.lastSegment = next
        controller.publishDecoration(next, controller.decoration.value?.matches.orEmpty(), controller.decoration.value?.currentMatch)
    }
}

/** What each kind of row looks like, in the field's text (no characters are added; everything is style). */
internal class RowStyles(private val colors: androidx.compose.material3.ColorScheme, private val density: Density, private val spans: SpanColors) {
    private fun sp(dp: Float) = with(density) { dp.dp.toSp() }

    val markerIndentDp = 30f
    val depthIndentDp = 22f

    fun lineHeight(row: EditorRow) = when (val k = row.kind) {
        is RowKind.Heading -> when (k.level) { 1 -> 38.sp; 2 -> 34.sp; 3 -> 30.sp; 4 -> 28.sp; else -> 26.sp }
        is RowKind.Code, RowKind.Raw -> 21.sp
        else -> 28.sp
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
            fontSize = when (k.level) { 1 -> 28.sp; 2 -> 24.sp; 3 -> 20.sp; 4 -> 18.sp; else -> 16.sp },
            fontWeight = FontWeight.Bold,
        )
        RowKind.Quote -> SpanStyle(color = colors.onSurfaceVariant)
        is RowKind.Code -> SpanStyle(fontFamily = FontFamily.Monospace, fontSize = 14.sp)
        RowKind.Raw -> SpanStyle(fontFamily = FontFamily.Monospace, fontSize = 14.sp, color = colors.onSurfaceVariant)
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
        if (length != seg.text.length) return // a frame between the field and the model: no styling rather than the wrong one
        for ((i, row) in seg.rows.withIndex()) {
            val s = seg.start(i)
            val e = seg.end(i)
            val paragraphEnd = if (i < seg.rows.lastIndex) e + 1 else e
            if (paragraphEnd > s) addStyle(styles.paragraph(row), s, paragraphEnd)
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
        TextFieldState(segment.text, if (a != null && b != null) TextRange(a, b) else TextRange(segment.text.length))
    }
    val spanColors = remember(colors) {
        SpanColors(link = colors.primary, codeBackground = colors.surfaceVariant, muted = colors.onSurfaceVariant, highlight = colors.tertiaryContainer, match = colors.secondaryContainer)
    }
    val styles = remember(colors, density) { RowStyles(colors, density, spanColors) }

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
            val seg = controller.current() ?: return@collect
            if (state.text.length == seg.text.length) vm.onFieldSelection(seg, selection.start, selection.end)
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
        color = colors.onBackground, fontSize = 16.sp, lineHeight = 28.sp,
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
    val selectAllLabel = stringResource(R.string.selection_all)
    val clipboard = rememberAppClipboard()
    val appName = stringResource(R.string.app_name)
    val hint = stringResource(R.string.editor_hint)

    Box(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .drawBehind {
                val layout = controller.layout?.invoke() ?: return@drawBehind
                val seg = rowsNow
                if (layout.layoutInput.text.length != seg.text.length) return@drawBehind
                for ((i, row) in seg.rows.withIndex()) {
                    val s = seg.start(i)
                    val e = seg.end(i)
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
                .testTag("segment")
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
                    item(key = "op-select-all", label = selectAllLabel) { state.edit { selectAll() }; close() }
                }
                .filterTextContextMenuComponents { false }, // the system's own items (found first) are dropped; ours come from the modifier above
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
                    if (showHint && state.text.isEmpty()) Text(hint, style = textStyle.copy(color = colors.onSurfaceVariant))
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
                            if (layout == null || i < 0 || layout.layoutInput.text.length != seg.text.length) IntOffset(-1000, -1000)
                            else IntOffset(0, layout.getLineTop(layout.getLineForOffset(seg.start(i))).toInt() - 14.dp.roundToPx())
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
                            if (layout == null || i < 0 || layout.layoutInput.text.length != seg.text.length) IntOffset(-1000, -1000) else {
                                val line = layout.getLineForOffset(seg.start(i))
                                val top = layout.getLineTop(line)
                                IntOffset((styles.depthIndentDp.dp.toPx() * row.depth).toInt(), top.toInt())
                            }
                        }
                        .testTag("marker"),
                )
            }
        }
        // real checkboxes over the first line of every task item
        CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 26.dp) {
            for (row in segment.rows) {
                val task = (row.kind as? RowKind.ListItem)?.takeIf { it.checked != null } ?: continue
                androidx.compose.runtime.key(row.id) {
                    val label = stringResource(if (task.checked == true) R.string.task_done_description else R.string.task_open_description, row.text.text.take(60))
                    Checkbox(
                        checked = task.checked == true,
                        onCheckedChange = { vm.setChecked(row.id, it) },
                        enabled = !readOnly,
                        modifier = Modifier
                            .offset {
                                val layout = controller.layout?.invoke()
                                val seg = rowsNow
                                val i = seg.indexOf(row.id)
                                if (layout == null || i < 0 || layout.layoutInput.text.length != seg.text.length) IntOffset(-1000, -1000) else {
                                    val line = layout.getLineForOffset(seg.start(i))
                                    val top = layout.getLineTop(line)
                                    val height = layout.getLineBottom(line) - top
                                    IntOffset((styles.depthIndentDp.dp.toPx() * row.depth).toInt(), (top + (height - 26.dp.toPx()) / 2f).toInt())
                                }
                            }
                            .size(26.dp)
                            .semantics { contentDescription = label }
                            .testTag("checkbox"),
                    )
                }
            }
        }
    }
}

internal val BULLETS = listOf("•", "◦", "▪")
