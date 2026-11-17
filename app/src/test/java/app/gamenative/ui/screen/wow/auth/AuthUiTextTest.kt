package app.gamenative.ui.screen.wow.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class AuthUiTextTest {
    @Test
    fun chinaEditionLocalizesKnownSignInFailures() {
        assertEquals(
            "战网连接已断开，请检查网络后重试。",
            bgsErrorText(BgsException("The Battle.net connection ended. Check your connection and try again."), chinaEdition = true),
        )
        assertEquals(
            "战网暂未接受此游戏版本，请稍后重试。",
            bgsErrorText(BgsException("Battle.net is not accepting this game build yet. Try again later.", 33), chinaEdition = true),
        )
    }

    @Test
    fun chinaEditionPreservesOnlyNumericStatusForAnUnknownFailure() {
        val privatePayload = "Unexpected provider payload with a private address"
        assertEquals("战网拒绝了此次登录（状态码 12）。", bgsErrorText(BgsException(privatePayload, 12), chinaEdition = true))
        val fallback = bgsErrorText(BgsException(privatePayload), chinaEdition = true)
        assertEquals("战网登录失败，请重试。", fallback)
        assertFalse(fallback.contains(privatePayload))
    }

    @Test
    fun normalEditionKeepsItsExistingEnglishErrorText() {
        val english = "Battle.net did not respond in time. Try again."
        assertEquals(english, bgsErrorText(BgsException(english), chinaEdition = false))
    }
}
