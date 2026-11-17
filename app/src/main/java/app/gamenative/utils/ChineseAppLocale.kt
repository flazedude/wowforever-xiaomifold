package app.gamenative.utils

import android.app.LocaleManager
import android.content.Context
import android.os.Build
import android.os.LocaleList
import app.gamenative.BuildConfig

/** The separately installed China edition always presents Android resources in Simplified Chinese. */
internal object ChineseAppLocale {
    const val LANGUAGE_TAG = "zh-CN"

    fun languageCode(savedLanguage: String, chinaEdition: Boolean = BuildConfig.CHINA_EDITION): String =
        if (chinaEdition) LANGUAGE_TAG else savedLanguage

    fun activityContext(
        context: Context,
        savedLanguage: String,
        chinaEdition: Boolean = BuildConfig.CHINA_EDITION,
    ): Context =
        // The configuration context also covers Android 10–12, before the per-app locale API.
        LocaleHelper.applyLanguage(context, languageCode(savedLanguage, chinaEdition))

    fun applyApplicationLocale(context: Context, chinaEdition: Boolean = BuildConfig.CHINA_EDITION) {
        if (!chinaEdition || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        // https://developer.android.com/guide/topics/resources/app-languages
        val manager = context.getSystemService(LocaleManager::class.java) ?: return
        val locales = LocaleList.forLanguageTags(LANGUAGE_TAG)
        // Setting this triggers configuration changes; avoid doing it again on every process start.
        if (manager.applicationLocales != locales) manager.applicationLocales = locales
    }
}
