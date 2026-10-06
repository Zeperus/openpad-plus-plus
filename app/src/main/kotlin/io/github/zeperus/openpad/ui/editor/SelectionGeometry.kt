package io.github.zeperus.openpad.ui.editor

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.text.TextLayoutResult
import io.github.zeperus.openpad.editor.DocumentPosition
import io.github.zeperus.openpad.editor.EditorDocument
import io.github.zeperus.openpad.editor.RowKind

/** Where one row is on screen (only while it is composed) and how its text is laid out. */
class RowGeometry {
    var row: LayoutCoordinates? = null
    var field: LayoutCoordinates? = null
    var layout: TextLayoutResult? = null
}

/**
 * The link between the screen and logical document positions, for selections that span rows: which row/offset is under a
 * finger, and where a position is drawn (for the selection handles). Rows that are not composed (scrolled away) simply have no
 * geometry; the selection itself is logical and does not depend on it.
 */
class EditorGeometry {
    private val rows = HashMap<Long, RowGeometry>()

    fun of(rowId: Long): RowGeometry = rows.getOrPut(rowId) { RowGeometry() }

    fun forget(rowId: Long) { rows.remove(rowId) }

    private fun attached(g: RowGeometry?) = g?.row?.takeIf { it.isAttached }

    /** The document position under [root] (screen coordinates of the editor window), or null if no row is on screen. */
    fun hitTest(root: Offset, doc: EditorDocument): DocumentPosition? {
        val visible = doc.rows.filter { attached(rows[it.id]) != null }
        if (visible.isEmpty()) return null
        val chosen = visible.firstOrNull { root.y < attached(rows[it.id])!!.boundsInRoot().bottom } ?: visible.last()
        val bounds = attached(rows[chosen.id])!!.boundsInRoot()
        val length = if (chosen.kind == RowKind.Rule) 1 else chosen.text.length
        if (root.y < bounds.top) return DocumentPosition(chosen.id, 0)
        if (root.y >= bounds.bottom) return DocumentPosition(chosen.id, length)
        val geometry = rows[chosen.id]!!
        val field = geometry.field?.takeIf { it.isAttached }
        val layout = geometry.layout
        if (field == null || layout == null) { // a rule or a rendered block: before or after its middle
            return DocumentPosition(chosen.id, if (root.y < (bounds.top + bounds.bottom) / 2) 0 else length)
        }
        val local = root - field.positionInRoot()
        val inField = layout.getOffsetForPosition(local) - 1 // the field text starts with the invisible marker
        return DocumentPosition(chosen.id, inField.coerceIn(0, chosen.text.length))
    }

    /** The bottom-left of the caret at [position] in screen coordinates, or null if that row is not on screen. */
    fun caretBottom(position: DocumentPosition): Offset? {
        val geometry = rows[position.rowId] ?: return null
        val row = attached(geometry) ?: return null
        val field = geometry.field?.takeIf { it.isAttached }
        val layout = geometry.layout
        if (field == null || layout == null) {
            val b = row.boundsInRoot()
            return Offset(if (position.offset == 0) b.left else b.right, b.bottom)
        }
        val rect = layout.getCursorRect((position.offset + 1).coerceIn(0, layout.layoutInput.text.length))
        return field.positionInRoot() + Offset(rect.left, rect.bottom)
    }
}
