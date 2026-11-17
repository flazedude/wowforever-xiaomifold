package app.gamenative.ui.screen.wow.auth

import app.gamenative.ui.screen.wow.GameRegion
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Locale

/** URL boundary for Battle.net's browser challenge. Never include a rejected URL in an error. */
internal object BrowserAuthUrl {
    private const val MAX_URL_LENGTH = 16_384
    private const val MAX_TICKET_LENGTH = 2_048
    private const val MAX_CALLBACK_PARAMETERS = 4
    private val callbackParameterName = Regex("[A-Za-z_][A-Za-z0-9_.-]{0,31}")
    private val globalNavigationDomains = listOf("battle.net", "blizzard.com")
    private val chinaNavigationDomains = listOf("battlenet.com.cn", "blizzard.cn")
    // Observed in the official account.battlenet.com.cn -> NetEase OAuth flow and its
    // published SMS/account-verification links. Do not allow every site under 163.com.
    private val netEaseNavigationHosts = setOf(
        "oauth.g.mkey.163.com",
        "id.163.com",
        "zc.reg.163.com",
        "mima.163.com",
        "mima.ds.163.com",
        "hc.reg.163.com",
    )
    private val diagnosticSchemes = setOf(
        "http", "https", "about", "intent", "javascript", "data", "blob", "file", "content",
        "battlenet", "blizzard", "blizzard-auth", "bnet", "blz", "wow", "netease", "ntes", "mkey", "urs",
    )
    private val diagnosticHosts = netEaseNavigationHosts + setOf(
        "localhost", "battle.net", "us.battle.net", "eu.battle.net", "kr.battle.net", "tw.battle.net", "cn.battle.net",
        "account.blizzard.com", "blizzard.com", "battlenet.com.cn", "www.battlenet.com.cn", "account.battlenet.com.cn",
        "cn.battlenet.com.cn", "blizzard.cn", "wow.blizzard.cn",
    )
    private val diagnosticQueryKeys = setOf(
        "st", "ticket", "loginticket", "login_ticket", "sessionticket", "session_ticket", "sessiontoken", "session_token",
        "token", "code", "authcode", "authorization_code", "state", "error", "error_description", "app", "scope",
        "scheme", "ref", "fromapp", "scene", "account", "email", "phone", "mobile", "username", "password",
        "id_token", "access_token", "response_type", "redirect_uri", "returnurl", "redirecturl", "client_id",
    )

    fun isInitialUrl(value: String, region: GameRegion? = null): Boolean {
        val uri = parse(value) ?: return false
        if (uri.rawFragment != null) return false
        val domain = if (region == GameRegion.CN) "battlenet.com.cn" else "battle.net"
        return httpsHost(value)?.let { belongsTo(it, domain) } == true
    }

    fun isNavigationUrl(value: String, region: GameRegion? = null): Boolean = httpsHost(value)?.let { host ->
        if (region == GameRegion.CN) {
            chinaNavigationDomains.any { belongsTo(host, it) } || host in netEaseNavigationHosts
        } else {
            globalNavigationDomains.any { belongsTo(host, it) }
        }
    } == true

    /** Only the main-frame callback may call this. Other URLs, including lookalike hosts, return null. */
    fun ticket(value: String): ByteArray? {
        val uri = parse(value) ?: return null
        if (!uri.scheme.equals("http", ignoreCase = true) || !uri.host.equals("localhost", ignoreCase = true) ||
            uri.port != 0 || uri.rawUserInfo != null || uri.rawFragment != null || uri.rawPath !in listOf("", "/")
        ) return null
        val query = uri.rawQuery ?: return null
        if (';' in query) return null
        val parameters = query.split('&')
        if (parameters.size > MAX_CALLBACK_PARAMETERS) return null
        val names = mutableSetOf<String>()
        var rawTicket: String? = null
        for (parameter in parameters) {
            val name = parameter.substringBefore('=')
            // NetEase adds an empty completion flag beside ST. Accept only bounded literal
            // identifiers and empty metadata, never another credential or redirect value.
            if (!callbackParameterName.matches(name) || !names.add(name.lowercase(Locale.ROOT))) return null
            val rawValue = parameter.substringAfter('=', "")
            if (name.equals("ST", ignoreCase = true)) {
                if (name != "ST" || '=' !in parameter) return null
                rawTicket = rawValue
            } else if (rawValue.isNotEmpty()) {
                return null
            }
        }
        val encodedTicket = rawTicket ?: return null
        val decoded = try {
            URLDecoder.decode(encodedTicket, StandardCharsets.UTF_8.name())
        } catch (_: IllegalArgumentException) {
            return null
        }
        if (decoded.isEmpty() || decoded.length > MAX_TICKET_LENGTH || decoded.any { it.code !in 33..126 }) return null
        return decoded.toByteArray(StandardCharsets.US_ASCII)
    }

    /** The dialog shows the site's origin only. The path and query can contain sign-in credentials. */
    fun displayOrigin(value: String): String? = httpsHost(value)?.let { "https://$it" }

    /** Debug metadata only: never emit a path, query value, fragment, unknown hostname, or arbitrary key. */
    fun navigationShape(value: String): String {
        if (value.length > MAX_URL_LENGTH) return "uri=oversized length=${value.length}"
        val uri = try { URI(value) } catch (_: Exception) {
            return "uri=invalid length=${value.length}"
        }
        val rawScheme = uri.scheme.orEmpty().lowercase(Locale.ROOT)
        val scheme = if (rawScheme in diagnosticSchemes) rawScheme else "other(length=${rawScheme.length})"
        val rawHost = uri.host?.lowercase(Locale.ROOT)
        // Even a subdomain under a provider's domain might contain a per-session identifier.
        val trustedHost = rawHost in diagnosticHosts
        val host = if (trustedHost) rawHost else if (rawHost == null) "none" else "redacted"
        val path = uri.rawPath.orEmpty()
        val query = uri.rawQuery.orEmpty()
        val pairs = if (query.isEmpty()) emptyList() else query.split('&')
        val keys = pairs.take(12).joinToString(",") { pair ->
            val rawKey = pair.substringBefore('=')
            val decodedKey = try {
                URLDecoder.decode(rawKey, StandardCharsets.UTF_8.name())
            } catch (_: IllegalArgumentException) { "" }
            val key = if (decodedKey.lowercase(Locale.ROOT) in diagnosticQueryKeys) decodedKey else "redacted(length=${rawKey.length})"
            val valueLength = if ('=' in pair) pair.substringAfter('=').length else 0
            "$key(valueLength=$valueLength,encodedKey=${rawKey != decodedKey})"
        }
        return "scheme=$scheme host=$host port=${uri.port} opaque=${uri.isOpaque} opaquePartLength=${if (uri.isOpaque) uri.rawSchemeSpecificPart.length else 0} " +
            "pathRoot=${path.isEmpty() || path == "/"} pathLength=${path.length} userInfo=${uri.rawUserInfo != null} " +
            "queryKeys=[$keys] queryCount=${pairs.size} queryLength=${query.length} querySemicolon=${';' in query} " +
            "fragmentPresent=${uri.rawFragment != null} fragmentLength=${uri.rawFragment?.length ?: 0}"
    }

    private fun httpsHost(value: String): String? {
        val uri = parse(value) ?: return null
        if (!uri.scheme.equals("https", ignoreCase = true) || uri.rawUserInfo != null || uri.port !in listOf(-1, 443)) return null
        return uri.host?.lowercase(Locale.ROOT)
    }

    private fun belongsTo(host: String, domain: String): Boolean = host == domain || host.endsWith(".$domain")

    private fun parse(value: String): URI? {
        if (value.length > MAX_URL_LENGTH || value.any { it.code <= 32 || it.code >= 127 }) return null
        return try {
            URI(value).takeUnless { it.isOpaque }
        } catch (_: Exception) {
            null
        }
    }
}
