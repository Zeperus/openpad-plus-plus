package io.github.zeperus.openpad.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.background
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.RadioButton
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.zeperus.openpad.R
import kotlinx.coroutines.launch

/** A full-width bar above the editor (read-only notice, save error); [content] is laid out in a row. Polite live region: TalkBack announces it. */
@Composable
internal fun NoticeBar(containerColor: Color, tag: String, content: @Composable RowScope.() -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(containerColor)
            .padding(start = 16.dp, end = 8.dp)
            .semantics { liveRegion = LiveRegionMode.Polite }
            .testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

@Composable
internal fun ConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirmLabel) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.dialog_cancel)) } },
    )
}

@Composable
internal fun RenameDialog(
    initial: String,
    onRename: suspend (String) -> RenameResult,
    onDismiss: () -> Unit,
) = NameDialog(
    title = stringResource(R.string.dialog_rename_title),
    initial = initial,
    confirmLabel = stringResource(R.string.dialog_rename_confirm),
    takenMessage = stringResource(R.string.rename_taken),
    onSubmit = onRename,
    onDismiss = onDismiss,
)

/** A one-line name dialog (rename a note, create or rename a folder) with the usual error messages. */
@Composable
internal fun NameDialog(
    title: String,
    initial: String,
    confirmLabel: String,
    takenMessage: String,
    onSubmit: suspend (String) -> RenameResult,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(initial) }
    var error by remember { mutableStateOf<RenameResult?>(null) }
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            TextField(
                value = name,
                onValueChange = { name = it; error = null },
                label = { Text(stringResource(R.string.dialog_rename_label)) },
                singleLine = true,
                isError = error != null,
                modifier = Modifier.testTag("name-field"),
                supportingText = {
                    when (error) {
                        RenameResult.InvalidName -> Text(stringResource(R.string.rename_invalid))
                        RenameResult.NameTaken -> Text(takenMessage)
                        RenameResult.Failed -> Text(stringResource(R.string.rename_failed))
                        else -> Unit
                    }
                },
            )
        },
        confirmButton = {
            TextButton(onClick = {
                scope.launch {
                    val result = onSubmit(name)
                    if (result == RenameResult.Ok) onDismiss() else error = result
                }
            }) { Text(confirmLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.dialog_cancel)) } },
    )
}

/** "Move to folder": the folders as a choice (plus "No folder"), and a way to start a new one. */
@Composable
internal fun MoveToFolderDialog(vm: NotesViewModel, onDismiss: () -> Unit) {
    var creating by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<RenameResult?>(null) }
    val scope = rememberCoroutineScope()
    val currentFolder = vm.current?.folderId
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.dialog_move_title)) },
        text = {
            Column(Modifier.selectableGroup()) {
                val options = listOf<Pair<String?, String>>(null to stringResource(R.string.folder_none)) + vm.folders.map { it.id to it.name }
                for ((id, label) in options) {
                    Row(
                        Modifier.fillMaxWidth().selectable(selected = currentFolder == id, role = Role.RadioButton, onClick = { vm.moveCurrentToFolder(id); onDismiss() }).padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = currentFolder == id, onClick = null)
                        Text(label, modifier = Modifier.padding(start = 12.dp))
                    }
                }
                if (creating) {
                    TextField(
                        value = name,
                        onValueChange = { name = it; error = null },
                        label = { Text(stringResource(R.string.dialog_rename_label)) },
                        singleLine = true,
                        isError = error != null,
                        modifier = Modifier.testTag("name-field"),
                        supportingText = {
                            when (error) {
                                RenameResult.InvalidName -> Text(stringResource(R.string.rename_invalid))
                                RenameResult.NameTaken -> Text(stringResource(R.string.folder_taken))
                                RenameResult.Failed -> Text(stringResource(R.string.rename_failed))
                                else -> Unit
                            }
                        },
                    )
                } else {
                    TextButton(onClick = { creating = true }, modifier = Modifier.testTag("move-new-folder")) { Text(stringResource(R.string.new_folder)) }
                }
            }
        },
        confirmButton = {
            if (creating) {
                TextButton(onClick = {
                    scope.launch {
                        val result = vm.createFolderAndMove(name)
                        if (result == RenameResult.Ok) onDismiss() else error = result
                    }
                }) { Text(stringResource(R.string.dialog_new_folder_confirm)) }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.dialog_cancel)) } },
    )
}
