package io.github.zeperus.openpad.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.zeperus.openpad.R
import io.github.zeperus.openpad.domain.DocumentTab

/**
 * The open documents as a compact, horizontally scrollable strip. There is deliberately no close button on the
 * tabs (too easy to mis-tap): a long press opens Close / Close others / Close all instead.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun TabStrip(vm: NotesViewModel) {
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
                        .heightIn(min = 48.dp)
                        .testTag("tab")
                        .semantics { selected = item.isActive }
                        .combinedClickable(
                            onClick = { vm.selectTab(item.tab) },
                            onLongClick = { menuOpen = true },
                            onLongClickLabel = optionsLabel,
                            role = Role.Tab,
                        ),
                ) {
                    // The inner box has its own minimum height so the text is centred even if the Surface does not pass its constraints down.
                    Box(
                        modifier = Modifier.heightIn(min = 48.dp).padding(horizontal = 12.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.labelLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.widthIn(max = 160.dp),
                        )
                    }
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

internal fun tabKey(tab: DocumentTab): String = when (tab) {
    is DocumentTab.Saved -> "note-" + tab.id.value
    DocumentTab.Draft -> "draft"
}
