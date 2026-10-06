package io.github.zeperus.openpad.ui.editor

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.zeperus.openpad.R
import io.github.zeperus.openpad.editor.DocumentSelection
import io.github.zeperus.openpad.editor.DocumentSelections
import io.github.zeperus.openpad.ui.NotesViewModel
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.roundToInt

/**
 * Selecting across rows. A long press that stays in one row is the normal text selection of that row's field. If the finger
 * then moves into another row, the gesture becomes a selection across rows: from where the press began to where the finger is.
 * The gesture is observed in the Initial pass so it can take over (and stop the field's own selection drag) exactly when it
 * crosses a row; until then nothing is consumed and the field behaves as usual.
 */
internal suspend fun PointerInputScope.documentSelectionGestures(
    vm: NotesViewModel,
    geometry: EditorGeometry,
    container: () -> LayoutCoordinates?,
    listState: LazyListState,
) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        val start = down.position
        val early = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
            while (true) {
                val change = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == down.id } ?: return@withTimeoutOrNull true
                if (!change.pressed || (change.position - start).getDistance() > viewConfiguration.touchSlop) return@withTimeoutOrNull true
            }
            @Suppress("UNREACHABLE_CODE") false
        }
        if (early != null) return@awaitEachGesture // a tap, a scroll or a drag - not a long press
        val coordinates = container()?.takeIf { it.isAttached } ?: return@awaitEachGesture
        val anchor = geometry.hitTest(coordinates.localToRoot(start), vm.ui.doc) ?: return@awaitEachGesture
        var active = false
        var last = start
        val edge = 56.dp.toPx()
        while (true) {
            val event = withTimeoutOrNull(16L) { awaitPointerEvent(PointerEventPass.Initial) }
            val change = event?.changes?.firstOrNull { it.id == down.id }
            if (event != null && change == null) break
            if (change != null) {
                last = change.position
                if (!change.pressed) {
                    if (active) change.consume()
                    break
                }
            }
            val focus = geometry.hitTest(coordinates.localToRoot(last), vm.ui.doc)
            if (focus != null && (active || focus.rowId != anchor.rowId)) {
                active = true
                change?.consume()
                vm.setDocumentSelection(DocumentSelection(anchor, focus))
            }
            if (active) {
                if (last.y < edge) listState.dispatchRawDelta(-22f) else if (last.y > size.height - edge) listState.dispatchRawDelta(22f)
            }
        }
    }
}

/** The two drag handles of a selection across rows. */
@Composable
internal fun SelectionHandles(vm: NotesViewModel, geometry: EditorGeometry, container: () -> LayoutCoordinates?, listState: LazyListState) {
    val selection = vm.docSelection ?: return
    val doc = vm.ui.doc
    val (start, end) = DocumentSelections.ordered(doc, selection) ?: return
    @Suppress("UNUSED_VARIABLE") val scroll = listState.firstVisibleItemScrollOffset + listState.firstVisibleItemIndex // redraw while scrolling
    for ((isStart, position) in listOf(true to start, false to end)) {
        val tag = if (isStart) "selection-handle-start" else "selection-handle-end"
        var handle: LayoutCoordinates? = null
        Box(
            Modifier
                .offset {
                    val c = container()?.takeIf { it.isAttached }
                    val root = geometry.caretBottom(position)
                    if (c == null || root == null) IntOffset(-1000, -1000) else {
                        val local = root - c.positionInRoot()
                        IntOffset((local.x - 12.dp.toPx()).roundToInt(), local.y.roundToInt())
                    }
                }
                .size(24.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary)
                .testTag(tag)
                .onGloballyPositioned { handle = it }
                .pointerInput(isStart) {
                    detectDragGestures { change, _ ->
                        change.consume()
                        val h = handle ?: return@detectDragGestures
                        val root = h.localToRoot(change.position) - Offset(0f, 36.dp.toPx())
                        val hit = geometry.hitTest(root, vm.ui.doc) ?: return@detectDragGestures
                        val current = vm.docSelection ?: return@detectDragGestures
                        val (s, e) = DocumentSelections.ordered(vm.ui.doc, current) ?: return@detectDragGestures
                        vm.setDocumentSelection(if (isStart) DocumentSelection(hit, e) else DocumentSelection(s, hit))
                    }
                },
        )
    }
}

/** Clipboard access in one place. Copy uses the plain-text clip type; the label says what it is. */
internal class AppClipboard(private val context: Context) {
    private val manager get() = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

    fun copy(label: String, text: String) = manager.setPrimaryClip(ClipData.newPlainText(label, text))

    fun text(): String? = manager.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()?.takeIf { it.isNotEmpty() }
}

@Composable
internal fun rememberAppClipboard(): AppClipboard {
    val context = LocalContext.current
    return remember(context) { AppClipboard(context) }
}

/** What can be done with a selection across rows. Replaces the formatting bar while a selection exists. */
@Composable
fun SelectionBar(vm: NotesViewModel, modifier: Modifier = Modifier) {
    val clipboard = rememberAppClipboard()
    val readOnly = vm.readOnly
    val label = stringResource(R.string.app_name)
    Surface(tonalElevation = 3.dp, modifier = modifier.fillMaxWidth().testTag("selection-bar")) {
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            BarButton(stringResource(R.string.selection_copy), stringResource(R.string.selection_copy)) { vm.selectedText(markdown = false)?.let { clipboard.copy(label, it) } }
            BarButton(stringResource(R.string.selection_cut), stringResource(R.string.selection_cut), enabled = !readOnly) {
                vm.cutSelection()?.let { clipboard.copy(label, it) }
            }
            BarButton(stringResource(R.string.selection_copy_markdown), stringResource(R.string.selection_copy_markdown)) {
                vm.selectedText(markdown = true)?.let { clipboard.copy("Markdown", it) }
            }
            BarButton(stringResource(R.string.selection_paste), stringResource(R.string.selection_paste), enabled = !readOnly) { clipboard.text()?.let { vm.pasteText(it) } }
            BarButton(stringResource(R.string.selection_delete), stringResource(R.string.selection_delete), enabled = !readOnly) { vm.deleteSelection() }
            BarButton(stringResource(R.string.selection_all), stringResource(R.string.selection_all)) { vm.selectAll() }
            BarButton("✕", stringResource(R.string.selection_done)) { vm.clearSelection() }
        }
    }
}

@Composable
private fun BarButton(text: String, description: String, enabled: Boolean = true, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .height(48.dp)
            .padding(horizontal = 2.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp)
            .semantics { contentDescription = description },
    ) {
        Text(text, fontSize = 15.sp, color = if (enabled) colors.onSurface else colors.onSurface.copy(alpha = 0.38f))
    }
}
