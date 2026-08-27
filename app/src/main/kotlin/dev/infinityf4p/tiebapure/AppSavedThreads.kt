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
import dev.infinityf4p.tiebapure.core.model.ThreadPage
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
import kotlinx.coroutines.CancellationException
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

data class SavedThreadSaveSource(
    val page: ThreadPage,
    val loadedPosts: List<Post>,
)

data class SavedThreadSaveResult(
    val snapshot: SavedThreadSnapshot,
    val retainedPrevious: Boolean = false,
)

internal suspend fun <T> attemptSavedThreadRequest(action: suspend () -> T): T? = try {
    action()
} catch (error: CancellationException) {
    throw error
} catch (_: Exception) {
    null
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
    val replyCaptureComplete: Boolean = true,
    val subpostCaptureComplete: Boolean = true,
    val mediaCaptureComplete: Boolean = true,
) {
    val mainPost: SavedThreadPostSnapshot?
        get() = posts.firstOrNull { it.post.floor == 1 }
    val replyCount: Int
        get() = posts.count { it.post.floor != 1 }
    val subpostCount: Int
        get() = posts.sumOf { it.subposts.size }
    val newReplyCount: Int
        get() = (latestReplyCount - thread.replyCount).coerceAtLeast(0)
    val isPartial: Boolean
        get() = !replyCaptureComplete || !subpostCaptureComplete || !mediaCaptureComplete

    fun validated(): SavedThreadSnapshot {
        val mainPosts = posts.filter { it.post.floor == 1 }
        val postIDs = posts.map { it.post.id }
        val postFloors = posts.map { it.post.floor }
        val subpostIDs = posts.flatMap { saved -> saved.subposts.map(Subpost::id) }
        check(
            thread.id > 0 && (forum.id > 0 || forum.name.isNotBlank() || forum.displayName.isNotBlank()) &&
                savedAtMilliseconds > 0,
        ) { "帖子或贴吧标识无效。" }
        check(mainPosts.size == 1 && mainPosts.single().post.let { it.id > 0uL || it.blocks.isNotEmpty() }) {
            "没有拿到完整主楼。"
        }
        check(posts.all { saved ->
            (saved.post.id > 0uL || saved.post.floor == 1 && saved.post.blocks.isNotEmpty()) &&
                saved.post.threadId == thread.id && saved.post.floor > 0 &&
                saved.subposts.all { it.id > 0uL && it.floor > 0 } &&
                saved.subposts.map(Subpost::id).distinct().size == saved.subposts.size
        }) { "帖子楼层不完整。" }
        check(postIDs.distinct().size == posts.size && postFloors.distinct().size == posts.size) {
            "帖子分页内容重复。"
        }
        check(subpostIDs.distinct().size == subpostIDs.size) { "楼中楼分页内容重复。" }
        check(latestReplyCount >= 0) { "帖子回复数无效。" }
        check(mediaAssets.map { it.sourceKey }.distinct().size == mediaAssets.size) { "离线媒体来源重复。" }
        check(mediaAssets.map { it.fileName }.distinct().size == mediaAssets.size) { "离线媒体文件重复。" }
        check(mediaAssets.all {
            it.sourceKey.isNotBlank() && it.sourceKey.length <= 8_192 &&
                it.fileName.matches(FILE_NAME_PATTERN) &&
                it.byteCount in 1..MAXIMUM_MEDIA_BYTES && it.sha256.matches(DIGEST_PATTERN)
        }) { "离线媒体索引无效。" }
        var mediaBytes = 0L
        mediaAssets.forEach { asset ->
            check(asset.byteCount <= MAXIMUM_MEDIA_BYTES - mediaBytes) { "离线媒体超过单帖上限。" }
            mediaBytes += asset.byteCount
        }
        check(mediaMode != SavedThreadMediaMode.TextOnly || mediaAssets.isEmpty()) { "纯文字保存不应包含媒体。" }
        return this
    }

    private companion object {
        const val MAXIMUM_MEDIA_BYTES = 512L * 1_024 * 1_024
        val FILE_NAME_PATTERN = Regex("^[a-z0-9_-]{1,100}\\.[a-z0-9]{1,8}$")
        val DIGEST_PATTERN = Regex("^[0-9a-f]{64}$")
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
    val snapshotBytes: Long,
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
        source: SavedThreadSaveSource,
        mode: SavedThreadMediaMode = SavedThreadMediaMode.TextOnly,
    ): SavedThreadSaveResult {
        val threadId = source.page.thread.id
        require(threadId > 0) { "帖子 ID 无效。" }
        val savedAt = nowMilliseconds()
        val baseline = savedThreadBaseline(source, savedAt, mode).validated()
        val existing = serialized {
            dao.load(threadId)?.let { entity ->
                runCatching { SavedThreadBlobCodec.decode(entity.snapshotBlob).validated() }.getOrNull()
            }
        }
        val fallback = existing ?: persistInitialBaseline(baseline)
        val captured = try {
            capture(source, baseline).validated()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            return SavedThreadSaveResult(fallback, retainedPrevious = existing != null)
        }
        if (existing != null && captured.isPartial && captured.contentItemCount < existing.contentItemCount) {
            return SavedThreadSaveResult(existing, retainedPrevious = true)
        }

        val candidate = preferRicherTextSnapshot(fallback, captured).copy(
            savedAtMilliseconds = savedAt,
            mediaMode = SavedThreadMediaMode.TextOnly,
            mediaAssets = emptyList(),
            mediaCaptureComplete = mode == SavedThreadMediaMode.TextOnly,
        ).validated()
        val candidateEncoded = encodeBestEffort(candidate)
            ?: return SavedThreadSaveResult(fallback, retainedPrevious = existing != null)
        val textSnapshot = if (existing == null) {
            try {
                persist(candidate, candidateEncoded)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                return SavedThreadSaveResult(fallback)
            }
        } else candidate

        val prepared = try {
            mediaStore.prepare(textSnapshot, mode)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            return SavedThreadSaveResult(
                fallbackAfterEnrichmentFailure(existing, textSnapshot, mode),
                retainedPrevious = existing != null,
            )
        }
        if (!prepared.snapshot.mediaCaptureComplete && existing?.mediaAssets?.isNotEmpty() == true) {
            prepared.transaction.rollback()
            val preserved = textSnapshot.copy(
                mediaMode = existing.mediaMode,
                mediaAssets = existing.mediaAssets,
                mediaCaptureComplete = false,
            ).validated()
            val preservedEncoded = encodeBestEffort(preserved)
                ?: return SavedThreadSaveResult(existing, retainedPrevious = true)
            return try {
                SavedThreadSaveResult(persist(preserved, preservedEncoded))
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                SavedThreadSaveResult(existing, retainedPrevious = true)
            }
        }
        val encoded = encodeBestEffort(prepared.snapshot)
        if (encoded == null) {
            prepared.transaction.rollback()
            return SavedThreadSaveResult(
                fallbackAfterEnrichmentFailure(existing, textSnapshot, mode),
                retainedPrevious = existing != null,
            )
        }
        return try {
            SavedThreadSaveResult(commit(prepared, encoded))
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            SavedThreadSaveResult(
                fallbackAfterEnrichmentFailure(existing, textSnapshot, mode),
                retainedPrevious = existing != null,
            )
        }
    }

    private suspend fun persistInitialBaseline(baseline: SavedThreadSnapshot): SavedThreadSnapshot {
        val encoded = encodeBestEffort(baseline)
        if (encoded != null) return persist(baseline, encoded)
        val mainOnly = baseline.mainPostOnly().validated()
        return persist(mainOnly, SavedThreadBlobCodec.encode(mainOnly))
    }

    private suspend fun persist(snapshot: SavedThreadSnapshot, encoded: ByteArray): SavedThreadSnapshot = serialized {
        dao.upsert(snapshot.toEntity(encoded))
        snapshot
    }

    private fun encodeBestEffort(snapshot: SavedThreadSnapshot): ByteArray? = try {
        SavedThreadBlobCodec.encodeIfWithinLimit(snapshot)
    } catch (_: Exception) {
        null
    }

    private suspend fun commit(prepared: PreparedSavedThreadMedia, encoded: ByteArray): SavedThreadSnapshot = serialized {
        try {
            prepared.transaction.commit()
            dao.upsert(prepared.snapshot.toEntity(encoded))
        } catch (error: Throwable) {
            prepared.transaction.rollback()
            throw error
        }
        runCatching { prepared.transaction.finish() }
        runCatching { mediaStore.removeOrphans(dao.threadIds().toSet()) }
        prepared.snapshot
    }

    private fun fallbackAfterEnrichmentFailure(
        existing: SavedThreadSnapshot?,
        textSnapshot: SavedThreadSnapshot,
        requestedMode: SavedThreadMediaMode,
    ): SavedThreadSnapshot {
        if (existing != null) return existing
        check(textSnapshot.mediaCaptureComplete == (requestedMode == SavedThreadMediaMode.TextOnly))
        return textSnapshot
    }

    suspend fun load(threadId: Long): SavedThreadSnapshot? {
        val snapshot = serialized {
            val entity = dao.load(threadId) ?: return@serialized null
            SavedThreadBlobCodec.decode(entity.snapshotBlob).validated().also {
                check(it.thread.id == entity.threadId) { "本地保存索引与快照不一致。" }
            }
        }
        return snapshot?.let { mediaStore.resolve(it) }
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

    suspend fun exportBackup(uri: String): Int = serialized { backup.export(uri) }

    suspend fun importBackup(uri: String, mode: SavedThreadImportMode): SavedThreadBackupImportResult =
        serialized { backup.import(uri, mode) }

    suspend fun checkForUpdates(threadId: Long? = null): SavedThreadUpdateCheckResult {
        val targetIDs = serialized {
            if (threadId == null) dao.threadIds() else listOfNotNull(dao.load(threadId)?.threadId)
        }
        val activeAccount = account()
        val remote = targetIDs.associateWith { targetID ->
            val page = repositories.thread.page(
                threadId = targetID,
                page = 1,
                sort = ThreadReplySort.Ascending,
                account = activeAccount,
            )
            check(page.thread.id == targetID) { "帖子更新检查响应不一致。" }
            maxOf(0, page.thread.replyCount) to nowMilliseconds()
        }
        return serialized {
            val current = dao.load(targetIDs)
            val updated = current.mapNotNull { entity ->
                val (latest, checkedAt) = remote[entity.threadId] ?: return@mapNotNull null
                SavedThreadBlobCodec.decode(entity.snapshotBlob).validated().copy(
                    lastCheckedAtMilliseconds = checkedAt,
                    latestReplyCount = latest,
                ).validated()
            }
            dao.upsertAll(updated.map { it.toEntity(SavedThreadBlobCodec.encode(it)) })
            SavedThreadUpdateCheckResult(
                checkedThreads = updated.size,
                changedThreads = updated.count { it.newReplyCount > 0 },
                newReplies = updated.sumOf(SavedThreadSnapshot::newReplyCount),
            )
        }
    }

    private suspend fun <T> serialized(action: suspend () -> T): T = withContext(Dispatchers.IO) {
        saveMutex.withLock { action() }
    }

    private suspend fun repairMediaStorage() {
        val validThreadIDs = dao.threadIds().toSet()
        val pendingRecoveryIDs = mediaStore.pendingRecoveryThreadIds().intersect(validThreadIDs)
        val metadataEntities = dao.loadNeedingMetadataRepair(CURRENT_METADATA_VERSION)
        val recoveryEntities = dao.load(pendingRecoveryIDs)
        val decoded = (metadataEntities + recoveryEntities)
            .distinctBy(SavedThreadEntity::threadId)
            .mapNotNull { entity ->
                runCatching { SavedThreadBlobCodec.decode(entity.snapshotBlob).validated() }
                    .getOrNull()?.let { entity.threadId to it }
            }.toMap()
        val repairedMetadata = metadataEntities.mapNotNull { entity ->
            decoded[entity.threadId]?.toEntity(entity.snapshotBlob)
        }
        if (repairedMetadata.isNotEmpty()) dao.refreshMetadata(repairedMetadata)
        val textOnlyThreadIDs = dao.threadIdsWithMediaMode(SavedThreadMediaMode.TextOnly.name).toSet()
        mediaStore.repair(
            validThreadIDs,
            decoded.filterKeys(pendingRecoveryIDs::contains),
            textOnlyThreadIDs,
        )
    }

    private suspend fun capture(
        source: SavedThreadSaveSource,
        baseline: SavedThreadSnapshot,
    ): SavedThreadSnapshot {
        val threadId = source.page.thread.id
        val activeAccount = account()
        val first = attemptSavedThreadRequest {
            repositories.thread.page(
                threadId = threadId,
                page = 1,
                sort = ThreadReplySort.Ascending,
                account = activeAccount,
            )
        } ?: return baseline
        if (first.thread.id != threadId) return baseline
        val mainPost = first.mainPostResolved() ?: checkNotNull(baseline.mainPost).post
        val forum = first.forum.takeIf { it.id > 0 || it.name.isNotBlank() || it.displayName.isNotBlank() }
            ?: baseline.forum

        val postsById = linkedMapOf(mainPost.id to mainPost)
        baseline.posts.asSequence().map(SavedThreadPostSnapshot::post).forEach { post ->
            if (post.floor > 1 && post.id > 0uL && post.threadId == threadId && post.id !in postsById) {
                postsById[post.id] = post
            }
        }
        first.posts.forEach { post ->
            if (post.floor > 1 && post.id > 0uL && post.threadId == threadId) postsById[post.id] = post
        }
        var repliesComplete = first.totalPage in 1..MAXIMUM_PAGES && first.currentPage == 1 &&
            (first.totalPage > 1 || !first.hasMore)
        val targetPage = first.totalPage.coerceIn(1, MAXIMUM_PAGES)
        for (pageNumber in 2..targetPage) {
            val page = attemptSavedThreadRequest {
                repositories.thread.page(
                    threadId = threadId,
                    page = pageNumber,
                    forumId = forum.id.takeIf { it > 0 },
                    sort = ThreadReplySort.Ascending,
                    account = activeAccount,
                )
            }
            if (page == null || page.thread.id != threadId || page.currentPage != pageNumber) {
                repliesComplete = false
                continue
            }
            page.posts.forEach { post ->
                if (post.floor > 1 && post.id > 0uL && post.threadId == threadId) postsById[post.id] = post
            }
        }

        val orderedPosts = postsById.values.sortedWith(compareBy<Post> { it.floor }.thenBy { it.id })
        if (orderedPosts.count { it.floor > 1 } < first.thread.replyCount.coerceAtLeast(0)) {
            repliesComplete = false
        }
        var subpostsComplete = true
        val savedPosts = orderedPosts.map { post ->
            val known = baseline.posts.firstOrNull { it.post.id == post.id && it.post.floor == post.floor }
                ?.subposts.orEmpty()
            val captured = loadAllSubposts(forum.id, post, activeAccount, known)
            if (!captured.complete) subpostsComplete = false
            SavedThreadPostSnapshot(post.copy(previewSubposts = emptyList()), captured.values)
        }
        return baseline.copy(
            thread = first.thread,
            forum = forum,
            posts = savedPosts,
            replyCaptureComplete = repliesComplete,
            subpostCaptureComplete = subpostsComplete,
        )
    }

    private suspend fun loadAllSubposts(
        forumId: Long,
        post: Post,
        activeAccount: Account?,
        knownSubposts: List<Subpost>,
    ): CapturedSubposts {
        val values = linkedMapOf<ULong, Subpost>()
        (knownSubposts + post.previewSubposts).forEach { subpost ->
            if (subpost.id > 0uL && subpost.floor > 0 && subpost.id !in values) {
                values[subpost.id] = subpost
            }
        }
        if (post.subpostCount <= values.size) return CapturedSubposts(values.sortedSubposts(), complete = true)
        if (post.id == 0uL || forumId <= 0) return CapturedSubposts(values.sortedSubposts(), complete = false)
        val first = attemptSavedThreadRequest {
            repositories.thread.subposts(
                threadId = post.threadId,
                postId = post.id,
                forumId = forumId,
                page = 1,
                account = activeAccount,
            )
        } ?: return CapturedSubposts(values.sortedSubposts(), complete = false)
        var complete = first.totalPage in 1..MAXIMUM_PAGES && first.currentPage == 1 &&
            (first.totalPage > 1 || !first.hasMore)
        first.subposts.forEach { subpost ->
            if (subpost.id > 0uL && subpost.floor > 0) values[subpost.id] = subpost
        }
        val targetPage = first.totalPage.coerceIn(1, MAXIMUM_PAGES)
        for (pageNumber in 2..targetPage) {
            val page = attemptSavedThreadRequest {
                repositories.thread.subposts(
                    threadId = post.threadId,
                    postId = post.id,
                    forumId = forumId,
                    page = pageNumber,
                    account = activeAccount,
                )
            }
            if (page == null || page.currentPage != pageNumber) {
                complete = false
                continue
            }
            page.subposts.forEach { subpost ->
                if (subpost.id > 0uL && subpost.floor > 0) values[subpost.id] = subpost
            }
        }
        if (values.size < maxOf(post.subpostCount, post.previewSubposts.size)) {
            complete = false
        }
        return CapturedSubposts(values.sortedSubposts(), complete)
    }

    private companion object {
        const val MAXIMUM_PAGES = 10_000
        const val CURRENT_METADATA_VERSION = 1
    }

    private data class CapturedSubposts(val values: List<Subpost>, val complete: Boolean)
}

internal fun savedThreadBaseline(
    source: SavedThreadSaveSource,
    savedAtMilliseconds: Long,
    requestedMode: SavedThreadMediaMode,
): SavedThreadSnapshot {
    val page = source.page
    val threadId = page.thread.id
    require(threadId > 0 && savedAtMilliseconds > 0) { "帖子 ID 或保存时间无效。" }
    val sourceMain = page.mainPostResolved()
        ?: source.loadedPosts.firstOrNull { it.floor == 1 }
        ?: page.thread.blocks.takeIf { it.isNotEmpty() }?.let { blocks ->
            Post(
                id = page.thread.firstPostId ?: 0uL,
                threadId = threadId,
                floor = 1,
                author = page.thread.author,
                ipAddress = page.thread.author.ipAddress,
                createdAtEpochSeconds = page.thread.createdAtEpochSeconds,
                blocks = blocks,
                subpostCount = 0,
                likeCount = page.thread.likeCount,
                isLiked = page.thread.isLiked,
                previewSubposts = emptyList(),
            )
        }
        ?: error("当前页面没有可保存的主楼文本。")
    val main = sourceMain.copy(
        threadId = threadId,
        floor = 1,
        blocks = sourceMain.blocks.ifEmpty { page.thread.blocks },
        previewSubposts = emptyList(),
    )
    check(main.id > 0uL || main.blocks.isNotEmpty()) { "当前页面没有可保存的主楼文本。" }

    val seenIDs = mutableSetOf(main.id)
    val seenFloors = mutableSetOf(1)
    val seenSubpostIDs = mutableSetOf<ULong>()
    fun uniqueSubposts(values: List<Subpost>): List<Subpost> =
        values.validSubposts().filter { seenSubpostIDs.add(it.id) }
    val savedPosts = mutableListOf(
        SavedThreadPostSnapshot(
            post = main,
            subposts = uniqueSubposts(sourceMain.previewSubposts),
        ),
    )
    (source.loadedPosts + page.posts)
        .asSequence()
        .filter { it.threadId == threadId && it.id > 0uL && it.floor > 1 }
        .sortedWith(compareBy<Post> { it.floor }.thenBy { it.id })
        .forEach { post ->
            if (seenIDs.add(post.id) && seenFloors.add(post.floor)) {
                savedPosts += SavedThreadPostSnapshot(
                    post = post.copy(previewSubposts = emptyList()),
                    subposts = uniqueSubposts(post.previewSubposts),
                )
            }
        }
    val forum = page.forum.takeIf { it.id > 0 || it.name.isNotBlank() || it.displayName.isNotBlank() }
        ?: page.thread.forumRoute()
        ?: Forum(
            id = page.thread.forumId ?: 0,
            name = page.thread.forumName.orEmpty(),
            displayName = page.thread.forumName?.let { if (it.endsWith("吧")) it else "${it}吧" } ?: "未知贴吧",
            avatarUrl = page.thread.forumAvatarUrl,
        )
    val savedReplyCount = savedPosts.count { it.post.floor > 1 }
    return SavedThreadSnapshot(
        thread = page.thread,
        forum = forum,
        posts = savedPosts,
        savedAtMilliseconds = savedAtMilliseconds,
        replyCaptureComplete = !page.hasMore && savedReplyCount >= page.thread.replyCount.coerceAtLeast(0),
        subpostCaptureComplete = savedPosts.all { it.subposts.size >= it.post.subpostCount.coerceAtLeast(0) },
        mediaCaptureComplete = requestedMode == SavedThreadMediaMode.TextOnly,
    )
}

private fun List<Subpost>.validSubposts(): List<Subpost> = asSequence()
    .filter { it.id > 0uL && it.floor > 0 }
    .distinctBy(Subpost::id)
    .sortedWith(compareBy<Subpost> { it.floor }.thenBy { it.id })
    .toList()

private fun LinkedHashMap<ULong, Subpost>.sortedSubposts(): List<Subpost> =
    values.sortedWith(compareBy<Subpost> { it.floor }.thenBy { it.id })

private val SavedThreadSnapshot.contentItemCount: Long
    get() = posts.size.toLong() + posts.sumOf { it.subposts.size.toLong() }

private fun preferRicherTextSnapshot(
    current: SavedThreadSnapshot,
    captured: SavedThreadSnapshot,
): SavedThreadSnapshot = if (captured.contentItemCount >= current.contentItemCount) captured else current

private fun SavedThreadSnapshot.mainPostOnly(): SavedThreadSnapshot {
    val main = checkNotNull(mainPost)
    return copy(
        posts = listOf(
            main.copy(
                post = main.post.copy(previewSubposts = emptyList()),
                subposts = emptyList(),
            ),
        ),
        replyCaptureComplete = thread.replyCount <= 0,
        subpostCaptureComplete = main.post.subpostCount <= 0,
    )
}

internal fun savedThreadSaveMessage(result: SavedThreadSaveResult): String {
    if (result.retainedPrevious) return "更新未完成，已保留原有本地保存。"
    val snapshot = result.snapshot
    val base = "已保存主楼文本、${snapshot.replyCount} 层回复和 ${snapshot.subpostCount} 条楼中楼" +
        "（${savedThreadMediaModeLabel(snapshot.mediaMode)}）"
    val incomplete = savedThreadIncompleteContent(snapshot)
    return if (incomplete == null) "$base。" else "$base；$incomplete。"
}

internal fun savedThreadIncompleteContent(snapshot: SavedThreadSnapshot): String? = buildList {
    if (!snapshot.replyCaptureComplete) add("部分回复未保存")
    if (!snapshot.subpostCaptureComplete) add("部分楼中楼未保存")
    if (!snapshot.mediaCaptureComplete) add("部分媒体未下载")
}.takeIf { it.isNotEmpty() }?.joinToString("、")

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
    mediaMode = mediaMode.name,
    mediaByteCount = mediaAssets.sumOf(SavedThreadMediaAsset::byteCount),
    newReplyCount = newReplyCount,
    lastCheckedAtMilliseconds = lastCheckedAtMilliseconds,
    metadataVersion = 1,
)

private fun dev.infinityf4p.tiebapure.core.model.ThreadPage.mainPostResolved(): Post? =
    mainPost ?: posts.firstOrNull { it.floor == 1 }

private fun SavedThreadMetadata.toListItem(): SavedThreadListItem {
    return SavedThreadListItem(
        threadId = threadId,
        title = title,
        authorName = authorName,
        forumName = forumName,
        savedAtMilliseconds = savedAtMilliseconds,
        mediaMode = SavedThreadMediaMode.entries.firstOrNull { it.name == mediaMode }
            ?: SavedThreadMediaMode.TextOnly,
        mediaBytes = mediaByteCount.coerceAtLeast(0),
        snapshotBytes = snapshotByteCount.coerceAtLeast(0),
        newReplyCount = newReplyCount.coerceAtLeast(0),
        lastCheckedAtMilliseconds = lastCheckedAtMilliseconds,
    )
}

private class SavedThreadSnapshotTooLargeException : IllegalStateException("帖子内容超过本机保存大小上限。")

internal object SavedThreadBlobCodec {
    private const val MAGIC = 0x54505354
    private const val VERSION = 3
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
                output.writeBoolean(snapshot.replyCaptureComplete)
                output.writeBoolean(snapshot.subpostCaptureComplete)
                output.writeBoolean(snapshot.mediaCaptureComplete)
            }
            bytes.toByteArray()
        }
        if (payload.size + CHECKSUM_BYTES > MAXIMUM_BYTES) throw SavedThreadSnapshotTooLargeException()
        return payload + MessageDigest.getInstance("SHA-256").digest(payload)
    }

    fun encodeIfWithinLimit(snapshot: SavedThreadSnapshot): ByteArray? = try {
        encode(snapshot)
    } catch (_: SavedThreadSnapshotTooLargeException) {
        null
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
                    replyCaptureComplete = if (version >= 3) input.readBoolean() else true,
                    subpostCaptureComplete = if (version >= 3) input.readBoolean() else true,
                    mediaCaptureComplete = if (version >= 3) input.readBoolean() else true,
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
