package dev.infinityf4p.tiebapure.core.media

import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.clickable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import coil3.compose.AsyncImage
import dev.infinityf4p.tiebapure.core.model.TiebaEmoticon
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import okhttp3.OkHttpClient

@Composable
fun RemoteEmoticonImage(
    code: String,
    modifier: Modifier = Modifier,
    loadsAutomatically: Boolean = true,
    contentDescription: String? = null,
    onLoadFailed: () -> Unit = {},
) {
    val imageUrl = remember(code) {
        TiebaEmoticon.imageUrlFor(code)?.takeIf(MediaUrlPolicy::isAllowedTiebaEmoticon)
    }
    val label = remember(code) { TiebaEmoticon.displayName(code) }
    val context = LocalContext.current.applicationContext
    val loader = remember(context) { TiebaEmoticonImageLoader.get(context) }
    var imageFile by remember(imageUrl, loadsAutomatically) { mutableStateOf<File?>(null) }
    var manuallyAuthorized by remember(imageUrl, loadsAutomatically) { mutableStateOf(false) }
    val shouldLoad = loadsAutomatically || manuallyAuthorized

    LaunchedEffect(imageUrl, shouldLoad) {
        imageFile = null
        if (!shouldLoad || imageUrl == null) return@LaunchedEffect
        try {
            imageFile = loader.load(imageUrl)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            imageFile = null
            onLoadFailed()
        }
    }

    Box(
        modifier = modifier
            .then(
                if (!shouldLoad && imageUrl != null) {
                    Modifier.clickable { manuallyAuthorized = true }
                } else {
                    Modifier
                },
            )
            .semantics {
                this.contentDescription = contentDescription
                    ?: if (!shouldLoad && imageUrl != null) "加载表情$label" else "表情$label"
            },
        contentAlignment = Alignment.Center,
    ) {
        val resolved = imageFile
        if (resolved == null) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Clip,
            )
        } else {
            AsyncImage(
                model = resolved,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit,
                onError = {
                    imageFile = null
                    onLoadFailed()
                },
            )
        }
    }
}

private class TiebaEmoticonFileLoader private constructor(context: Context) {
    private val cacheDirectory = File(context.cacheDir, "tieba-emoticons")
    private val downloader = SecureMediaDownloader(
        directory = cacheDirectory,
        suppliedClient = OkHttpClient(),
        maximumBytes = MAXIMUM_ARTWORK_BYTES,
        kind = RemoteMediaKind.Emoticon,
    )
    private val downloadLocks = Array(8) { Mutex() }
    private val downloadPermits = Semaphore(4)

    suspend fun load(url: String): File {
        val lock = downloadLocks[(url.hashCode() and Int.MAX_VALUE) % downloadLocks.size]
        return lock.withLock {
            withContext(Dispatchers.IO) {
                require(MediaUrlPolicy.isAllowedTiebaEmoticon(url)) { "Unsupported emoticon URL" }
                val destination = File(cacheDirectory, "${url.sha256()}.png")
                if (destination.isFile &&
                    destination.length() in 1..MAXIMUM_ARTWORK_BYTES &&
                    isSafeTiebaEmoticonFile(destination)
                ) {
                    destination.setLastModified(System.currentTimeMillis())
                    return@withContext destination
                }
                destination.delete()

                val downloaded = downloadPermits.withPermit { downloader.download(url) }
                try {
                    check(isSafeTiebaEmoticonFile(downloaded.lease.file)) {
                        "Emoticon artwork dimensions are invalid"
                    }
                    val committed = downloaded.lease.moveTo(destination)
                    pruneCache(committed)
                    committed
                } catch (error: Throwable) {
                    downloaded.lease.release()
                    throw error
                }
            }
        }
    }

    private fun pruneCache(preserving: File) {
        val files = cacheDirectory.listFiles().orEmpty()
            .filter { it.isFile && !it.name.startsWith("TiebaPure-") }
        var totalBytes = files.sumOf(File::length)
        var entryCount = files.size
        files.filter { it != preserving }
            .sortedWith(compareBy<File>(File::lastModified).thenBy(File::getName))
            .forEach { candidate ->
                if (totalBytes <= MAXIMUM_CACHE_BYTES && entryCount <= MAXIMUM_CACHE_ENTRIES) return
                val bytes = candidate.length()
                if (candidate.delete()) {
                    totalBytes -= bytes
                    entryCount -= 1
                }
            }
    }

    companion object {
        const val MAXIMUM_ARTWORK_BYTES = 1L * 1_024 * 1_024
        const val MAXIMUM_CACHE_BYTES = 16L * 1_024 * 1_024
        const val MAXIMUM_CACHE_ENTRIES = 128

        @Volatile private var instance: TiebaEmoticonFileLoader? = null

        fun get(context: Context): TiebaEmoticonFileLoader = instance ?: synchronized(this) {
            instance ?: TiebaEmoticonFileLoader(context.applicationContext).also { instance = it }
        }
    }
}

internal fun isSafeTiebaEmoticonDimensions(width: Int, height: Int): Boolean {
    if (width !in 1..4_096 || height !in 1..4_096) return false
    return width.toLong() * height <= 16_777_216L
}

private fun isSafeTiebaEmoticonFile(file: File): Boolean {
    if (!MediaFileSignatures.matches(file, "image/png", RemoteMediaKind.Emoticon)) return false
    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, options)
    return isSafeTiebaEmoticonDimensions(options.outWidth, options.outHeight)
}

private object TiebaEmoticonImageLoader {
    fun get(context: Context): TiebaEmoticonFileLoader = TiebaEmoticonFileLoader.get(context)
}

private fun String.sha256(): String = MessageDigest.getInstance("SHA-256")
    .digest(toByteArray())
    .joinToString("") { byte -> "%02x".format(byte) }
