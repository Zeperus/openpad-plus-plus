package io.github.zeperus.openpad.ui.editor

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.zeperus.openpad.R
import io.github.zeperus.openpad.editor.RowKind
import io.github.zeperus.openpad.editor.SpanKind
import io.github.zeperus.openpad.markdown.CodeStyle
import io.github.zeperus.openpad.ui.EditorUi
import io.github.zeperus.openpad.ui.NotesViewModel

/**
 * The compact formatting bar above the keyboard: paragraph style, inline styles, lists, indentation, rule, undo/redo. One
 * thin scrolling row of small buttons; it only exists while a document that can be changed is open. The buttons act on
 * the document through the view model - they never take the keyboard focus from the text.
 */
@Composable
fun FormattingBar(vm: NotesViewModel, modifier: Modifier = Modifier) {
    val ui = vm.ui
    var linkDialog by remember { mutableStateOf(false) }
    var styleMenu by remember { mutableStateOf(false) }
    val kind = ui.rowKind
    val inlineOk = ui.cursor != null && kind != RowKind.Rule && kind !is RowKind.Code && kind != RowKind.Raw
    val listKind = kind as? RowKind.ListItem

    Surface(tonalElevation = 3.dp, modifier = modifier.fillMaxWidth().testTag("formatting-bar")) {
        Row(
            Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box {
                FormatButton(paragraphLabel(kind), stringResource(R.string.format_paragraph_style), selected = false, enabled = ui.cursor != null && kind != RowKind.Raw, wide = true) { styleMenu = true }
                DropdownMenu(expanded = styleMenu, onDismissRequest = { styleMenu = false }) {
                    val items = listOf(
                        R.string.format_text to RowKind.Paragraph,
                        R.string.format_heading_1 to RowKind.Heading(1),
                        R.string.format_heading_2 to RowKind.Heading(2),
                        R.string.format_heading_3 to RowKind.Heading(3),
                        R.string.format_heading_4 to RowKind.Heading(4),
                        R.string.format_quote to RowKind.Quote,
                        R.string.format_code_block to RowKind.Code(null, CodeStyle.Fenced),
                    )
                    for ((label, target) in items) {
                        DropdownMenuItem(
                            text = { Text(stringResource(label)) },
                            onClick = { styleMenu = false; vm.setBlock(target) },
                        )
                    }
                }
            }
            FormatButton("B", stringResource(R.string.format_bold), SpanKind.Bold in ui.active, inlineOk, bold = true) { vm.toggleStyle(SpanKind.Bold) }
            FormatButton("I", stringResource(R.string.format_italic), SpanKind.Italic in ui.active, inlineOk, italic = true) { vm.toggleStyle(SpanKind.Italic) }
            FormatButton("S", stringResource(R.string.format_strike), SpanKind.Strike in ui.active, inlineOk, strike = true) { vm.toggleStyle(SpanKind.Strike) }
            FormatButton("</>", stringResource(R.string.format_code), SpanKind.Code in ui.active, inlineOk, mono = true) { vm.toggleStyle(SpanKind.Code) }
            FormatButton("🔗", stringResource(R.string.format_link), ui.link != null, inlineOk) { linkDialog = true }
            Divider()
            FormatButton("•", stringResource(R.string.format_bullets), listKind != null && !listKind.list.ordered && listKind.checked == null, ui.cursor != null) { vm.toggleList(false) }
            FormatButton("1.", stringResource(R.string.format_numbers), listKind != null && listKind.list.ordered, ui.cursor != null) { vm.toggleList(true) }
            FormatButton("☑", stringResource(R.string.format_tasks), listKind?.checked != null, ui.cursor != null) { vm.toggleTask() }
            FormatButton("⇤", stringResource(R.string.format_outdent), false, listKind != null) { vm.outdent() }
            FormatButton("⇥", stringResource(R.string.format_indent), false, listKind != null) { vm.indent() }
            FormatButton("―", stringResource(R.string.format_rule), false, ui.cursor != null) { vm.insertRule() }
            Divider()
            FormatButton("↶", stringResource(R.string.format_undo), false, ui.canUndo) { vm.undo() }
            FormatButton("↷", stringResource(R.string.format_redo), false, ui.canRedo) { vm.redo() }
        }
    }

    if (linkDialog) LinkDialog(ui, onApply = { vm.setLink(it) }, onRemove = { vm.removeLink() }, onDismiss = { linkDialog = false })
}

@Composable
private fun paragraphLabel(kind: RowKind?): String = when (kind) {
    is RowKind.Heading -> "H${kind.level}"
    RowKind.Quote -> "❝"
    is RowKind.Code -> "{ }"
    else -> "Aa"
}

@Composable
private fun Divider() {
    Box(Modifier.padding(horizontal = 4.dp).width(1.dp).height(20.dp).background(MaterialTheme.colorScheme.outlineVariant))
}

@Composable
private fun FormatButton(
    label: String,
    description: String,
    selected: Boolean,
    enabled: Boolean,
    wide: Boolean = false,
    bold: Boolean = false,
    italic: Boolean = false,
    strike: Boolean = false,
    mono: Boolean = false,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .padding(horizontal = 1.dp)
            .height(38.dp)
            .width(if (wide) 46.dp else 38.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) colors.secondaryContainer else Color.Transparent)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description; this.selected = selected },
    ) {
        Text(
            text = label,
            fontSize = 16.sp,
            fontWeight = if (bold) FontWeight.Bold else null,
            fontStyle = if (italic) FontStyle.Italic else null,
            fontFamily = if (mono) FontFamily.Monospace else null,
            textDecoration = if (strike) TextDecoration.LineThrough else null,
            color = if (enabled) colors.onSurface else colors.onSurface.copy(alpha = 0.38f),
        )
    }
}

/** Adds, changes or removes the link at the selection. Opening it is a separate, deliberate button. */
@Composable
private fun LinkDialog(ui: EditorUi, onApply: (String) -> Unit, onRemove: () -> Unit, onDismiss: () -> Unit) {
    var url by remember { mutableStateOf(ui.link?.href.orEmpty()) }
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.link_title)) },
        text = {
            TextField(
                value = url,
                onValueChange = { url = it },
                label = { Text(stringResource(R.string.link_address)) },
                singleLine = true,
                modifier = Modifier.testTag("link-address"),
            )
        },
        confirmButton = {
            TextButton(onClick = { if (url.isNotBlank()) onApply(url); onDismiss() }, enabled = url.isNotBlank()) {
                Text(stringResource(R.string.link_apply))
            }
        },
        dismissButton = {
            Row {
                if (ui.link != null) {
                    TextButton(onClick = { openLink(context, ui.link.href.orEmpty()) }) { Text(stringResource(R.string.link_open)) }
                    TextButton(onClick = { onRemove(); onDismiss() }) { Text(stringResource(R.string.link_remove)) }
                }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.dialog_cancel)) }
            }
        },
    )
}

private fun openLink(context: android.content.Context, href: String) {
    val uri = runCatching { Uri.parse(href.trim()) }.getOrNull() ?: return
    if (uri.scheme !in setOf("http", "https", "mailto", "tel")) return
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}
