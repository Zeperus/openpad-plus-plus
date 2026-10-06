package io.github.zeperus.openpad.data

import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import io.github.zeperus.openpad.domain.AppLanguage
import io.github.zeperus.openpad.domain.LocaleApplier

/**
 * Android's per-app locales through AppCompat: on Android 13+ this is the system's own per-app language service, before that
 * AppCompat applies it to its activities. The screens are re-created by the framework; the Compose resources then resolve normally.
 * Must be called on the main thread.
 */
object AppCompatLocaleApplier : LocaleApplier {
    override fun apply(language: AppLanguage) {
        val wanted = LocaleListCompat.forLanguageTags(language.tag.orEmpty())
        if (AppCompatDelegate.getApplicationLocales().toLanguageTags() != wanted.toLanguageTags()) {
            AppCompatDelegate.setApplicationLocales(wanted)
        }
    }

    override fun current(): AppLanguage? {
        val locales = AppCompatDelegate.getApplicationLocales()
        return if (locales.isEmpty) AppLanguage.System else AppLanguage.fromTag(locales.get(0)?.language)
    }
}
