package app.gamenative.utils.downloader

import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RuntimeComponentCacheTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun bundledArchive_isPublishedBeforeNetworkAndReportsCompletion() = runBlocking {
        val destination = File(temporary.root, "cache/wrapper-gamenative-20260724.tzst")
        var progress = 0f

        val result = RuntimeComponentCache.ensureAvailable(
            destination,
            openBundled = { ByteArrayInputStream("bundled wrapper".toByteArray()) },
            onProgress = { progress = it },
            download = { error("Network must not be used when the APK includes this archive") },
        )

        assertEquals(destination, result)
        assertEquals("bundled wrapper", result.readText())
        assertEquals(1f, progress, 0f)
        assertEquals(listOf(destination.name), destination.parentFile.list()?.toList())
    }

    @Test
    fun existingCache_isPreferredWithoutOpeningAssetsOrNetwork() = runBlocking {
        val destination = temporary.newFile("cached.tzst").apply { writeText("cached archive") }
        RuntimeComponentCache.ensureAvailable(
            destination,
            openBundled = { error("Cached file should be reused") },
            download = { error("Cached file should be reused") },
        )
        assertEquals("cached archive", destination.readText())
    }

    @Test
    fun missingBundle_usesDownloadAndRejectsEmptyResult() {
        val destination = File(temporary.root, "download.tzst")
        runBlocking {
            RuntimeComponentCache.ensureAvailable(destination, openBundled = { null }) {
                destination.writeText("downloaded archive")
            }
        }
        assertEquals("downloaded archive", destination.readText())
        destination.delete()
        assertThrows(IOException::class.java) {
            runBlocking {
                RuntimeComponentCache.ensureAvailable(destination, openBundled = { null }) {
                    destination.createNewFile()
                }
            }
        }
        assertFalse(destination.exists())
    }

    @Test
    fun interruptedBundleCopy_doesNotLeaveAReusablePartialArchive() {
        val destination = File(temporary.root, "partial.tzst")
        assertThrows(IOException::class.java) {
            runBlocking {
                RuntimeComponentCache.ensureAvailable(
                    destination,
                    openBundled = {
                        object : InputStream() {
                            private var reads = 0
                            override fun read(): Int = if (reads++ < 5) 1 else throw IOException("Interrupted asset read")
                        }
                    },
                    download = { error("An unreadable packaged archive must not silently use a download") },
                )
            }
        }
        assertFalse(destination.exists())
        assertTrue(temporary.root.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun preloadAndForegroundRequest_shareOneCompletedDownload() = runBlocking {
        val destination = File(temporary.root, "shared.tzst")
        val enteredDownload = CompletableDeferred<Unit>()
        val foregroundStarted = CompletableDeferred<Unit>()
        val finishDownload = CompletableDeferred<Unit>()
        var downloads = 0
        suspend fun ensure() = RuntimeComponentCache.ensureAvailable(destination, openBundled = { null }) {
            downloads++
            enteredDownload.complete(Unit)
            finishDownload.await()
            destination.writeText("complete archive")
        }

        val preload = async { ensure() }
        enteredDownload.await()
        val foreground = async { foregroundStarted.complete(Unit); ensure() }
        foregroundStarted.await()
        assertFalse(destination.exists())
        finishDownload.complete(Unit)

        assertEquals(preload.await(), foreground.await())
        assertEquals(1, downloads)
        assertEquals("complete archive", destination.readText())
    }
}
