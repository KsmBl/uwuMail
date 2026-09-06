package de.uwumail.core

import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import de.uwumail.R

/**
 * Which language the app is read in.
 *
 * The chosen language is not kept in uwuMail's own settings. Android already
 * stores a per-app locale — the one behind Settings › Apps › uwuMail › Language
 * — and keeping a second copy would mean two answers that could disagree. This
 * reads and writes that one, so changing it here and changing it there are the
 * same act.
 *
 * The language names are deliberately not translated: someone looking for
 * German wants to find "Deutsch", whatever language the app is showing them.
 */
enum class AppLanguage(@StringRes val label: Int, val tag: String?) {
    SYSTEM(R.string.language_system, null),
    ENGLISH(R.string.language_english, "en"),
    GERMAN(R.string.language_german, "de");

    companion object {

        /** What the app is set to right now. */
        fun current(): AppLanguage {
            val locales = AppCompatDelegate.getApplicationLocales()
            if (locales.isEmpty) return SYSTEM
            val language = locales[0]?.language ?: return SYSTEM
            return entries.firstOrNull { it.tag == language } ?: SYSTEM
        }

        /**
         * Applies [language]. Android restarts the activity to redraw in the
         * new language, so there is nothing to refresh by hand.
         */
        fun apply(language: AppLanguage) {
            AppCompatDelegate.setApplicationLocales(
                language.tag?.let { LocaleListCompat.forLanguageTags(it) }
                    ?: LocaleListCompat.getEmptyLocaleList()
            )
        }
    }
}
