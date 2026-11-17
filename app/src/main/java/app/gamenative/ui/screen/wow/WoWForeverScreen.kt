package app.gamenative.ui.screen.wow

import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.SystemClock
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import app.gamenative.BuildConfig
import app.gamenative.PluviaApp
import app.gamenative.events.AndroidEvent
import app.gamenative.ui.screen.wow.WowClientDownloader.BUILD_INFO
import app.gamenative.ui.screen.wow.WowClientDownloader.EXE_NAME
import app.gamenative.ui.screen.wow.WowClientDownloader.FLAVOR_DIR
import app.gamenative.ui.screen.wow.WowClientDownloader.LEGACY_FLAVOR_DIR
import app.gamenative.ui.screen.wow.WowClientDownloader.LEGACY_PRODUCT
import app.gamenative.ui.screen.wow.WowClientDownloader.TARGET_PRODUCT
import app.gamenative.ui.theme.WowBackgroundGradient
import app.gamenative.ui.theme.WowBronze
import app.gamenative.ui.theme.WowError
import app.gamenative.ui.theme.WowGold
import app.gamenative.ui.theme.WowMuted
import app.gamenative.ui.theme.WowSubtle
import app.gamenative.ui.util.SnackbarManager
import app.gamenative.utils.AppUpdater
import app.gamenative.utils.StorageUtils
import app.gamenative.utils.renderReleaseNotes
import app.gamenative.ui.screen.wow.auth.BattleNetBrowserLogin
import com.winlator.container.Container
import com.winlator.container.ContainerManager
import com.winlator.contents.ContentProfile
import com.winlator.contents.ContentsManager
import com.winlator.core.FileUtils
import com.winlator.core.GPUInformation
import com.winlator.core.TarCompressorUtils
import com.winlator.xenvironment.ImageFs
import com.winlator.xenvironment.ImageFsInstaller
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import timber.log.Timber
import java.io.File
import java.io.IOException
import java.util.Locale

object WoWLauncherState {
    var shouldAutoLaunch = WowEdition.current.autoLaunchAllowed
}

private const val CONTAINER_ID = "wow_forever"
private val READY_STATUS = wowText("Ready to launch", "准备就绪")

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun WoWForeverScreen(
    onLaunch: (appId: String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val gpu = remember {
        WowGpuProfile.detect(
            GPUInformation.getRenderer(context),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Build.SOC_MODEL else "",
            Build.HARDWARE,
        )
    }

    var gamePath by remember { mutableStateOf(GamePath.load(context)) }
    var files by remember { mutableStateOf(GamePath.Status()) }
    var isLaunching by remember { mutableStateOf(false) }
    var launchJob by remember { mutableStateOf<Job?>(null) }
    var region by remember(gamePath, files) { mutableStateOf(GameRegion.current(context, gamePath)) }
    val browserLogin = BattleNetBrowserLogin.enabled(context, region)
    var hasSavedLogin by remember(region, browserLogin) { mutableStateOf(!browserLogin && BattleNetSignIn.load(context, region) != null) }
    var resolution by remember { mutableStateOf(RenderResolution.current(context, gpu.screenSize)) }
    var showLoginDialog by remember { mutableStateOf(false) }
    var statusText by remember { mutableStateOf(READY_STATUS) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var showStoragePermissionDialog by remember { mutableStateOf(false) }
    var versionStatus by remember { mutableStateOf<WowClientDownloader.VersionCheckResult?>(null) }
    var isCheckingVersion by remember { mutableStateOf(false) }
    var isUpdating by remember { mutableStateOf(false) }
    var updateStatusText by remember { mutableStateOf("") }
    var appUpdate by remember { mutableStateOf<AppUpdater.Release?>(null) }
    var isDownloadingAppUpdate by remember { mutableStateOf(false) }
    var appUpdateProgress by remember { mutableFloatStateOf(0f) }
    var showAppUpdateDialog by remember { mutableStateOf(false) }
    var showAppUpdatePermissionDialog by remember { mutableStateOf(false) }
    var pendingInstallPermission by remember { mutableStateOf(false) }
    var downloadedApk by remember { mutableStateOf<File?>(null) }
    var showUpgradeDialog by remember { mutableStateOf(false) }

    suspend fun fetchAppUpdate(): AppUpdater.Release? {
        if (!AppUpdater.isEnabled) return null
        val release = AppUpdater.check()
        appUpdate = release
        if (release != null) {
            showAppUpdateDialog = true
        }
        return release
    }

    fun checkAppUpdate() {
        if (AppUpdater.isEnabled) scope.launch { fetchAppUpdate() }
    }

    fun installOrRequestPermission(apk: File) {
        if (AppUpdater.canInstall(context)) {
            AppUpdater.install(context, apk)
        } else {
            pendingInstallPermission = true
            showAppUpdatePermissionDialog = true
        }
    }

    fun performAppUpdate() {
        val target = appUpdate ?: return
        if (isDownloadingAppUpdate) return
        downloadedApk?.takeIf { it.exists() && it.length() == target.sizeBytes }?.let { apk ->
            installOrRequestPermission(apk)
            return
        }
        isDownloadingAppUpdate = true
        appUpdateProgress = 0f
        errorMessage = null
        scope.launch {
            try {
                val apk = AppUpdater.download(context, target) { progress ->
                    appUpdateProgress = progress
                }
                downloadedApk = apk
                isDownloadingAppUpdate = false
                installOrRequestPermission(apk)
            } catch (e: Exception) {
                Timber.e(e, "Error downloading app update")
                errorMessage = wowText("App update download failed: ${e.message}", "应用更新下载失败：${e.message}")
                isDownloadingAppUpdate = false
            }
        }
    }

    suspend fun fetchVersionStatus() {
        isCheckingVersion = true
        val checked = region
        val status = withContext(Dispatchers.IO) { WowClientDownloader.checkVersion(File(gamePath), checked.cdnRegion) }
        // A check for a region the player has since switched away from is stale.
        if (checked == region) versionStatus = status
        isCheckingVersion = false
    }

    fun checkVersionStatus() {
        if (!File(gamePath, BUILD_INFO).exists()) return
        scope.launch { fetchVersionStatus() }
    }

    fun checkFiles(): Boolean {
        files = GamePath.status(context, gamePath)
        if (files.isWrongGame) {
            errorMessage = wowText("Incompatible game (${files.products.joinToString()}). WoW Forever requires World of Warcraft Forever ($TARGET_PRODUCT).", "游戏版本不兼容（${files.products.joinToString()}）。本启动器需要魔兽世界 Forever（$TARGET_PRODUCT）。")
        }
        return files.isReady
    }

    fun denyStorageAccess() {
        files = files.copy(hasStorageAccess = false)
        showStoragePermissionDialog = true
    }

    fun resetLaunch() {
        launchJob?.cancel()
        launchJob = null
        isLaunching = false
        statusText = READY_STATUS
    }

    fun launchGame() {
        if (isLaunching || files.isWrongGame) return
        if (!files.hasStorageAccess) {
            showStoragePermissionDialog = true
            return
        }
        isLaunching = true
        errorMessage = null
        statusText = wowText("Preparing components...", "正在准备运行组件…")

        launchJob = scope.launch {
            try {
                val screenSize = fitScreenSizeToWindow(context, resolution.size)
                val containerId = withContext(Dispatchers.IO) {
                    prepareLaunch(context, File(gamePath), gpu, screenSize) { msg ->
                        scope.launch(Dispatchers.Main) { statusText = wowClientProgress(msg) }
                    }
                }
                statusText = wowText("Booting into World of Warcraft...", "正在启动魔兽世界…")
                onLaunch(containerId)
            } catch (e: CancellationException) {
                throw e
            } catch (_: StorageAccessDeniedException) {
                isLaunching = false
                statusText = READY_STATUS
                denyStorageAccess()
            } catch (e: Exception) {
                Timber.e(e, "Error launching WoW Forever")
                errorMessage = e.message ?: wowText("Launch failed", "启动失败")
                statusText = wowText("Launch failed", "启动失败")
                isLaunching = false
            }
        }
    }

    LifecycleResumeEffect(gamePath) {
        // Storage access may have just been granted, which can reveal an install that was invisible before.
        if (!isUpdating && !isLaunching) gamePath = GamePath.load(context)
        checkFiles()
        isLaunching = false
        statusText = READY_STATUS
        BattleNetSignIn.removeLoginFile(File(gamePath))
        if (versionStatus == null && !WoWLauncherState.shouldAutoLaunch) {
            checkVersionStatus()
        }
        if (appUpdate == null && !WoWLauncherState.shouldAutoLaunch) {
            checkAppUpdate()
        }
        if (pendingInstallPermission && AppUpdater.canInstall(context)) {
            pendingInstallPermission = false
            downloadedApk?.takeIf { it.exists() }?.let { AppUpdater.install(context, it) }
        }
        onPauseOrDispose {
            launchJob?.cancel()
            launchJob = null
        }
    }

    DisposableEffect(Unit) {
        val reset: (Any) -> Unit = {
            resetLaunch()
            BattleNetSignIn.removeLoginFile(File(gamePath))
        }
        PluviaApp.events.on<AndroidEvent.ForceCloseApp, Unit>(reset)
        PluviaApp.events.on<AndroidEvent.GuestProgramTerminated, Unit>(reset)
        onDispose {
            PluviaApp.events.off<AndroidEvent.ForceCloseApp, Unit>(reset)
            PluviaApp.events.off<AndroidEvent.GuestProgramTerminated, Unit>(reset)
        }
    }

    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        val path = StorageUtils.getPathFromTreeUri(context, uri) ?: return@rememberLauncherForActivityResult
        val root = GamePath.findGameRoot(File(path)) ?: GamePath.installDirFor(File(path)).also { it.mkdirs() }
        errorMessage = null
        gamePath = root.absolutePath
        GamePath.save(context, gamePath)
        versionStatus = null
        val ok = checkFiles()
        if (!files.hasStorageAccess) {
            showStoragePermissionDialog = true
        } else if (ok) {
            checkVersionStatus()
        }
    }

    val addonPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        errorMessage = null
        scope.launch {
            try {
                val installed = withContext(Dispatchers.IO) {
                    val zip = File(context.cacheDir, "addon-import.zip")
                    try {
                        context.contentResolver.openInputStream(uri)?.use { input -> zip.outputStream().use { input.copyTo(it) } }
                            ?: throw IOException(wowText("Couldn't open the selected file", "无法打开所选文件"))
                        AddonImporter.install(zip, File(gamePath, "$FLAVOR_DIR/Interface/AddOns"))
                    } finally {
                        zip.delete()
                    }
                }
                SnackbarManager.show(wowText("Installed ${installed.joinToString()}", "已安装 ${installed.joinToString()}"))
            } catch (e: Exception) {
                Timber.e(e, "Add-on import failed")
                if (e.isPermissionDenied()) denyStorageAccess()
                errorMessage = wowText("Add-on import failed: ${e.message}", "插件导入失败：${e.message}")
            }
        }
    }

    val canPlay = !isLaunching && !isUpdating && !files.needsInstall && !files.isWrongGame

    val view = LocalView.current
    DisposableEffect(isUpdating) {
        view.keepScreenOn = isUpdating
        onDispose { view.keepScreenOn = false }
    }

    // Progress shows under the cards, which is below the fold on a landscape handheld.
    val scrollState = rememberScrollState()
    LaunchedEffect(isUpdating, isLaunching) {
        if (isUpdating || isLaunching) scrollState.animateScrollTo(scrollState.maxValue)
    }

    fun downloadGameFiles(failure: String, work: (File, (String) -> Unit) -> Unit, onSuccess: () -> Unit) {
        if (isUpdating || isLaunching) return
        if (!files.hasStorageAccess) {
            showStoragePermissionDialog = true
            return
        }
        isUpdating = true
        updateStatusText = wowText("Connecting to Blizzard CDN...", "正在连接暴雪内容分发网络…")
        errorMessage = null
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    work(File(gamePath)) { msg -> scope.launch(Dispatchers.Main) { updateStatusText = wowClientProgress(msg) } }
                }
                isUpdating = false
                onSuccess()
            } catch (e: Exception) {
                Timber.e(e, failure)
                if (e.isPermissionDenied()) denyStorageAccess()
                errorMessage = "$failure: ${e.message}"
                isUpdating = false
            }
        }
    }

    fun performInstall() {
        showUpgradeDialog = false
        downloadGameFiles(wowText("Install failed", "安装失败"), { root, onStatus -> WowClientDownloader.install(root, region.cdnRegion, onStatus) }) {
            versionStatus = null
            if (checkFiles()) {
                checkVersionStatus()
                if (WowEdition.current.autoLaunchAllowed && hasSavedLogin) launchGame()
            }
        }
    }

    fun performUpdate() {
        val target = versionStatus ?: return
        downloadGameFiles(wowText("Update failed", "更新失败"), { root, onStatus -> WowClientDownloader.updateGame(root, target, onStatus) }) {
            checkFiles()
            versionStatus = target.copy(isOutdated = false, localVersion = target.remoteVersion)
            if (WowEdition.current.autoLaunchAllowed) launchGame()
        }
    }

    LaunchedEffect(gamePath) {
        val ready = checkFiles()
        // Every setup path reads or writes shared storage, so ask up front rather than after the user picks one.
        if (!files.hasStorageAccess) {
            showStoragePermissionDialog = true
        }
        Timber.i("WoWForeverScreen LaunchedEffect: gamePath=$gamePath, ready=$ready, hasSavedLogin=$hasSavedLogin, shouldAutoLaunch=${WoWLauncherState.shouldAutoLaunch}")
        when {
            !ready || browserLogin || !hasSavedLogin -> {
                WoWLauncherState.shouldAutoLaunch = false
                PluviaApp.events.emit(AndroidEvent.ClearBootingSplash)
                if (files.isLegacyBeta) showUpgradeDialog = true
                if (ready) {
                    checkVersionStatus()
                    checkAppUpdate()
                }
            }
            !WoWLauncherState.shouldAutoLaunch -> {
                checkVersionStatus()
                checkAppUpdate()
            }
            else -> {
                val updateJob = async { fetchAppUpdate() }
                fetchVersionStatus()
                val update = updateJob.await()
                Timber.i("WoWForeverScreen LaunchedEffect: isOutdated=${versionStatus?.isOutdated}, appUpdate=$update")
                WoWLauncherState.shouldAutoLaunch = false
                if (versionStatus?.isOutdated == true || update != null) {
                    PluviaApp.events.emit(AndroidEvent.ClearBootingSplash)
                } else {
                    launchGame()
                }
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(WowBackgroundGradient))
            .padding(24.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Spacer(modifier = Modifier.height(20.dp))
                Text(
                    text = wowText("WORLD OF WARCRAFT", "魔兽世界"),
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Black,
                    fontFamily = FontFamily.Serif,
                    letterSpacing = 3.sp,
                    color = Color(0xFFF0E6D2)
                )
                Text(
                    text = wowText("FOREVER (ARM64 NATIVE)", "FOREVER（ARM64 原生）"),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 2.sp,
                    color = WowGold
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(text = wowText("wowforever-xmfold", "wowforever-xmfold 国服版"), fontSize = 12.sp, color = WowGold, fontWeight = FontWeight.Bold)
                Text(text = wowText("${gpu.edition} Edition", "${gpu.edition} 适配版"), fontSize = 11.sp, color = WowSubtle)
            }

            Spacer(modifier = Modifier.height(20.dp))

            Card(
                modifier = Modifier
                    .fillMaxWidth(0.9f)
                    .border(1.dp, Color(0xFF2A3C54), RoundedCornerShape(16.dp)),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF131D2E).copy(alpha = 0.85f)),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    SectionTitle(wowText("ENVIRONMENT READINESS", "运行环境检查"), WowGold)
                    Spacer(modifier = Modifier.height(14.dp))

                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        CheckItem(label = if (files.exeExists) wowText("ARM64 Binary ($EXE_NAME)", "ARM64 客户端（$EXE_NAME）") else wowText("ARM64 Binary (downloads on Play)", "ARM64 客户端（启动时下载）"), ready = files.exeExists)
                        CheckItem(label = wowText("Game Asset Archives (Data/)", "游戏资源文件（Data/）"), ready = files.dataExists)
                        CheckItem(
                            label = when {
                                files.isWrongGame -> wowText("Install Info ($BUILD_INFO - requires $TARGET_PRODUCT)", "安装信息（$BUILD_INFO；需要 $TARGET_PRODUCT）")
                                files.isLegacyBeta -> wowText("Install Info ($BUILD_INFO - beta, upgrade required)", "安装信息（$BUILD_INFO；测试版，需要升级）")
                                else -> wowText("Install Info ($BUILD_INFO)", "安装信息（$BUILD_INFO）")
                            },
                            ready = files.hasForever,
                        )
                        CheckItem(label = wowText("All Files Access Permission", "所有文件访问权限"), ready = files.hasStorageAccess)
                        CheckItem(
                            label = when {
                                browserLogin -> if (region == GameRegion.CN) wowText("China mobile sign-in (verification required on Play)", "国服手机验证登录（启动时验证）") else wowText("Battle.net browser sign-in (required on Play)", "战网网页登录（启动时验证）")
                                hasSavedLogin -> wowText("${BattleNetSignIn.accountLabel(region)} Login (Configured)", "${BattleNetSignIn.accountLabel(region)} 登录（已配置）")
                                else -> wowText("${BattleNetSignIn.accountLabel(region)} Login (Configure below play button)", "${BattleNetSignIn.accountLabel(region)} 登录（请在启动按钮下方配置）")
                            },
                            ready = !browserLogin && hasSavedLogin,
                        )
                        appUpdate?.let {
                            CheckItem(
                                label = wowText("App Build: v${BuildConfig.VERSION_NAME} (v${it.version} available)", "启动器版本：v${BuildConfig.VERSION_NAME}（可更新至 v${it.version}）"),
                                ready = false,
                            )
                        } ?: CheckItem(
                            label = wowText("App Build: v${BuildConfig.VERSION_NAME}", "启动器版本：v${BuildConfig.VERSION_NAME}"),
                            ready = true,
                        )
                        versionStatus?.let { version ->
                            CheckItem(
                                label = if (version.isOutdated) {
                                    wowText("Client Build: ${version.localVersion} (${region.label} update required)", "客户端版本：${version.localVersion}（${region.label}需要更新）")
                                } else {
                                    wowText("Client Build: ${version.localVersion} (Up to date)", "客户端版本：${version.localVersion}（已是最新）")
                                },
                                ready = !version.isOutdated,
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))
                    HorizontalDivider(color = Color(0xFF1E2D44))
                    Spacer(modifier = Modifier.height(12.dp))

                    Text(text = wowText("Target Path: $gamePath", "游戏目录：$gamePath"), fontSize = 11.sp, color = WowSubtle, maxLines = 2)

                    Row {
                        if (WowEdition.current.lockedRegion != null) {
                            Text("地区：国服（中国）", color = WowGold, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp))
                        } else OptionMenu("Region: ${region.label}", Icons.Default.Public, GameRegion.entries, region, { it.label }, !isLaunching && !isUpdating) {
                            BattleNetSignIn.removeLoginFile(File(gamePath))
                            GameRegion.save(context, it)
                            region = it
                            // Regions can be on different builds, so the install may now need an update.
                            versionStatus = null
                            checkVersionStatus()
                        }
                        OptionMenu(wowText("Resolution: ${resolution.label}", "分辨率：${resolution.label}"), Icons.Default.AspectRatio, RenderResolution.entries, resolution, { it.label }, !isLaunching) {
                            RenderResolution.save(context, it)
                            resolution = it
                        }
                    }

                    longUptimeDays()?.let { days ->
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = wowText(
                                "Your device has been on for $days days. After about 49 days WoW's clock stops, " +
                                    "so the login screen stays blank and nothing loads. Restart your device before playing.",
                                "设备已连续运行 $days 天。运行约 49 天后，游戏时钟可能停止，导致登录界面空白且无法加载。请重启设备后再开始游戏。",
                            ),
                            fontSize = 12.sp,
                            color = WowError,
                            fontWeight = FontWeight.Medium,
                        )
                    }

                    errorMessage?.let {
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(text = wowText("Error: $it", "错误：$it"), fontSize = 12.sp, color = WowError, fontWeight = FontWeight.Medium)
                    }
                }
            }

            if (files.needsInstall && files.hasStorageAccess) {
                Spacer(modifier = Modifier.height(16.dp))
                InstallCard(
                    isUpgrade = files.isLegacyBeta,
                    gamePath = gamePath,
                    freeSpaceBytes = remember(gamePath, isUpdating) { GamePath.freeSpaceBytes(gamePath) },
                    enabled = !isUpdating && !isLaunching,
                    onChangeLocation = { folderPicker.launch(null) },
                )
            }

            versionStatus?.takeIf { it.isOutdated && !files.needsInstall }?.let { version ->
                Spacer(modifier = Modifier.height(16.dp))
                Card(
                    modifier = Modifier
                        .fillMaxWidth(0.9f)
                        .border(1.dp, Color(0xFFE53E3E).copy(alpha = 0.6f), RoundedCornerShape(16.dp)),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF2D1515).copy(alpha = 0.85f)),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Warning, contentDescription = null, tint = WowError, modifier = Modifier.size(20.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            SectionTitle(wowText("GAME UPDATE REQUIRED", "需要更新游戏"), WowError)
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = if (version.localVersion == version.remoteVersion) {
                                wowText("The client needs to be prepared for ${region.label}. Your existing game data will be reused.", "需要准备${region.label}客户端。现有游戏资源将会保留并继续使用。")
                            } else {
                                wowText("${region.label} servers require build ${version.remoteVersion}, but local files are build ${version.localVersion}. Logging in may fail or disconnect at realm selection.", "${region.label}需要版本 ${version.remoteVersion}，当前客户端为 ${version.localVersion}。版本不匹配可能导致登录失败或在选择服务器时断开。")
                            },
                            fontSize = 12.sp,
                            color = Color(0xFFFED7D7),
                            lineHeight = 16.sp
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = wowText("Tap Update below to download the latest files directly from Blizzard's CDN over Wi-Fi.", "请连接 Wi-Fi，然后点击下方更新按钮，从暴雪内容分发网络下载最新文件。"),
                            fontSize = 11.sp,
                            color = Color(0xFFCBD5E1)
                        )
                    }
                }
            }

            appUpdate?.takeIf { AppUpdater.isEnabled }?.let { update ->
                Spacer(modifier = Modifier.height(16.dp))
                Card(
                    modifier = Modifier
                        .fillMaxWidth(0.9f)
                        .border(1.dp, WowGold.copy(alpha = 0.6f), RoundedCornerShape(16.dp)),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF1E2838).copy(alpha = 0.85f)),
                    shape = RoundedCornerShape(16.dp),
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Refresh, contentDescription = null, tint = WowGold, modifier = Modifier.size(20.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            SectionTitle(wowText("APP UPDATE AVAILABLE (v${update.version})", "启动器有新版本（v${update.version}）"), WowGold)
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = wowText("A new release is available on GitHub. Tap below to view notes and update.", "GitHub 上已有新版本。点击下方按钮查看更新说明并更新。"),
                            fontSize = 12.sp,
                            color = Color(0xFFCBD5E1),
                            lineHeight = 16.sp,
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        PrimaryButton(
                            text = when {
                                isDownloadingAppUpdate -> wowText("DOWNLOADING UPDATE...", "正在下载更新…")
                                downloadedApk?.exists() == true -> wowText("INSTALL UPDATE (v${update.version})", "安装更新（v${update.version}）")
                                else -> wowText("UPDATE APP (v${update.version})", "更新启动器（v${update.version}）")
                            },
                            icon = Icons.Default.Refresh,
                            enabled = !isDownloadingAppUpdate && !isLaunching && !isUpdating,
                            onClick = {
                                if (downloadedApk?.exists() == true) {
                                    performAppUpdate()
                                } else {
                                    showAppUpdateDialog = true
                                }
                            },
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                if (isLaunching || isUpdating || (isCheckingVersion && WoWLauncherState.shouldAutoLaunch)) {
                    CircularProgressIndicator(color = WowGold, modifier = Modifier.size(36.dp))
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = when {
                            isUpdating -> updateStatusText
                            isLaunching -> statusText
                            else -> wowText("Checking for game updates...", "正在检查游戏更新…")
                        },
                        fontSize = 13.sp,
                        color = Color(0xFFE2E8F0),
                        textAlign = TextAlign.Center
                    )
                } else {
                    when {
                        !files.hasStorageAccess -> PrimaryButton(
                            text = wowText("GRANT STORAGE PERMISSION", "授予存储访问权限"),
                            icon = Icons.Default.FolderOpen,
                            enabled = !isLaunching && !isUpdating,
                            onClick = { StorageUtils.requestManageExternalStoragePermission(context) },
                        )
                        files.needsInstall -> {
                            PrimaryButton(
                                text = if (files.isLegacyBeta) wowText("UPGRADE TO FULL RELEASE", "升级至正式版") else wowText("DOWNLOAD & INSTALL", "下载并安装国服客户端"),
                                icon = Icons.Default.Download,
                                enabled = !isLaunching && !isUpdating,
                                onClick = ::performInstall,
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                            OutlinedButton(
                                onClick = { folderPicker.launch(null) },
                                modifier = Modifier.fillMaxWidth(0.85f).height(48.dp),
                                shape = RoundedCornerShape(14.dp),
                            ) {
                                Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(20.dp), tint = WowGold)
                                Spacer(modifier = Modifier.width(10.dp))
                                Text(wowText("LOCATE EXISTING FILES", "选择已有游戏目录"), fontSize = 13.sp, fontWeight = FontWeight.Bold, color = WowGold)
                            }
                        }
                        versionStatus?.isOutdated == true -> {
                            PrimaryButton(
                                text = if (versionStatus?.localVersion == versionStatus?.remoteVersion) {
                                    wowText("PREPARE ${region.label.uppercase(Locale.ROOT)} CLIENT", "准备${region.label}客户端")
                                } else {
                                    wowText("UPDATE TO ${versionStatus?.remoteVersion}", "更新至 ${versionStatus?.remoteVersion}")
                                },
                                icon = Icons.Default.Refresh,
                                enabled = canPlay,
                                onClick = ::performUpdate,
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                            OutlinedButton(
                                onClick = ::launchGame,
                                enabled = canPlay,
                                modifier = Modifier.fillMaxWidth(0.85f).height(48.dp),
                                shape = RoundedCornerShape(14.dp)
                            ) {
                                Text(text = wowText("LAUNCH ANYWAY (OUTDATED)", "仍然启动（版本过旧）"), fontSize = 13.sp, fontWeight = FontWeight.Bold, color = WowError)
                            }
                        }
                        else -> PrimaryButton(
                            text = when {
                                files.isWrongGame -> wowText("WOW FOREVER REQUIRED", "需要魔兽世界 FOREVER 客户端")
                                browserLogin -> wowText("SIGN IN & PLAY", "登录并开始游戏")
                                else -> wowText("PLAY WORLD OF WARCRAFT", "启动魔兽世界")
                            },
                            icon = Icons.Default.PlayArrow,
                            enabled = canPlay,
                            onClick = ::launchGame,
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // Wraps onto a second line on narrow portrait phones, where a Row squeezed the last button's text
                    // into one letter per line and hid the login option (#44).
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
                    ) {
                        LinkButton(wowText("Refresh", "刷新"), Icons.Default.Refresh) {
                            checkFiles()
                            checkVersionStatus()
                            checkAppUpdate()
                        }
                        if (!files.needsInstall) {
                            LinkButton(wowText("Location", "游戏目录"), Icons.Default.FolderOpen) { folderPicker.launch(null) }
                            // Some file managers label zips as generic binary files, so both types are offered.
                            LinkButton(wowText("Add-on", "导入插件"), Icons.Default.Extension, enabled = !isLaunching && !isUpdating) {
                                addonPicker.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream"))
                            }
                        }
                        LinkButton(
                            text = if (browserLogin) wowText("${region.label} web sign-in", "${region.label}网页登录") else wowText("Login", "登录"),
                            icon = Icons.Default.Key,
                            enabled = !isLaunching && !isUpdating,
                        ) {
                            if (browserLogin) launchGame() else showLoginDialog = true
                        }
                        if (hasSavedLogin && !browserLogin) {
                            LinkButton(wowText("Forget Login", "清除已保存登录"), Icons.Default.Delete) {
                                BattleNetSignIn.forget(context, region, File(gamePath))
                                hasSavedLogin = false
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }

    if (showLoginDialog && !browserLogin) {
        BattleNetCredentialDialog(
            region = region,
            onDismiss = { showLoginDialog = false },
            onConfirm = { login ->
                showLoginDialog = false
                BattleNetSignIn.save(context, login, region)
                hasSavedLogin = true
            },
        )
    }

    if (showUpgradeDialog) {
        AlertDialog(
            onDismissRequest = { showUpgradeDialog = false },
            title = { Text(text = wowText("WoW Forever Has Launched", "魔兽世界 Forever 正式版已发布"), fontWeight = FontWeight.Bold, fontSize = 17.sp, color = WowGold) },
            text = {
                Text(
                    text = wowText(
                        "Blizzard has moved WoW Forever from beta to its full release, and the beta client in this folder " +
                            "is being retired.\n\nThe full release lives in a new $FLAVOR_DIR folder next to Data/. Upgrading reuses " +
                            "the game data you already have, so only the new ARM64 client downloads (about 100 MB).\n\n" +
                            "Already copied the full release from a PC? Choose that folder instead.",
                        "魔兽世界 Forever 已从测试版转为正式版，此目录中的测试版客户端将停用。\n\n正式版使用 Data/ 旁的新 $FLAVOR_DIR 目录。升级会保留现有游戏资源，只需下载新的 ARM64 客户端（约 100 MB）。\n\n已从电脑复制正式版？请选择对应的游戏目录。",
                    ),
                    fontSize = 13.sp,
                    color = Color(0xFFE2E8F0),
                    lineHeight = 18.sp,
                )
            },
            confirmButton = {
                Button(
                    onClick = ::performInstall,
                    colors = ButtonDefaults.buttonColors(containerColor = WowBronze, contentColor = Color.White),
                    shape = RoundedCornerShape(10.dp),
                ) {
                    Text(wowText("UPGRADE NOW", "立即升级"), fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        showUpgradeDialog = false
                        folderPicker.launch(null)
                    }) {
                        Text(wowText("CHOOSE FOLDER", "选择目录"), color = WowMuted)
                    }
                    TextButton(onClick = { showUpgradeDialog = false }) {
                        Text(wowText("LATER", "稍后"), color = WowMuted)
                    }
                }
            },
            containerColor = Color(0xFF1B2838),
            shape = RoundedCornerShape(16.dp),
        )
    }

    if (showStoragePermissionDialog) {
        AlertDialog(
            onDismissRequest = { showStoragePermissionDialog = false },
            title = { Text(text = wowText("Storage Permission Required", "需要存储访问权限"), fontWeight = FontWeight.Bold, fontSize = 17.sp, color = WowGold) },
            text = {
                Text(
                    text = wowText("Android requires 'All files access' for WoW Forever to read and update game files in this directory.\n\nPlease enable 'Allow access to manage all files' on the next screen.", "启动器需要 Android 的“所有文件访问权限”，才能读取和更新此目录中的游戏文件。\n\n请在下一页启用“允许管理所有文件”。"),
                    fontSize = 13.sp,
                    color = Color(0xFFE2E8F0),
                    lineHeight = 18.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showStoragePermissionDialog = false
                        StorageUtils.requestManageExternalStoragePermission(context)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = WowBronze, contentColor = Color.White),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text(wowText("OPEN SETTINGS", "打开设置"), fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showStoragePermissionDialog = false }) {
                    Text(wowText("CANCEL", "取消"), color = WowMuted)
                }
            },
            containerColor = Color(0xFF1B2838),
            shape = RoundedCornerShape(16.dp),
        )
    }

    if (showAppUpdateDialog && AppUpdater.isEnabled) {
        appUpdate?.let { update ->
            AlertDialog(
                onDismissRequest = {
                    if (!isDownloadingAppUpdate) showAppUpdateDialog = false
                },
                title = {
                    Text(
                        text = wowText("App Update Available (v${update.version})", "启动器有新版本（v${update.version}）"),
                        fontWeight = FontWeight.Bold,
                        fontSize = 17.sp,
                        color = WowGold,
                    )
                },
                text = {
                    Column {
                        if (update.notes.isNotBlank()) {
                            Text(
                                text = renderReleaseNotes(update.notes),
                                fontSize = 12.sp,
                                color = Color(0xFFCBD5E1),
                                lineHeight = 16.sp,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 240.dp)
                                    .verticalScroll(rememberScrollState()),
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                        }
                        if (isDownloadingAppUpdate) {
                            Text(
                                text = wowText("Downloading: ${(appUpdateProgress * 100).toInt()}%", "正在下载：${(appUpdateProgress * 100).toInt()}%"),
                                fontSize = 12.sp,
                                color = WowGold,
                                fontWeight = FontWeight.Bold,
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            LinearProgressIndicator(
                                progress = { appUpdateProgress },
                                modifier = Modifier.fillMaxWidth(),
                                color = WowGold,
                            )
                        }
                    }
                },
                confirmButton = {
                    Button(
                        onClick = ::performAppUpdate,
                        enabled = !isDownloadingAppUpdate,
                        colors = ButtonDefaults.buttonColors(containerColor = WowBronze, contentColor = Color.White),
                        shape = RoundedCornerShape(10.dp),
                    ) {
                        Text(
                            text = when {
                                isDownloadingAppUpdate -> wowText("DOWNLOADING...", "正在下载…")
                                downloadedApk?.exists() == true -> wowText("INSTALL NOW", "立即安装")
                                else -> wowText("UPDATE NOW", "立即更新")
                            },
                            fontWeight = FontWeight.Bold,
                        )
                    }
                },
                dismissButton = {
                    if (!isDownloadingAppUpdate) {
                        TextButton(onClick = { showAppUpdateDialog = false }) {
                            Text(wowText("LATER", "稍后"), color = WowMuted)
                        }
                    }
                },
                containerColor = Color(0xFF1B2838),
                shape = RoundedCornerShape(16.dp),
            )
        }
    }

    if (showAppUpdatePermissionDialog && AppUpdater.isEnabled) {
        AlertDialog(
            onDismissRequest = { showAppUpdatePermissionDialog = false },
            title = {
                Text(
                    text = wowText("Install Permission Required", "需要安装应用权限"),
                    fontWeight = FontWeight.Bold,
                    fontSize = 17.sp,
                    color = WowGold,
                )
            },
            text = {
                Text(
                    text = wowText("Android requires permission to install apps from WoW Forever.\n\nPlease enable 'Allow from this source' on the next screen.", "Android 需要授权启动器安装应用。\n\n请在下一页启用“允许来自此来源的应用”。"),
                    fontSize = 13.sp,
                    color = Color(0xFFE2E8F0),
                    lineHeight = 18.sp,
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showAppUpdatePermissionDialog = false
                        pendingInstallPermission = true
                        AppUpdater.openInstallPermissionSettings(context)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = WowBronze, contentColor = Color.White),
                    shape = RoundedCornerShape(10.dp),
                ) {
                    Text(wowText("OPEN SETTINGS", "打开设置"), fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showAppUpdatePermissionDialog = false }) {
                    Text(wowText("CANCEL", "取消"), color = WowMuted)
                }
            },
            containerColor = Color(0xFF1B2838),
            shape = RoundedCornerShape(16.dp),
        )
    }
}

private fun prefs(context: Context) = context.getSharedPreferences("wow_forever", Context.MODE_PRIVATE)

/**
 * Wine's tick count follows the device's uptime, and once it passes 2^32 ms (about 49.7 days) WoW's frame time stops
 * advancing: the login UI never fades in and auto-login never starts (#36). A restart fixes it, so the launcher says so.
 */
internal fun longUptimeDays(uptimeMs: Long = SystemClock.elapsedRealtime()): Long? =
    if (uptimeMs >= UPTIME_WARNING_MS) uptimeMs / 86_400_000L else null

// A little under 2^32 ms, so the warning shows before the clock stops rather than after.
private const val UPTIME_WARNING_MS = 48L * 86_400_000L

/**
 * The Battle.net region WoW logs into. Without the Battle.net app to pick one, WoW stops at a region picker before
 * login, so the launcher writes it to Config.wtf as the portal.
 */
enum class GameRegion(private val englishLabel: String, private val chineseLabel: String) {
    US("Americas", "美洲"), EU("Europe", "欧洲"), KR("Korea", "韩国"), TW("Taiwan", "台湾"), CN("China", "国服");

    val label get() = wowText(englishLabel, chineseLabel)

    /** The region's name in Blizzard's patch server tables. */
    val cdnRegion get() = name.lowercase(Locale.ROOT)

    companion object {
        private const val KEY_REGION = "region"

        /** The region picked in the launcher, or null to keep the install's current portal. */
        fun saved(context: Context): GameRegion? = WowEdition.current.lockedRegion ?: prefs(context).getString(KEY_REGION, null)?.let(::of)

        fun save(context: Context, region: GameRegion) {
            prefs(context).edit().putString(KEY_REGION, (WowEdition.current.lockedRegion ?: region).name).apply()
        }

        fun current(context: Context, gamePath: String): GameRegion = saved(context) ?: of(portalOf(File(gamePath))) ?: US

        private fun of(portal: String) = entries.firstOrNull { it.name.equals(portal, ignoreCase = true) }
    }
}

/** The 16:9 render budget, before it's reshaped to the window's aspect ratio. Defaults to the GPU profile's size. */
enum class RenderResolution(val size: String, val label: String) {
    HD("1280x720", "720p"), HD_PLUS("1600x900", "900p"), FULL_HD("1920x1080", "1080p");

    companion object {
        private const val KEY_RESOLUTION = "resolution"

        fun current(context: Context, default: String): RenderResolution =
            prefs(context).getString(KEY_RESOLUTION, null)?.let { saved -> entries.firstOrNull { it.name == saved } }
                ?: entries.first { it.size == default }

        fun save(context: Context, resolution: RenderResolution) {
            prefs(context).edit().putString(KEY_RESOLUTION, resolution.name).apply()
        }
    }
}

object GamePath {
    private const val KEY_GAME_PATH = "game_path"

    private val defaultPath = File(Environment.getExternalStorageDirectory(), WowEdition.current.gameFolderName)

    data class Status(
        val exeExists: Boolean = false,
        val dataExists: Boolean = false,
        val buildInfoExists: Boolean = false,
        val products: Set<String> = emptySet(),
        val hasStorageAccess: Boolean = true,
    ) {
        val hasForever get() = TARGET_PRODUCT in products
        val isLegacyBeta get() = !hasForever && LEGACY_PRODUCT in products
        val isWrongGame get() = buildInfoExists && products.isNotEmpty() && !hasForever && !isLegacyBeta
        val needsInstall get() = !isWrongGame && !(hasForever && dataExists)
        val isReady get() = exeExists && dataExists && hasForever && hasStorageAccess
    }

    fun status(context: Context, path: String): Status {
        val root = File(path)
        return Status(
            exeExists = File(root, "$FLAVOR_DIR/$EXE_NAME").exists(),
            dataExists = File(root, "Data").listFiles()?.isNotEmpty() == true,
            buildInfoExists = File(root, BUILD_INFO).isFile,
            products = productsOf(root),
            hasStorageAccess = StorageUtils.hasStoragePermission(context, path),
        )
    }

    /**
     * The folder the user picked, as long as it still exists. Otherwise the first default location (internal storage, then
     * each SD card) that holds an install, preferring the full release over the beta.
     */
    fun load(context: Context): String {
        val saved = prefs(context).getString(KEY_GAME_PATH, null)?.let(::File)
        if (saved != null && saved.isDirectory) return saved.absolutePath
        val defaults = listOf(defaultPath) + context.getExternalFilesDirs(null).drop(1).filterNotNull()
            .map { File(it.path.substringBefore("/Android/"), defaultPath.name) }
        val usable = defaults.firstOrNull { TARGET_PRODUCT in productsOf(it) } ?: defaults.firstOrNull { LEGACY_PRODUCT in productsOf(it) }
        return (usable ?: saved ?: defaultPath).absolutePath
    }

    /** Installs into an empty picked folder, or into this edition's subfolder on a populated volume. */
    fun installDirFor(picked: File): File = WowEdition.current.installDirFor(picked)

    fun save(context: Context, path: String) {
        prefs(context).edit().putString(KEY_GAME_PATH, path).apply()
    }

    fun isReady(context: Context, path: String = load(context)): Boolean =
        path.isNotEmpty() && status(context, path).isReady

    fun findGameRoot(picked: File): File? = WowEdition.current.findGameRoot(picked)

    fun freeSpaceBytes(path: String): Long =
        generateSequence(File(path)) { it.parentFile }.firstOrNull { it.exists() }?.usableSpace ?: 0L

    private fun productsOf(root: File) = WowClientDownloader.installedProducts(File(root, BUILD_INFO))
}

@Composable
private fun InstallCard(isUpgrade: Boolean, gamePath: String, freeSpaceBytes: Long, enabled: Boolean, onChangeLocation: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth(0.9f)
            .border(1.dp, WowGold.copy(alpha = 0.6f), RoundedCornerShape(16.dp)),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E2838).copy(alpha = 0.85f)),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Download, contentDescription = null, tint = WowGold, modifier = Modifier.size(20.dp))
                Spacer(modifier = Modifier.width(8.dp))
                SectionTitle(if (isUpgrade) wowText("WOW FOREVER HAS LAUNCHED", "FOREVER 正式版已发布") else wowText("INSTALL WORLD OF WARCRAFT", "安装魔兽世界国服客户端"), WowGold)
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = if (isUpgrade) {
                    wowText(
                        "This folder has the Forever beta, which Blizzard is retiring. Upgrade to install the full release into " +
                            "$FLAVOR_DIR/. Your existing game data is reused, so only the new client downloads (about 100 MB).",
                        "此目录中是即将停用的 Forever 测试版。升级将正式版安装到 $FLAVOR_DIR/，保留现有游戏资源，只需下载新的客户端（约 100 MB）。",
                    )
                } else {
                    wowText(
                        "Downloads the ARM64 game client and archive indexes (about 250 MB) from Blizzard's CDN. The rest of the " +
                            "game streams in as you play and can grow past 50 GB, so pick a location with room to spare.",
                        "从国服官方内容分发网络下载 ARM64 客户端和资源索引（约 250 MB）。其余游戏资源会在游玩时按需下载，最终可能超过 50 GB，请选择空间充足的目录。下载完成后，请手动点击“登录并开始游戏”。",
                    )
                },
                fontSize = 12.sp,
                color = Color(0xFFCBD5E1),
                lineHeight = 16.sp,
            )
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = wowText("Install location: $gamePath (${"%.1f".format(freeSpaceBytes / 1e9)} GB free)", "安装目录：$gamePath（可用 ${"%.1f".format(freeSpaceBytes / 1e9)} GB）"),
                fontSize = 11.sp,
                color = WowSubtle,
            )
            if (!isUpgrade) {
                LinkButton(wowText("Change Install Location", "更改安装目录"), Icons.Default.FolderOpen, enabled = enabled, onClick = onChangeLocation)
                Text(
                    text = wowText("Already copied the game from a PC? Use Locate Existing Files below.", "已从电脑复制游戏？请点击下方“选择已有游戏目录”。"),
                    fontSize = 11.sp,
                    color = WowSubtle,
                )
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String, color: Color) {
    Text(text = text, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp, color = color)
}

@Composable
private fun PrimaryButton(text: String, icon: ImageVector, enabled: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth(0.85f).height(56.dp),
        colors = ButtonDefaults.buttonColors(containerColor = WowBronze, contentColor = Color.White),
        shape = RoundedCornerShape(14.dp)
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(24.dp))
        Spacer(modifier = Modifier.width(10.dp))
        Text(text = text, fontSize = 15.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
    }
}

@Composable
private fun LinkButton(text: String, icon: ImageVector, enabled: Boolean = true, onClick: () -> Unit) {
    TextButton(onClick = onClick, enabled = enabled) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp), tint = WowMuted)
        Spacer(modifier = Modifier.width(6.dp))
        Text(text, fontSize = 12.sp, color = WowMuted, maxLines = 1)
    }
}

@Composable
private fun <T> OptionMenu(
    text: String,
    icon: ImageVector,
    options: List<T>,
    selected: T,
    optionLabel: (T) -> String,
    enabled: Boolean,
    onSelect: (T) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        LinkButton(text, icon, enabled) { expanded = true }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(optionLabel(option)) },
                    trailingIcon = { if (option == selected) Icon(Icons.Default.Check, contentDescription = null, tint = WowGold) },
                    onClick = {
                        expanded = false
                        if (option != selected) onSelect(option)
                    },
                )
            }
        }
    }
}

@Composable
private fun CheckItem(label: String, ready: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = if (ready) Icons.Default.CheckCircle else Icons.Default.Warning,
            contentDescription = null,
            tint = if (ready) Color(0xFF48BB78) else Color(0xFFED8936),
            modifier = Modifier.size(18.dp)
        )
        Spacer(modifier = Modifier.width(10.dp))
        Text(text = label, fontSize = 13.sp, color = if (ready) Color(0xFFCBD5E1) else Color(0xFFED8936))
    }
}

private class StorageAccessDeniedException(cause: Throwable) : Exception(cause)

private fun Throwable.isPermissionDenied() =
    message?.let { "EPERM" in it || "Operation not permitted" in it } == true

private const val PROTON_VERSION = "proton-11.0-90624-arm64ec"
private const val DXVK_VERSION = "2.4.1-wow-aarch64-test"

/**
 * Reshapes the GPU's 16:9 render budget to the current window's aspect ratio so the game fills
 * non-16:9 screens (e.g. a foldable's near-square inner display) instead of being letterboxed.
 * The pixel count stays the same as [baseSize], so GPU cost matches the 16:9 default.
 */
private fun fitScreenSizeToWindow(context: Context, baseSize: String): String {
    val (windowWidth, windowHeight) = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        context.getSystemService(WindowManager::class.java).currentWindowMetrics.bounds.let { it.width() to it.height() }
    } else {
        context.resources.displayMetrics.let { it.widthPixels to it.heightPixels }
    }
    return fitScreenSize(baseSize, windowWidth, windowHeight)
}

internal fun fitScreenSize(baseSize: String, windowWidth: Int, windowHeight: Int): String {
    if (windowWidth <= 0 || windowHeight <= 0) return baseSize
    val (baseWidth, baseHeight) = baseSize.split("x").map { it.toInt() }
    val aspect = maxOf(windowWidth, windowHeight).toFloat() / minOf(windowWidth, windowHeight)
    val height = sqrt(baseWidth * baseHeight / aspect)
    val width = height * aspect
    return "${(width / 2).roundToInt() * 2}x${(height / 2).roundToInt() * 2}"
}

private fun prepareLaunch(context: Context, gameRoot: File, gpu: WowGpuProfile, screenSize: String, onStatus: (String) -> Unit): String {
    installBundledComponents(context, gpu, onStatus)

    check(File(gameRoot, BUILD_INFO).exists()) { wowText("Missing $BUILD_INFO in $gameRoot. Copy it from your WoW install.", "$gameRoot 中缺少 $BUILD_INFO。请从完整的游戏安装目录复制此文件。") }
    val launchRegion = GameRegion.current(context, gameRoot.path)
    try {
        ensureGameConfig(gameRoot, screenSize, GameRegion.saved(context))
    } catch (e: Exception) {
        if (e.isPermissionDenied()) throw StorageAccessDeniedException(e)
        throw e
    }

    val arm64Exe = File(gameRoot, "$FLAVOR_DIR/$EXE_NAME")
    val needsRegionClient = WowClientDownloader.regionClientRequired(
        WowClientDownloader.readBuildInfo(File(gameRoot, BUILD_INFO))?.get("Branch"),
        launchRegion.cdnRegion,
    )
    if (!arm64Exe.exists() || needsRegionClient) {
        try {
            WowClientDownloader.download(gameRoot, launchRegion.cdnRegion, onStatus)
        } catch (e: Exception) {
            if (!arm64Exe.exists() || needsRegionClient) throw IllegalStateException(wowText("Couldn't prepare ${launchRegion.label} client: ${e.message}", "无法准备${launchRegion.label}客户端：${e.message}"), e)
            Timber.w(e, "Client update check failed, launching the existing $EXE_NAME")
        }
    }

    onStatus(wowText("Configuring ${gpu.label} container...", "正在配置 ${gpu.label} 运行环境…"))
    val containerManager = ContainerManager(context)
    val config = containerConfig(gameRoot, gpu, screenSize, WowMachineIdentity.load(context))
    val container = containerManager.getContainerById(CONTAINER_ID)
    if (container == null) {
        onStatus(wowText("Creating prefix environment (first boot)...", "正在创建运行环境（首次启动）…"))
        containerManager.createContainer(CONTAINER_ID, config)
    } else {
        container.loadData(config)
        container.saveData()
    }
    check(containerManager.hasContainer(CONTAINER_ID)) { wowText("Container creation failed. Check system storage and logs.", "创建运行环境失败。请检查可用存储空间和运行日志。") }
    // Written last, so the password isn't on disk during the client download or container setup.
    val login = if (BattleNetBrowserLogin.enabled(context, launchRegion)) null else BattleNetSignIn.load(context, launchRegion)
    if (login == null) {
        BattleNetSignIn.removeLoginFile(gameRoot)
    } else {
        BattleNetSignIn.writeLoginFile(gameRoot, login)
        BattleNetSignIn.startLoginWatcher(gameRoot)
    }
    return CONTAINER_ID
}

private fun containerConfig(gameRoot: File, gpu: WowGpuProfile, screenSize: String, identity: WowMachineIdentity): JSONObject {
    return JSONObject().apply {
        put("id", CONTAINER_ID)
        put("name", "WoW Forever")
        put("screenSize", screenSize)
        put("envVars", gpu.environmentVariables(Build.MANUFACTURER, identity.computerName))
        put("graphicsDriver", gpu.graphicsDriver)
        put("graphicsDriverVersion", gpu.driver)
        put("graphicsDriverConfig", gpu.graphicsDriverConfig)
        put("displayRenderer", "vulkan")
        put("dxwrapper", "dxvk-$DXVK_VERSION-1")
        put("dxwrapperConfig", gpu.dxvkConfig("$DXVK_VERSION-1"))
        put("wineVersion", "$PROTON_VERSION-1")
        put("containerVariant", "bionic")
        put("fexcoreVersion", "2609-0")
        put("drives", "D:/storage/emulated/0/DownloadE:/data/data/${BuildConfig.APPLICATION_ID}/storageG:${gameRoot.path}")
        put("executablePath", "G:\\$FLAVOR_DIR\\$EXE_NAME")
        put("execArgs", "-d3d11")
        put("showFPS", true)
        // Keep running in the background: a suspended client drops its Battle.net connection, e.g. while the
        // player fetches an authenticator code from another app.
        put("suspendPolicy", Container.SUSPEND_POLICY_NEVER)
        put("startupSelection", Container.STARTUP_SELECTION_AGGRESSIVE.toInt())
        put("wow64Mode", true)
        put("wincomponents", "direct3d=0,directsound=0,directinput8=0,directinput=0,directmusic=0,directshow=0,directplay=0,vcrun2010=0,wmdecoder=0,opengl=0")
    }
}

private val CONFIG_DEFAULTS = linkedMapOf(
    "agentUID" to "\"$TARGET_PRODUCT\"",
    "gxApi" to "\"D3D11\"",
    "textLocale" to "\"${WowEdition.current.defaultTextLocale}\"",
    "audioLocale" to "\"${WowEdition.current.defaultAudioLocale}\"",
    "gxMaximize" to "\"1\"",
    "graphicsQuality" to "\"0\"",
    "ResampleQuality" to "\"0\"",
    "RenderScale" to "\"1\"",
    "farclip" to "\"1200\"",
    "horizonClip" to "\"1200\"",
    "RAIDfarclip" to "\"1200\"",
    "RAIDhorizonClip" to "\"1200\"",
    "shadowMode" to "\"0\"",
    "graphicsShadowQuality" to "\"0\"",
    "raidGraphicsShadowQuality" to "\"0\"",
    "worldBaseMip" to "\"0\"",
    "RAIDworldBaseMip" to "\"0\"",
    "graphicsTextureResolution" to "\"2\"",
    "raidGraphicsTextureResolution" to "\"2\"",
    "componentTextureLevel" to "\"0\"",
    "RAIDcomponentTextureLevel" to "\"0\"",
    "entityShadowFadeScale" to "\"0\"",
    "refraction" to "\"0\"",
    "groundEffectDensity" to "\"16\"",
    "GamePadEnable" to "\"1\"",
    "InputDeviceInterfaceStyle" to "\"1\"",
    // Answers the first-run Screen Narrator prompt, which otherwise blocks login.txt auto-login.
    "showScreenNarrationDialog" to "\"0\"",
    "accessibilityScreenNarrationEnabled" to "\"0\"",
)

internal fun ensureGameConfig(root: File, screenSize: String, region: GameRegion?) {
    val flavorDir = File(root, FLAVOR_DIR)
    flavorDir.mkdirs()
    val flavorInfo = File(flavorDir, ".flavor.info")
    if (!flavorInfo.exists()) {
        flavorInfo.writeText("Product Flavor!STRING:0\n$TARGET_PRODUCT\n")
    }
    // Handheld defaults are only written on first boot so in-game changes stick, with three exceptions that are
    // forced on every launch. gxApi: DXVK only works with D3D11, and a config copied from a Mac would otherwise
    // select Metal. Resolutions match the container, which the launcher sizes: WoW's "auto" picks a 720p size
    // because it sees a low-end Intel GPU via Wine, and saves that size on exit as if the player chose it.
    // The portal is the region picked in the launcher, or kept as is (filled from the install's region when
    // missing); without the Battle.net app to supply it, WoW otherwise stops at a region picker before login.
    val configWtf = File(root, CONFIG_WTF)
    val betaConfigWtf = File(root, "$LEGACY_FLAVOR_DIR/WTF/Config.wtf")
    if (!configWtf.exists() && betaConfigWtf.isFile) {
        // Carry the player's settings over from the beta, minus the lines that pointed it at the beta servers.
        configWtf.parentFile?.mkdirs()
        val betaOnly = listOf("SET portal ", "SET agentUID ", "SET CACHE-")
        val carried = betaConfigWtf.readLines().filterNot { line -> betaOnly.any { line.startsWith(it, ignoreCase = true) } }
        configWtf.writeText((carried + "SET agentUID ${CONFIG_DEFAULTS.getValue("agentUID")}").joinToString("\n") + "\n")
    }
    if (!configWtf.exists()) {
        configWtf.parentFile?.mkdirs()
        configWtf.writeText(CONFIG_DEFAULTS.entries.joinToString("\n") { "SET ${it.key} ${it.value}" } + "\n")
    }

    val forced = mapOf("gxApi" to CONFIG_DEFAULTS.getValue("gxApi"), "portal" to "\"${region?.name ?: portalOf(root)}\"") +
        RESOLUTION_KEYS.associateWith { "\"$screenSize\"" }
    val forcedLines = forced.map { (key, value) -> "SET $key $value" }
    val original = configWtf.readLines()
    if (!original.containsAll(forcedLines)) {
        val kept = original.filterNot { line -> forced.keys.any { line.startsWith("SET $it ", ignoreCase = true) } }
        configWtf.writeText((kept + forcedLines).joinToString("\n") + "\n")
    }
}

private val RESOLUTION_KEYS = listOf("gxFullscreenResolution", "gxWindowedResolution")

private const val CONFIG_WTF = "$FLAVOR_DIR/WTF/Config.wtf"

// The portal Config.wtf already points at, else the install's region from .build.info. Runs while the launcher is
// composed, before storage access may be granted: after a reinstall, files the old install left can't be read yet.
private fun portalOf(root: File) =
    runCatching { File(root, CONFIG_WTF).takeIf { it.isFile }?.readLines() }.getOrNull()
        ?.firstOrNull { it.startsWith("SET portal ", ignoreCase = true) }
        ?.substringAfter(" ")?.substringAfter(" ")?.trim('"')?.takeIf { it.isNotBlank() }
        ?: WowClientDownloader.readBuildInfo(File(root, BUILD_INFO))?.get("Branch")?.takeIf { it.isNotBlank() }?.uppercase()
        ?: WowClientDownloader.defaultBranch().uppercase()

private fun installBundledComponents(context: Context, gpu: WowGpuProfile, onStatus: (String) -> Unit) {
    if (!gpu.usesSystemDriver) {
        val assetZip = if (gpu.driver == WowGpuProfile.ADRENO_8XX_DRIVER) "turnip-V32-RP6sched.zip" else "turnip-wow-scheduler-test.zip"
        installDriver(context, gpu.driver, assetZip, onStatus)
    }

    val protonDir = File(ImageFs.getSharedProtonDir(context), PROTON_VERSION)
    if (!File(protonDir, "profile.json").exists() || !File(protonDir, "bin/wine").exists()) {
        onStatus(wowText("Extracting Proton 11 ARM64EC (takes ~10s)...", "正在解压 Proton 11 ARM64EC（约需 10 秒）…"))
        check(extractBundledWcp(context, "$PROTON_VERSION.wcp", protonDir)) { wowText("Failed to extract Proton or graphics components.", "解压 Proton 或图形组件失败。") }
        File(protonDir, "bin").listFiles()?.forEach {
            it.setExecutable(true, false)
            it.setReadable(true, false)
        }
    }

    val dxvkDir = File(ContentsManager.getContentTypeDir(context, ContentProfile.ContentType.CONTENT_TYPE_DXVK), DXVK_VERSION)
    if (!File(dxvkDir, "profile.json").exists()) {
        onStatus(wowText("Extracting DXVK ARM64...", "正在解压 DXVK ARM64…"))
        if (!extractBundledWcp(context, "dxvk-$DXVK_VERSION.wcp", dxvkDir)) Timber.e("Failed to extract DXVK")
    }

    ImageFsInstaller.ensureBionicLib(context, ImageFs.find(context).rootDir)
    ContentsManager(context).syncContents()
}

private fun installDriver(context: Context, driverName: String, assetZip: String, onStatus: (String) -> Unit) {
    val driverDir = File(context.filesDir, "contents/adrenotools/$driverName")
    if (File(driverDir, "meta.json").exists()) return
    onStatus(wowText("Installing $driverName driver...", "正在安装 $driverName 驱动…"))
    driverDir.mkdirs()
    if (!FileUtils.extractZipFromAssets(context, "bundled_components/$assetZip", driverDir)) {
        Timber.e("Error extracting driver $driverName from $assetZip")
    }
}

private fun extractBundledWcp(context: Context, assetName: String, destination: File): Boolean {
    destination.mkdirs()
    return TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, context, "bundled_components/$assetName", destination)
}
