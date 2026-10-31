package io.github.zeperus.openpad.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.ui.Alignment
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.zeperus.openpad.R
import io.github.zeperus.openpad.domain.NoteFileName
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

/** [destructive] colors the confirm button as an error (delete, clear, remove), so it does not look like a harmless "OK". */
@Composable
internal fun ConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    destructive: Boolean = false,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                colors = if (destructive) {
                    ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                } else {
                    ButtonDefaults.textButtonColors()
                },
            ) { Text(confirmLabel) }
        },
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
    fileNameHint = true, // a note name becomes a file name, so show how it will be saved
)

/**
 * A one-line name dialog (rename a note, create or rename a folder) with the usual error messages.
 * The text is selected and focused on open. Confirm is disabled while the name is blank or a submission runs.
 * [fileNameHint] (note names only) shows the saved file name when sanitizing changes what was typed.
 */
@Composable
internal fun NameDialog(
    title: String,
    initial: String,
    confirmLabel: String,
    takenMessage: String,
    onSubmit: suspend (String) -> RenameResult,
    onDismiss: () -> Unit,
    fileNameHint: Boolean = false,
) {
    // the typed text, its selection and the error survive a rotation; a running submission does not (its scope ends with the composition)
    var name by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(initial, selection = TextRange(0, initial.length)))
    }
    var error by rememberSaveable { mutableStateOf<RenameResult?>(null) }
    var submitting by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }
    val scope = rememberCoroutineScope()
    // the file name it will be saved as, when that differs from the typed name (e.g. "a/b" -> "a b")
    val savedAs = if (fileNameHint) NoteFileName.sanitizeOrNull(name.text)?.takeIf { it != name.text.trim() } else null
    fun submit() {
        if (submitting || name.text.isBlank()) return // also blocks a second IME "done" while the first is still running
        submitting = true
        scope.launch {
            val result = onSubmit(name.text)
            if (result == RenameResult.Ok) {
                onDismiss()
            } else {
                error = result
                submitting = false // the user corrects the name and confirms again
            }
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            // requested here, where the field is composed: a request from outside the dialog content finds no field yet
            LaunchedEffect(Unit) { focusRequester.requestFocus() }
            TextField(
                value = name,
                onValueChange = { name = it; error = null },
                label = { Text(stringResource(R.string.dialog_rename_label)) },
                singleLine = true,
                isError = error != null,
                modifier = Modifier.focusRequester(focusRequester).testTag("name-field"),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { submit() }),
                supportingText = {
                    val errorText = when (error) {
                        RenameResult.InvalidName -> stringResource(R.string.rename_invalid)
                        RenameResult.NameTaken -> takenMessage
                        RenameResult.Failed -> stringResource(R.string.rename_failed)
                        else -> null
                    }
                    val supporting = errorText ?: savedAs?.let { stringResource(R.string.name_sanitized_hint, it) }
                    if (supporting != null) Text(supporting)
                },
            )
        },
        confirmButton = {
            TextButton(
                onClick = { submit() },
                enabled = name.text.isNotBlank() && !submitting,
            ) { Text(confirmLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.dialog_cancel)) } },
    )
}

/** "Move to folder": the folders as a choice (plus "No folder"), and a way to start a new one. */
@Composable
internal fun MoveToFolderDialog(vm: NotesViewModel, onDismiss: () -> Unit) {
    var creating by rememberSaveable { mutableStateOf(false) }
    var name by rememberSaveable { mutableStateOf("") }
    var error by rememberSaveable { mutableStateOf<RenameResult?>(null) }
    var submitting by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }
    val scope = rememberCoroutineScope()
    val currentFolder = vm.current?.folderId
    fun createFolder() {
        if (submitting || name.isBlank()) return // also blocks a second IME "done" while the first is still running
        submitting = true
        scope.launch {
            val result = vm.createFolderAndMove(name)
            if (result == RenameResult.Ok) {
                onDismiss()
            } else {
                error = result
                submitting = false // the user corrects the name and confirms again
            }
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.dialog_move_title)) },
        text = {
            // the folder list scrolls and takes only the height left over, so "New folder" and its field stay in view (landscape)
            Column {
                Column(
                    Modifier
                        .weight(1f, fill = false)
                        .heightIn(max = 320.dp)
                        .verticalScroll(rememberScrollState())
                        .selectableGroup(),
                ) {
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
                }
                if (creating) {
                    LaunchedEffect(Unit) { focusRequester.requestFocus() }
                    TextField(
                        value = name,
                        onValueChange = { name = it; error = null },
                        label = { Text(stringResource(R.string.dialog_rename_label)) },
                        singleLine = true,
                        isError = error != null,
                        modifier = Modifier.focusRequester(focusRequester).testTag("name-field"),
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { createFolder() }),
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
                TextButton(onClick = { createFolder() }, enabled = name.isNotBlank() && !submitting) {
                    Text(stringResource(R.string.dialog_new_folder_confirm))
                }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.dialog_cancel)) } },
    )
}
