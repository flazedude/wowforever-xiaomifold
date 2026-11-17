package app.gamenative.ui.screen.wow

import app.gamenative.BuildConfig
import java.io.File

/** Build-time edition policy; the China app has its own package, preferences and game folder. */
internal data class WowEdition(
    val isChina: Boolean,
    val defaultTextLocale: String,
    val defaultAudioLocale: String,
) {
    val gameFolderName: String get() = if (isChina) "WoW Forever China" else "WoW Forever"
    val lockedRegion: GameRegion? get() = if (isChina) GameRegion.CN else null
    val autoLaunchAllowed: Boolean get() = !isChina

    fun text(english: String, chinese: String): String = if (isChina) chinese else english

    fun installDirFor(picked: File): File =
        if (picked.listFiles().isNullOrEmpty()) picked else File(picked, gameFolderName)

    fun findGameRoot(picked: File): File? {
        val candidates = if (isChina) {
            // Picking a storage volume or a client subfolder must not discover the other edition.
            listOfNotNull(picked, picked.parentFile?.takeIf { it.name == gameFolderName }, File(picked, gameFolderName))
        } else {
            listOfNotNull(picked, picked.parentFile, File(picked, gameFolderName), File(picked, "World of Warcraft"))
        }
        return candidates.firstOrNull { File(it, WowClientDownloader.BUILD_INFO).isFile }
    }

    fun clientProgress(message: String): String {
        if (!isChina) return message
        return when {
            message == "Checking the latest World of Warcraft build..." -> "正在查询国服最新版本…"
            message.startsWith("Fetching build ") && message.endsWith(" manifest...") ->
                "正在获取版本 ${message.removePrefix("Fetching build ").removeSuffix(" manifest...")} 的清单…"
            message.startsWith("Reading build ") && message.endsWith(" manifest...") ->
                "正在读取版本 ${message.removePrefix("Reading build ").removeSuffix(" manifest...")} 的清单…"
            message == "Looking up client files..." -> "正在查找客户端文件…"
            message.startsWith("Downloading ") -> "正在下载 ${message.removePrefix("Downloading ")}"
            message.startsWith("Syncing archive indexes ") -> "正在同步游戏资源索引 ${message.removePrefix("Syncing archive indexes ")}"
            message == "Updating build information..." -> "正在更新版本信息…"
            else -> message
        }
    }

    companion object {
        val current: WowEdition = WowEdition(
            BuildConfig.CHINA_EDITION,
            BuildConfig.DEFAULT_GAME_TEXT_LOCALE,
            BuildConfig.DEFAULT_GAME_AUDIO_LOCALE,
        )
    }
}

internal fun wowText(english: String, chinese: String): String = WowEdition.current.text(english, chinese)

/** Translate downloader stage descriptions at the UI boundary, retaining filenames and counts. */
internal fun wowClientProgress(message: String): String = WowEdition.current.clientProgress(message)
