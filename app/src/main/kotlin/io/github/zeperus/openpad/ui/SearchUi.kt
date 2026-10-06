package io.github.zeperus.openpad.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import io.github.zeperus.openpad.R
import io.github.zeperus.openpad.domain.SearchHit

/** Search over all notes: a text field and, below it, the notes that match with the line around the match. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(vm: NotesViewModel, onBack: () -> Unit, onOpen: (SearchHit) -> Unit) {
    BackHandler(onBack = onBack)
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    TextField(
                        value = vm.searchQuery,
                        onValueChange = vm::search,
                        singleLine = true,
                        placeholder = { Text(stringResource(R.string.search_hint)) },
                        modifier = Modifier.fillMaxWidth().focusRequester(focus).testTag("search-field"),
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.settings_back)) }
                },
            )
        },
    ) { padding ->
        val results = vm.searchResults
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (vm.searchQuery.isNotBlank() && results.isEmpty()) {
                Text(stringResource(R.string.search_no_results), color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(24.dp))
            }
            LazyColumn(Modifier.fillMaxSize()) {
                items(results, key = { it.note.id.value }) { hit ->
                    Column(Modifier.fillMaxWidth().clickable { onOpen(hit) }.padding(horizontal = 24.dp, vertical = 12.dp).testTag("search-result")) {
                        Text(hit.note.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        hit.snippet?.let { snippet ->
                            val range = hit.snippetMatch
                            Text(
                                text = buildAnnotatedString {
                                    if (range == null || range.isEmpty()) append(snippet) else {
                                        append(snippet.substring(0, range.first))
                                        withStyle(SpanStyle(fontWeight = FontWeight.Bold, background = MaterialTheme.colorScheme.secondaryContainer)) {
                                            append(snippet.substring(range.first, range.last + 1))
                                        }
                                        append(snippet.substring(range.last + 1))
                                    }
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        if (hit.matches > 1) {
                            Text(pluralStringResource(R.plurals.search_matches, hit.matches, hit.matches), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}

/** The compact Find in note bar: the text, "2 of 5", previous / next, close. */
@Composable
fun FindBar(vm: NotesViewModel, modifier: Modifier = Modifier) {
    val find = vm.find ?: return
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    Surface(tonalElevation = 2.dp, modifier = modifier.fillMaxWidth().testTag("find-bar")) {
        Row(Modifier.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextField(
                value = find.query,
                onValueChange = vm::setFindQuery,
                singleLine = true,
                placeholder = { Text(stringResource(R.string.find_hint)) },
                modifier = Modifier.weight(1f).focusRequester(focus).testTag("find-field"),
            )
            Text(
                text = if (find.matches.isEmpty()) (if (find.query.isEmpty()) "" else stringResource(R.string.find_none)) else stringResource(R.string.find_position, find.current + 1, find.matches.size),
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.testTag("find-count"),
            )
            IconButton(onClick = vm::findPrevious, enabled = find.matches.isNotEmpty()) {
                Icon(Icons.Default.KeyboardArrowUp, contentDescription = stringResource(R.string.find_previous))
            }
            IconButton(onClick = vm::findNext, enabled = find.matches.isNotEmpty()) {
                Icon(Icons.Default.KeyboardArrowDown, contentDescription = stringResource(R.string.find_next))
            }
            IconButton(onClick = vm::closeFind) { Icon(Icons.Default.Close, contentDescription = stringResource(R.string.find_close)) }
        }
    }
}
