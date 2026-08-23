package dev.infinityf4p.tiebapure

import android.content.Context
import dev.infinityf4p.tiebapure.core.data.SavedThreadEntity
import dev.infinityf4p.tiebapure.core.data.SavedThreadMetadata
import dev.infinityf4p.tiebapure.core.data.TiebaPureDatabase
import dev.infinityf4p.tiebapure.core.data.TiebaRepositories
import dev.infinityf4p.tiebapure.core.model.Account
import dev.infinityf4p.tiebapure.core.model.ContentBlock
import dev.infinityf4p.tiebapure.core.model.Forum
import dev.infinityf4p.tiebapure.core.model.ImageContent
import dev.infinityf4p.tiebapure.core.model.Post
import dev.infinityf4p.tiebapure.core.model.Subpost
import dev.infinityf4p.tiebapure.core.model.ThreadReplySort
import dev.infinityf4p.tiebapure.core.model.ThreadSummary
import dev.infinityf4p.tiebapure.core.model.UserSummary
import dev.infinityf4p.tiebapure.core.model.VideoContent
import dev.infinityf4p.tiebapure.core.model.VoiceContent
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class SavedThreadPostSnapshot(
    val post: Post,
    val subposts: List<Subpost>,
) {
    val displayPost: Post
        get() = post.copy(
            subpostCount = subposts.size,
            previewSubposts = subposts.take(3),
        )
}

enum class SavedThreadMediaMode { TextOnly, Images, Complete }

enum class SavedThreadMediaKind { Image, Video, Voice }

data class SavedThreadMediaAsset(
    val sourceKey: String,
    val kind: SavedThreadMediaKind,
    val fileName: String,
    val byteCount: Long,
    val sha256: String,
)

data class SavedThreadSnapshot(
    val thread: ThreadSummary,
    val forum: Forum,
    val posts: List<SavedThreadPostSnapshot>,
    val savedAtMilliseconds: Long,
    val mediaMode: SavedThreadMediaMode = SavedThreadMediaMode.TextOnly,
    val mediaAssets: List<SavedThreadMediaAsset> = emptyList(),
    val lastCheckedAtMilliseconds: Long? = null,
    val latestReplyCount: Int = thread.replyCount,
) {
    val mainPost: SavedThreadPostSnapshot?
        get() = posts.firstOrNull { it.post.floor == 1 }
    val replyCount: Int
        get() = posts.count { it.post.floor != 1 }
    val subpostCount: Int
        get() = posts.sumOf { it.subposts.size }
    val newReplyCount: Int
        get() = (latestReplyCount - thread.replyCount).coerceAtLeast(0)

    fun validated(): SavedThreadSnapshot {
        check(thread.id > 0 && forum.id > 0) { "帖子或贴吧标识无效。" }
        check(mainPost?.post?.id != null && mainPost!!.post.id > 0uL) { "没有拿到完整主楼。" }
        check(posts.all { it.post.id > 0uL && it.post.threadId == thread.id }) { "帖子楼层不完整。" }
        check(posts.map { it.post.id }.distinct().size == posts.size) { "帖子分页内容重复。" }
        check(latestReplyCount >= 0) { "帖子回复数无效。" }
        check(mediaAssets.map { it.sourceKey }.distinct().size == mediaAssets.size) { "离线媒体来源重复。" }
        check(mediaAssets.map { it.fileName }.distinct().size == mediaAssets.size) { "离线媒体文件重复。" }
        check(mediaAssets.all {
            it.sourceKey.isNotBlank() && it.sourceKey.length <= 8_192 &&
                it.fileName.matches(Regex("^[a-z0-9_-]{1,100}\\.[a-z0-9]{1,8}$")) &&
                it.byteCount in 1..MAXIMUM_MEDIA_BYTES && it.sha256.matches(Regex("^[0-9a-f]{64}$"))
        }) { "离线媒体索引无效。" }
        check(mediaAssets.sumOf(SavedThreadMediaAsset::byteCount) <= MAXIMUM_MEDIA_BYTES) { "离线媒体超过单帖上限。" }
        check(mediaMode != SavedThreadMediaMode.TextOnly || mediaAssets.isEmpty()) { "纯文字保存不应包含媒体。" }
        return this
    }

    private companion object {
        const val MAXIMUM_MEDIA_BYTES = 512L * 1_024 * 1_024
    }
}

data class SavedThreadListItem(
    val threadId: Long,
    val title: String,
    val authorName: String,
    val forumName: String,
    val savedAtMilliseconds: Long,
    val mediaMode: SavedThreadMediaMode,
    val mediaBytes: Long,
    val newReplyCount: Int,
    val lastCheckedAtMilliseconds: Long?,
)

class AppSavedThreadRepository(
    database: TiebaPureDatabase,
    private val repositories: TiebaRepositories,
    private val account: () -> Account?,
    private val mediaStore: AppSavedThreadMediaStore,
    context: Context,
    private val nowMilliseconds: () -> Long = System::currentTimeMillis,
) {
    private val dao = database.savedThreadDao()
    private val saveMutex = Mutex()
    private val backup = AppSavedThreadBackupService(context, dao, mediaStore)

    val entries: Flow<List<SavedThreadListItem>> = dao.observeAll().map { values ->
        values.map(SavedThreadMetadata::toListItem)
    }.flowOn(Dispatchers.Default)

    suspend fun save(
        threadId: Long,
        mode: SavedThreadMediaMode = SavedThreadMediaMode.TextOnly,
    ): SavedThreadSnapshot = serialized {
        val captured = capture(threadId).validated()
        val prepared = mediaStore.prepare(captured, mode)
        val snapshot = prepared.snapshot
        val encoded = SavedThreadBlobCodec.encode(snapshot)
        try {
            prepared.transaction.commit()
            dao.upsert(snapshot.toEntity(encoded))
        } catch (error: Throwable) {
            prepared.transaction.rollback()
            throw error
        }
        runCatching { prepared.transaction.finish() }
        runCatching { repairMediaStorage() }
        snapshot
    }

    suspend fun load(threadId: Long): SavedThreadSnapshot? = serialized {
        val entity = dao.load(threadId) ?: return@serialized null
        val snapshot = SavedThreadBlobCodec.decode(entity.snapshotBlob).validated().also {
            check(it.thread.id == entity.threadId) { "本地保存索引与快照不一致。" }
        }
        mediaStore.resolve(snapshot)
    }

    suspend fun remove(threadId: Long) = serialized {
        dao.remove(threadId)
        mediaStore.remove(threadId)
    }

    suspend fun clearAll() = serialized {
        dao.clear()
        mediaStore.clear()
    }

    suspend fun repairStorage() = serialized {
        repairMediaStorage()
    }

    suspend fun storageBytes(): Long = serialized {
        dao.loadAll().sumOf { it.snapshotBlob.size.toLong() } + mediaStore.storageBytes()
    }

    suspend fun exportBackup(uri: String): Int = serialized { backup.export(uri) }

    suspend fun importBackup(uri: String, mode: SavedThreadImportMode): SavedThreadBackupImportResult =
        serialized { backup.import(uri, mode) }

    suspend fun checkForUpdates(threadId: Long? = null): SavedThreadUpdateCheckResult = serialized {
        val targets = if (threadId == null) dao.loadAll() else listOfNotNull(dao.load(threadId))
        var checked = 0
        var changed = 0
        var newReplies = 0
        targets.forEach { entity ->
            val snapshot = SavedThreadBlobCodec.decode(entity.snapshotBlob).validated()
            val page = repositories.thread.page(
                threadId = entity.threadId,
                page = 1,
                sort = ThreadReplySort.Ascending,
                account = account(),
            )
            check(page.thread.id == entity.threadId) { "帖子更新检查响应不一致。" }
            val latest = maxOf(0, page.thread.replyCount)
            val updated = snapshot.copy(
                lastCheckedAtMilliseconds = nowMilliseconds(),
                latestReplyCount = latest,
            ).validated()
            dao.upsert(updated.toEntity(SavedThreadBlobCodec.encode(updated)))
            checked += 1
            if (updated.newReplyCount > 0) {
                changed += 1
                newReplies += updated.newReplyCount
            }
        }
        SavedThreadUpdateCheckResult(checked, changed, newReplies)
    }

    private suspend fun <T> serialized(action: suspend () -> T): T = withContext(Dispatchers.IO) {
        saveMutex.withLock { action() }
    }

    private suspend fun repairMediaStorage() {
        val entities = dao.loadAll()
        val snapshots = entities.mapNotNull { entity ->
            runCatching { SavedThreadBlobCodec.decode(entity.snapshotBlob).validated() }
                .getOrNull()?.let { entity.threadId to it }
        }.toMap()
        mediaStore.repair(entities.map(SavedThreadEntity::threadId).toSet(), snapshots)
    }

    private suspend fun capture(threadId: Long): SavedThreadSnapshot {
        require(threadId > 0) { "帖子 ID 无效。" }
        val activeAccount = account()
        var first = repositories.thread.page(
            threadId = threadId,
            page = 1,
            sort = ThreadReplySort.Ascending,
            account = activeAccount,
        )
        if (first.mainPostResolved() == null) {
            first = repositories.thread.page(
                threadId = threadId,
                page = 1,
                sort = ThreadReplySort.Ascending,
                account = activeAccount,
            )
        }
        val mainPost = checkNotNull(first.mainPostResolved()) { "没有拿到完整主楼，未写入本地保存。" }
        check(first.thread.id == threadId && first.forum.id > 0) { "帖子响应标识不一致。" }
        check(first.totalPage in 1..MAXIMUM_PAGES) { "帖子页数超出本机保存上限。" }

        val postsById = linkedMapOf(mainPost.id to mainPost)
        first.posts.forEach { post ->
            if (post.id > 0uL && !postsById.containsKey(post.id)) postsById[post.id] = post
        }
        for (pageNumber in 2..first.totalPage) {
            val page = repositories.thread.page(
                threadId = threadId,
                page = pageNumber,
                forumId = first.forum.id,
                sort = ThreadReplySort.Ascending,
                account = activeAccount,
            )
            check(
                page.thread.id == threadId && page.currentPage == pageNumber &&
                    page.totalPage == first.totalPage,
            ) { "帖子分页在保存期间发生变化，请稍后重试。" }
            page.posts.forEach { post ->
                if (post.id > 0uL && !postsById.containsKey(post.id)) postsById[post.id] = post
            }
        }

        val orderedPosts = postsById.values.sortedWith(compareBy<Post> { it.floor }.thenBy { it.id })
        check(orderedPosts.firstOrNull()?.id == mainPost.id) { "帖子主楼排序异常。" }
        val savedPosts = orderedPosts.map { post ->
            SavedThreadPostSnapshot(post, loadAllSubposts(first.forum.id, post, activeAccount))
        }
        return SavedThreadSnapshot(
            thread = first.thread,
            forum = first.forum,
            posts = savedPosts,
            savedAtMilliseconds = nowMilliseconds(),
        )
    }

    private suspend fun loadAllSubposts(
        forumId: Long,
        post: Post,
        activeAccount: Account?,
    ): List<Subpost> {
        if (post.subpostCount <= 0 && post.previewSubposts.isEmpty()) return emptyList()
        val first = repositories.thread.subposts(
            threadId = post.threadId,
            postId = post.id,
            forumId = forumId,
            page = 1,
            account = activeAccount,
        )
        check(first.totalPage in 1..MAXIMUM_PAGES) { "楼中楼页数超出本机保存上限。" }
        val values = linkedMapOf<ULong, Subpost>()
        first.subposts.forEach { subpost ->
            if (subpost.id > 0uL && !values.containsKey(subpost.id)) values[subpost.id] = subpost
        }
        for (pageNumber in 2..first.totalPage) {
            val page = repositories.thread.subposts(
                threadId = post.threadId,
                postId = post.id,
                forumId = forumId,
                page = pageNumber,
                account = activeAccount,
            )
            check(page.currentPage == pageNumber && page.totalPage == first.totalPage) {
                "楼中楼分页在保存期间发生变化，请稍后重试。"
            }
            page.subposts.forEach { subpost ->
                if (subpost.id > 0uL && !values.containsKey(subpost.id)) values[subpost.id] = subpost
            }
        }
        check(values.size >= maxOf(post.subpostCount, post.previewSubposts.size)) {
            "楼中楼响应不完整，未写入本地保存。"
        }
        return values.values.sortedWith(compareBy<Subpost> { it.floor }.thenBy { it.id })
    }

    private companion object {
        const val MAXIMUM_PAGES = 10_000
    }
}

data class SavedThreadUpdateCheckResult(
    val checkedThreads: Int,
    val changedThreads: Int,
    val newReplies: Int,
)

private fun SavedThreadSnapshot.toEntity(encoded: ByteArray) = SavedThreadEntity(
    threadId = thread.id,
    title = thread.title.ifBlank { thread.textPreview },
    authorName = thread.author.resolvedDisplayName,
    forumName = forum.displayName.ifBlank { forum.name },
    savedAtMilliseconds = savedAtMilliseconds,
    snapshotBlob = encoded,
)

private fun dev.infinityf4p.tiebapure.core.model.ThreadPage.mainPostResolved(): Post? =
    mainPost ?: posts.firstOrNull { it.floor == 1 }

private fun SavedThreadMetadata.toListItem(): SavedThreadListItem {
    val snapshot = runCatching { SavedThreadBlobCodec.decode(snapshotBlob).validated() }.getOrNull()
    return SavedThreadListItem(
        threadId = threadId,
        title = title,
        authorName = authorName,
        forumName = forumName,
        savedAtMilliseconds = savedAtMilliseconds,
        mediaMode = snapshot?.mediaMode ?: SavedThreadMediaMode.TextOnly,
        mediaBytes = snapshot?.mediaAssets?.sumOf(SavedThreadMediaAsset::byteCount) ?: 0L,
        newReplyCount = snapshot?.newReplyCount ?: 0,
        lastCheckedAtMilliseconds = snapshot?.lastCheckedAtMilliseconds,
    )
}

internal object SavedThreadBlobCodec {
    private const val MAGIC = 0x54505354
    private const val VERSION = 2
    private const val CHECKSUM_BYTES = 32
    private const val MAXIMUM_BYTES = 16 * 1_024 * 1_024
    private const val MAXIMUM_COLLECTION_COUNT = 1_000_000
    private const val MAXIMUM_STRING_BYTES = 8 * 1_024 * 1_024

    fun encode(snapshot: SavedThreadSnapshot): ByteArray {
        val payload = ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use { output ->
                output.writeInt(MAGIC)
                output.writeInt(VERSION)
                output.writeThread(snapshot.thread)
                output.writeForum(snapshot.forum)
                output.writeCollection(snapshot.posts) { savedPost ->
                    writePost(savedPost.post)
                    writeCollection(savedPost.subposts) { writeSubpost(it) }
                }
                output.writeLong(snapshot.savedAtMilliseconds)
                output.writeString(snapshot.mediaMode.name)
                output.writeCollection(snapshot.mediaAssets) { asset ->
                    writeString(asset.sourceKey)
                    writeString(asset.kind.name)
                    writeString(asset.fileName)
                    writeLong(asset.byteCount)
                    writeString(asset.sha256)
                }
                output.writeNullableLong(snapshot.lastCheckedAtMilliseconds)
                output.writeInt(snapshot.latestReplyCount)
            }
            bytes.toByteArray()
        }
        check(payload.size + CHECKSUM_BYTES <= MAXIMUM_BYTES) { "帖子内容超过本机保存大小上限。" }
        return payload + MessageDigest.getInstance("SHA-256").digest(payload)
    }

    fun decode(encoded: ByteArray): SavedThreadSnapshot {
        require(encoded.size in (CHECKSUM_BYTES + 8)..MAXIMUM_BYTES) { "本地保存文件大小无效。" }
        val payload = encoded.copyOfRange(0, encoded.size - CHECKSUM_BYTES)
        val checksum = encoded.copyOfRange(encoded.size - CHECKSUM_BYTES, encoded.size)
        check(MessageDigest.isEqual(checksum, MessageDigest.getInstance("SHA-256").digest(payload))) {
            "本地保存校验失败。"
        }
        return DataInputStream(ByteArrayInputStream(payload)).use { input ->
            check(input.readInt() == MAGIC) { "本地保存文件标识无效。" }
            val version = input.readInt()
            check(version in 1..VERSION) { "本地保存版本不受支持。" }
            val thread = input.readThread()
            val forum = input.readForum()
            val posts = input.readCollection {
                    SavedThreadPostSnapshot(readPost(), readCollection { readSubpost() })
                }
            val savedAt = input.readLong()
            val snapshot = if (version == 1) {
                SavedThreadSnapshot(thread, forum, posts, savedAt)
            } else {
                SavedThreadSnapshot(
                    thread = thread,
                    forum = forum,
                    posts = posts,
                    savedAtMilliseconds = savedAt,
                    mediaMode = SavedThreadMediaMode.valueOf(input.readStringValue()),
                    mediaAssets = input.readCollection {
                        SavedThreadMediaAsset(
                            sourceKey = readStringValue(),
                            kind = SavedThreadMediaKind.valueOf(readStringValue()),
                            fileName = readStringValue(),
                            byteCount = readLong(),
                            sha256 = readStringValue(),
                        )
                    },
                    lastCheckedAtMilliseconds = input.readNullableLong(),
                    latestReplyCount = input.readInt(),
                )
            }
            check(input.available() == 0) { "本地保存包含无法识别的数据。" }
            snapshot
        }
    }

    private fun DataOutputStream.writeThread(value: ThreadSummary) {
        writeLong(value.id)
        writeNullableLong(value.forumId)
        writeString(value.title)
        writeUser(value.author)
        writeNullableString(value.forumName)
        writeNullableString(value.forumAvatarUrl)
        writeInt(value.replyCount)
        writeInt(value.viewCount)
        writeInt(value.likeCount)
        writeNullableULong(value.firstPostId)
        writeBoolean(value.isLiked)
        writeNullableLong(value.createdAtEpochSeconds)
        writeNullableLong(value.lastReplyAtEpochSeconds)
        writeCollection(value.blocks) { writeBlock(it) }
        writeBoolean(value.isTop)
        writeBoolean(value.isGood)
        writeBoolean(value.hasVideo)
    }

    private fun DataInputStream.readThread() = ThreadSummary(
        id = readLong(),
        forumId = readNullableLong(),
        title = readStringValue(),
        author = readUser(),
        forumName = readNullableString(),
        forumAvatarUrl = readNullableString(),
        replyCount = readInt(),
        viewCount = readInt(),
        likeCount = readInt(),
        firstPostId = readNullableULong(),
        isLiked = readBoolean(),
        createdAtEpochSeconds = readNullableLong(),
        lastReplyAtEpochSeconds = readNullableLong(),
        blocks = readCollection { readBlock() },
        isTop = readBoolean(),
        isGood = readBoolean(),
        hasVideo = readBoolean(),
    )

    private fun DataOutputStream.writeForum(value: Forum) {
        writeLong(value.id)
        writeString(value.name)
        writeString(value.displayName)
        writeNullableString(value.avatarUrl)
        writeInt(value.memberCount)
        writeInt(value.threadCount)
    }

    private fun DataInputStream.readForum() = Forum(
        id = readLong(),
        name = readStringValue(),
        displayName = readStringValue(),
        avatarUrl = readNullableString(),
        memberCount = readInt(),
        threadCount = readInt(),
    )

    private fun DataOutputStream.writePost(value: Post) {
        writeLong(value.id.toLong())
        writeLong(value.threadId)
        writeInt(value.floor)
        writeUser(value.author)
        writeNullableString(value.ipAddress)
        writeNullableLong(value.createdAtEpochSeconds)
        writeCollection(value.blocks) { writeBlock(it) }
        writeInt(value.subpostCount)
        writeInt(value.likeCount)
        writeBoolean(value.isLiked)
        writeCollection(value.previewSubposts) { writeSubpost(it) }
    }

    private fun DataInputStream.readPost() = Post(
        id = readLong().toULong(),
        threadId = readLong(),
        floor = readInt(),
        author = readUser(),
        ipAddress = readNullableString(),
        createdAtEpochSeconds = readNullableLong(),
        blocks = readCollection { readBlock() },
        subpostCount = readInt(),
        likeCount = readInt(),
        isLiked = readBoolean(),
        previewSubposts = readCollection { readSubpost() },
    )

    private fun DataOutputStream.writeSubpost(value: Subpost) {
        writeLong(value.id.toLong())
        writeInt(value.floor)
        writeUser(value.author)
        writeNullableString(value.ipAddress)
        writeCollection(value.blocks) { writeBlock(it) }
        writeNullableLong(value.createdAtEpochSeconds)
        writeInt(value.likeCount)
        writeBoolean(value.isLiked)
    }

    private fun DataInputStream.readSubpost() = Subpost(
        id = readLong().toULong(),
        floor = readInt(),
        author = readUser(),
        ipAddress = readNullableString(),
        blocks = readCollection { readBlock() },
        createdAtEpochSeconds = readNullableLong(),
        likeCount = readInt(),
        isLiked = readBoolean(),
    )

    private fun DataOutputStream.writeUser(value: UserSummary) {
        writeLong(value.id)
        writeString(value.name)
        writeString(value.displayName)
        writeString(value.portrait)
        writeNullableInt(value.level)
        writeNullableString(value.levelName)
        writeNullableString(value.ipAddress)
    }

    private fun DataInputStream.readUser() = UserSummary(
        id = readLong(),
        name = readStringValue(),
        displayName = readStringValue(),
        portrait = readStringValue(),
        level = readNullableInt(),
        levelName = readNullableString(),
        ipAddress = readNullableString(),
    )

    private fun DataOutputStream.writeBlock(value: ContentBlock) {
        when (value) {
            is ContentBlock.Text -> { writeByte(1); writeString(value.value) }
            is ContentBlock.Link -> { writeByte(2); writeString(value.title); writeNullableString(value.url) }
            is ContentBlock.Mention -> { writeByte(3); writeNullableLong(value.userId); writeString(value.text) }
            is ContentBlock.Emoticon -> { writeByte(4); writeString(value.code) }
            is ContentBlock.Image -> {
                writeByte(5)
                with(value.value) {
                    writeNullableString(thumbnailUrl); writeNullableString(originalUrl)
                    writeInt(width); writeInt(height); writeBoolean(showOriginalButton)
                    writeNullableLong(originalSizeBytes)
                }
            }
            is ContentBlock.Video -> {
                writeByte(6)
                with(value.value) {
                    writeNullableString(videoUrl); writeNullableString(coverUrl); writeNullableString(webUrl)
                    writeInt(width); writeInt(height); writeInt(durationSeconds)
                }
            }
            is ContentBlock.Voice -> {
                writeByte(7); writeString(value.value.md5); writeInt(value.value.durationMilliseconds)
            }
        }
    }

    private fun DataInputStream.readBlock(): ContentBlock = when (readUnsignedByte()) {
        1 -> ContentBlock.Text(readStringValue())
        2 -> ContentBlock.Link(readStringValue(), readNullableString())
        3 -> ContentBlock.Mention(readNullableLong(), readStringValue())
        4 -> ContentBlock.Emoticon(readStringValue())
        5 -> ContentBlock.Image(
            ImageContent(
                readNullableString(), readNullableString(), readInt(), readInt(), readBoolean(), readNullableLong(),
            ),
        )
        6 -> ContentBlock.Video(
            VideoContent(
                readNullableString(), readNullableString(), readNullableString(), readInt(), readInt(), readInt(),
            ),
        )
        7 -> ContentBlock.Voice(
            VoiceContent.create(readStringValue(), readInt()) ?: throw IOException("语音内容无效。"),
        )
        else -> throw IOException("未知帖子内容类型。")
    }

    private fun DataOutputStream.writeString(value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAXIMUM_STRING_BYTES) { "帖子文本过长。" }
        writeInt(bytes.size)
        write(bytes)
    }

    private fun DataInputStream.readStringValue(): String {
        val count = readInt()
        if (count !in 0..MAXIMUM_STRING_BYTES || count > available()) throw IOException("帖子文本长度无效。")
        return ByteArray(count).also(::readFully).toString(Charsets.UTF_8)
    }

    private fun DataOutputStream.writeNullableString(value: String?) {
        writeBoolean(value != null)
        if (value != null) writeString(value)
    }

    private fun DataInputStream.readNullableString(): String? = if (readBoolean()) readStringValue() else null
    private fun DataOutputStream.writeNullableLong(value: Long?) { writeBoolean(value != null); if (value != null) writeLong(value) }
    private fun DataInputStream.readNullableLong(): Long? = if (readBoolean()) readLong() else null
    private fun DataOutputStream.writeNullableULong(value: ULong?) { writeBoolean(value != null); if (value != null) writeLong(value.toLong()) }
    private fun DataInputStream.readNullableULong(): ULong? = if (readBoolean()) readLong().toULong() else null
    private fun DataOutputStream.writeNullableInt(value: Int?) { writeBoolean(value != null); if (value != null) writeInt(value) }
    private fun DataInputStream.readNullableInt(): Int? = if (readBoolean()) readInt() else null

    private fun <T> DataOutputStream.writeCollection(values: List<T>, writeValue: DataOutputStream.(T) -> Unit) {
        require(values.size <= MAXIMUM_COLLECTION_COUNT) { "帖子条目过多。" }
        writeInt(values.size)
        values.forEach { writeValue(it) }
    }

    private fun <T> DataInputStream.readCollection(readValue: DataInputStream.() -> T): List<T> {
        val count = readInt()
        if (count !in 0..MAXIMUM_COLLECTION_COUNT) throw IOException("帖子条目数量无效。")
        return List(count) { readValue() }
    }
}
