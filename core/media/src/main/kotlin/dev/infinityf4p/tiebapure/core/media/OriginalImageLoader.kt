package dev.infinityf4p.tiebapure.core.media

import android.content.Context
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient

class OriginalImageLoader internal constructor(
    context: Context,
    client: OkHttpClient,
) {
    constructor(context: Context) : this(context, OkHttpClient())

    private val cacheDirectory = File(context.cacheDir, "trusted-images")
    private val downloader = SecureMediaDownloader(
        directory = cacheDirectory,
        suppliedClient = client,
        maximumBytes = MAXIMUM_IMAGE_BYTES,
        kind = RemoteMediaKind.Image,
    )

    init {
        removeStaleTemporaryFiles(cacheDirectory)
    }

    suspend fun load(
        url: String,
        onProgress: (Float) -> Unit = {},
    ): File = withContext(Dispatchers.IO) {
        OfflineMediaPolicy.resolve(url)?.let { local ->
            require(MediaFileSignatures.isSupportedImage(local)) { "Unsupported local image" }
            onProgress(1f)
            return@withContext local
        }
        require(MediaUrlPolicy.isAllowed(url)) { "Unsupported media URL" }
        val destination = File(cacheDirectory, url.sha256())
        if (destination.isFile &&
            destination.length() in 1..MAXIMUM_IMAGE_BYTES &&
            MediaFileSignatures.isSupportedImage(destination)
        ) {
            destination.setLastModified(System.currentTimeMillis())
            onProgress(1f)
            return@withContext destination
        }
        destination.delete()

        val downloaded = downloader.download(url, onProgress)
        try {
            val committed = downloaded.lease.moveTo(destination)
            pruneMediaCache(
                directory = cacheDirectory,
                maximumBytes = MAXIMUM_IMAGE_CACHE_BYTES,
                preserving = committed,
            )
            committed
        } catch (error: Throwable) {
            downloaded.lease.release()
            throw error
        }
    }

    private companion object {
        const val MAXIMUM_IMAGE_BYTES = 96L * 1_024 * 1_024
        const val MAXIMUM_IMAGE_CACHE_BYTES = 256L * 1_024 * 1_024
    }
}

/**
 * A validated private file for an explicit user-requested image save.
 * Copy it to MediaStore and always call [release] (or use [close]) afterwards.
 */
class DownloadedImageLease internal constructor(
    val file: File,
    val mimeType: String,
    val byteCount: Long,
    private val lease: TemporaryMediaFileLease,
) : AutoCloseable {
    fun release() = lease.release()

    override fun close() = release()
}

/** Fetches an image without exposing redirects or untrusted response bytes to DownloadManager. */
class SecureImageDownloadClient internal constructor(
    context: Context,
    client: OkHttpClient,
) {
    constructor(context: Context) : this(context, OkHttpClient())

    private val directory = File(context.cacheDir, "image-exports")
    private val downloader = SecureMediaDownloader(
        directory = directory,
        suppliedClient = client,
        maximumBytes = MAXIMUM_IMAGE_BYTES,
        kind = RemoteMediaKind.Image,
    )

    init {
        removeStaleTemporaryFiles(directory)
    }

    suspend fun download(
        url: String,
        onProgress: (Float) -> Unit = {},
    ): DownloadedImageLease {
        OfflineMediaPolicy.resolve(url)?.let { local ->
            val mimeType = MediaFileSignatures.imageMimeTypes.firstOrNull {
                MediaFileSignatures.matches(local, it, RemoteMediaKind.Image)
            } ?: throw IllegalArgumentException("Unsupported local image")
            val temporary = File.createTempFile("TiebaPure-", ".image", directory.apply { mkdirs() })
            local.copyTo(temporary, overwrite = true)
            onProgress(1f)
            return DownloadedImageLease(
                file = temporary,
                mimeType = mimeType,
                byteCount = temporary.length(),
                lease = TemporaryMediaFileLease(temporary),
            )
        }
        val downloaded = downloader.download(url, onProgress)
        return DownloadedImageLease(
            file = downloaded.lease.file,
            mimeType = downloaded.mimeType,
            byteCount = downloaded.byteCount,
            lease = downloaded.lease,
        )
    }

    private companion object {
        const val MAXIMUM_IMAGE_BYTES = 96L * 1_024 * 1_024
    }
}

private fun pruneMediaCache(directory: File, maximumBytes: Long, preserving: File) {
    val cached = directory.listFiles()
        .orEmpty()
        .filter { it.isFile && it != preserving && !it.name.startsWith("TiebaPure-") }
        .sortedBy(File::lastModified)
    var totalBytes = directory.listFiles().orEmpty().filter(File::isFile).sumOf(File::length)
    for (candidate in cached) {
        if (totalBytes <= maximumBytes) break
        val size = candidate.length()
        if (candidate.delete()) totalBytes -= size
    }
}

private fun removeStaleTemporaryFiles(directory: File) {
    if (!directory.isDirectory && !directory.mkdirs()) return
    val cutoff = System.currentTimeMillis() - TimeUnit.HOURS.toMillis(24)
    directory.listFiles().orEmpty()
        .filter { it.isFile && it.name.startsWith("TiebaPure-") && it.lastModified() < cutoff }
        .forEach(File::delete)
}

private fun String.sha256(): String = MessageDigest.getInstance("SHA-256")
    .digest(toByteArray())
    .joinToString("") { byte -> "%02x".format(byte) }
