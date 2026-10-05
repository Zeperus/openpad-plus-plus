package io.github.zeperus.openpad.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.zeperus.openpad.R
import io.github.zeperus.openpad.domain.NoteInfo
import kotlinx.coroutines.launch

private sealed interface Dialog {
    data object Rename : Dialog
    data object Clear : Dialog
    data object Delete : Dialog
    data class Purge(val note: NoteInfo) : Dialog
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotesScreen(vm: NotesViewModel) {
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var dialog by remember { mutableStateOf<Dialog?>(null) }
    var menuOpen by remember { mutableStateOf(false) }

    BackHandler(enabled = drawerState.isOpen) { scope.launch { drawerState.close() } }

    val message = vm.message
    val messageText = when (message) {
        UserMessage.SaveFailed -> stringResource(R.string.message_save_failed)
        UserMessage.NoteUnreadable -> stringResource(R.string.message_note_unreadable)
        UserMessage.ActionFailed -> stringResource(R.string.message_action_failed)
        null -> null
    }
    LaunchedEffect(message) {
        if (messageText != null) {
            snackbar.showSnackbar(messageText)
            vm.messageShown()
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet {
                DrawerContent(
                    vm = vm,
                    onNewNote = { vm.newNote(); scope.launch { drawerState.close() } },
                    onOpen = { vm.openNote(it.id); scope.launch { drawerState.close() } },
                    onPurge = { dialog = Dialog.Purge(it) },
                )
            }
        },
    ) {
        Scaffold(
            snackbarHost = { SnackbarHost(snackbar) },
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            text = vm.current?.title ?: stringResource(R.string.untitled),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(Icons.Default.Menu, contentDescription = stringResource(R.string.open_drawer))
                        }
                    },
                    actions = {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.more_options))
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.action_rename)) },
                                enabled = vm.hasNote,
                                onClick = { menuOpen = false; dialog = Dialog.Rename },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.action_clear)) },
                                enabled = vm.text.isNotEmpty(),
                                onClick = { menuOpen = false; dialog = Dialog.Clear },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.action_delete)) },
                                enabled = vm.hasNote,
                                onClick = { menuOpen = false; dialog = Dialog.Delete },
                            )
                        }
                    },
                )
            },
        ) { padding ->
            // Temporary raw-Markdown editor; replaced by the formatted editor in a later milestone.
            TextField(
                value = vm.text,
                onValueChange = vm::onTextChange,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .imePadding(),
                placeholder = { Text(stringResource(R.string.editor_hint)) },
                textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.background,
                    unfocusedContainerColor = MaterialTheme.colorScheme.background,
                    focusedIndicatorColor = MaterialTheme.colorScheme.background,
                    unfocusedIndicatorColor = MaterialTheme.colorScheme.background,
                ),
            )
        }
    }

    when (val d = dialog) {
        null -> Unit
        Dialog.Rename -> RenameDialog(
            initial = vm.current?.title.orEmpty(),
            onRename = { vm.rename(it) },
            onDismiss = { dialog = null },
        )
        Dialog.Clear -> ConfirmDialog(
            title = stringResource(R.string.dialog_clear_title),
            message = stringResource(R.string.dialog_clear_message),
            confirmLabel = stringResource(R.string.dialog_clear_confirm),
            onConfirm = { vm.clear(); dialog = null },
            onDismiss = { dialog = null },
        )
        Dialog.Delete -> ConfirmDialog(
            title = stringResource(R.string.dialog_delete_title),
            message = stringResource(
                R.string.dialog_delete_message,
                vm.current?.title ?: stringResource(R.string.untitled),
            ),
            confirmLabel = stringResource(R.string.dialog_delete_confirm),
            onConfirm = { vm.deleteCurrent(); dialog = null },
            onDismiss = { dialog = null },
        )
        is Dialog.Purge -> ConfirmDialog(
            title = stringResource(R.string.dialog_purge_title),
            message = stringResource(R.string.dialog_purge_message, d.note.title),
            confirmLabel = stringResource(R.string.action_delete_permanently),
            onConfirm = { vm.deletePermanently(d.note.id); dialog = null },
            onDismiss = { dialog = null },
        )
    }
}

@Composable
private fun DrawerContent(
    vm: NotesViewModel,
    onNewNote: () -> Unit,
    onOpen: (NoteInfo) -> Unit,
    onPurge: (NoteInfo) -> Unit,
) {
    LazyColumn(contentPadding = PaddingValues(vertical = 12.dp), modifier = Modifier.fillMaxSize()) {
        item {
            Button(
                onClick = onNewNote,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            ) { Text(stringResource(R.string.new_note)) }
        }
        item { SectionHeader(stringResource(R.string.section_files)) }
        if (vm.notes.isEmpty()) {
            item { EmptyHint(stringResource(R.string.files_empty)) }
        }
        items(vm.notes, key = { it.id.value }) { note ->
            NavigationDrawerItem(
                label = { Text(note.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                selected = note.id == vm.current?.id,
                onClick = { onOpen(note) },
                modifier = Modifier.padding(horizontal = 12.dp),
            )
        }
        item { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }
        item { SectionHeader(stringResource(R.string.section_trash)) }
        if (vm.trash.isEmpty()) {
            item { EmptyHint(stringResource(R.string.trash_empty)) }
        }
        items(vm.trash, key = { it.id.value }) { note ->
            val restoreLabel = stringResource(R.string.action_restore)
            val purgeLabel = stringResource(R.string.action_delete_permanently)
            Column(Modifier.padding(horizontal = 28.dp, vertical = 4.dp)) {
                Text(note.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(
                        onClick = { vm.restore(note.id) },
                        modifier = Modifier.semantics { contentDescription = "$restoreLabel: ${note.title}" },
                    ) { Text(restoreLabel) }
                    TextButton(
                        onClick = { onPurge(note) },
                        modifier = Modifier.semantics { contentDescription = "$purgeLabel: ${note.title}" },
                    ) { Text(purgeLabel) }
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 28.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
    )
}

@Composable
private fun EmptyHint(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 28.dp, vertical = 8.dp),
    )
}

@Composable
private fun ConfirmDialog(
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
private fun RenameDialog(
    initial: String,
    onRename: suspend (String) -> RenameResult,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(initial) }
    var error by remember { mutableStateOf<RenameResult?>(null) }
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.dialog_rename_title)) },
        text = {
            TextField(
                value = name,
                onValueChange = { name = it; error = null },
                label = { Text(stringResource(R.string.dialog_rename_label)) },
                singleLine = true,
                isError = error != null,
                supportingText = {
                    when (error) {
                        RenameResult.InvalidName -> Text(stringResource(R.string.rename_invalid))
                        RenameResult.NameTaken -> Text(stringResource(R.string.rename_taken))
                        RenameResult.Failed -> Text(stringResource(R.string.rename_failed))
                        else -> Unit
                    }
                },
            )
        },
        confirmButton = {
            TextButton(onClick = {
                scope.launch {
                    val result = onRename(name)
                    if (result == RenameResult.Ok) onDismiss() else error = result
                }
            }) { Text(stringResource(R.string.dialog_rename_confirm)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.dialog_cancel)) } },
    )
}
