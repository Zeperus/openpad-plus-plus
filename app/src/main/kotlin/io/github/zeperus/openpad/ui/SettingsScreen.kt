package io.github.zeperus.openpad.ui

import androidx.activity.compose.BackHandler
import android.content.pm.ApplicationInfo
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.RadioButton
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import io.github.zeperus.openpad.domain.StartupMode
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.zeperus.openpad.R
import io.github.zeperus.openpad.ui.editor.EditorDiagnostics
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsScreen(vm: NotesViewModel, onBack: () -> Unit) {
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
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())) {
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
                                selected = vm.preferences.startupMode == mode,
                                onClick = { vm.preferences.chooseStartupMode(mode) },
                                role = Role.RadioButton,
                            )
                            .padding(horizontal = 24.dp, vertical = 12.dp),
                    ) {
                        RadioButton(selected = vm.preferences.startupMode == mode, onClick = null)
                        Text(stringResource(label), modifier = Modifier.padding(start = 16.dp))
                    }
                }
            }
            SectionHeader(stringResource(R.string.settings_language))
            val languages = listOf(
                io.github.zeperus.openpad.domain.AppLanguage.System to R.string.language_system,
                io.github.zeperus.openpad.domain.AppLanguage.German to R.string.language_german,
                io.github.zeperus.openpad.domain.AppLanguage.English to R.string.language_english,
            )
            Column(Modifier.selectableGroup().testTag("language-options")) {
                for ((language, label) in languages) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(
                                selected = vm.preferences.language == language,
                                onClick = { vm.preferences.chooseLanguage(language) },
                                role = Role.RadioButton,
                            )
                            .padding(horizontal = 24.dp, vertical = 12.dp),
                    ) {
                        RadioButton(selected = vm.preferences.language == language, onClick = null)
                        Text(stringResource(label), modifier = Modifier.padding(start = 16.dp))
                    }
                }
            }
            SectionHeader(stringResource(R.string.settings_editor))
            EditorTextSizeSetting(vm)
            SectionHeader(stringResource(R.string.settings_backup))
            ExportAllSetting(vm)
            val context = LocalContext.current
            val version = remember { runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull().orEmpty() }
            Text(
                text = stringResource(R.string.settings_version, version),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 24.dp).testTag("version"),
            )
            if ((context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0) { // debug builds only: the measured editor timing
                val t = EditorDiagnostics.timing
                Text(
                    text = stringResource(
                        R.string.settings_timing,
                        t.lastEditMs, t.avgEditMs, t.maxEditMs, t.edits, t.lastOpenMs, t.maxOpenMs,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp).testTag("editor-timing"),
                )
            }
        }
    }
}

/** Text size: [-] 16 sp [+] and a live preview in the editor's own sizes. */
@Composable
internal fun EditorTextSizeSetting(vm: NotesViewModel) {
    val size = vm.preferences.editorFontSize
    val typography = remember(size) { io.github.zeperus.openpad.editor.EditorTypography(size.toFloat()) }
    val decreaseLabel = stringResource(R.string.font_size_decrease)
    val increaseLabel = stringResource(R.string.font_size_increase)
    Column(Modifier.padding(horizontal = 24.dp, vertical = 8.dp)) {
        Text(stringResource(R.string.settings_text_size), style = MaterialTheme.typography.bodyLarge)
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(
                onClick = { vm.preferences.decreaseEditorFontSize() },
                enabled = size > io.github.zeperus.openpad.domain.EditorFontSize.MIN,
                modifier = Modifier.testTag("font-size-minus").semantics { contentDescription = decreaseLabel },
            ) { Text("\u2212", style = MaterialTheme.typography.headlineSmall) }
            Text(
                text = stringResource(R.string.font_size_value, size),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 12.dp).testTag("font-size-value"),
            )
            IconButton(
                onClick = { vm.preferences.increaseEditorFontSize() },
                enabled = size < io.github.zeperus.openpad.domain.EditorFontSize.MAX,
                modifier = Modifier.testTag("font-size-plus").semantics { contentDescription = increaseLabel },
            ) { Text("+", style = MaterialTheme.typography.headlineSmall) }
        }
        androidx.compose.runtime.CompositionLocalProvider(io.github.zeperus.openpad.ui.editor.LocalEditorTypography provides typography) {
            Column(Modifier.padding(top = 8.dp).testTag("font-size-preview")) {
                Text(
                    stringResource(R.string.font_preview_text),
                    style = androidx.compose.ui.text.TextStyle(fontSize = typography.body.sp, lineHeight = typography.bodyLineHeight.sp),
                    modifier = Modifier.testTag("font-preview-body"),
                )
                Text(
                    stringResource(R.string.font_preview_heading),
                    style = androidx.compose.ui.text.TextStyle(
                        fontSize = typography.headingSize(2).sp, lineHeight = typography.headingLineHeight(2).sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                    ),
                    modifier = Modifier.testTag("font-preview-heading"),
                )
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.testTag("font-preview-task")) {
                    androidx.compose.material3.Checkbox(
                        checked = false,
                        onCheckedChange = null,
                        modifier = Modifier.graphicsLayer(scaleX = typography.checkboxScale, scaleY = typography.checkboxScale),
                    )
                    Text(
                        stringResource(R.string.font_preview_task),
                        style = androidx.compose.ui.text.TextStyle(fontSize = typography.body.sp, lineHeight = typography.bodyLineHeight.sp),
                        modifier = Modifier.padding(start = (8 * typography.checkboxScale).dp),
                    )
                }
            }
        }
    }
}

/** Settings > Backup: writes a copy of every note as .md into a folder the user picks, then shows what was written. */
@Composable
internal fun ExportAllSetting(vm: NotesViewModel) {
    // null means the folder picker was cancelled: nothing to export
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) vm.exportAll(uri.toString())
    }
    Column(Modifier.padding(horizontal = 24.dp, vertical = 8.dp)) {
        Text(
            text = stringResource(R.string.export_explanation),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedButton(
            onClick = { pickFolder.launch(null) },
            enabled = !vm.exporting,
            modifier = Modifier.padding(top = 8.dp).testTag("export-all"),
        ) {
            Text(stringResource(R.string.export_all))
        }
        if (vm.exporting) {
            Text(
                text = stringResource(R.string.export_running),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 8.dp).semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
    }
    vm.exportResult?.let { result ->
        AlertDialog(
            onDismissRequest = { vm.exportResultShown() },
            title = { Text(stringResource(R.string.export_done_title)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(stringResource(R.string.export_done_written, result.written))
                    if (result.skippedExternal > 0) Text(stringResource(R.string.export_done_skipped, result.skippedExternal))
                    if (result.failed.isNotEmpty()) Text(stringResource(R.string.export_done_failed, failedTitlesForDialog(result.failed)))
                }
            },
            confirmButton = { TextButton(onClick = { vm.exportResultShown() }) { Text(stringResource(R.string.action_close)) } },
            modifier = Modifier.testTag("export-result"),
        )
    }
}

internal const val MAX_LISTED_EXPORT_FAILURES = 10

/** The first failed titles, then "and N more" when the list is longer than the dialog should show. */
@Composable
internal fun failedTitlesForDialog(failed: List<String>): String {
    val shown = failed.take(MAX_LISTED_EXPORT_FAILURES).joinToString(", ")
    val rest = failed.size - MAX_LISTED_EXPORT_FAILURES
    // the resource carries its own separator (", and" / " und"), so German gets no comma before "und"
    return if (rest > 0) shown + stringResource(R.string.export_done_more, rest) else shown
}
