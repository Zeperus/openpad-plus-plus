package io.github.zeperus.openpad.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.ui.platform.LocalContext
import io.github.zeperus.openpad.ShareHelper
import io.github.zeperus.openpad.data.ExternalAccess
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.stateDescription
import io.github.zeperus.openpad.domain.DocumentTab
import io.github.zeperus.openpad.domain.StartupMode
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
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
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.OutlinedButton
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
import io.github.zeperus.openpad.ui.editor.FormattingBar
import io.github.zeperus.openpad.ui.editor.RichEditor
import io.github.zeperus.openpad.ui.editor.SelectionBar
import io.github.zeperus.openpad.domain.FolderInfo
import io.github.zeperus.openpad.domain.NoteId
import io.github.zeperus.openpad.domain.NoteInfo
import kotlinx.coroutines.launch

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
    var dialog by remember { mutableStateOf<Dialog?>(null) }
    var menuOpen by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val clipboard = io.github.zeperus.openpad.ui.editor.rememberAppClipboard()
    // The system file picker: the document stays where it is and is edited in place.
    val openFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.openExternal(uri.toString(), ExternalAccess.takePersistable(context.contentResolver, uri))
    }

    BackHandler(enabled = drawerState.isOpen) { scope.launch { drawerState.close() } }

    val message = vm.message
    val messageText = when (message) {
        UserMessage.SaveFailed -> stringResource(R.string.message_save_failed)
        UserMessage.NoteUnreadable -> stringResource(R.string.message_note_unreadable)
        UserMessage.ActionFailed -> stringResource(R.string.message_action_failed)
        UserMessage.SourceUnavailable -> stringResource(R.string.message_source_unavailable)
        UserMessage.FolderNotEmpty -> stringResource(R.string.message_folder_not_empty)
        null -> null
    }
    LaunchedEffect(message) {
        if (messageText != null) {
            snackbar.showSnackbar(messageText)
            vm.messageShown()
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
                        Text(
                            text = vm.current?.title ?: stringResource(R.string.untitled),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
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
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
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
                                enabled = vm.hasNote && vm.current?.isExternal != true,
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
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.action_find)) },
                                onClick = { menuOpen = false; vm.startFind() },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.selection_all)) },
                                onClick = { menuOpen = false; vm.selectAll() },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.selection_copy_markdown)) },
                                onClick = {
                                    menuOpen = false
                                    // the selection, or - without one - the whole note
                                    if (vm.docSelection == null && vm.ui.cursor?.isCollapsed != false) vm.selectAll()
                                    vm.selectedText(markdown = true)?.let { clipboard.copy("Markdown", it) }
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.action_paste_markdown)) },
                                enabled = !vm.readOnly,
                                onClick = { menuOpen = false; clipboard.text()?.let { vm.pasteMarkdown(it) } },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.action_share)) },
                                enabled = vm.hasNote,
                                onClick = {
                                    menuOpen = false
                                    vm.share { title, markdown ->
                                        context.startActivity(ShareHelper.createIntent(context, title, markdown))
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
                        Text(
                            text = stringResource(R.string.read_only_banner),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(MaterialTheme.colorScheme.secondaryContainer)
                                .padding(horizontal = 16.dp, vertical = 6.dp),
                        )
                    }
                    RichEditor(vm, Modifier.fillMaxWidth().weight(1f))
                    if (vm.docSelection != null) SelectionBar(vm) else if (!vm.readOnly) FormattingBar(vm)
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
            initial = vm.current?.title.orEmpty(),
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
            onConfirm = { vm.deleteFolder(d.folder.id); dialog = null },
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
            title = stringResource(if (vm.current?.isExternal == true) R.string.dialog_remove_title else R.string.dialog_delete_title),
            message = stringResource(
                if (vm.current?.isExternal == true) R.string.dialog_remove_message else R.string.dialog_delete_message,
                vm.current?.title ?: stringResource(R.string.untitled),
            ),
            confirmLabel = stringResource(if (vm.current?.isExternal == true) R.string.dialog_remove_confirm else R.string.dialog_delete_confirm),
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
    onNewChecklist: () -> Unit,
    onOpenFile: () -> Unit,
    onOpen: (NoteInfo) -> Unit,
    onPurge: (NoteInfo) -> Unit,
    onNewFolder: () -> Unit,
    onRenameFolder: (FolderInfo) -> Unit,
    onDeleteFolder: (FolderInfo) -> Unit,
) {
    LazyColumn(contentPadding = PaddingValues(vertical = 12.dp), modifier = Modifier.fillMaxSize().testTag("drawer")) {
        item {
            Button(
                onClick = onNewNote,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            ) { Text(stringResource(R.string.new_note)) }
            OutlinedButton(
                onClick = onNewChecklist,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            ) { Text(stringResource(R.string.new_checklist)) }
            TextButton(
                onClick = onOpenFile,
                modifier = Modifier.padding(horizontal = 12.dp),
            ) { Text(stringResource(R.string.open_file)) }
        }
        // Favorites and Recent appear only when they have entries; a note may be in FILES and in one of them.
        if (vm.favorites.isNotEmpty()) {
            item(key = "header-favorites") { SectionHeader(stringResource(R.string.section_favorites)) }
            noteRows("favorite", vm.favorites, vm.current?.id, onOpen)
        }
        if (vm.recent.isNotEmpty()) {
            item(key = "header-recent") { SectionHeader(stringResource(R.string.section_recent)) }
            noteRows("recent", vm.recent, vm.current?.id, onOpen)
        }
        item(key = "header-files") { SectionHeader(stringResource(R.string.section_files)) }
        if (vm.notes.isEmpty()) {
            item(key = "files-empty") { EmptyHint(stringResource(R.string.files_empty)) }
        }
        if (vm.folders.isEmpty()) {
            noteRows("file", vm.notes, vm.current?.id, onOpen)
        } else {
            // folders first (collapsible), then the notes that are in no folder
            for (folder in vm.folders) {
                val inside = vm.notes.filter { it.folderId == folder.id }
                val open = folder.id in vm.expandedFolders || inside.any { it.id == vm.current?.id }
                item(key = "folder-${folder.id}") {
                    FolderHeader(folder, inside.size, open, onToggle = { vm.toggleFolder(folder.id) }, onRename = { onRenameFolder(folder) }, onDelete = { onDeleteFolder(folder) })
                }
                if (open) noteRows("in-${folder.id}", inside, vm.current?.id, onOpen, indent = 16.dp)
            }
            noteRows("file", vm.notes.filter { n -> n.folderId == null || vm.folders.none { it.id == n.folderId } }, vm.current?.id, onOpen)
            item(key = "new-folder") {
                TextButton(onClick = onNewFolder, modifier = Modifier.padding(horizontal = 12.dp).testTag("new-folder")) { Text(stringResource(R.string.new_folder)) }
            }
        }
        item(key = "divider") { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }
        item(key = "header-trash") { SectionHeader(stringResource(R.string.section_trash)) }
        if (vm.trash.isEmpty()) {
            item(key = "trash-empty") { EmptyHint(stringResource(R.string.trash_empty)) }
        }
        items(vm.trash, key = { "trash-" + it.id.value }) { note ->
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

/** One drawer row per note. [section] namespaces the lazy-list keys: the same note can appear in two sections. */
private fun LazyListScope.noteRows(
    section: String,
    notes: List<NoteInfo>,
    selected: NoteId?,
    onOpen: (NoteInfo) -> Unit,
    indent: androidx.compose.ui.unit.Dp = 0.dp,
) {
    items(notes, key = { "$section-${it.id.value}" }) { note ->
        val externalLabel = stringResource(R.string.external_file)
        NavigationDrawerItem(
            label = { Text(note.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            selected = note.id == selected,
            onClick = { onOpen(note) },
            badge = if (note.isExternal) {
                { Text("↗", style = MaterialTheme.typography.labelMedium, modifier = Modifier.semantics { contentDescription = externalLabel }) }
            } else null,
            modifier = Modifier.padding(start = 12.dp + indent, end = 12.dp),
        )
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
private fun NameDialog(
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

/** A folder in the FILES section: tap opens/closes it, long press offers Rename / Delete (only empty folders can be deleted). */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FolderHeader(folder: FolderInfo, count: Int, open: Boolean, onToggle: () -> Unit, onRename: () -> Unit, onDelete: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    val state = stringResource(if (open) R.string.folder_expanded else R.string.folder_collapsed)
    Box {
        Row(
            Modifier
                .fillMaxWidth()
                .combinedClickable(onClick = onToggle, onLongClick = { menu = true }, onLongClickLabel = stringResource(R.string.folder_options), role = Role.Button)
                .padding(horizontal = 28.dp, vertical = 12.dp)
                .semantics(mergeDescendants = true) { stateDescription = state }
                .testTag("folder"),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(if (open) "\u25BE" else "\u25B8", modifier = Modifier.padding(end = 8.dp))
            Text(folder.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Text(count.toString(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text(stringResource(R.string.action_rename)) }, onClick = { menu = false; onRename() })
            DropdownMenuItem(text = { Text(stringResource(R.string.action_delete_folder)) }, onClick = { menu = false; onDelete() })
        }
    }
}

/** "Move to folder": the folders as a choice (plus "No folder"), and a way to start a new one. */
@Composable
private fun MoveToFolderDialog(vm: NotesViewModel, onDismiss: () -> Unit) {
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

/**
 * The open documents as a compact, horizontally scrollable strip. There is deliberately no close button on the
 * tabs (too easy to mis-tap): a long press opens Close / Close others / Close all instead.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TabStrip(vm: NotesViewModel) {
    val tabs = vm.tabs
    val listState = rememberLazyListState()
    val activeIndex = tabs.indexOfFirst { it.isActive }
    LaunchedEffect(activeIndex, tabs.size) {
        if (activeIndex >= 0) listState.animateScrollToItem(activeIndex)
    }
    LazyRow(
        state = listState,
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
    ) {
        items(tabs, key = { tabKey(it.tab) }) { item ->
            var menuOpen by remember { mutableStateOf(false) }
            val title = if (item.tab == DocumentTab.Draft) stringResource(R.string.tab_new_note) else item.title
            val optionsLabel = stringResource(R.string.tab_options)
            Box {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = if (item.isActive) MaterialTheme.colorScheme.secondaryContainer
                    else MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier
                        .testTag("tab")
                        .semantics { selected = item.isActive }
                        .combinedClickable(
                            onClick = { vm.selectTab(item.tab) },
                            onLongClick = { menuOpen = true },
                            onLongClickLabel = optionsLabel,
                            role = Role.Tab,
                        ),
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.labelLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = 160.dp).padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.action_close)) },
                        onClick = { menuOpen = false; vm.closeTab(item.tab) },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.action_close_others)) },
                        enabled = tabs.size > 1,
                        onClick = { menuOpen = false; vm.closeOthers(item.tab) },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.action_close_all)) },
                        onClick = { menuOpen = false; vm.closeAll() },
                    )
                }
            }
        }
    }
}

private fun tabKey(tab: DocumentTab): String = when (tab) {
    is DocumentTab.Saved -> "note-" + tab.id.value
    DocumentTab.Draft -> "draft"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsScreen(vm: NotesViewModel, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.settings_back))
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            SectionHeader(stringResource(R.string.settings_startup))
            val options = listOf(
                StartupMode.ResumeSession to R.string.startup_resume_session,
                StartupMode.ResumeAndBlank to R.string.startup_resume_blank,
                StartupMode.BlankNote to R.string.startup_blank_note,
            )
            Column(Modifier.selectableGroup()) {
                for ((mode, label) in options) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(
                                selected = vm.startupMode == mode,
                                onClick = { vm.chooseStartupMode(mode) },
                                role = Role.RadioButton,
                            )
                            .padding(horizontal = 24.dp, vertical = 12.dp),
                    ) {
                        RadioButton(selected = vm.startupMode == mode, onClick = null)
                        Text(stringResource(label), modifier = Modifier.padding(start = 16.dp))
                    }
                }
            }
            val context = LocalContext.current
            val version = remember { runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull().orEmpty() }
            Text(
                text = stringResource(R.string.settings_version, version),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 24.dp).testTag("version"),
            )
        }
    }
}
