package io.github.zeperus.openpad.ui

import androidx.compose.runtime.Immutable
import io.github.zeperus.openpad.editor.Cursor
import io.github.zeperus.openpad.editor.EditorDocument
import io.github.zeperus.openpad.editor.RowKind
import io.github.zeperus.openpad.editor.Span
import io.github.zeperus.openpad.editor.SpanKind

/**
 * Everything the editor screen draws, as one immutable value: the rows, the caret and what the formatting bar shows
 * for it. The view model replaces it after every change, so the screen has a single thing to observe.
 */
@Immutable
data class EditorUi(
    val doc: EditorDocument,
    val cursor: Cursor?,
    /** Inline styles on at the caret/selection (the bar highlights them). */
    val active: Set<SpanKind>,
    /** The kind of the row the caret is in (paragraph style, list type). */
    val rowKind: RowKind?,
    /** The link at the caret, if any. */
    val link: Span?,
    val canUndo: Boolean,
    val canRedo: Boolean,
    val version: Long,
)

/** Ask the row [rowId] to take the keyboard focus. [token] makes repeated requests for the same row distinct. */
data class FocusRequest(val rowId: Long, val token: Long)
