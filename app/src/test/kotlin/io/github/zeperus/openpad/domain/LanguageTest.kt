package io.github.zeperus.openpad.domain

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class LanguageTest {
    private class Settings(var language: AppLanguage = AppLanguage.Default) : SettingsStore {
        override suspend fun startupMode() = StartupMode.Default
        override suspend fun setStartupMode(mode: StartupMode) = Unit
        override suspend fun language() = language
        override suspend fun setLanguage(language: AppLanguage) { this.language = language }
    }

    private class Applier(var applied: AppLanguage? = null, val system: AppLanguage? = null) : LocaleApplier {
        val calls = ArrayList<AppLanguage>()
        override fun apply(language: AppLanguage) { applied = language; calls += language }
        override fun current(): AppLanguage? = system ?: applied
    }

    @Test fun `the default is the system language`() {
        assertEquals(AppLanguage.System, AppLanguage.Default)
        assertEquals(null, AppLanguage.System.tag)
        assertEquals("de", AppLanguage.German.tag)
        assertEquals("en", AppLanguage.English.tag)
    }

    @Test fun `stored values are read back and unknown ones fall back safely`() {
        for (l in AppLanguage.entries) assertEquals(l, AppLanguage.fromStored(l.name))
        assertEquals(AppLanguage.System, AppLanguage.fromStored(null))
        assertEquals(AppLanguage.System, AppLanguage.fromStored(""))
        assertEquals(AppLanguage.System, AppLanguage.fromStored("Klingon"))
        assertEquals(AppLanguage.System, AppLanguage.fromStored("german")) // case matters: only what we wrote
    }

    @Test fun `locale tags map to the supported languages`() {
        assertEquals(AppLanguage.German, AppLanguage.fromTag("de"))
        assertEquals(AppLanguage.German, AppLanguage.fromTag("de-AT"))
        assertEquals(AppLanguage.German, AppLanguage.fromTag("de_CH"))
        assertEquals(AppLanguage.English, AppLanguage.fromTag("en-GB"))
        assertEquals(AppLanguage.System, AppLanguage.fromTag("fr"))
        assertEquals(AppLanguage.System, AppLanguage.fromTag(""))
        assertEquals(AppLanguage.System, AppLanguage.fromTag(null))
    }

    @Test fun `choosing a language remembers and applies it`() = runBlocking {
        val settings = Settings()
        val applier = Applier()
        val manager = LanguageManager(settings, applier, systemOwnsLocale = false)
        manager.choose(AppLanguage.German)
        assertEquals(AppLanguage.German, settings.language)
        assertEquals(AppLanguage.German, applier.applied)
        manager.choose(AppLanguage.English)
        manager.choose(AppLanguage.System)
        assertEquals(listOf(AppLanguage.German, AppLanguage.English, AppLanguage.System), applier.calls)
        assertEquals(AppLanguage.System, settings.language)
    }

    @Test fun `before Android 13 the stored choice is applied at every start`() = runBlocking {
        val settings = Settings(AppLanguage.German)
        val applier = Applier()
        val restored = LanguageManager(settings, applier, systemOwnsLocale = false).restore()
        assertEquals(AppLanguage.German, restored)
        assertEquals(AppLanguage.German, applier.applied)
    }

    @Test fun `on Android 13 and later the system language is authoritative and the setting follows it`() = runBlocking {
        val settings = Settings(AppLanguage.English)
        val applier = Applier(system = AppLanguage.German) // changed in Android's own per-app language settings
        val restored = LanguageManager(settings, applier, systemOwnsLocale = true).restore()
        assertEquals(AppLanguage.German, restored)
        assertEquals(AppLanguage.German, settings.language)
        assertEquals("nothing is re-applied: the system already has it", emptyList<AppLanguage>(), applier.calls)
    }

    @Test fun `if the system cannot be asked the stored choice is used`() = runBlocking {
        val settings = Settings(AppLanguage.English)
        val applier = object : LocaleApplier {
            var applied: AppLanguage? = null
            override fun apply(language: AppLanguage) { applied = language }
            override fun current(): AppLanguage? = null
        }
        assertEquals(AppLanguage.English, LanguageManager(settings, applier, systemOwnsLocale = true).restore())
        assertEquals(AppLanguage.English, applier.applied)
    }

    @Test fun `a fresh installation follows the system`() = runBlocking {
        val settings = Settings()
        val applier = Applier()
        assertEquals(AppLanguage.System, LanguageManager(settings, applier, systemOwnsLocale = false).restore())
        assertEquals(AppLanguage.System, applier.applied)
    }
}
