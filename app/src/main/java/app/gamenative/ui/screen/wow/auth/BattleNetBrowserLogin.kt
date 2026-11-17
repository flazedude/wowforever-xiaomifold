package app.gamenative.ui.screen.wow.auth

import android.content.Context
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.gamenative.PluviaApp
import app.gamenative.events.AndroidEvent
import app.gamenative.ui.screen.wow.BattleNetSignIn
import app.gamenative.ui.screen.wow.GameRegion
import app.gamenative.ui.screen.wow.WowClientDownloader
import app.gamenative.ui.screen.wow.wowText
import com.winlator.container.Container
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

/** China uses browser authentication; other regions can opt in. Tokens never enter files, arguments, preferences, or logs. */
internal object BattleNetBrowserLogin {
    private const val PREFS = "battle_net_browser_login"
    private const val ASSET = "wow-session-bridge.exe"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutableUi = MutableStateFlow<Ui>(Ui.Idle)
    val ui = mutableUi.asStateFlow()
    private var active: Attempt? = null

    sealed interface Ui {
        data object Idle : Ui
        class Waiting(val text: String) : Ui
        // Deliberately no generated toString: a challenge URL is sensitive.
        class Browser(val url: String, val region: GameRegion? = null) : Ui
        class Failed(val text: String) : Ui
    }

    fun enabled(context: Context, region: GameRegion? = null): Boolean = region == GameRegion.CN ||
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("enabled", false)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("enabled", enabled).apply()
    }

    /** Runs after Wine prefix initialization. Container executable remains the game for window tracking. */
    @Synchronized
    fun start(context: Context, container: Container): String {
        cancel()
        val gameRoot = Container.drivesIterator(container.drives).asSequence()
            .firstOrNull { it[0].equals("G", ignoreCase = true) }?.let { File(it[1]) }
            ?: throw IOException(wowText("Cannot locate the game drive for browser login", "无法找到用于网页登录的游戏目录"))
        val build = WowClientDownloader.readBuildInfo(File(gameRoot, WowClientDownloader.BUILD_INFO))
            ?.get("Version")?.substringAfterLast('.')?.toIntOrNull()?.takeIf { it > 0 }
            ?: throw IOException(wowText("Cannot read the installed game build for browser login", "无法读取已安装的游戏版本，无法开始网页登录"))
        val region = GameRegion.current(context, gameRoot.path)
        BattleNetSignIn.removeLoginFile(gameRoot)
        check(!File(gameRoot, "${WowClientDownloader.FLAVOR_DIR}/login.txt").exists()) {
            wowText("Remove the old login.txt before using browser login", "请先移除旧的 login.txt，再使用网页登录")
        }

        val directory = File(container.rootDir, ".wine/drive_c/wow-session")
        check(directory.isDirectory || directory.mkdirs()) { wowText("Cannot prepare browser login", "无法准备网页登录") }
        // The prefix is in Android's app-private directory, never shared game storage.
        val helper = File(directory, ASSET)
        context.assets.open(ASSET).use { input -> helper.outputStream().use { input.copyTo(it) } }
        val bridge = SessionBridge()
        val request = File(directory, "request-${UUID.randomUUID()}.txt")
        try {
            request.writeText(bridge.config, Charsets.US_ASCII)
            request.setReadable(false, false)
            request.setWritable(false, false)
            check(request.setReadable(true, true) && request.setWritable(true, true)) {
                wowText("Cannot protect the browser login request", "无法保护网页登录请求，请重试")
            }
            val log = File(gameRoot, "${WowClientDownloader.FLAVOR_DIR}/Logs/BrowserLogin.log")
            val attempt = Attempt(bridge, request, region, build, log)
            diag(attempt, "browser login started (app ${app.gamenative.BuildConfig.VERSION_NAME}, ${region.name}, build $build)")
            active = attempt
            mutableUi.value = Ui.Waiting(wowText("Starting Battle.net browser login…", "正在启动战网网页登录…"))
            // One deadline covers Wine startup, queues and browser interaction, below the helper's 15 minutes.
            attempt.watchdog = scope.launch {
                delay(10 * 60_000L)
                fail(attempt, wowText("Browser login timed out. Please try again.", "网页登录超时，请重试。"))
            }
            attempt.job = scope.launch { run(attempt) }
            // These arguments contain paths and public switches only. The token travels over the authenticated socket.
            // No quotes: ProcessHelper.splitCommand keeps quote characters, so Wine would get them as part of each
            // path and couldn't find the helper. None of these paths contain spaces.
            val gamePath = container.executablePath
            check(' ' !in gamePath) { wowText("The game path can't contain spaces for browser login", "使用网页登录时，游戏路径不能包含空格") }
            return "C:\\wow-session\\$ASSET C:\\wow-session\\${request.name} " +
                "$gamePath -launcherlogin -uid ${WowClientDownloader.TARGET_PRODUCT} -d3d11"
        } catch (error: Exception) {
            bridge.close()
            request.delete()
            throw error
        }
    }

    private suspend fun run(attempt: Attempt) {
        try {
            diag(attempt, "waiting for the helper to connect")
            attempt.bridge.accept().use { peer ->
                diag(attempt, "helper connected")
                val device = peer.device().also { attempt.device.set(it) }
                validateDevice(device)
                val url = withClient(attempt) { client ->
                    client.connect()
                    client.webChallenge(device, attempt.build)
                }
                diag(attempt, "showing the Battle.net sign-in page")
                publish(attempt, Ui.Browser(url, attempt.region))
                val ticket = withTimeout(15 * 60_000L) { attempt.ticket.await() }
                diag(attempt, "sign-in page finished")
                try {
                    publish(attempt, Ui.Waiting(wowText("Completing Battle.net sign-in…", "正在完成战网登录…")))
                    // A fresh connection avoids holding a BGS socket open while the user handles CAPTCHA or 2FA.
                    val token = withClient(attempt) { client ->
                        client.connect()
                        client.webChallenge(device, attempt.build)
                        client.verifyAndGenerateGameToken(ticket)
                    }
                    try {
                        diag(attempt, "Battle.net issued a game session; handing it to the helper")
                        publish(attempt, Ui.Waiting(wowText("Starting WoW with the Battle.net session…", "正在使用战网会话启动魔兽世界…")))
                        peer.launch(token)
                    } finally {
                        token.fill(0)
                    }
                } finally {
                    ticket.fill(0)
                    device.fill(0)
                }
                diag(attempt, "the game started with the Battle.net session")
                publish(attempt, Ui.Idle)
            }
        } catch (_: TimeoutCancellationException) {
            fail(attempt, wowText("Browser login timed out. Please try again.", "网页登录超时，请重试。"))
        } catch (_: CancellationException) {
            // User cancellation does not turn into a password-login attempt.
        } catch (error: BgsException) {
            diag(attempt, "Battle.net error: ${error.message}")
            fail(attempt, bgsErrorText(error))
        } catch (error: Exception) {
            // The exception type only: messages can carry provider payloads or addresses.
            diag(attempt, "stopped: ${error.javaClass.simpleName}")
            // Never expose provider payloads, redirect URLs, credentials or arbitrary peer output.
            fail(attempt, if (attempt.region == GameRegion.CN) {
                wowText(
                    "China browser sign-in could not start the game. Please retry China / NetEase sign-in from the launcher.",
                    "国服网页登录未能启动游戏。请返回启动器，重新进行国服／网易账号登录。",
                )
            } else {
                wowText(
                    "Browser sign-in could not start the game. You can retry or switch back to password login in the launcher.",
                    "网页登录未能启动游戏。请重试，或返回启动器切换为密码登录。",
                )
            })
        } finally {
            attempt.close()
            synchronized(this) { if (active === attempt) active = null }
        }
    }

    private fun <T> withClient(attempt: Attempt, action: (BgsClient) -> T): T {
        // The ARM64 helper reports the real Windows-on-ARM context, the same platform as the ARM64 game client.
        val client = BgsClient(BgsClient.endpointFor(attempt.region), platform = "WinA", region = attempt.region)
        attempt.client.set(client)
        try {
            if (attempt.cancelled) throw CancellationException()
            return action(client)
        } finally {
            attempt.client.compareAndSet(client, null)
            client.close()
        }
    }

    private fun validateDevice(device: ByteArray) {
        val expected = setOf("RGKY", "CPGE", "ULNG", "SLNG", "CNME", "UNME", "UTCO", "CARC", "CREV", "CLVL",
            "PMEM", "PSZE", "OVER", "CVRA", "CFTC", "CFTD", "CEFC", "CEFD", "CBRD", "CVEN", "ANME", "ADSC", "MAC")
        val json = JSONObject(device.toString(Charsets.UTF_8))
        check(json.keys().asSequence().toSet() == expected) { "Invalid device description" }
        expected.forEach { check(json.getLong(it) in 0..0xffff_ffffL) { "Invalid device description" } }
    }

    /** Appends a step to the game folder's BrowserLogin.log for device testing. Never pass credentials or URLs. */
    private fun diag(attempt: Attempt, message: String) {
        runCatching {
            attempt.log.parentFile?.mkdirs()
            val time = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.ROOT).format(java.util.Date())
            attempt.log.appendText("$time app: $message\n")
        }
    }

    @Synchronized
    private fun publish(attempt: Attempt, state: Ui) {
        if (active === attempt && !attempt.cancelled) mutableUi.value = state
    }

    @Synchronized
    fun submit(browser: Ui.Browser, ticket: ByteArray) {
        val attempt = active
        if (mutableUi.value !== browser || attempt == null || attempt.ticket.isCompleted) {
            ticket.fill(0)
        } else {
            attempt.ticketBytes.set(ticket)
            attempt.ticket.complete(ticket)
        }
    }

    @Synchronized
    fun cancel(preserveFailure: Boolean = false) {
        active?.let { attempt ->
            attempt.cancelled = true
            attempt.close()
            attempt.job?.cancel()
        }
        active = null
        if (!preserveFailure || mutableUi.value !is Ui.Failed) mutableUi.value = Ui.Idle
    }

    @Synchronized
    fun abort(state: Ui) {
        if (mutableUi.value !== state) return
        cancel()
        // Closing the control socket alone cannot stop a helper that has already received the token.
        PluviaApp.events.emit(AndroidEvent.ForceCloseApp)
    }

    @Synchronized
    fun webError(browser: Ui.Browser, message: String) {
        if (mutableUi.value === browser) active?.let { fail(it, message) }
    }

    @Synchronized
    private fun fail(attempt: Attempt, message: String) {
        if (active !== attempt || attempt.cancelled || mutableUi.value is Ui.Idle) return
        val failure = Ui.Failed(message)
        mutableUi.value = failure
        cancel(preserveFailure = true)
        scope.launch(Dispatchers.Main.immediate) {
            synchronized(this@BattleNetBrowserLogin) {
                // A queued failure must not tear down a newer attempt or a game the user has since launched.
                if (mutableUi.value === failure) PluviaApp.events.emit(AndroidEvent.ForceCloseApp)
            }
        }
    }

    @Synchronized
    fun guestExited() {
        val attempt = active
        if (attempt != null && mutableUi.value !is Ui.Idle && mutableUi.value !is Ui.Failed) {
            diag(attempt, "the Wine side exited before sign-in completed")
            mutableUi.value = Ui.Failed(
                wowText(
                    "The Wine login helper stopped before sign-in completed. Details are in _forever_/Logs/BrowserLogin.log.",
                    "登录尚未完成，Wine 登录助手已停止。详情见 _forever_/Logs/BrowserLogin.log。",
                ),
            )
        }
        cancel(preserveFailure = true)
    }

    private class Attempt(
        val bridge: SessionBridge,
        val request: File,
        val region: GameRegion,
        val build: Int,
        val log: File,
    ) {
        val ticket = CompletableDeferred<ByteArray>()
        val client = AtomicReference<BgsClient?>()
        val ticketBytes = AtomicReference<ByteArray?>()
        val device = AtomicReference<ByteArray?>()
        @Volatile var cancelled = false
        var job: Job? = null
        var watchdog: Job? = null
        fun close() {
            runCatching { client.getAndSet(null)?.close() }
            runCatching { bridge.close() }
            watchdog?.cancel()
            ticketBytes.getAndSet(null)?.fill(0)
            device.getAndSet(null)?.fill(0)
            request.delete()
        }
    }
}

@Composable
internal fun BattleNetBrowserLoginHost() {
    val state by BattleNetBrowserLogin.ui.collectAsStateWithLifecycle()
    when (val current = state) {
        BattleNetBrowserLogin.Ui.Idle -> Unit
        is BattleNetBrowserLogin.Ui.Browser -> BattleNetWebDialog(
            url = current.url,
            region = current.region,
            onTicket = { BattleNetBrowserLogin.submit(current, it) },
            onCancel = { BattleNetBrowserLogin.abort(current) },
            onError = { BattleNetBrowserLogin.webError(current, it) },
        )
        else -> AlertDialog(
            onDismissRequest = { BattleNetBrowserLogin.abort(current) },
            title = { Text(wowText("Battle.net browser login", "战网网页登录")) },
            text = { Text(when (current) {
                is BattleNetBrowserLogin.Ui.Waiting -> current.text
                is BattleNetBrowserLogin.Ui.Failed -> current.text
                else -> ""
            }) },
            confirmButton = {
                TextButton(onClick = { BattleNetBrowserLogin.abort(current) }) {
                    Text(if (current is BattleNetBrowserLogin.Ui.Failed) wowText("Close", "关闭") else wowText("Cancel", "取消"))
                }
            },
        )
    }
}
