package dev.infinityf4p.tiebapure.core.media

import android.net.Uri
import java.io.File
import java.util.concurrent.CopyOnWriteArraySet

enum class OfflineMediaKind { Image, Video, Voice }

/** Allows local playback only from private roots registered by the application. */
object OfflineMediaPolicy {
    private const val MAXIMUM_LOCAL_MEDIA_BYTES = 512L * 1_024 * 1_024
    private val roots = CopyOnWriteArraySet<File>()

    fun registerRoot(root: File) {
        val canonical = root.canonicalFile
        check(canonical.isDirectory || canonical.mkdirs()) { "Unable to prepare offline media directory" }
        roots += canonical
    }

    fun resolve(rawUrl: String?): File? {
        val uri = runCatching { Uri.parse(rawUrl) }.getOrNull() ?: return null
        if (uri.scheme != "file" || uri.query != null || uri.fragment != null) return null
        val path = uri.path?.takeIf(String::isNotBlank) ?: return null
        val file = runCatching { File(path).canonicalFile }.getOrNull() ?: return null
        if (!file.isFile || file.length() !in 1..MAXIMUM_LOCAL_MEDIA_BYTES) return null
        val filePath = file.path
        val allowed = roots.any { root ->
            file.parentFile != null && (filePath == root.path || filePath.startsWith(root.path + File.separator))
        }
        return file.takeIf { allowed }
    }

    fun isSupported(file: File, kind: OfflineMediaKind): Boolean {
        if (!file.isFile) return false
        return when (kind) {
            OfflineMediaKind.Image -> file.length() in 1..MAXIMUM_IMAGE_BYTES &&
                MediaFileSignatures.isSupportedImage(file)
            OfflineMediaKind.Video -> file.length() in 1..MAXIMUM_VIDEO_BYTES &&
                MediaFileSignatures.matches(file, "video/mp4", RemoteMediaKind.Video)
            OfflineMediaKind.Voice -> file.length() in 1..MAXIMUM_VOICE_BYTES
        }
    }

    private const val MAXIMUM_IMAGE_BYTES = 96L * 1_024 * 1_024
    private const val MAXIMUM_VIDEO_BYTES = 200L * 1_024 * 1_024
    private const val MAXIMUM_VOICE_BYTES = 8L * 1_024 * 1_024
}
