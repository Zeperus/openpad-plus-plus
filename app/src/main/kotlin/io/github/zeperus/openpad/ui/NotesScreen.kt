package io.github.zeperus.openpad.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.ui.platform.LocalContext
import io.github.zeperus.openpad.ShareHelper
import io.github.zeperus.openpad.data.ExternalAccess
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
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
import androidx.compose.material3.PermanentDrawerSheet
import androidx.compose.material3.PermanentNavigationDrawer
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.ui.unit.DpSize
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.zeperus.openpad.R
import io.github.zeperus.openpad.ui.editor.FormattingBar
import io.github.zeperus.openpad.ui.editor.RichEditor
import io.github.zeperus.openpad.domain.FolderInfo
import io.github.zeperus.openpad.domain.NoteInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private sealed interface Dialog {
    data object Rename : Dialog
    data object MoveToFolder : Dialog
    data object NewFolder : Dialog
    data class RenameFolder(val folder: FolderInfo) : Dialog
    data class DeleteFolder(val folder: FolderInfo) : Dialog
    data object Clear : Dialog
    data object Delete : Dialog
    data class Purge(val note: NoteInfo) : Dialog
}

/**
 * Keeps the open dialog across a rotation as a short string. A folder or note is looked up again on restore;
 * one that no longer exists restores as null, so the dialog simply closes.
 */
private fun dialogSaver(vm: NotesViewModel): Saver<Dialog?, String> = Saver(
    save = { dialog ->
        when (dialog) {
            null -> null
            Dialog.Rename -> "rename"
            Dialog.MoveToFolder -> "move"
            Dialog.NewFolder -> "newFolder"
            is Dialog.RenameFolder -> "renameFolder:${dialog.folder.id}"
            is Dialog.DeleteFolder -> "deleteFolder:${dialog.folder.id}"
            Dialog.Clear -> "clear"
            Dialog.Delete -> "delete"
            is Dialog.Purge -> "purge:${dialog.note.id.value}"
        }
    },
    restore = { saved ->
        val id = saved.substringAfter(':', missingDelimiterValue = "")
        when (saved.substringBefore(':')) {
            "rename" -> Dialog.Rename
            "move" -> Dialog.MoveToFolder
            "newFolder" -> Dialog.NewFolder
            "clear" -> Dialog.Clear
            "delete" -> Dialog.Delete
            "renameFolder" -> vm.folders.firstOrNull { it.id == id }?.let { Dialog.RenameFolder(it) }
            "deleteFolder" -> vm.folders.firstOrNull { it.id == id }?.let { Dialog.DeleteFolder(it) }
            "purge" -> vm.trash.firstOrNull { it.id.value == id }?.let { Dialog.Purge(it) }
            else -> null
        }
    },
)

@Composable
fun NotesScreen(vm: NotesViewModel) {
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var showSearch by rememberSaveable { mutableStateOf(false) }
    if (showSettings) {
        SettingsScreen(vm, onBack = { showSettings = false })
    } else if (showSearch) {
        SearchScreen(
            vm,
            onBack = { showSearch = false; vm.clearSearch() },
            onOpen = { hit ->
                val query = vm.searchQuery
                showSearch = false
                vm.openFromSearch(hit.note.id, query)
                vm.clearSearch()
            },
        )
    } else {
        NotesContent(vm, onOpenSettings = { showSettings = true }, onOpenSearch = { showSearch = true })
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3WindowSizeClassApi::class)
@Composable
private fun NotesContent(vm: NotesViewModel, onOpenSettings: () -> Unit, onOpenSearch: () -> Unit) {
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    // the open dialog, the overflow menu and its second page survive a rotation
    val dialogStateSaver = remember(vm) { dialogSaver(vm) }
    var dialog by rememberSaveable(stateSaver = dialogStateSaver) { mutableStateOf<Dialog?>(null) }
    var menuOpen by rememberSaveable { mutableStateOf(false) }
    var editPage by rememberSaveable { mutableStateOf(false) } // the overflow menu's second page: select / copy / paste
    val context = LocalContext.current
    val clipboard = io.github.zeperus.openpad.ui.editor.rememberAppClipboard()
    // The system file picker: the document stays where it is and is edited in place.
    val openFile = rememberLauncherForActivityResult(io.github.zeperus.openpad.data.OpenWritableDocument()) { uri ->
        if (uri != null) vm.openExternal(uri.toString(), ExternalAccess.takePersistable(context.contentResolver, uri))
    }

    // "Open with write access": pick the same file again, this time asking for write access (the picker opens next to it)
    val currentUri = vm.current?.externalUri
    val openWritable = rememberLauncherForActivityResult(
        remember(currentUri) { io.github.zeperus.openpad.data.OpenWritableDocument(currentUri?.let { android.net.Uri.parse(it) }) },
    ) { uri ->
        if (uri != null) vm.upgradeExternal(uri.toString(), ExternalAccess.takePersistable(context.contentResolver, uri))
    }

    BackHandler(enabled = drawerState.isOpen) { scope.launch { drawerState.close() } }

    val message = vm.message
    val messageText = when (message) {
        UserMessage.SaveFailed -> stringResource(R.string.message_save_failed)
        UserMessage.NoteUnreadable -> stringResource(R.string.message_note_unreadable)
        UserMessage.ActionFailed -> stringResource(R.string.message_action_failed)
        UserMessage.SourceUnavailable -> stringResource(R.string.message_source_unavailable)
        UserMessage.FolderNotEmpty -> stringResource(R.string.message_folder_not_empty)
        UserMessage.MovedToTrash -> stringResource(R.string.message_moved_to_trash)
        UserMessage.SmartChecklistOn -> stringResource(R.string.message_smart_checklist_on)
        null -> null
    }
    val undoLabel = stringResource(R.string.action_undo)
    LaunchedEffect(message) {
        if (messageText != null) {
            // only a note that was just moved to Trash offers undo
            val result = snackbar.showSnackbar(
                message = messageText,
                actionLabel = undoLabel.takeIf { message == UserMessage.MovedToTrash },
            )
            vm.messageShown()
            if (result == SnackbarResult.ActionPerformed) vm.undoTrash()
        }
    }

    // A wide window (tablet, unfolded foldable, desktop mode) keeps the navigation visible next to the editor; a phone slides it in.
    BoxWithConstraints(Modifier.fillMaxSize()) {
    val wide = WindowSizeClass.calculateFromSize(DpSize(maxWidth, maxHeight)).widthSizeClass == WindowWidthSizeClass.Expanded
    val drawerBody: @Composable () -> Unit = {
        DrawerContent(
            vm = vm,
            onNewNote = { vm.newNote(); scope.launch { drawerState.close() } },
            onNewChecklist = { vm.newChecklist(); scope.launch { drawerState.close() } },
            onOpenFile = { scope.launch { drawerState.close() }; openFile.launch(arrayOf("*/*")) },
            onOpen = { vm.openNote(it.id); scope.launch { drawerState.close() } },
            onPurge = { dialog = Dialog.Purge(it) },
            onNewFolder = { dialog = Dialog.NewFolder },
            onRenameFolder = { dialog = Dialog.RenameFolder(it) },
            onDeleteFolder = { dialog = Dialog.DeleteFolder(it) },
        )
    }
    val content: @Composable () -> Unit = {
        Scaffold(
            snackbarHost = { SnackbarHost(snackbar) },
            topBar = {
                TopAppBar(
                    title = {
                        val renameLabel = stringResource(R.string.dialog_rename_title)
                        // An automatic title (the first line, or "Untitled") is renamed by a tap; a title the user chose only by a
                        // long press, so that it is not changed by accident. External documents keep their file name.
                        val canRename = vm.canRename
                        // A title the user chose has no tap action: no ripple and no "rename" tap label, only the long press.
                        val titleInteraction = remember { MutableInteractionSource() }
                        val titleClicks = if (vm.hasExplicitTitle) {
                            Modifier.combinedClickable(
                                interactionSource = titleInteraction,
                                indication = null,
                                enabled = canRename,
                                onLongClickLabel = renameLabel,
                                onClick = {},
                                onLongClick = { dialog = Dialog.Rename },
                            )
                        } else {
                            Modifier.combinedClickable(
                                enabled = canRename,
                                onClickLabel = renameLabel,
                                onLongClickLabel = renameLabel,
                                onClick = { dialog = Dialog.Rename },
                                onLongClick = { dialog = Dialog.Rename },
                            )
                        }
                        Text(
                            text = (vm.current?.title ?: stringResource(R.string.untitled)) +
                                if (vm.readOnly) " \u00B7 " + stringResource(R.string.read_only_short) else "",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = titleClicks.testTag("title"),
                        )
                    },
                    navigationIcon = {
                        if (!wide) {
                            IconButton(onClick = { scope.launch { drawerState.open() } }) {
                                Icon(Icons.Default.Menu, contentDescription = stringResource(R.string.open_drawer))
                            }
                        }
                    },
                    actions = {
                        IconButton(onClick = onOpenSearch) {
                            Icon(Icons.Default.Search, contentDescription = stringResource(R.string.action_search))
                        }
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.more_options))
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false; editPage = false }) {
                            if (editPage) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.selection_copy_markdown)) },
                                onClick = {
                                    menuOpen = false
                                    editPage = false
                                    // the selection, or - without one - the whole note
                                    vm.markdownToCopy()?.let { clipboard.copy("Markdown", it) }
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.action_paste_markdown)) },
                                enabled = !vm.readOnly,
                                onClick = { menuOpen = false; editPage = false; clipboard.text()?.let { vm.pasteMarkdown(it) } },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.action_paste_checklist)) },
                                enabled = !vm.readOnly,
                                onClick = { menuOpen = false; editPage = false; clipboard.text()?.let { vm.pasteAsChecklist(it) } },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.menu_back)) },
                                onClick = { editPage = false },
                            )
                            } else {
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        stringResource(
                                            if (vm.current?.favorite == true) R.string.action_unfavorite
                                            else R.string.action_favorite,
                                        ),
                                    )
                                },
                                enabled = vm.hasNote,
                                onClick = { menuOpen = false; vm.toggleFavorite() },
                            )
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        stringResource(
                                            if (vm.current?.smartChecklist == true) R.string.action_smart_checklist_off
                                            else R.string.action_smart_checklist_on,
                                        ),
                                    )
                                },
                                enabled = vm.hasNote && !vm.readOnly,
                                onClick = { menuOpen = false; vm.toggleSmartChecklist() },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.action_move_to_folder)) },
                                enabled = vm.hasNote && vm.current?.isExternal != true,
                                onClick = { menuOpen = false; dialog = Dialog.MoveToFolder },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.action_rename)) },
                                enabled = vm.canRename,
                                onClick = { menuOpen = false; dialog = Dialog.Rename },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.action_clear)) },
                                enabled = vm.text.isNotEmpty() && !vm.readOnly,
                                onClick = { menuOpen = false; dialog = Dialog.Clear },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.action_close)) },
                                onClick = { menuOpen = false; vm.closeCurrent() },
                            )
                            // the same actions as the long-press menu of a tab (TabStrip)
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.action_close_others)) },
                                enabled = vm.tabs.size > 1,
                                onClick = { menuOpen = false; vm.closeOthers(vm.session.active) },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.action_close_all)) },
                                enabled = vm.tabs.size > 1,
                                onClick = { menuOpen = false; vm.closeAll() },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.action_find)) },
                                onClick = { menuOpen = false; vm.startFind() },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.action_edit_menu)) },
                                onClick = { editPage = true },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.action_share)) },
                                enabled = vm.hasNote,
                                onClick = {
                                    menuOpen = false
                                    vm.share { title, markdown ->
                                        // the share file is written off the main thread; only the intent is started here
                                        scope.launch {
                                            val intent = withContext(Dispatchers.IO) { ShareHelper.createIntent(context, title, markdown) }
                                            context.startActivity(intent)
                                        }
                                    }
                                },
                            )
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        stringResource(
                                            if (vm.current?.isExternal == true) R.string.action_remove_external else R.string.action_delete,
                                        ),
                                    )
                                },
                                enabled = vm.hasNote,
                                onClick = { menuOpen = false; dialog = Dialog.Delete },
                            )
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.action_settings)) },
                                onClick = { menuOpen = false; onOpenSettings() },
                            )
                            }
                        }
                    },
                )
            },
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding).imePadding(), contentAlignment = Alignment.TopCenter) {
                // the writing area stays a comfortable width on a big screen
                Column(Modifier.fillMaxHeight().widthIn(max = 920.dp)) {
                if (vm.ready) {
                    TabStrip(vm)
                    FindBar(vm)
                    if (vm.readOnly) {
                        val reason = when (vm.readOnlyReason) {
                            io.github.zeperus.openpad.domain.ReadOnlyReason.NoWriteGrant -> R.string.read_only_reason_no_grant
                            io.github.zeperus.openpad.domain.ReadOnlyReason.ProviderRefuses -> R.string.read_only_reason_provider
                            io.github.zeperus.openpad.domain.ReadOnlyReason.Unavailable -> R.string.read_only_reason_unknown
                            null -> R.string.read_only_banner
                        }
                        NoticeBar(MaterialTheme.colorScheme.secondaryContainer, "read-only-banner") {
                            Text(
                                text = stringResource(reason),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.weight(1f).padding(vertical = 6.dp),
                            )
                            if (vm.current?.isExternal == true && vm.readOnlyReason != io.github.zeperus.openpad.domain.ReadOnlyReason.ProviderRefuses) {
                                TextButton(onClick = { openWritable.launch(arrayOf("*/*")) }, modifier = Modifier.testTag("open-writable")) {
                                    Text(stringResource(R.string.action_open_writable))
                                }
                            }
                        }
                    }
                    // a save that failed stays visible until a retry succeeds; the text is kept
                    if (vm.saveFailed) {
                        NoticeBar(MaterialTheme.colorScheme.errorContainer, "save-failed-banner") {
                            Text(
                                text = stringResource(R.string.save_failed_banner),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.weight(1f).padding(vertical = 6.dp),
                            )
                            TextButton(onClick = { vm.retrySave() }, modifier = Modifier.testTag("save-retry")) {
                                Text(stringResource(R.string.save_retry))
                            }
                        }
                    }
                    // the dialog was dismissed: the conflict stays until the user decides
                    val conflict = vm.externalConflict
                    if (conflict != null && !vm.conflictDialogVisible) {
                        NoticeBar(MaterialTheme.colorScheme.errorContainer, "external-conflict-banner") {
                            Text(
                                text = stringResource(R.string.external_conflict_banner, conflict),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.weight(1f).padding(vertical = 6.dp),
                            )
                            TextButton(onClick = { vm.showConflictDialog() }, modifier = Modifier.testTag("external-conflict-decide")) {
                                Text(stringResource(R.string.external_conflict_decide))
                            }
                        }
                    }
                    RichEditor(vm, Modifier.fillMaxWidth().weight(1f))
                    if (!vm.readOnly) FormattingBar(vm)
                }
                }
            }
        }
    }
    if (wide) {
        PermanentNavigationDrawer(
            drawerContent = { PermanentDrawerSheet(Modifier.width(320.dp).testTag("sidebar")) { drawerBody() } },
            content = content,
        )
    } else {
        ModalNavigationDrawer(
            drawerState = drawerState,
            drawerContent = { ModalDrawerSheet { drawerBody() } },
            content = content,
        )
    }
    }

    when (val d = dialog) {
        null -> Unit
        Dialog.Rename -> RenameDialog(
            initial = vm.current?.title.orEmpty(), // a blank page has no name yet: the field starts empty
            onRename = { vm.rename(it) },
            onDismiss = { dialog = null },
        )
        Dialog.MoveToFolder -> MoveToFolderDialog(vm, onDismiss = { dialog = null })
        Dialog.NewFolder -> NameDialog(
            title = stringResource(R.string.dialog_new_folder_title),
            initial = "",
            confirmLabel = stringResource(R.string.dialog_new_folder_confirm),
            takenMessage = stringResource(R.string.folder_taken),
            onSubmit = { vm.createFolder(it) },
            onDismiss = { dialog = null },
        )
        is Dialog.RenameFolder -> NameDialog(
            title = stringResource(R.string.dialog_rename_folder_title),
            initial = d.folder.name,
            confirmLabel = stringResource(R.string.dialog_rename_confirm),
            takenMessage = stringResource(R.string.folder_taken),
            onSubmit = { vm.renameFolder(d.folder.id, it) },
            onDismiss = { dialog = null },
        )
        is Dialog.DeleteFolder -> ConfirmDialog(
            title = stringResource(R.string.dialog_delete_folder_title),
            message = stringResource(R.string.dialog_delete_folder_message, d.folder.name),
            confirmLabel = stringResource(R.string.dialog_delete_folder_confirm),
            destructive = true,
            onConfirm = { vm.deleteFolder(d.folder.id); dialog = null },
            onDismiss = { dialog = null },
        )
        Dialog.Clear -> ConfirmDialog(
            title = stringResource(R.string.dialog_clear_title),
            message = stringResource(R.string.dialog_clear_message),
            confirmLabel = stringResource(R.string.dialog_clear_confirm),
            destructive = true,
            onConfirm = { vm.clear(); dialog = null },
            onDismiss = { dialog = null },
        )
        Dialog.Delete -> ConfirmDialog(
            title = stringResource(if (vm.current?.isExternal == true) R.string.dialog_remove_title else R.string.dialog_delete_title),
            message = stringResource(
                if (vm.current?.isExternal == true) R.string.dialog_remove_message else R.string.dialog_delete_message,
                vm.current?.title ?: stringResource(R.string.untitled),
            ),
            confirmLabel = stringResource(if (vm.current?.isExternal == true) R.string.dialog_remove_confirm else R.string.dialog_delete_confirm),
            destructive = true,
            onConfirm = { vm.deleteCurrent(); dialog = null },
            onDismiss = { dialog = null },
        )
        is Dialog.Purge -> ConfirmDialog(
            title = stringResource(R.string.dialog_purge_title),
            message = stringResource(R.string.dialog_purge_message, d.note.title),
            confirmLabel = stringResource(R.string.action_delete_permanently),
            destructive = true,
            onConfirm = { vm.deletePermanently(d.note.id); dialog = null },
            onDismiss = { dialog = null },
        )
    }
    // another app changed the open external document: the user chooses which version survives
    val conflict = vm.externalConflict
    if (conflict != null && vm.conflictDialogVisible) {
        val copyTitle = stringResource(R.string.external_conflict_copy_title, conflict)
        AlertDialog(
            onDismissRequest = { vm.dismissExternalConflict() },
            title = { Text(stringResource(R.string.external_conflict_title)) },
            text = { Text(stringResource(R.string.external_conflict_message, conflict, copyTitle)) },
            confirmButton = { TextButton(onClick = { vm.reloadExternal(copyTitle) }) { Text(stringResource(R.string.external_conflict_reload)) } },
            dismissButton = { TextButton(onClick = { vm.keepMineExternal() }) { Text(stringResource(R.string.external_conflict_keep_mine)) } },
            modifier = Modifier.testTag("external-conflict"),
        )
    }
}
