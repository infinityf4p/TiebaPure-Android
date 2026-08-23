package dev.infinityf4p.tiebapure

import android.content.Context
import android.net.Uri
import dev.infinityf4p.tiebapure.core.media.MediaUrlPolicy
import dev.infinityf4p.tiebapure.core.media.OfflineMediaPolicy
import dev.infinityf4p.tiebapure.core.media.OfflineMediaKind
import dev.infinityf4p.tiebapure.core.media.OriginalImageLoader
import dev.infinityf4p.tiebapure.core.media.SecureVideoDownloadClient
import dev.infinityf4p.tiebapure.core.media.SecureVoiceAudioDownloadClient
import dev.infinityf4p.tiebapure.core.model.ContentBlock
import dev.infinityf4p.tiebapure.core.model.Post
import dev.infinityf4p.tiebapure.core.model.Subpost
import dev.infinityf4p.tiebapure.core.model.UserSummary
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class PreparedSavedThreadMedia(
    val snapshot: SavedThreadSnapshot,
    val transaction: SavedThreadMediaTransaction,
)

internal class SavedThreadMediaTransaction(
    private val staging: File,
    private val destination: File,
) {
    private var backup: File? = null
    private var committed = false

    fun commit() {
        check(!committed)
        destination.parentFile?.mkdirs()
        if (destination.exists()) {
            val old = File(destination.parentFile, ".backup-${destination.name}-${UUID.randomUUID()}")
            check(destination.renameTo(old)) { "无法暂存原有离线媒体。" }
            backup = old
        }
        try {
            check(staging.renameTo(destination)) { "无法提交离线媒体。" }
            committed = true
        } catch (error: Throwable) {
            backup?.renameTo(destination)
            throw error
        }
    }

    fun finish() {
        check(committed)
        val obsoleteBackup = backup
        backup = null
        committed = false
        obsoleteBackup?.deleteRecursively()
    }

    fun rollback() {
        if (committed) destination.deleteRecursively() else staging.deleteRecursively()
        backup?.let { old ->
            if (!destination.exists()) old.renameTo(destination)
        }
        backup = null
        committed = false
    }
}

class AppSavedThreadMediaStore(context: Context) {
    private val appContext = context.applicationContext
    private val root = File(appContext.filesDir, "saved-thread-media")
    private val imageLoader = OriginalImageLoader(appContext)
    private val videoDownloader = SecureVideoDownloadClient(appContext)
    private val voiceDownloader = SecureVoiceAudioDownloadClient(appContext)

    init {
        prepareRoot()
        OfflineMediaPolicy.registerRoot(root)
    }

    internal suspend fun prepare(snapshot: SavedThreadSnapshot, mode: SavedThreadMediaMode): PreparedSavedThreadMedia =
        withContext(Dispatchers.IO) {
            val staging = createStaging(snapshot.thread.id)
            val assets = linkedMapOf<String, SavedThreadMediaAsset>()
            var totalBytes = 0L

            fun commitFile(sourceKey: String, kind: SavedThreadMediaKind, source: File, extension: String) {
                if (assets.containsKey(sourceKey)) return
                val size = source.length()
                check(size > 0) { "离线媒体文件为空。" }
                totalBytes += size
                check(totalBytes <= MAXIMUM_THREAD_MEDIA_BYTES) { "单个帖子离线媒体超过 512 MB。" }
                val fileName = "${kind.name.lowercase()}-${sha256(sourceKey)}.$extension"
                val destination = File(staging, fileName)
                source.inputStream().use { input ->
                    FileOutputStream(destination).use { output ->
                        input.copyTo(output)
                        output.fd.sync()
                    }
                }
                restrictToOwner(destination)
                assets[sourceKey] = SavedThreadMediaAsset(
                    sourceKey = sourceKey,
                    kind = kind,
                    fileName = fileName,
                    byteCount = size,
                    sha256 = sha256(destination),
                )
            }

            suspend fun saveImage(rawUrl: String?) {
                val url = rawUrl?.takeIf(MediaUrlPolicy::isAllowed) ?: return
                if (assets.containsKey(url)) return
                commitFile(url, SavedThreadMediaKind.Image, imageLoader.load(url), "img")
            }

            suspend fun saveVideo(rawUrl: String?) {
                val url = rawUrl?.takeIf(MediaUrlPolicy::isAllowedDownloadableVideo) ?: return
                if (assets.containsKey(url)) return
                val lease = videoDownloader.download(url)
                try {
                    commitFile(url, SavedThreadMediaKind.Video, lease.file, "mp4")
                } finally {
                    lease.release()
                }
            }

            suspend fun saveVoice(md5: String) {
                val key = voiceKey(md5)
                if (assets.containsKey(key)) return
                val lease = voiceDownloader.download(md5)
                try {
                    commitFile(key, SavedThreadMediaKind.Voice, lease.file, "audio")
                } finally {
                    lease.release()
                }
            }

            try {
                if (mode != SavedThreadMediaMode.TextOnly) {
                    saveImage(snapshot.forum.avatarUrl)
                    saveImage(snapshot.thread.forumAvatarUrl)
                    saveImage(snapshot.thread.author.portrait)
                    suspend fun saveBlocks(blocks: List<ContentBlock>) {
                        blocks.forEach { block ->
                            when (block) {
                                is ContentBlock.Image -> {
                                    saveImage(block.value.thumbnailUrl)
                                    saveImage(block.value.originalUrl)
                                }
                                is ContentBlock.Video -> {
                                    saveImage(block.value.coverUrl)
                                    if (mode == SavedThreadMediaMode.Complete) saveVideo(block.value.videoUrl)
                                }
                                is ContentBlock.Voice -> if (mode == SavedThreadMediaMode.Complete) saveVoice(block.value.md5)
                                else -> Unit
                            }
                        }
                    }
                    saveBlocks(snapshot.thread.blocks)
                    snapshot.posts.forEach { savedPost ->
                        saveImage(savedPost.post.author.portrait)
                        saveBlocks(savedPost.post.blocks)
                        savedPost.subposts.forEach { subpost ->
                            saveImage(subpost.author.portrait)
                            saveBlocks(subpost.blocks)
                        }
                    }
                }
                val updated = snapshot.copy(mediaMode = mode, mediaAssets = assets.values.toList()).validated()
                PreparedSavedThreadMedia(updated, SavedThreadMediaTransaction(staging, threadDirectory(snapshot.thread.id)))
            } catch (error: Throwable) {
                staging.deleteRecursively()
                throw error
            }
        }

    internal suspend fun prepareImported(
        snapshot: SavedThreadSnapshot,
        sourceDirectory: File,
    ): PreparedSavedThreadMedia = withContext(Dispatchers.IO) {
        val staging = createStaging(snapshot.thread.id)
        try {
            val expected = snapshot.mediaAssets.map(SavedThreadMediaAsset::fileName).toSet()
            val actual = sourceDirectory.listFiles().orEmpty().filter(File::isFile).map(File::getName).toSet()
            check(actual == expected) { "备份中的媒体文件集合不一致。" }
            snapshot.mediaAssets.forEach { asset ->
                val source = File(sourceDirectory, asset.fileName).canonicalFile
                check(source.parentFile == sourceDirectory.canonicalFile && source.isFile) { "备份媒体路径无效。" }
                check(source.length() == asset.byteCount && sha256(source) == asset.sha256) { "备份媒体校验失败。" }
                check(OfflineMediaPolicy.isSupported(source, asset.kind.offlineKind)) { "备份媒体类型无效。" }
                val destination = source.copyTo(File(staging, asset.fileName), overwrite = false)
                restrictToOwner(destination)
            }
            PreparedSavedThreadMedia(snapshot.validated(), SavedThreadMediaTransaction(staging, threadDirectory(snapshot.thread.id)))
        } catch (error: Throwable) {
            staging.deleteRecursively()
            throw error
        }
    }

    suspend fun resolve(snapshot: SavedThreadSnapshot): SavedThreadSnapshot = withContext(Dispatchers.IO) {
        if (snapshot.mediaMode == SavedThreadMediaMode.TextOnly) return@withContext snapshot
        val directory = threadDirectory(snapshot.thread.id).canonicalFile
        val localBySource = snapshot.mediaAssets.mapNotNull { asset ->
            val file = File(directory, asset.fileName).canonicalFile
            if (file.parentFile != directory || !file.isFile || file.length() != asset.byteCount ||
                sha256(file) != asset.sha256 || !OfflineMediaPolicy.isSupported(file, asset.kind.offlineKind)
            ) {
                null
            } else {
                asset.sourceKey to Uri.fromFile(file).toString()
            }
        }.toMap()

        fun imageUrl(remote: String?): String? = remote?.let(localBySource::get)
        fun user(value: UserSummary) = value.copy(portrait = imageUrl(value.portrait).orEmpty())
        fun blocks(values: List<ContentBlock>): List<ContentBlock> = values.map { block ->
            when (block) {
                is ContentBlock.Image -> ContentBlock.Image(
                    block.value.copy(
                        thumbnailUrl = imageUrl(block.value.thumbnailUrl),
                        originalUrl = imageUrl(block.value.originalUrl),
                    ),
                )
                is ContentBlock.Video -> ContentBlock.Video(
                    block.value.copy(
                        videoUrl = if (snapshot.mediaMode == SavedThreadMediaMode.Complete) {
                            block.value.videoUrl?.let(localBySource::get)
                        } else block.value.videoUrl,
                        coverUrl = imageUrl(block.value.coverUrl),
                        webUrl = if (snapshot.mediaMode == SavedThreadMediaMode.Complete) null else block.value.webUrl,
                    ),
                )
                is ContentBlock.Voice -> ContentBlock.Voice(
                    dev.infinityf4p.tiebapure.core.model.VoiceContent.create(
                        block.value.md5,
                        block.value.durationMilliseconds,
                        localUrl = localBySource[voiceKey(block.value.md5)],
                        offlineOnly = snapshot.mediaMode == SavedThreadMediaMode.Complete,
                    ) ?: block.value,
                )
                else -> block
            }
        }
        fun subpostValue(value: Subpost) = value.copy(author = user(value.author), blocks = blocks(value.blocks))
        fun post(value: Post) = value.copy(
            author = user(value.author),
            blocks = blocks(value.blocks),
            previewSubposts = value.previewSubposts.map(::subpostValue),
        )

        snapshot.copy(
            thread = snapshot.thread.copy(
                author = user(snapshot.thread.author),
                forumAvatarUrl = imageUrl(snapshot.thread.forumAvatarUrl),
                blocks = blocks(snapshot.thread.blocks),
            ),
            forum = snapshot.forum.copy(avatarUrl = imageUrl(snapshot.forum.avatarUrl)),
            posts = snapshot.posts.map { saved ->
                saved.copy(post = post(saved.post), subposts = saved.subposts.map(::subpostValue))
            },
        )
    }

    suspend fun remove(threadId: Long) = withContext(Dispatchers.IO) {
        threadDirectory(threadId).deleteRecursively()
    }

    suspend fun clear() = withContext(Dispatchers.IO) {
        root.listFiles().orEmpty().forEach(File::deleteRecursively)
    }

    suspend fun storageBytes(): Long = withContext(Dispatchers.IO) {
        root.walkTopDown().filter(File::isFile).sumOf(File::length)
    }

    suspend fun repair(
        validThreadIds: Set<Long>,
        snapshots: Map<Long, SavedThreadSnapshot>,
    ) = withContext(Dispatchers.IO) {
        prepareRoot()
        val all = root.listFiles().orEmpty()
        all.filter { it.name.startsWith(".staging-") }.forEach(File::deleteRecursively)
        val backups = all.filter { it.name.startsWith(".backup-") }.groupBy { backupThreadId(it.name) }

        all.filter { it.name.toLongOrNull() != null }.forEach { destination ->
            val threadId = checkNotNull(destination.name.toLongOrNull())
            if (threadId !in validThreadIds) destination.deleteRecursively()
        }
        backups.forEach { (threadId, candidates) ->
            if (threadId == null || threadId !in validThreadIds) candidates.forEach(File::deleteRecursively)
        }

        snapshots.forEach { (threadId, snapshot) ->
            val destination = threadDirectory(threadId)
            val candidates = backups[threadId].orEmpty()
            if (directoryMatches(destination, snapshot)) {
                candidates.forEach(File::deleteRecursively)
            } else {
                val restorable = candidates.firstOrNull { directoryMatches(it, snapshot) }
                destination.deleteRecursively()
                if (restorable != null) check(restorable.renameTo(destination)) { "无法恢复离线媒体。" }
                candidates.filterNot { it == restorable }.forEach(File::deleteRecursively)
            }
        }
    }

    fun threadDirectory(threadId: Long): File {
        require(threadId > 0)
        return File(root, threadId.toString())
    }

    private fun createStaging(threadId: Long): File {
        prepareRoot()
        val staging = File(root, ".staging-$threadId-${UUID.randomUUID()}")
        check(staging.mkdir()) { "无法创建离线媒体暂存目录。" }
        return staging
    }

    private fun prepareRoot() {
        check(root.isDirectory || root.mkdirs()) { "无法创建离线媒体目录。" }
        root.setReadable(false, false)
        root.setWritable(false, false)
        root.setExecutable(false, false)
        check(root.setReadable(true, true) && root.setWritable(true, true) && root.setExecutable(true, true)) {
            "无法保护离线媒体目录。"
        }
    }

    private fun directoryMatches(directory: File, snapshot: SavedThreadSnapshot): Boolean {
        if (!directory.isDirectory) return false
        val expected = snapshot.mediaAssets.map(SavedThreadMediaAsset::fileName).toSet()
        val children = directory.listFiles() ?: return false
        if (children.any { !it.isFile } || children.map(File::getName).toSet() != expected) return false
        return snapshot.mediaAssets.all { asset ->
            val file = File(directory, asset.fileName)
            file.length() == asset.byteCount && sha256(file) == asset.sha256 &&
                OfflineMediaPolicy.isSupported(file, asset.kind.offlineKind)
        }
    }

    private fun restrictToOwner(file: File) {
        file.setReadable(false, false)
        file.setWritable(false, false)
        file.setExecutable(false, false)
        check(file.setReadable(true, true) && file.setWritable(true, true)) { "无法保护离线媒体文件。" }
    }

    private companion object {
        const val MAXIMUM_THREAD_MEDIA_BYTES = 512L * 1_024 * 1_024
    }
}

private val SavedThreadMediaKind.offlineKind: OfflineMediaKind
    get() = when (this) {
        SavedThreadMediaKind.Image -> OfflineMediaKind.Image
        SavedThreadMediaKind.Video -> OfflineMediaKind.Video
        SavedThreadMediaKind.Voice -> OfflineMediaKind.Voice
    }

private fun backupThreadId(name: String): Long? = name.removePrefix(".backup-").substringBefore('-').toLongOrNull()

private fun voiceKey(md5: String) = "voice:${md5.lowercase()}"

internal fun sha256(file: File): String = file.inputStream().use { input ->
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    while (true) {
        val count = input.read(buffer)
        if (count < 0) break
        digest.update(buffer, 0, count)
    }
    digest.digest().toLowerHex()
}

internal fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8)).toLowerHex()

private fun ByteArray.toLowerHex(): String = joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
