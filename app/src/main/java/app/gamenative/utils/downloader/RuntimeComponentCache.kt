package app.gamenative.utils.downloader

import android.content.Context
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber

/** Makes APK-bundled runtime archives available to the existing file-based extractors. */
internal object RuntimeComponentCache {
    private val locks = ConcurrentHashMap<String, Mutex>()

    suspend fun ensureAvailable(
        context: Context,
        componentName: String,
        destination: File,
        onProgress: (Float) -> Unit,
        download: suspend () -> Unit,
    ): File = ensureAvailable(
        destination = destination,
        openBundled = {
            try {
                context.assets.open("bundled_components/$componentName")
            } catch (_: FileNotFoundException) {
                null
            }
        },
        onProgress = onProgress,
        download = download,
    )

    internal suspend fun ensureAvailable(
        destination: File,
        openBundled: () -> InputStream?,
        onProgress: (Float) -> Unit = {},
        download: suspend () -> Unit,
    ): File = locks.computeIfAbsent(destination.absolutePath) { Mutex() }.withLock {
        // Serialize the cache check, bundled copy, and network fallback together: preload and
        // foreground setup must not read or overwrite an archive while another caller writes it.
        if (destination.isFile && destination.length() > 0) return@withLock destination
        val parent = destination.parentFile ?: throw IOException("Missing runtime cache directory")
        if (!parent.isDirectory && !parent.mkdirs() && !parent.isDirectory) {
            throw IOException("Couldn't create runtime cache directory $parent")
        }

        try {
            val bundled = openBundled()
            if (bundled != null) {
                bundled.use { input ->
                    val temporary = File.createTempFile(destination.name + ".", ".bundled.part", parent)
                    try {
                        temporary.outputStream().use { output -> input.copyTo(output) }
                        if (temporary.length() == 0L) throw IOException("Empty bundled runtime archive ${destination.name}")
                        Files.move(
                            temporary.toPath(), destination.toPath(),
                            StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING,
                        )
                    } finally {
                        temporary.delete()
                    }
                }
                Timber.i("Using bundled runtime archive: ${destination.name}")
                onProgress(1f)
            } else {
                download()
            }
            if (!destination.isFile || destination.length() == 0L) {
                throw IOException("Runtime archive is missing or empty: ${destination.name}")
            }
            destination
        } catch (e: Exception) {
            destination.delete()
            throw e
        }
    }
}
