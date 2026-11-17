package app.gamenative.ui.screen.wow

import app.gamenative.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class GameConfigTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun configWtf(root: File) = File(root, "${WowClientDownloader.FLAVOR_DIR}/WTF/Config.wtf")

    private fun portals(root: File) = configWtf(root).readLines().filter { it.startsWith("SET portal ") }

    private fun install(branch: String, config: String? = null): File {
        val root = tempFolder.newFolder()
        File(root, WowClientDownloader.BUILD_INFO).writeText(
            "Branch!STRING:0|Product!STRING:0\n$branch|${WowClientDownloader.TARGET_PRODUCT}\n",
        )
        config?.let { configWtf(root).apply { parentFile?.mkdirs() }.writeText(it) }
        return root
    }

    @Test
    fun noRegionPicked_newConfig_usesInstallRegion() {
        val root = install("eu")
        ensureGameConfig(root, "1920x1080", null)
        assertEquals(listOf("SET portal \"EU\""), portals(root))
    }

    @Test
    fun noRegionPicked_keepsExistingPortal() {
        val root = install("us", "SET portal \"EU\"\n")
        ensureGameConfig(root, "1920x1080", null)
        assertEquals(listOf("SET portal \"EU\""), portals(root))
    }

    @Test
    fun pickedRegion_replacesExistingPortal() {
        val root = install("us", "SET portal \"US\"\nSET gxApi \"D3D11\"\n")
        ensureGameConfig(root, "1920x1080", GameRegion.EU)
        assertEquals(listOf("SET portal \"EU\""), portals(root))
    }

    @Test
    fun chinaRegion_replacesEuropeanPortalAndPreservesLanguage() {
        val root = install("eu", "SET portal \"EU\"\nSET textLocale \"enUS\"\n")
        ensureGameConfig(root, "1280x720", GameRegion.CN)
        assertEquals(listOf("SET portal \"CN\""), portals(root))
        assertEquals("cn", GameRegion.CN.cdnRegion)
        assertEquals("SET textLocale \"enUS\"", configWtf(root).readLines().first { it.startsWith("SET textLocale ") })
    }

    @Test
    fun newConfig_usesEditionTextAndAudioDefaults() {
        val root = install("cn")
        ensureGameConfig(root, "1280x720", GameRegion.CN)
        val lines = configWtf(root).readLines()
        assertEquals(
            listOf("SET textLocale \"${BuildConfig.DEFAULT_GAME_TEXT_LOCALE}\""),
            lines.filter { it.startsWith("SET textLocale ") },
        )
        assertEquals(
            listOf("SET audioLocale \"${BuildConfig.DEFAULT_GAME_AUDIO_LOCALE}\""),
            lines.filter { it.startsWith("SET audioLocale ") },
        )
    }

    @Test
    fun existingConfig_preservesPlayerTextAndAudioChoicesAcrossLaunches() {
        val root = install("cn", "SET textLocale \"zhTW\"\nSET audioLocale \"enUS\"\n")
        ensureGameConfig(root, "1280x720", GameRegion.CN)
        ensureGameConfig(root, "1600x900", GameRegion.CN)
        assertEquals(
            listOf("SET textLocale \"zhTW\"", "SET audioLocale \"enUS\""),
            configWtf(root).readLines().filter { it.startsWith("SET textLocale ") || it.startsWith("SET audioLocale ") },
        )
    }

    @Test
    fun betaMigration_preservesPlayerTextAndAudioChoices() {
        val root = install("cn")
        File(root, "${WowClientDownloader.LEGACY_FLAVOR_DIR}/WTF/Config.wtf").apply {
            parentFile?.mkdirs()
            writeText("SET portal \"EU\"\nSET textLocale \"zhTW\"\nSET audioLocale \"enUS\"\n")
        }
        ensureGameConfig(root, "1280x720", GameRegion.CN)
        assertEquals(listOf("SET portal \"CN\""), portals(root))
        assertEquals(
            listOf("SET textLocale \"zhTW\"", "SET audioLocale \"enUS\""),
            configWtf(root).readLines().filter { it.startsWith("SET textLocale ") || it.startsWith("SET audioLocale ") },
        )
    }

    @Test
    fun resolution_alwaysMatchesContainer() {
        val root = install("us", "SET gxFullscreenResolution \"1280x720\"\nSET gxWindowedResolution \"953x720\"\n")
        ensureGameConfig(root, "1920x1080", null)
        assertEquals(
            listOf("SET gxFullscreenResolution \"1920x1080\"", "SET gxWindowedResolution \"1920x1080\""),
            configWtf(root).readLines().filter { it.contains("Resolution ") },
        )
    }
}
