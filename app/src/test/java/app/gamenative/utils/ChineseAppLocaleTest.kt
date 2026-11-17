package app.gamenative.utils

import android.app.Application
import android.app.LocaleManager
import android.content.res.Configuration
import android.os.LocaleList
import app.gamenative.R
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [29, 34])
class ChineseAppLocaleTest {
    private val originalLocale = Locale.getDefault()

    @After
    fun restoreDefaultLocale() {
        Locale.setDefault(originalLocale)
    }

    @Test
    fun chinaResourcesRemainChineseWithAnEnglishDeviceOrSavedPreference() {
        val application = RuntimeEnvironment.getApplication()
        val configuration = Configuration(application.resources.configuration).apply { setLocale(Locale.US) }
        val english = application.createConfigurationContext(configuration)

        val china = ChineseAppLocale.activityContext(english, savedLanguage = "en", chinaEdition = true)

        assertNotSame(english, china)
        assertEquals("zh-CN", china.resources.configuration.locales[0].toLanguageTag())
        assertEquals("取消", china.getString(R.string.cancel))
        assertEquals("en-US", english.resources.configuration.locales[0].toLanguageTag())
        assertEquals("Cancel", english.getString(R.string.cancel))
    }

    @Test
    fun normalEditionPreservesItsExistingLanguagePreference() {
        val application = RuntimeEnvironment.getApplication()
        assertSame(application, ChineseAppLocale.activityContext(application, "", chinaEdition = false))
        val german = ChineseAppLocale.activityContext(application, "de", chinaEdition = false)
        assertEquals("de", german.resources.configuration.locales[0].language)
        assertEquals("de", ChineseAppLocale.languageCode("de", chinaEdition = false))
        assertEquals("zh-CN", ChineseAppLocale.languageCode("de", chinaEdition = true))
    }

    @Test
    @Config(sdk = [34])
    fun android13ApplicationLocalesAreScopedToTheChinaEdition() {
        val application = RuntimeEnvironment.getApplication()
        val manager = application.getSystemService(LocaleManager::class.java)
        manager.applicationLocales = LocaleList.forLanguageTags("fr")

        ChineseAppLocale.applyApplicationLocale(application, chinaEdition = false)
        assertEquals("fr", manager.applicationLocales.toLanguageTags())

        ChineseAppLocale.applyApplicationLocale(application, chinaEdition = true)
        assertEquals("zh-CN", manager.applicationLocales.toLanguageTags())
        ChineseAppLocale.applyApplicationLocale(application, chinaEdition = true)
        assertEquals("zh-CN", manager.applicationLocales.toLanguageTags())
    }

    @Test
    @Config(sdk = [29])
    fun olderAndroidUsesTheActivityWrapperWithoutCallingANewSystemApi() {
        val application = RuntimeEnvironment.getApplication()
        val original = Configuration(application.resources.configuration)
        ChineseAppLocale.applyApplicationLocale(application, chinaEdition = true)
        assertEquals(original, application.resources.configuration)
    }
}
