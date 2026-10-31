package io.github.zeperus.openpad.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.zeperus.openpad.R
import io.github.zeperus.openpad.domain.FolderInfo
import io.github.zeperus.openpad.domain.NoteId
import io.github.zeperus.openpad.domain.NoteInfo

@Composable
internal fun DrawerContent(
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
internal fun LazyListScope.noteRows(
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
internal fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 28.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
    )
}

@Composable
internal fun EmptyHint(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 28.dp, vertical = 8.dp),
    )
}

/** A folder in the FILES section: tap opens/closes it, long press offers Rename / Delete (only empty folders can be deleted). */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun FolderHeader(folder: FolderInfo, count: Int, open: Boolean, onToggle: () -> Unit, onRename: () -> Unit, onDelete: () -> Unit) {
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
