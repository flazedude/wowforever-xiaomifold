package app.gamenative.ui.screen.wow.auth

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.http.SslError
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.SslErrorHandler
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.gamenative.ui.screen.wow.GameRegion
import app.gamenative.ui.screen.wow.wowText
import app.gamenative.BuildConfig
import java.io.ByteArrayInputStream
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Shows Battle.net's own page and reads only its final navigation address.
 * The ST redirect contract follows Scuffle's MIT-licensed browser login implementation.
 */
@SuppressLint("SetJavaScriptEnabled") // Battle.net's sign-in page requires JavaScript.
@Composable
internal fun BattleNetWebDialog(
    url: String,
    onTicket: (ByteArray) -> Unit,
    onCancel: () -> Unit,
    onError: (String) -> Unit,
    region: GameRegion? = null,
) {
    val ticketCallback by rememberUpdatedState(onTicket)
    val cancelCallback by rememberUpdatedState(onCancel)
    val errorCallback by rememberUpdatedState(onError)
    val completed = remember(url, region) { AtomicBoolean(false) }
    if (!BrowserAuthUrl.isInitialUrl(url, region)) {
        LaunchedEffect(url, region) {
            if (completed.compareAndSet(false, true)) errorCallback(wowText("Battle.net returned an unsupported sign-in address.", "战网返回了不受支持的登录地址。"))
        }
        return
    }

    val context = LocalContext.current
    var origin by remember(url, region) { mutableStateOf(BrowserAuthUrl.displayOrigin(url) ?: "Battle.net") }
    var canGoBack by remember(url, region) { mutableStateOf(false) }
    val webView = remember(url, region) {
        WebView.setWebContentsDebuggingEnabled(false)
        WebView(context).apply {
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                allowFileAccess = false
                allowContentAccess = false
                @Suppress("DEPRECATION")
                allowFileAccessFromFileURLs = false
                @Suppress("DEPRECATION")
                allowUniversalAccessFromFileURLs = false
                mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                setSupportMultipleWindows(false)
                javaScriptCanOpenWindowsAutomatically = false
                cacheMode = WebSettings.LOAD_NO_CACHE
                @Suppress("DEPRECATION")
                saveFormData = false
            }
            // NetEase's official SMS flow embeds the URS account widget across origins. This
            // cookie permission is scoped to this auth view and never enabled for global login.
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, region == GameRegion.CN)
            webChromeClient = object : WebChromeClient() {
                // The provider's own scripts log form/widget data to the JavaScript console.
                // Consume those messages so a debug APK cannot echo them into Android logs.
                override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean = true
            }
            webViewClient = object : WebViewClient() {
                private fun fail(view: WebView, message: String) {
                    view.stopLoading()
                    if (completed.compareAndSet(false, true)) errorCallback(message)
                }

                private fun navigate(view: WebView, address: String): Boolean {
                    if (completed.get()) return true
                    val ticket = BrowserAuthUrl.ticket(address)
                    if (ticket != null) {
                        view.stopLoading()
                        if (completed.compareAndSet(false, true)) ticketCallback(ticket) else ticket.fill(0)
                        return true
                    }
                    if (!BrowserAuthUrl.isNavigationUrl(address, region)) {
                        if (BuildConfig.DEBUG) {
                            val shape = BrowserAuthUrl.navigationShape(address)
                            Log.w("BrowserAuthNavigation", shape)
                            runCatching {
                                val directory = File(context.filesDir, "runtime_logs")
                                if (directory.isDirectory || directory.mkdirs()) {
                                    val log = File(directory, "browser_navigation.log")
                                    if (log.length() > 64 * 1024) log.writeText("")
                                    log.appendText("timestampMs=${System.currentTimeMillis()} $shape\n")
                                }
                            }
                        }
                        val rejectedOrigin = BrowserAuthUrl.displayOrigin(address)
                        val site = rejectedOrigin?.let { " ($it)" } ?: ""
                        fail(view, wowText(
                            "Sign-in tried to open an unsupported site$site. Cancel and try again.",
                            "登录页面尝试打开不受支持的网站$site。请取消后重试。",
                        ))
                        return true
                    }
                    origin = BrowserAuthUrl.displayOrigin(address) ?: "Battle.net"
                    canGoBack = view.canGoBack()
                    return false
                }

                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    if (!request.isForMainFrame) return !request.url.scheme.equals("https", ignoreCase = true)
                    return navigate(view, request.url.toString())
                }

                override fun onPageStarted(view: WebView, address: String, favicon: Bitmap?) {
                    // Covers navigations for which WebView does not invoke shouldOverrideUrlLoading (e.g. POST).
                    navigate(view, address)
                }

                override fun onPageFinished(view: WebView, address: String) {
                    canGoBack = view.canGoBack()
                }

                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                    if (request.isForMainFrame && !BrowserAuthUrl.isNavigationUrl(request.url.toString(), region)) {
                        // No network request is made to the localhost callback or another unapproved origin.
                        return WebResourceResponse("text/plain", "UTF-8", ByteArrayInputStream(ByteArray(0)))
                    }
                    return null
                }

                override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
                    handler.cancel()
                    fail(view, wowText("The sign-in page's secure connection could not be verified.", "无法验证登录页面的安全连接。"))
                }

                override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                    if (request.isForMainFrame && !completed.get()) fail(view, wowText("The sign-in page could not load. Check your connection and try again.", "无法加载登录页面，请检查网络后重试。"))
                }

                override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, errorResponse: WebResourceResponse) {
                    if (request.isForMainFrame && !completed.get()) fail(view, wowText("Battle.net could not complete this sign-in page. Try again later.", "战网无法完成此登录页面，请稍后重试。"))
                }
            }
            loadUrl(url)
        }
    }
    DisposableEffect(webView) {
        onDispose {
            completed.set(true)
            webView.stopLoading()
            CookieManager.getInstance().setAcceptThirdPartyCookies(webView, false)
            webView.webViewClient = WebViewClient()
            webView.removeAllViews()
            webView.destroy()
        }
    }
    val cancel = {
        if (completed.compareAndSet(false, true)) {
            webView.stopLoading()
            cancelCallback()
        }
    }
    val backOrCancel = {
        if (!completed.get() && webView.canGoBack()) webView.goBack() else cancel()
    }
    Dialog(onDismissRequest = backOrCancel, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column {
                Row(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Text(origin, modifier = Modifier.weight(1f))
                    if (canGoBack) {
                        TextButton(onClick = { webView.goBack() }) { Text(wowText("Back", "返回")) }
                    }
                    TextButton(onClick = cancel) { Text(wowText("Cancel", "取消")) }
                }
                AndroidView(factory = { webView }, modifier = Modifier.weight(1f).fillMaxSize())
            }
        }
    }
}
