package app.gamenative.ui.screen.wow

import app.gamenative.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class WowEditionTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    private val international = WowEdition(false, "enUS", "enUS")
    private val china = WowEdition(true, "zhCN", "zhCN")

    @Test
    fun chinaPolicy_keepsRegionAndLanguageSeparateWithoutAutomaticLaunch() {
        assertEquals(GameRegion.CN, china.lockedRegion)
        assertEquals("zhCN", china.defaultTextLocale)
        assertEquals("zhCN", china.defaultAudioLocale)
        assertFalse(china.autoLaunchAllowed)
        assertEquals("中文", china.text("English", "中文"))

        assertNull(international.lockedRegion)
        assertEquals("enUS", international.defaultTextLocale)
        assertTrue(international.autoLaunchAllowed)
        assertEquals("English", international.text("English", "中文"))
    }

    @Test
    fun currentEdition_usesBuildDefaultsAndSeparateApplicationId() {
        assertEquals(BuildConfig.CHINA_EDITION, WowEdition.current.isChina)
        assertEquals(BuildConfig.DEFAULT_GAME_TEXT_LOCALE, WowEdition.current.defaultTextLocale)
        assertEquals(BuildConfig.DEFAULT_GAME_AUDIO_LOCALE, WowEdition.current.defaultAudioLocale)
        assertEquals(
            if (BuildConfig.CHINA_EDITION) "app.wowforever.xmfold.cn" else "app.wowforever.xmfold",
            BuildConfig.APPLICATION_ID,
        )
        assertEquals(if (BuildConfig.CHINA_EDITION) "中文" else "English", wowText("English", "中文"))
    }

    @Test
    fun populatedVolume_installsIntoDifferentEditionSubfolders() {
        val storage = temporaryFolder.newFolder()
        File(storage, "existing-file").writeText("keep")

        assertEquals(File(storage, "WoW Forever China"), china.installDirFor(storage))
        assertEquals(File(storage, "WoW Forever"), international.installDirFor(storage))
        assertEquals("keep", File(storage, "existing-file").readText())
    }

    @Test
    fun emptyExplicitFolder_isUsedAsChosen() {
        val picked = temporaryFolder.newFolder()
        assertEquals(picked, china.installDirFor(picked))
        assertEquals(picked, international.installDirFor(picked))
    }

    @Test
    fun chinaDiscovery_doesNotSelectInternationalFolderOnStorageVolume() {
        val storage = temporaryFolder.newFolder()
        val globalRoot = installedRoot(File(storage, "WoW Forever"))
        installedRoot(File(storage, "World of Warcraft"))
        assertNull(china.findGameRoot(storage))
        assertEquals(globalRoot, international.findGameRoot(storage))

        val chinaRoot = installedRoot(File(storage, "WoW Forever China"))
        assertEquals(chinaRoot, china.findGameRoot(storage))
        assertEquals(globalRoot, international.findGameRoot(storage))
    }

    @Test
    fun chinaDiscovery_findsOwnClientSubfolderWithoutSelectingOtherEditionParent() {
        val storage = temporaryFolder.newFolder()
        val globalRoot = installedRoot(File(storage, "WoW Forever"))
        val chinaRoot = installedRoot(File(storage, "WoW Forever China"))
        val globalClient = File(globalRoot, WowClientDownloader.FLAVOR_DIR).apply { mkdirs() }
        val chinaClient = File(chinaRoot, WowClientDownloader.FLAVOR_DIR).apply { mkdirs() }

        assertNull(china.findGameRoot(globalClient))
        assertEquals(globalRoot, international.findGameRoot(globalClient))
        assertEquals(chinaRoot, china.findGameRoot(chinaClient))
        // An explicitly chosen existing root remains available; only automatic discovery is isolated.
        assertEquals(globalRoot, china.findGameRoot(globalRoot))
    }

    @Test
    fun progressTranslation_preservesBuildFileAndCountAndLeavesInternationalUnchanged() {
        val stages = mapOf(
            "Checking the latest World of Warcraft build..." to "正在查询国服最新版本…",
            "Fetching build 1.60.1.70938 manifest..." to "正在获取版本 1.60.1.70938 的清单…",
            "Reading build 1.60.1.70938 manifest..." to "正在读取版本 1.60.1.70938 的清单…",
            "Looking up client files..." to "正在查找客户端文件…",
            "Downloading Wow-ARM64.exe (1/6)..." to "正在下载 Wow-ARM64.exe (1/6)...",
            "Syncing archive indexes (12/42)..." to "正在同步游戏资源索引 (12/42)...",
            "Updating build information..." to "正在更新版本信息…",
            "Diagnostic stage 17" to "Diagnostic stage 17",
        )
        stages.forEach { (english, chinese) ->
            assertEquals(chinese, china.clientProgress(english))
            assertEquals(english, international.clientProgress(english))
        }
    }

    private fun installedRoot(root: File): File = root.apply {
        mkdirs()
        File(this, WowClientDownloader.BUILD_INFO).writeText("test manifest")
    }
}
