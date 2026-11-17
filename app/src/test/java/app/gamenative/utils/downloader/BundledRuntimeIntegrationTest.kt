package app.gamenative.utils.downloader

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.gamenative.utils.Net
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockkObject
import io.mockk.unmockkObject
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class BundledRuntimeIntegrationTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        ContainerFilesDownloader.clearCache(context)
        GraphicsDriverDownloader.clearCache(context)
        mockkObject(Net)
        coEvery { Net.fetchFileWithFallback(any(), any(), any()) } throws IOException("Offline test")
    }

    @After
    fun tearDown() {
        unmockkObject(Net)
        ContainerFilesDownloader.clearCache(context)
        GraphicsDriverDownloader.clearCache(context)
    }

    @Test
    fun commonContainerArchive_usesExactBundledManifestFilenameOffline() = runBlocking {
        val file = ContainerFilesDownloader.ensureContainerFileAvailable(context, "container_pattern_common")
        assertBundledCopy("container_pattern_common_20260821.tzst", file)
        coVerify(exactly = 0) { Net.fetchFileWithFallback(any(), any(), any()) }
    }

    @Test
    fun maliWrapperAndExtraLibraries_areAvailableOffline() = runBlocking {
        assertBundledCopy(
            "wrapper-gamenative-20260724.tzst",
            GraphicsDriverDownloader.ensureGraphicsDriverAvailable(context, "wrapper-gamenative"),
        )
        assertBundledCopy("extra_libs.tzst", GraphicsDriverDownloader.ensureGraphicsDriverAvailable(context, "extra_libs"))
        coVerify(exactly = 0) { Net.fetchFileWithFallback(any(), any(), any()) }
    }

    private fun assertBundledCopy(name: String, file: File?) {
        assertNotNull(file)
        assertEquals(name, file!!.name)
        assertEquals(
            context.assets.open("bundled_components/$name").use(::sha256),
            file.inputStream().use(::sha256),
        )
    }

    private fun sha256(input: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}
