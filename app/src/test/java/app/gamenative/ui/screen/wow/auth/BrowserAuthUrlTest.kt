package app.gamenative.ui.screen.wow.auth

import app.gamenative.ui.screen.wow.GameRegion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserAuthUrlTest {
    @Test
    fun acceptsOnlyBattleNetHttpsChallenges() {
        assertTrue(BrowserAuthUrl.isInitialUrl("https://eu.battle.net/login/en/?app=wow"))
        assertTrue(BrowserAuthUrl.isInitialUrl("https://BATTLE.NET:443/login"))
        listOf(
            "http://eu.battle.net/login",
            "https://battle.net.evil.example/login",
            "https://evilbattle.net/login",
            "https://battle.net@evil.example/login",
            "https://user@battle.net/login",
            "https://battle.net:444/login",
            "https://battle.net./login",
            "https://battle.net\\@evil.example/login",
            "javascript:alert(1)",
            "https://blizzard.com/login",
            "https://battle.net/login\n",
            "https://battle.net/" + "x".repeat(16_384),
        ).forEach { assertFalse(it, BrowserAuthUrl.isInitialUrl(it)) }
    }

    @Test
    fun acceptsBlizzardAuthNavigationWithoutDomainConfusion() {
        assertTrue(BrowserAuthUrl.isNavigationUrl("https://account.blizzard.com/login"))
        assertTrue(BrowserAuthUrl.isNavigationUrl("https://account.battlenet.com.cn/login", GameRegion.CN))
        assertFalse(BrowserAuthUrl.isNavigationUrl("https://account.blizzard.com.evil.example/login"))
        assertFalse(BrowserAuthUrl.isNavigationUrl("file:///storage/emulated/0/login.txt"))
        assertFalse(BrowserAuthUrl.isNavigationUrl("intent://login"))
    }

    @Test
    fun chinaChallenge_requiresOfficialChinaBattleNetOrigin() {
        assertTrue(BrowserAuthUrl.isInitialUrl("https://account.battlenet.com.cn/login/zh/?app=wow", GameRegion.CN))
        assertTrue(BrowserAuthUrl.isInitialUrl("https://CN.BATTLENET.COM.CN:443/login/zh/", GameRegion.CN))
        listOf(
            "https://us.battle.net/login/zh/",
            "https://oauth.g.mkey.163.com/oauth-front/",
            "https://account.battlenet.com.cn.evil.example/login",
            "https://battlenet.com.cn@evil.example/login",
            "https://user@account.battlenet.com.cn/login",
            "http://account.battlenet.com.cn/login",
            "https://account.battlenet.com.cn:444/login",
            "https://account.battlenet.com.cn/login#callback",
        ).forEach { assertFalse(it, BrowserAuthUrl.isInitialUrl(it, GameRegion.CN)) }
        assertFalse(BrowserAuthUrl.isInitialUrl("https://account.battlenet.com.cn/login", GameRegion.US))
    }

    @Test
    fun chinaNavigation_supportsObservedNetEaseSmsAndAccountVerificationSites() {
        listOf(
            "https://account.battlenet.com.cn/login/zh/",
            "https://oauth.g.mkey.163.com/oauth/oauth_page",
            "https://oauth.g.mkey.163.com/oauth-front/#/login",
            "https://id.163.com/mail/safeMobile.html",
            "https://zc.reg.163.com/regInitialized#/",
            "https://mima.163.com/main.html",
            "https://mima.ds.163.com/account-repair",
            "https://hc.reg.163.com/webcomponentH5/doc.html",
            "https://wow.blizzard.cn/account-link/",
        ).forEach { assertTrue(it, BrowserAuthUrl.isNavigationUrl(it, GameRegion.CN)) }
    }

    @Test
    fun chinaNavigation_rejectsProviderLookalikesAndUnobservedExternalHosts() {
        listOf(
            "https://oauth.g.mkey.163.com.evil.example/login",
            "https://evil-oauth.g.mkey.163.com/login",
            "https://163.com/login",
            "https://mail.163.com/login",
            "https://example.netease.com/login",
            "https://us.battle.net/login/",
            "https://user:secret@oauth.g.mkey.163.com/login",
            "https://oauth.g.mkey.163.com:444/login",
            "http://oauth.g.mkey.163.com/login",
            "intent://oauth.g.mkey.163.com/login",
        ).forEach { assertFalse(it, BrowserAuthUrl.isNavigationUrl(it, GameRegion.CN)) }
        assertFalse(BrowserAuthUrl.isNavigationUrl("https://oauth.g.mkey.163.com/oauth-front/", GameRegion.US))
    }

    @Test
    fun capturesExactRedirectAndDecodesTicketOnce() {
        assertEquals("EU-ABC-123", BrowserAuthUrl.ticket("http://localhost:0/?ST=EU-ABC-123")?.decodeToString())
        assertEquals("token+value", BrowserAuthUrl.ticket("http://localhost:0/?ST=token%2Bvalue")?.decodeToString())
        assertEquals("%20", BrowserAuthUrl.ticket("http://localhost:0/?ST=%2520")?.decodeToString())
    }

    @Test
    fun chinaCallback_acceptsTheObservedTicketAndEmptyCompletionFlagShape() {
        val ticket = "CN-" + "A".repeat(42)
        assertEquals(45, ticket.length)
        val url = "http://localhost:0/?ST=$ticket&completionFlag="
        assertEquals(64, java.net.URI(url).rawQuery.length)
        assertEquals(ticket, BrowserAuthUrl.ticket(url)?.decodeToString())
        assertEquals(ticket, BrowserAuthUrl.ticket("http://localhost:0/?completionFlag&ST=$ticket")?.decodeToString())
        assertEquals(ticket, BrowserAuthUrl.ticket("http://localhost:0/?ST=$ticket&complete=&ready=&verified")?.decodeToString())
        assertNull(BrowserAuthUrl.ticket("http://localhost:0/?completionFlag="))
    }

    @Test
    fun callbackMetadata_cannotIntroduceAnotherTicketOrNonemptyValues() {
        listOf(
            "ST=good&ST=other",
            "ST=good&ST=",
            "ST=good&st=",
            "st=good&completionFlag=",
            "%53T=good&completionFlag=",
            "ST=good&%53T=",
            "ST=good&completionFlag=1",
            "ST=good&redirect_uri=https%3A%2F%2Fevil.example",
            "ST=good&completionFlag=%20",
            "ST=good&completionFlag=&completionFlag=",
            "ST=good&completion%46lag=",
            "ST=good&=",
            "ST=good&&completionFlag=",
            "ST=good&first=&second=&third=&fourth=",
            "ST=good&${"x".repeat(33)}=",
        ).forEach { assertNull(it, BrowserAuthUrl.ticket("http://localhost:0/?$it")) }
    }

    @Test
    fun rejectsRedirectLookalikesAndAmbiguousParameters() {
        listOf(
            "https://localhost:0/?ST=x",
            "http://localhost:1/?ST=x",
            "http://127.0.0.1:0/?ST=x",
            "http://localhost.evil.example:0/?ST=x",
            "http://localhost:0.evil.example/?ST=x",
            "http://user@localhost:0/?ST=x",
            "http://localhost:0/other?ST=x",
            "http://localhost:0/?ST=x#fragment",
            "http://localhost:0/?ST=x&ST=y",
            "http://localhost:0/?ST=x&other=y",
            "http://localhost:0/?ST=x;ST=y",
            "http://localhost:0/?%53T=x",
            "http://localhost:0/?st=x",
            "http://localhost:0/?ST=",
        ).forEach { assertNull(it, BrowserAuthUrl.ticket(it)) }
    }

    @Test
    fun rejectsNonAsciiWhitespaceAndOversizedTickets() {
        listOf("a+b", "a%20b", "%00", "%0A", "%7F", "%C3%A9", "%GG", "x".repeat(2049)).forEach {
            assertNull(it.take(32), BrowserAuthUrl.ticket("http://localhost:0/?ST=$it"))
        }
        assertEquals(2048, BrowserAuthUrl.ticket("http://localhost:0/?ST=${"x".repeat(2048)}")?.size)
    }

    @Test
    fun displayOriginDoesNotExposePathOrQuerySecrets() {
        assertEquals("https://eu.battle.net", BrowserAuthUrl.displayOrigin("https://eu.battle.net/path-secret?ST=query-secret#fragment-secret"))
        assertNull(BrowserAuthUrl.displayOrigin("http://localhost:0/?ST=secret"))
        assertNull(BrowserAuthUrl.displayOrigin("https://user:secret@battle.net/"))
    }

    @Test
    fun rejectedCallbackDiagnostic_reportsOnlyKeysAndValueLengths() {
        val url = "http://localhost/?ST=private-ticket&email=person%40example.com&code=123456#private-fragment"
        val shape = BrowserAuthUrl.navigationShape(url)
        assertTrue(shape, shape.contains("scheme=http host=localhost port=-1"))
        assertTrue(shape, shape.contains("pathRoot=true pathLength=1"))
        assertTrue(shape, shape.contains("ST(valueLength=14"))
        assertTrue(shape, shape.contains("email(valueLength=20"))
        assertTrue(shape, shape.contains("code(valueLength=6"))
        assertTrue(shape, shape.contains("fragmentPresent=true fragmentLength=16"))
        listOf("private-ticket", "person", "example.com", "123456", "private-fragment").forEach {
            assertFalse(shape, shape.contains(it))
        }
        assertNull(BrowserAuthUrl.ticket(url))
    }

    @Test
    fun navigationDiagnostic_redactsUnknownHostPathUserInfoAndQueryKeys() {
        val shape = BrowserAuthUrl.navigationShape(
            "https://private-user:private-password@private-host.example/private-path?private-key=private-value&ST=private-ticket",
        )
        assertTrue(shape, shape.contains("host=redacted"))
        assertTrue(shape, shape.contains("userInfo=true"))
        assertTrue(shape, shape.contains("redacted(length=11)"))
        assertTrue(shape, shape.contains("ST(valueLength=14"))
        listOf("private-user", "private-password", "private-host", "private-path", "private-key", "private-value", "private-ticket").forEach {
            assertFalse(shape, shape.contains(it))
        }
        val providerSubdomain = BrowserAuthUrl.navigationShape("https://private-ticket.battlenet.com.cn/?ST=private-ticket")
        assertTrue(providerSubdomain.contains("host=redacted"))
        assertFalse(providerSubdomain.contains("private-ticket"))
    }

    @Test
    fun navigationDiagnostic_handlesOpaqueAndInvalidReturnsWithoutTheirContents() {
        val opaque = BrowserAuthUrl.navigationShape("about:private-ticket")
        assertTrue(opaque, opaque.contains("scheme=about"))
        assertTrue(opaque, opaque.contains("opaque=true"))
        assertTrue(opaque, opaque.contains("opaquePartLength=14"))
        assertFalse(opaque.contains("private-ticket"))
        val blank = BrowserAuthUrl.navigationShape("about:blank")
        assertTrue(blank.contains("scheme=about"))
        assertTrue(blank.contains("opaque=true opaquePartLength=5"))
        assertNull(BrowserAuthUrl.ticket("about:blank"))
        val unknownScheme = BrowserAuthUrl.navigationShape("private-scheme:private-ticket")
        assertTrue(unknownScheme, unknownScheme.contains("scheme=other(length=14)"))
        assertFalse(unknownScheme.contains("private-scheme"))
        assertFalse(unknownScheme.contains("private-ticket"))
        val invalid = BrowserAuthUrl.navigationShape("http://localhost/?ST=private-ticket%GG")
        assertTrue(invalid.startsWith("uri=invalid"))
        assertFalse(invalid.contains("private-ticket"))
        val oversized = BrowserAuthUrl.navigationShape("https://private-host/" + "private-ticket".repeat(2048))
        assertTrue(oversized.startsWith("uri=oversized"))
        assertFalse(oversized.contains("private-host"))
        assertFalse(oversized.contains("private-ticket"))
    }

    @Test
    fun navigationDiagnostic_marksEncodedKeyWithoutAcceptingAnAmbiguousCallback() {
        val url = "http://localhost:0/?%53T=private-ticket&ST=private-ticket"
        val shape = BrowserAuthUrl.navigationShape(url)
        assertTrue(shape, shape.contains("ST(valueLength=14,encodedKey=true)"))
        assertTrue(shape, shape.contains("queryCount=2"))
        assertFalse(shape.contains("private-ticket"))
        assertNull(BrowserAuthUrl.ticket(url))
    }
}
