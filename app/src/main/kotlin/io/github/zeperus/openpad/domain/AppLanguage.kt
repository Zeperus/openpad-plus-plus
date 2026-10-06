package io.github.zeperus.openpad.domain

/** The language of the app's own screens. [System] follows Android (the phone's language or Android's per-app language setting). */
enum class AppLanguage(val tag: String?) {
    System(null),
    German("de"),
    English("en"),
    ;

    companion object {
        val Default = System

        /** A stored value; anything unknown (damaged file, a future version) is [System]. */
        fun fromStored(value: String?): AppLanguage = entries.firstOrNull { it.name == value } ?: Default

        /** The language for a locale tag such as "de", "de-DE" or "en-GB"; [System] for an empty or unsupported one. */
        fun fromTag(tag: String?): AppLanguage {
            val language = tag?.substringBefore('-')?.substringBefore('_')?.lowercase().orEmpty()
            return entries.firstOrNull { it.tag != null && it.tag == language } ?: System
        }
    }
}

/** Applies a language to the running app (Android: per-app locales). Implementations must be cheap when nothing changes. */
interface LocaleApplier {
    fun apply(language: AppLanguage)

    /** The language Android currently applies to this app, or null if that cannot be asked. */
    fun current(): AppLanguage?
}

/**
 * The language setting. On Android 13+ the *system* owns the app locale (it can also be changed in Android's own per-app
 * language settings), so at start the setting follows it; before Android 13 the stored choice is applied at every start.
 */
class LanguageManager(
    private val settings: SettingsStore,
    private val applier: LocaleApplier,
    private val systemOwnsLocale: Boolean,
) {
    suspend fun restore(): AppLanguage {
        if (systemOwnsLocale) {
            val system = applier.current()
            if (system != null) {
                if (settings.language() != system) settings.setLanguage(system)
                return system
            }
        }
        val stored = settings.language()
        applier.apply(stored)
        return stored
    }

    /** The user's choice: remembered and applied (the screens switch language without a phone-wide change). */
    suspend fun choose(language: AppLanguage) {
        settings.setLanguage(language)
        applier.apply(language)
    }
}
