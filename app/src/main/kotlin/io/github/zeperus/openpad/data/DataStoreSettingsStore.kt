package io.github.zeperus.openpad.data

import androidx.datastore.core.DataStore
import androidx.datastore.core.IOException
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import io.github.zeperus.openpad.domain.AppLanguage
import io.github.zeperus.openpad.domain.EditorFontSize
import io.github.zeperus.openpad.domain.SettingsStore
import io.github.zeperus.openpad.domain.StartupMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import java.io.File

/** Simple app settings in Jetpack DataStore (preferences). Unusable stored values fall back to the defaults. */
class DataStoreSettingsStore(private val dataStore: DataStore<Preferences>) : SettingsStore {
    override suspend fun startupMode(): StartupMode {
        val stored = dataStore.data
            .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
            .first()[STARTUP_MODE]
        return StartupMode.entries.firstOrNull { it.name == stored } ?: StartupMode.Default
    }

    override suspend fun setStartupMode(mode: StartupMode) {
        dataStore.edit { it[STARTUP_MODE] = mode.name }
    }

    override suspend fun language(): AppLanguage {
        val stored = dataStore.data
            .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
            .first()[LANGUAGE]
        return AppLanguage.fromStored(stored)
    }

    override suspend fun setLanguage(language: AppLanguage) {
        dataStore.edit { it[LANGUAGE] = language.name }
    }

    override suspend fun editorFontSize(): Int = try {
        val stored = dataStore.data
            .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
            .first()[EDITOR_FONT_SIZE]
        EditorFontSize.fromStored(stored)
    } catch (e: ClassCastException) { // the key holds something that is not a number
        EditorFontSize.DEFAULT
    }

    override suspend fun setEditorFontSize(sp: Int) {
        dataStore.edit { it[EDITOR_FONT_SIZE] = EditorFontSize.clamp(sp) }
    }

    companion object {
        private val EDITOR_FONT_SIZE = intPreferencesKey("editor_font_size")
        private val LANGUAGE = stringPreferencesKey("language")
        private val STARTUP_MODE = stringPreferencesKey("startup_mode")

        /** At most one DataStore may be active per [file]; [scope] bounds its lifetime. */
        fun create(file: File, scope: CoroutineScope) = DataStoreSettingsStore(
            PreferenceDataStoreFactory.create(
                corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
                scope = scope,
                produceFile = { file },
            ),
        )
    }
}
