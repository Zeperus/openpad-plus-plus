package io.github.zeperus.openpad.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import io.github.zeperus.openpad.R
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
/**
 * Keeps the view where it is while the text changes under it. Ticking a task in a Smart Checklist moves the item to the bottom of the
 * list - a change of the text, which makes the text field scroll its caret into view (the caret is wherever the user left it, often at
 * the end), and the user, who is looking at the top of the list, is thrown to the bottom. [hold] remembers the scroll position and puts
 * it back whenever something else moves it during the next moments - until the user scrolls the list themselves: a drag of the list
 * ends the hold at once, so whoever ticks a box and scrolls right away is not pulled back.
 */
internal class ScrollHold(private val state: LazyListState, private val scope: CoroutineScope) {
    private var job: Job? = null

    fun hold() {
        val index = state.firstVisibleItemIndex
        val offset = state.firstVisibleItemScrollOffset
        job?.cancel()
        job = scope.launch {
            withTimeoutOrNull(HOLD_MILLIS) {
                val restore = launch {
                    snapshotFlow { state.firstVisibleItemIndex to state.firstVisibleItemScrollOffset }.collect { (i, o) ->
                        if (i != index || o != offset) state.scrollToItem(index, offset)
                    }
                }
                // the list's own drags (the user's finger) are reported here; a scroll into view by the text field is not a drag
                state.interactionSource.interactions.first { it is DragInteraction.Start }
                restore.cancel()
            }
        }
    }

    private companion object {
        const val HOLD_MILLIS = 800L
    }
}

internal val LocalScrollHold = staticCompositionLocalOf<ScrollHold?> { null }

@Composable
fun RichEditor(vm: NotesViewModel, modifier: Modifier = Modifier) {
    val typography = androidx.compose.runtime.remember(vm.preferences.editorFontSize) { io.github.zeperus.openpad.editor.EditorTypography(vm.preferences.editorFontSize.toFloat()) }
    androidx.compose.runtime.CompositionLocalProvider(LocalEditorTypography provides typography) { RichEditorContent(vm, modifier) }
}

@Composable
private fun RichEditorContent(vm: NotesViewModel, modifier: Modifier = Modifier) {
    key(vm.epoch) {
        // Only the document is read from vm.ui: a caret or selection move publishes a new EditorUi with the same document, and that
        // must not recompose the editor (documents are immutable and compared by instance). A field learns of the model's caret moves
        // through vm.cursorEpoch (see SegmentField).
        val doc = remember { derivedStateOf { vm.ui.doc } }.value
        val readOnly = vm.readOnly
        val listState = rememberLazyListState()
        val sourceRows = remember { mutableStateListOf<Long>() } // raw blocks currently opened as text
        // one Set instance until the rows opened as source change: the fields compare it by instance
        val source = remember { derivedStateOf { sourceRows.toSet() } }.value
        val keys = remember { SegmentKeys() }
        val items = remember(doc, source) { Segments.splitFor(doc, source) }
        val segmentKeys = remember(items) { keys.assign(items.filterIsInstance<DocItem.Text>().map { it.segment.ids }) }
        // how many text segments come before each item: the index of its key in segmentKeys, computed once per list
        val textIndexBefore = remember(items) {
            IntArray(items.size).also { before ->
                var count = 0
                items.forEachIndexed { i, entry ->
                    before[i] = count
                    if (entry is DocItem.Text) count++
                }
            }
        }
        val layouts = remember { HashMap<Int, () -> TextLayoutResult?>() }
        val find = vm.find
        val findByRow: Map<Long, List<IntRange>> = remember(find?.matches) {
            find?.matches?.groupBy({ it.rowId }, { it.start until it.end }) ?: emptyMap()
        }
        val currentMatch = find?.currentMatch
        val hint = doc.rows.size == 1 && doc.rows[0].kind == RowKind.Paragraph && doc.rows[0].text.isEmpty && !readOnly

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
            val key = segmentKeys.getOrNull(textIndexBefore[index]) ?: return
            val layout = layouts[key]?.invoke() ?: return
            val at = item.segment.absolute(io.github.zeperus.openpad.editor.DocumentPosition(rowId, offset)) ?: return
            if (at + 1 <= layout.layoutInput.text.length) listState.scrollToItem(index, scrollOffset = layout.getLineTop(layout.getLineForOffset(at + 1)).toInt())
        }
        // coming back to a tab: show the row the caret was in
        LaunchedEffect(Unit) {
            val ui = vm.ui
            val c = ui.cursor ?: return@LaunchedEffect
            if (ui.doc.indexOf(c.rowId) > 0) {
                kotlinx.coroutines.delay(50) // the first layout
                reveal(c.rowId, c.start)
            }
        }
        LaunchedEffect(currentMatch) { currentMatch?.let { reveal(it.rowId, it.start) } }

        val scrollScope = rememberCoroutineScope()
        val hold = remember(listState) { ScrollHold(listState, scrollScope) }
        val continueLabel = stringResource(R.string.editor_continue_at_end)
        androidx.compose.runtime.CompositionLocalProvider(LocalScrollHold provides hold) {
        LazyColumn(state = listState, modifier = modifier.fillMaxSize().testTag("editor")) {
            items(items.size, key = { itemKeys[it] }) { index ->
                when (val item = items[index]) {
                    is DocItem.Text -> {
                        val segment = item.segment
                        val key = segmentKeys[textIndexBefore[index]]
                        // the same Map instance as long as the segment and the find results are the same ones, so that the field
                        // can be skipped when nothing it shows has changed
                        val matches = remember(segment, findByRow) { segment.ids.mapNotNull { id -> findByRow[id]?.let { id to it } }.toMap() }
                        SegmentField(
                            vm = vm,
                            segment = segment,
                            sourceRows = source,
                            readOnly = readOnly,
                            showHint = hint,
                            matches = matches,
                            // only the field that holds the current match draws it (SegmentOutput looks at its own rows only)
                            currentMatch = currentMatch?.takeIf { segment.indexOf(it.rowId) >= 0 },
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
                // a tap below the last row puts the caret at the end of the note. In an editable note the area is a button whose
                // action TalkBack announces (onClickLabel); a read-only note has no such action, so the area carries no semantics at all
                val endInteraction = remember { MutableInteractionSource() }
                val endTap = if (readOnly) Modifier else Modifier.clickable(
                    interactionSource = endInteraction,
                    indication = null,
                    onClickLabel = continueLabel,
                    role = Role.Button,
                ) { vm.focusEnd() }
                Box(Modifier.fillMaxWidth().height(160.dp).then(endTap))
            }
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
