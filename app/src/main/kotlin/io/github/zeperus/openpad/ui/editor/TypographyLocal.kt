package io.github.zeperus.openpad.ui.editor

import androidx.compose.runtime.staticCompositionLocalOf
import io.github.zeperus.openpad.editor.EditorTypography

/** The editor's text sizes (from the user's Settings -> Editor -> Text size); every part of the editor and the Settings preview reads them from here. */
val LocalEditorTypography = staticCompositionLocalOf { EditorTypography(EditorTypography.DEFAULT_BASE) }
