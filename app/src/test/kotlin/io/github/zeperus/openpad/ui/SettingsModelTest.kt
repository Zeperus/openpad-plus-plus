package io.github.zeperus.openpad.ui

import io.github.zeperus.openpad.domain.AppLanguage
import io.github.zeperus.openpad.domain.EditorFontSize
import io.github.zeperus.openpad.domain.LanguageManager
import io.github.zeperus.openpad.domain.LocaleApplier
import io.github.zeperus.openpad.domain.SettingsStore
import io.github.zeperus.openpad.domain.StartupMode
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsModelTest {
    /** Keeps what is written, like the DataStore file does. */
    private class FakeSettings(
        var mode: StartupMode = StartupMode.ResumeAndBlank,
        var language: AppLanguage = AppLanguage.System,
        var size: Int? = null,
    ) : SettingsStore {
        override suspend fun startupMode() = mode
        override suspend fun setStartupMode(mode: StartupMode) { this.mode = mode }
        override suspend fun language() = language
        override suspend fun setLanguage(language: AppLanguage) { this.language = language }
        override suspend fun editorFontSize() = EditorFontSize.fromStored(size)
        override suspend fun setEditorFontSize(sp: Int) { size = sp }
    }

    /** A store whose every read fails, as an unreadable preferences file would. */
    private object UnreadableSettings : SettingsStore {
        override suspend fun startupMode(): StartupMode = throw IOException("unreadable")
        override suspend fun setStartupMode(mode: StartupMode) = Unit
        override suspend fun language(): AppLanguage = throw IOException("unreadable")
        override suspend fun editorFontSize(): Int = throw IOException("unreadable")
    }

    private class FakeApplier : LocaleApplier {
        val applied = ArrayList<AppLanguage>()
        override fun apply(language: AppLanguage) { applied += language }
        override fun current(): AppLanguage? = null
    }

    @Test fun `load returns the stored mode and reads the language and font size`() = runTest {
        val settings = FakeSettings(mode = StartupMode.BlankNote, language = AppLanguage.German, size = 22)
        val model = SettingsModel(settings, null, this)
        assertEquals(StartupMode.BlankNote, model.load())
        assertEquals(StartupMode.BlankNote, model.startupMode)
        assertEquals(AppLanguage.German, model.language)
        assertEquals(22, model.editorFontSize)
    }

    @Test fun `nothing stored means the defaults`() = runTest {
        val model = SettingsModel(FakeSettings(), null, this)
        assertEquals(StartupMode.ResumeAndBlank, model.load())
        assertEquals(AppLanguage.System, model.language)
        assertEquals(EditorFontSize.DEFAULT, model.editorFontSize)
    }

    @Test fun `a store that cannot be read falls back to the defaults`() = runTest {
        val model = SettingsModel(UnreadableSettings, null, this)
        assertEquals(StartupMode.Default, model.load())
        assertEquals(AppLanguage.Default, model.language)
        assertEquals(EditorFontSize.DEFAULT, model.editorFontSize)
    }

    @Test fun `a cancelled read is not swallowed`() = runTest {
        val cancelling = object : SettingsStore {
            override suspend fun startupMode(): StartupMode = throw CancellationException("cancelled")
            override suspend fun setStartupMode(mode: StartupMode) = Unit
        }
        try {
            SettingsModel(cancelling, null, this).load()
            fail("the cancellation was swallowed")
        } catch (_: CancellationException) {
            // expected
        }
    }

    @Test fun `the font size is clamped to 12 to 28 and stepped by one`() = runTest {
        val settings = FakeSettings()
        val model = SettingsModel(settings, null, this)
        model.chooseEditorFontSize(1000); assertEquals(28, model.editorFontSize)
        model.increaseEditorFontSize(); assertEquals(28, model.editorFontSize)
        model.chooseEditorFontSize(-1); assertEquals(12, model.editorFontSize)
        model.decreaseEditorFontSize(); assertEquals(12, model.editorFontSize)
        model.increaseEditorFontSize(); assertEquals(13, model.editorFontSize)
        advanceUntilIdle()
        assertEquals(13, settings.size)
    }

    @Test fun `choosing the startup mode, the font size and the language is stored`() = runTest {
        val settings = FakeSettings()
        val model = SettingsModel(settings, null, this)
        model.chooseStartupMode(StartupMode.BlankNote)
        model.chooseEditorFontSize(20)
        model.chooseLanguage(AppLanguage.English)
        advanceUntilIdle()
        assertEquals(StartupMode.BlankNote, settings.mode)
        assertEquals(StartupMode.BlankNote, model.startupMode)
        assertEquals(20, settings.size)
        assertEquals(AppLanguage.English, settings.language)
        assertEquals(AppLanguage.English, model.language)
    }

    @Test fun `a stored choice survives a new model, like a restart`() = runTest {
        val settings = FakeSettings()
        SettingsModel(settings, null, this).apply {
            chooseStartupMode(StartupMode.ResumeSession)
            chooseEditorFontSize(24)
        }
        advanceUntilIdle()
        val restarted = SettingsModel(settings, null, this)
        assertEquals(StartupMode.ResumeSession, restarted.load())
        assertEquals(24, restarted.editorFontSize)
    }

    @Test fun `the language is applied through the language manager`() = runTest {
        val settings = FakeSettings()
        val applier = FakeApplier()
        val model = SettingsModel(settings, LanguageManager(settings, applier, systemOwnsLocale = false), this)
        model.chooseLanguage(AppLanguage.German)
        advanceUntilIdle()
        assertEquals(listOf(AppLanguage.German), applier.applied)
        assertEquals(AppLanguage.German, settings.language)
        assertEquals(AppLanguage.German, model.language)
    }
}
