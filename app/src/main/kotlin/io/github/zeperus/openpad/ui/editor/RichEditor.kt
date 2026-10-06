package io.github.zeperus.openpad.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import io.github.zeperus.openpad.editor.DocItem
import io.github.zeperus.openpad.editor.EditorRow
import io.github.zeperus.openpad.editor.RawBlocks
import io.github.zeperus.openpad.editor.RowKind
import io.github.zeperus.openpad.editor.SegmentKeys
import io.github.zeperus.openpad.editor.Segments
import io.github.zeperus.openpad.ui.NotesViewModel

/**
 * The formatted editor. The note is a list of **text segments** and **blocks**: consecutive paragraphs, headings, list items,
 * quotes and code lines share one text field (so a selection can run from one into the next with the system's own handles, see
 * [SegmentField]); a rule, a table, an image or simple HTML is a block of its own between two fields. Everything the user does
 * is turned into a document operation; the Markdown is written from the document, never read back from the screen.
 */
@Composable
fun RichEditor(vm: NotesViewModel, modifier: Modifier = Modifier) {
    key(vm.epoch) {
        val ui = vm.ui
        val readOnly = vm.readOnly
        val listState = rememberLazyListState()
        val sourceRows = remember { mutableStateListOf<Long>() } // raw blocks currently opened as text
        val source = sourceRows.toSet()
        val keys = remember { SegmentKeys() }
        val items = remember(ui.doc, source) { Segments.split(ui.doc) { Segments.isBlock(it, source) } }
        val segmentKeys = remember(items) { keys.assign(items.filterIsInstance<DocItem.Text>().map { it.segment.ids }) }
        val layouts = remember { HashMap<Int, () -> TextLayoutResult?>() }
        val find = vm.find
        val findByRow: Map<Long, List<IntRange>> = remember(find?.matches) {
            find?.matches?.groupBy({ it.rowId }, { it.start until it.end }) ?: emptyMap()
        }
        val currentMatch = find?.currentMatch
        val hint = ui.doc.rows.size == 1 && ui.doc.rows[0].kind == RowKind.Paragraph && ui.doc.rows[0].text.isEmpty && !readOnly

        // where each item sits in the lazy list, and the key of each text segment
        val itemKeys = remember(items, segmentKeys) {
            var t = 0
            items.map { item -> if (item is DocItem.Text) "s${segmentKeys[t++]}" else "b${(item as DocItem.Block).row.id}" }
        }

        /** Scrolls so that [rowId] (and, for a long segment, its line) is on screen. */
        suspend fun reveal(rowId: Long, offset: Int) {
            val index = items.indexOfFirst { it is DocItem.Text && rowId in it.segment.ids || it is DocItem.Block && it.row.id == rowId }
            if (index < 0) return
            listState.scrollToItem(index)
            val item = items[index] as? DocItem.Text ?: return
            val key = segmentKeys.getOrNull(items.take(index).count { it is DocItem.Text }) ?: return
            val layout = layouts[key]?.invoke() ?: return
            val at = item.segment.absolute(io.github.zeperus.openpad.editor.DocumentPosition(rowId, offset)) ?: return
            if (at + 1 <= layout.layoutInput.text.length) listState.scrollToItem(index, scrollOffset = layout.getLineTop(layout.getLineForOffset(at + 1)).toInt())
        }
        // coming back to a tab: show the row the caret was in
        LaunchedEffect(Unit) {
            val c = ui.cursor ?: return@LaunchedEffect
            if (ui.doc.indexOf(c.rowId) > 0) {
                kotlinx.coroutines.delay(50) // the first layout
                reveal(c.rowId, c.start)
            }
        }
        LaunchedEffect(currentMatch) { currentMatch?.let { reveal(it.rowId, it.start) } }

        LazyColumn(state = listState, modifier = modifier.fillMaxSize().testTag("editor")) {
            items(items.size, key = { itemKeys[it] }) { index ->
                when (val item = items[index]) {
                    is DocItem.Text -> {
                        val segment = item.segment
                        val key = segmentKeys[items.take(index).count { it is DocItem.Text }]
                        SegmentField(
                            vm = vm,
                            segment = segment,
                            sourceRows = source,
                            readOnly = readOnly,
                            showHint = hint,
                            matches = segment.ids.mapNotNull { id -> findByRow[id]?.let { id to it } }.toMap(),
                            currentMatch = currentMatch,
                            focusRequest = vm.focusRequest?.takeIf { it.rowId in segment.ids },
                            onLayoutProvider = { provider -> if (provider != null) layouts[key] = provider },
                            onSourceDone = { id -> sourceRows.remove(id) },
                        )
                    }
                    is DocItem.Block -> BlockView(
                        row = item.row,
                        readOnly = readOnly,
                        onEditSource = { sourceRows.add(item.row.id); vm.focusRow(item.row.id) },
                    )
                }
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

/** A row that is not text: a rule, or a table / image / simple HTML drawn as what it is ("Edit source" opens it as text). */
@Composable
private fun BlockView(row: EditorRow, readOnly: Boolean, onEditSource: () -> Unit) {
    if (row.kind == RowKind.Rule) {
        HorizontalDivider(Modifier.padding(horizontal = 16.dp, vertical = 12.dp).testTag("rule"))
        return
    }
    val block = remember(row.text.text) { RawBlocks.classify(row.text.text) }
    RenderedRawBlock(block = block, readOnly = readOnly, selected = false, onEditSource = onEditSource)
}
