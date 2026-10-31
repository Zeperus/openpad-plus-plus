package io.github.zeperus.openpad.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.zeperus.openpad.domain.AppLanguage
import io.github.zeperus.openpad.domain.EditorFontSize
import io.github.zeperus.openpad.domain.LanguageManager
import io.github.zeperus.openpad.domain.SettingsStore
import io.github.zeperus.openpad.domain.StartupMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

/** The app preferences shown in Settings (startup mode, language, editor text size). Independent of notes; never touches a note. */
class SettingsModel(
    private val store: SettingsStore,
    private val languageManager: LanguageManager?,
    private val scope: CoroutineScope,
) {
    var startupMode by mutableStateOf(StartupMode.Default)
        private set

    /** The language chosen in Settings (the screens follow it through Android's per-app locales). */
    var language by mutableStateOf(AppLanguage.Default)
        private set

    /** The editor's body text size in sp (Settings -> Editor -> Text size). A global preference: no effect on any note's content, undo or saving. */
    var editorFontSize by mutableIntStateOf(EditorFontSize.DEFAULT)
        private set

    /** Reads the stored values; each falls back to its default if it cannot be read. Called once at startup. Returns the startup mode. */
    suspend fun load(): StartupMode {
        val mode = readOr(StartupMode.Default) { store.startupMode() }
        startupMode = mode
        language = readOr(AppLanguage.Default) { languageManager?.restore() ?: store.language() }
        editorFontSize = readOr(EditorFontSize.DEFAULT) { store.editorFontSize() }
        return mode
    }

    /** Remembered for the next launch. The screen follows once the store has it. */
    fun chooseStartupMode(mode: StartupMode) {
        scope.launch {
            if (write { store.setStartupMode(mode) }) startupMode = mode
        }
    }

    /** The user's choice: remembered and applied (the screens switch language without a phone-wide change). */
    fun chooseLanguage(choice: AppLanguage) {
        scope.launch {
            language = choice
            write { languageManager?.choose(choice) ?: store.setLanguage(choice) }
        }
    }

    /** Sets the text size (clamped to the supported range). The screen follows at once; saving the preference happens in the background. */
    fun chooseEditorFontSize(sp: Int) {
        val size = EditorFontSize.clamp(sp)
        if (size == editorFontSize) return
        editorFontSize = size
        scope.launch { write { store.setEditorFontSize(size) } }
    }

    fun increaseEditorFontSize() = chooseEditorFontSize(EditorFontSize.increased(editorFontSize))

    fun decreaseEditorFontSize() = chooseEditorFontSize(EditorFontSize.decreased(editorFontSize))

    /**
     * Stores a preference. A failed write (e.g. storage full) must not crash the app: the choice then holds for this run only.
     * Returns false if it could not be stored.
     */
    private suspend fun write(block: suspend () -> Unit): Boolean = try {
        block()
        true
    } catch (e: CancellationException) {
        throw e
    } catch (_: java.io.IOException) {
        false
    }

    /** A stored value, or [default] if it cannot be read. Cancellation is never swallowed. */
    private suspend fun <T> readOr(default: T, read: suspend () -> T): T = try {
        read()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        default
    }
}
