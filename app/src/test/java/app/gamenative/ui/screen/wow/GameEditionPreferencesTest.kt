package app.gamenative.ui.screen.wow

import android.app.Application
import app.gamenative.BuildConfig
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class GameEditionPreferencesTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    private val context get() = RuntimeEnvironment.getApplication()
    private val preferences get() = context.getSharedPreferences("wow_forever", Application.MODE_PRIVATE)

    @Before
    @After
    fun clearPreferences() {
        preferences.edit().clear().commit()
    }

    @Test
    fun chinaRegionCannotBeOverriddenBySavedPreferenceOrRegionSelection() {
        val root = temporaryFolder.newFolder()
        File(root, WowClientDownloader.BUILD_INFO).writeText(
            "Branch!STRING:0|Product!STRING:0\neu|${WowClientDownloader.TARGET_PRODUCT}\n",
        )
        preferences.edit().putString("region", GameRegion.EU.name).commit()
        assertEquals(if (BuildConfig.CHINA_EDITION) GameRegion.CN else GameRegion.EU, GameRegion.saved(context))
        assertEquals(if (BuildConfig.CHINA_EDITION) GameRegion.CN else GameRegion.EU, GameRegion.current(context, root.path))

        GameRegion.save(context, GameRegion.US)
        assertEquals(if (BuildConfig.CHINA_EDITION) GameRegion.CN else GameRegion.US, GameRegion.current(context, root.path))
        assertEquals(if (BuildConfig.CHINA_EDITION) "CN" else "US", preferences.getString("region", null))
    }

    @Test
    fun separateDefaultFolderDoesNotReplacePlayerChosenFolder() {
        assertEquals(if (BuildConfig.CHINA_EDITION) "WoW Forever China" else "WoW Forever", File(GamePath.load(context)).name)
        val selected = temporaryFolder.newFolder()
        GamePath.save(context, selected.path)
        assertEquals(selected.path, GamePath.load(context))
    }
}
