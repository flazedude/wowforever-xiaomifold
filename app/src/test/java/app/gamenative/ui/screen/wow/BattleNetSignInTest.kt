package app.gamenative.ui.screen.wow

import android.content.Context
import android.content.SharedPreferences
import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class BattleNetSignInTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private val context = mock<Context>()
    private val global = mock<SharedPreferences>()
    private val china = mock<SharedPreferences>()

    private fun configureStores() {
        whenever(context.getSharedPreferences("battle_net_login", Context.MODE_PRIVATE)).thenReturn(global)
        whenever(context.getSharedPreferences("battle_net_login_cn", Context.MODE_PRIVATE)).thenReturn(china)
    }

    @Test
    fun chinaLogin_doesNotReadExistingInternationalCredentials() {
        configureStores()
        whenever(global.getString("email", null)).thenReturn("global@example.invalid")
        whenever(global.getString("password", null)).thenReturn("encrypted-global-fixture")

        assertNull(BattleNetSignIn.load(context, GameRegion.CN))

        verify(china).getString("email", null)
        verifyNoInteractions(global)
    }

    @Test
    fun internationalLogin_doesNotReadChinaCredentials() {
        configureStores()
        whenever(china.getString("email", null)).thenReturn("china@example.invalid")
        whenever(china.getString("password", null)).thenReturn("encrypted-china-fixture")

        assertNull(BattleNetSignIn.load(context, GameRegion.EU))

        verify(global).getString("email", null)
        verifyNoInteractions(china)
    }

    @Test
    fun forgettingChina_clearsOnlyChinaStoreAndRemovesSharedLoginFiles() {
        configureStores()
        val editor = mock<SharedPreferences.Editor>()
        whenever(china.edit()).thenReturn(editor)
        whenever(editor.clear()).thenReturn(editor)
        val gameRoot = tempFolder.newFolder("game")
        val loginFiles = listOf(WowClientDownloader.FLAVOR_DIR, WowClientDownloader.LEGACY_FLAVOR_DIR).map { flavor ->
            File(gameRoot, "$flavor/login.txt").apply {
                parentFile.mkdirs()
                writeText("fixture-account\nfixture-password\n")
            }
        }

        BattleNetSignIn.forget(context, GameRegion.CN, gameRoot)

        verify(editor).clear()
        verify(editor).apply()
        verifyNoInteractions(global)
        loginFiles.forEach { assertFalse(it.exists()) }
    }

    @Test
    fun forgettingInternationalLogin_preservesChinaStore() {
        configureStores()
        val editor = mock<SharedPreferences.Editor>()
        whenever(global.edit()).thenReturn(editor)
        whenever(editor.clear()).thenReturn(editor)

        BattleNetSignIn.forget(context, GameRegion.US)

        verify(editor).clear()
        verify(editor).apply()
        verifyNoInteractions(china)
    }
}
