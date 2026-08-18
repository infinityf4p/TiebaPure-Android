package dev.infinityf4p.tiebapure.core.media

import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.CacheControl
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

internal enum class RemoteMediaKind {
    Image,
    Emoticon,
    Video,
    Audio,
}

internal data class DownloadedTemporaryMedia(
    val lease: TemporaryMediaFileLease,
    val mimeType: String,
    val byteCount: Long,
)

internal class MediaDownloadException(message: String, cause: Throwable? = null) :
    IOException(message, cause)

internal class TemporaryMediaFileLease internal constructor(
    val file: File,
) : AutoCloseable {
    private val lock = Any()
    private var ownsFile = true

    fun release() {
        val shouldDelete = synchronized(lock) {
            if (!ownsFile) false else {
                ownsFile = false
                true
            }
        }
        if (shouldDelete) file.delete()
    }

    internal fun moveTo(destination: File): File {
        synchronized(lock) {
            check(ownsFile) { "Media file has already been released" }
            check(destination.parentFile?.canonicalFile == file.parentFile?.canonicalFile) {
                "Media cache transfer must remain in its private directory"
            }
            if (!file.renameTo(destination)) {
                file.copyTo(destination, overwrite = true)
                check(file.delete()) { "Unable to finalize media cache file" }
            }
            ownsFile = false
            return destination
        }
    }

    override fun close() = release()
}

internal class SecureMediaDownloader(
    private val directory: File,
    suppliedClient: OkHttpClient,
    private val maximumBytes: Long,
    private val kind: RemoteMediaKind,
) {
    init {
        require(maximumBytes > 0L)
    }

    private val client = suppliedClient.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(180, TimeUnit.SECONDS)
        .build()

    suspend fun download(
        sourceUrl: String,
        onProgress: (Float) -> Unit = {},
    ): DownloadedTemporaryMedia {
        val allowed = when (kind) {
            RemoteMediaKind.Image -> MediaUrlPolicy.isAllowed(sourceUrl)
            RemoteMediaKind.Emoticon -> MediaUrlPolicy.isAllowedTiebaEmoticon(sourceUrl)
            RemoteMediaKind.Video -> MediaUrlPolicy.isAllowedDownloadableVideo(sourceUrl)
            RemoteMediaKind.Audio -> VoiceAudioUrlPolicy.isAllowedSourceUrl(sourceUrl)
        }
        if (!allowed) throw MediaDownloadException("Unsupported media URL")

        val privateDirectory = preparePrivateDirectory(directory)
        removeExpiredTemporaryFiles(privateDirectory)
        val suffix = when (kind) {
            RemoteMediaKind.Image -> ".image"
            RemoteMediaKind.Emoticon -> ".png"
            RemoteMediaKind.Video -> ".mp4"
            RemoteMediaKind.Audio -> ".audio"
        }
        val temporary = File.createTempFile("TiebaPure-", suffix, privateDirectory)
        restrictToOwner(temporary)

        return suspendCancellableCoroutine { continuation ->
            val operation = MediaDownloadOperation(
                client = client,
                sourceUrl = sourceUrl,
                temporary = temporary,
                maximumBytes = maximumBytes,
                kind = kind,
                onProgress = onProgress,
                continuation = continuation,
            )
            continuation.invokeOnCancellation { operation.cancel() }
            operation.start()
        }
    }
}

private class MediaDownloadOperation(
    private val client: OkHttpClient,
    private val sourceUrl: String,
    private val temporary: File,
    private val maximumBytes: Long,
    private val kind: RemoteMediaKind,
    private val onProgress: (Float) -> Unit,
    private val continuation: CancellableContinuation<DownloadedTemporaryMedia>,
) {
    private val lock = Any()

    @Volatile
    private var terminal = false
    private var activeCall: Call? = null

    fun start() {
        reportProgress(0f)
        enqueue(sourceUrl, redirectCount = 0)
    }

    fun cancel() {
        val call = synchronized(lock) {
            if (terminal) return
            terminal = true
            activeCall.also { activeCall = null }
        }
        call?.cancel()
        temporary.delete()
    }

    private fun enqueue(url: String, redirectCount: Int) {
        val request = Request.Builder()
            .url(url)
            .get()
            .cacheControl(CacheControl.Builder().noCache().noStore().build())
            .header("User-Agent", "tieba/12.52.1.0")
            .header("Referer", "https://tieba.baidu.com/")
            .header(
                "Accept",
                when (kind) {
                    RemoteMediaKind.Audio -> "audio/*, application/octet-stream"
                    RemoteMediaKind.Emoticon -> "image/png,image/*;q=0.8"
                    RemoteMediaKind.Image, RemoteMediaKind.Video -> "*/*"
                },
            )
            .build()
        val call = client.newCall(request)
        synchronized(lock) {
            if (terminal) return
            activeCall = call
        }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                fail(if (terminal) MediaDownloadException("Media download cancelled", e) else e)
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    try {
                        if (response.code in 300..399) {
                            if (redirectCount >= MAXIMUM_REDIRECTS) {
                                throw MediaDownloadException("Too many media redirects")
                            }
                            val location = response.header("Location")
                                ?: throw MediaDownloadException("Media redirect had no destination")
                            val redirected = when (kind) {
                                RemoteMediaKind.Image -> MediaUrlPolicy.resolveRedirect(url, location)
                                RemoteMediaKind.Emoticon -> MediaUrlPolicy.resolveEmoticonRedirect(url, location)
                                RemoteMediaKind.Video -> MediaUrlPolicy.resolveVideoRedirect(url, location)
                                RemoteMediaKind.Audio -> VoiceAudioUrlPolicy.resolveRedirect(url, location)
                            } ?: throw MediaDownloadException("Media redirect left the trusted boundary")
                            clearActiveCall(call)
                            enqueue(redirected, redirectCount + 1)
                            return
                        }

                        val downloaded = copyResponse(response)
                        succeed(downloaded)
                    } catch (error: Throwable) {
                        fail(error)
                    }
                }
            }
        })
    }

    private fun copyResponse(response: Response): DownloadedTemporaryMedia {
        if (response.code != 200 || response.header("Content-Range") != null) {
            throw MediaDownloadException("Media request failed (${response.code})")
        }
        val body = response.body
        val mediaType = body.contentType()
            ?: throw MediaDownloadException("Media response had no content type")
        val mimeType = "${mediaType.type}/${mediaType.subtype}".lowercase(Locale.ROOT)
        if (!acceptsMimeType(mimeType, kind)) {
            throw MediaDownloadException("Media response had an unsupported content type")
        }
        val declaredBytes = body.contentLength()
        if (declaredBytes > maximumBytes) {
            throw MediaDownloadException("Media response is too large")
        }

        var copiedBytes = 0L
        var lastReportedPercent = -1
        body.byteStream().use { input ->
            FileOutputStream(temporary, false).use { fileOutput ->
                BufferedOutputStream(fileOutput).use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        if (terminal) throw MediaDownloadException("Media download cancelled")
                        val count = input.read(buffer)
                        if (count < 0) break
                        copiedBytes += count
                        if (copiedBytes > maximumBytes) {
                            throw MediaDownloadException("Media response is too large")
                        }
                        output.write(buffer, 0, count)
                        if (declaredBytes > 0L) {
                            val percent = ((copiedBytes * 100L) / declaredBytes).toInt().coerceIn(0, 100)
                            if (percent != lastReportedPercent) {
                                lastReportedPercent = percent
                                reportProgress(percent / 100f)
                            }
                        }
                    }
                    output.flush()
                }
            }
        }
        if (copiedBytes == 0L) throw MediaDownloadException("Media response was empty")
        if (!MediaFileSignatures.matches(temporary, mimeType, kind)) {
            throw MediaDownloadException("Media response did not match its declared type")
        }
        reportProgress(1f)
        return DownloadedTemporaryMedia(
            lease = TemporaryMediaFileLease(temporary),
            mimeType = mimeType,
            byteCount = copiedBytes,
        )
    }

    private fun succeed(downloaded: DownloadedTemporaryMedia) {
        val shouldResume = synchronized(lock) {
            if (terminal) false else {
                terminal = true
                activeCall = null
                true
            }
        }
        if (!shouldResume) {
            downloaded.lease.release()
            return
        }
        continuation.resume(downloaded) { _, value, _ -> value.lease.release() }
    }

    private fun fail(error: Throwable) {
        val shouldResume = synchronized(lock) {
            if (terminal) false else {
                terminal = true
                activeCall = null
                true
            }
        }
        temporary.delete()
        if (shouldResume) continuation.resumeWith(Result.failure(error))
    }

    private fun clearActiveCall(call: Call) {
        synchronized(lock) {
            if (activeCall === call) activeCall = null
        }
    }

    private fun reportProgress(progress: Float) {
        runCatching { onProgress(progress.coerceIn(0f, 1f)) }
    }

    private companion object {
        const val MAXIMUM_REDIRECTS = 5
    }
}

private fun acceptsMimeType(mimeType: String, kind: RemoteMediaKind): Boolean = when (kind) {
    RemoteMediaKind.Image -> mimeType in MediaFileSignatures.imageMimeTypes
    RemoteMediaKind.Emoticon -> mimeType == "image/png"
    RemoteMediaKind.Video -> mimeType in setOf("video/mp4", "video/x-m4v", "application/mp4")
    RemoteMediaKind.Audio -> VoiceAudioUrlPolicy.isAllowedMimeType(mimeType)
}

internal object MediaFileSignatures {
    val imageMimeTypes = setOf(
        "image/jpeg",
        "image/png",
        "image/gif",
        "image/webp",
        "image/bmp",
        "image/avif",
        "image/heic",
        "image/heif",
    )

    fun isSupportedImage(file: File): Boolean = imageMimeTypes.any { matches(file, it, RemoteMediaKind.Image) }

    fun matches(file: File, mimeType: String, kind: RemoteMediaKind): Boolean {
        val header = ByteArray(32)
        val count = runCatching { file.inputStream().use { it.read(header) } }.getOrDefault(-1)
        if (count <= 0) return false
        return when (kind) {
            RemoteMediaKind.Video -> hasFtyp(header, count)
            RemoteMediaKind.Audio -> true
            RemoteMediaKind.Emoticon -> count >= PNG.size &&
                header.copyOfRange(0, PNG.size).contentEquals(PNG)
            RemoteMediaKind.Image -> when (mimeType) {
                "image/jpeg" -> count >= 3 && header[0] == 0xff.toByte() && header[1] == 0xd8.toByte() && header[2] == 0xff.toByte()
                "image/png" -> count >= PNG.size && header.copyOfRange(0, PNG.size).contentEquals(PNG)
                "image/gif" -> count >= 6 && String(header, 0, 6, Charsets.US_ASCII) in setOf("GIF87a", "GIF89a")
                "image/webp" -> count >= 12 && ascii(header, 0, 4) == "RIFF" && ascii(header, 8, 4) == "WEBP"
                "image/bmp" -> count >= 2 && ascii(header, 0, 2) == "BM"
                "image/avif" -> hasFtypBrand(header, count, setOf("avif", "avis"))
                "image/heic", "image/heif" -> hasFtypBrand(header, count, setOf("heic", "heix", "hevc", "hevx", "heim", "heis", "mif1", "msf1"))
                else -> false
            }
        }
    }

    private fun hasFtyp(header: ByteArray, count: Int): Boolean = count >= 12 && ascii(header, 4, 4) == "ftyp"

    private fun hasFtypBrand(header: ByteArray, count: Int, allowedBrands: Set<String>): Boolean {
        if (!hasFtyp(header, count)) return false
        if (ascii(header, 8, 4) in allowedBrands) return true
        var offset = 16
        while (offset + 4 <= count) {
            if (ascii(header, offset, 4) in allowedBrands) return true
            offset += 4
        }
        return false
    }

    private fun ascii(bytes: ByteArray, offset: Int, length: Int): String =
        String(bytes, offset, length, Charsets.US_ASCII)

    private val PNG = byteArrayOf(
        0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a,
    )
}

private fun preparePrivateDirectory(directory: File): File {
    check(directory.isDirectory || directory.mkdirs()) { "Unable to prepare private media directory" }
    val canonical = directory.canonicalFile
    check(canonical.isDirectory) { "Private media directory is unavailable" }
    return canonical
}

private fun restrictToOwner(file: File) {
    file.setReadable(false, false)
    file.setWritable(false, false)
    file.setExecutable(false, false)
    check(file.setReadable(true, true) && file.setWritable(true, true)) {
        "Unable to restrict temporary media file"
    }
}

private fun removeExpiredTemporaryFiles(directory: File) {
    val cutoff = System.currentTimeMillis() - TimeUnit.HOURS.toMillis(24)
    directory.listFiles().orEmpty()
        .filter { it.isFile && it.name.startsWith("TiebaPure-") && it.lastModified() < cutoff }
        .forEach(File::delete)
}
