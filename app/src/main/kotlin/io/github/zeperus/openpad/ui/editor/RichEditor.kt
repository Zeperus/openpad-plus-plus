package io.github.zeperus.openpad.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.zeperus.openpad.R
import io.github.zeperus.openpad.editor.EditorRow
import io.github.zeperus.openpad.editor.RowKind
import io.github.zeperus.openpad.editor.SpanKind
import io.github.zeperus.openpad.ui.FocusRequest
import io.github.zeperus.openpad.ui.NotesViewModel

/**
 * Invisible first character of every text field. A soft keyboard reports nothing when Backspace is pressed with the caret
 * at the very start, but it does delete a character: that is how "Backspace at the start of a row" (leave a list, merge
 * with the previous row) is noticed. The field never lets the caret in front of it.
 */
private const val SENTINEL = "​"

/**
 * The formatted editor: one text field per row (paragraph, heading, list item, ...) in a lazy list. The fields hold plain
 * text; the formatting is drawn on top of it from the row's spans, and everything the user does is reported to the view
 * model as an operation on the document - the Markdown is written from the document, never read back from the screen.
 */
@Composable
fun RichEditor(vm: NotesViewModel, modifier: Modifier = Modifier) {
    key(vm.epoch) {
        val ui = vm.ui
        val readOnly = vm.readOnly
        val listState = rememberLazyListState()
        val rows = ui.doc.rows
        // coming back to a tab: show the row the caret was in
        LaunchedEffect(Unit) {
            val index = ui.cursor?.let { ui.doc.indexOf(it.rowId) } ?: -1
            if (index > 0) listState.scrollToItem(index)
        }
        val hint = rows.size == 1 && rows[0].kind == RowKind.Paragraph && rows[0].text.isEmpty && !readOnly
        LazyColumn(state = listState, modifier = modifier.testTag("editor")) {
            items(rows, key = { it.id }) { row ->
                val index = ui.doc.indexOf(row.id)
                val request = vm.focusRequest?.takeIf { it.rowId == row.id }
                RowView(
                    row = row,
                    number = ui.doc.numberOf(index),
                    vm = vm,
                    cursorStart = ui.cursor?.takeIf { it.rowId == row.id }?.let { it.start to it.end },
                    readOnly = readOnly,
                    focusRequest = request,
                    showHint = hint,
                )
            }
            item(key = "end") {
                // a tap below the last row puts the caret at the end of the note
                Box(
                    Modifier.fillMaxWidth().height(160.dp).clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        enabled = !readOnly,
                    ) { vm.focusEnd() },
                )
            }
        }
    }
}

/**
 * One row. Every kind of row (paragraph, heading, list item, quote, code, raw) is drawn by the **same composable structure**:
 * a `Row` with a leading slot (bullet / number / checkbox / nothing) and the text field. Only modifiers, the slot content and
 * the text style depend on the kind, so turning a paragraph into a list item (or back) *changes* the row instead of replacing
 * it, and the text field - with its focus, caret and keyboard connection - is the same field before and after. (Drawing each
 * kind in its own branch looked simpler, but made Compose dispose the focused field and create a new one on every
 * structural edit: the keyboard closed and reopened, and a held Backspace stopped.)
 */
@Composable
private fun RowView(
    row: EditorRow,
    number: Int?,
    vm: NotesViewModel,
    cursorStart: Pair<Int, Int>?,
    readOnly: Boolean,
    focusRequest: FocusRequest?,
    showHint: Boolean,
) {
    val kind = row.kind
    if (kind == RowKind.Rule) { // never has a text field, so no identity to keep
        HorizontalDivider(Modifier.padding(horizontal = 16.dp, vertical = 12.dp).testTag("rule"))
        return
    }
    val colors = MaterialTheme.colorScheme
    val body = MaterialTheme.typography.bodyLarge.copy(color = colors.onBackground, lineHeight = 26.sp)
    val list = kind as? RowKind.ListItem
    val checked = list?.checked
    val style = when (kind) {
        is RowKind.Heading -> headingStyle(kind.level, body)
        RowKind.Quote -> body.copy(color = colors.onSurfaceVariant)
        is RowKind.Code -> mono(body)
        RowKind.Raw -> mono(body).copy(color = colors.onSurfaceVariant)
        is RowKind.ListItem -> if (checked == true) body.copy(color = colors.onSurfaceVariant, textDecoration = TextDecoration.LineThrough) else body
        else -> body
    }
    val frame = when (kind) {
        is RowKind.Heading -> Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp)
        RowKind.Quote -> Modifier
            .padding(horizontal = 16.dp, vertical = 2.dp)
            .drawBehind { drawRect(colors.primary, Offset.Zero, Size(3.dp.toPx(), size.height)) }
            .padding(start = 14.dp)
        is RowKind.Code -> Modifier.padding(horizontal = 16.dp, vertical = 4.dp).background(colors.surfaceVariant, RoundedCornerShape(6.dp)).padding(10.dp)
        RowKind.Raw -> Modifier.padding(horizontal = 16.dp, vertical = 4.dp).background(colors.surfaceVariant.copy(alpha = 0.5f), RoundedCornerShape(6.dp)).padding(10.dp)
        is RowKind.ListItem -> Modifier.padding(start = 16.dp + 22.dp * row.depth, end = 16.dp, top = 2.dp, bottom = 2.dp)
        else -> Modifier.padding(horizontal = 16.dp, vertical = 5.dp)
    }
    Row(Modifier.fillMaxWidth().then(frame), verticalAlignment = Alignment.Top) {
        Box(Modifier.width(if (list != null) 30.dp else 0.dp).height(if (list != null) 26.dp else 0.dp), contentAlignment = Alignment.CenterStart) {
            if (list != null) {
                if (checked != null) {
                    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 26.dp) {
                        Checkbox(
                            checked = checked,
                            onCheckedChange = { vm.setChecked(row.id, it) },
                            enabled = !readOnly,
                            modifier = Modifier.size(26.dp).testTag("checkbox"),
                        )
                    }
                } else {
                    Text(
                        text = if (list.list.ordered) "${number ?: 1}${if (list.list.marker == ')') ")" else "."}" else BULLETS[row.depth.coerceAtLeast(0) % BULLETS.size],
                        style = body,
                        modifier = Modifier.testTag("marker"),
                    )
                }
            }
        }
        Column(Modifier.weight(1f)) {
            if (kind == RowKind.Raw) {
                Text(stringResource(R.string.raw_block_label), style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
            }
            RowField(
                row, vm, cursorStart, readOnly, focusRequest, style,
                plain = kind is RowKind.Code || kind == RowKind.Raw,
                hintText = if (showHint && kind == RowKind.Paragraph) stringResource(R.string.editor_hint) else null,
            )
        }
    }
}

private val BULLETS = listOf("•", "◦", "▪")

private fun mono(base: TextStyle) = base.copy(fontFamily = FontFamily.Monospace, fontSize = 14.sp, lineHeight = 20.sp)

private fun headingStyle(level: Int, base: TextStyle): TextStyle = base.copy(
    fontSize = when (level) { 1 -> 28.sp; 2 -> 24.sp; 3 -> 20.sp; 4 -> 18.sp; else -> 16.sp },
    lineHeight = when (level) { 1 -> 36.sp; 2 -> 32.sp; 3 -> 28.sp; 4 -> 26.sp; else -> 24.sp },
    fontWeight = FontWeight.Bold,
)

/**
 * The text field of one row. Its value is plain text (with the leading [SENTINEL]); formatting is applied by a visual
 * transformation. Text changes are reported as they come, selection changes as selection changes. If the document
 * ends up with different text than the field holds (an operation changed it), the field shows the document's text.
 */
@Composable
private fun RowField(
    row: EditorRow,
    vm: NotesViewModel,
    cursorStart: Pair<Int, Int>?,
    readOnly: Boolean,
    focusRequest: FocusRequest?,
    style: TextStyle,
    plain: Boolean = false,
    hintText: String? = null,
) {
    val modelText = SENTINEL + row.text.text
    var raw by remember(row.id) {
        val selection = cursorStart?.let { TextRange(it.first + 1, it.second + 1) } ?: TextRange(modelText.length)
        mutableStateOf(TextFieldValue(modelText, selection))
    }
    val value = if (raw.text == modelText) raw else {
        val selection = cursorStart?.let { TextRange(it.first + 1, it.second + 1) } ?: TextRange(modelText.length)
        TextFieldValue(modelText, selection)
    }
    val current by rememberUpdatedState(value)
    val colors = MaterialTheme.colorScheme
    val transformation = remember(row.text, colors) {
        SpanTransformation(row.text, SpanColors(link = colors.primary, codeBackground = colors.surfaceVariant, muted = colors.onSurfaceVariant))
    }
    val focus = remember { FocusRequester() }
    DisposableEffect(Unit) {
        EditorDiagnostics.fieldCreated()
        onDispose { EditorDiagnostics.fieldDisposed() }
    }
    LaunchedEffect(focusRequest) {
        if (focusRequest != null) {
            try { focus.requestFocus() } catch (_: IllegalStateException) { /* not attached yet: the next request retries */ }
            vm.focusHandled(focusRequest)
        }
    }

    BasicTextField(
        value = value,
        onValueChange = { new ->
            when {
                new.text.startsWith(SENTINEL) -> {
                    val start = maxOf(new.selection.start, 1)
                    val end = maxOf(new.selection.end, 1)
                    raw = new.copy(selection = TextRange(start, end))
                    val body = new.text.substring(1)
                    if (body == row.text.text) vm.onSelection(row.id, start - 1, end - 1) else vm.onRowText(row.id, body, end - 1)
                }
                new.text == row.text.text -> { // the invisible first character was deleted: Backspace at the start
                    raw = TextFieldValue(modelText, TextRange(1))
                    vm.onBackspaceAtStart(row.id)
                }
                else -> { // the whole content was replaced
                    raw = TextFieldValue(SENTINEL + new.text, TextRange(new.selection.start + 1, new.selection.end + 1))
                    vm.onRowText(row.id, new.text, new.selection.end)
                }
            }
        },
        readOnly = readOnly,
        textStyle = style,
        cursorBrush = SolidColor(colors.primary),
        visualTransformation = transformation,
        keyboardOptions = KeyboardOptions(
            capitalization = if (plain) KeyboardCapitalization.None else KeyboardCapitalization.Sentences,
            autoCorrectEnabled = !plain,
        ),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("row")
            .focusRequester(focus)
            .onFocusChanged { state ->
                if (state.isFocused) {
                    val sel = current.selection
                    vm.onSelection(row.id, maxOf(sel.start, 1) - 1, maxOf(sel.end, 1) - 1)
                }
            }
            .onPreviewKeyEvent { event -> if (event.type == KeyEventType.KeyDown) shortcut(event, vm) else false },
        decorationBox = { inner ->
            Box {
                if (hintText != null) Text(hintText, style = style.copy(color = colors.onSurfaceVariant))
                inner()
            }
        },
    )
}

/** Hardware keyboard: Ctrl+B/I/Z/Y and Tab / Shift+Tab. */
private fun shortcut(event: androidx.compose.ui.input.key.KeyEvent, vm: NotesViewModel): Boolean {
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
