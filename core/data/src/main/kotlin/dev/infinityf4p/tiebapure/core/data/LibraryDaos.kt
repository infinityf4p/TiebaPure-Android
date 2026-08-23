package dev.infinityf4p.tiebapure.core.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

internal object ContentDraftLimits {
    const val maximumDraftsPerAccount = 100
    const val maximumDraftsGlobally = 200
    const val maximumAttachmentBytesPerDraft = 96L * 1_024 * 1_024
    const val maximumAttachmentBytesPerAccount = 256L * 1_024 * 1_024
    const val maximumAttachmentBytesGlobally = 512L * 1_024 * 1_024
}

internal data class ContentDraftPruneCandidate(
    val accountId: String,
    val targetKey: String,
    val attachmentByteCount: Long,
    val updatedAtMilliseconds: Long,
)

internal fun retainedContentDraftKeys(
    candidates: List<ContentDraftPruneCandidate>,
    preferredKey: Pair<String, String>? = null,
): Set<Pair<String, String>> {
    val ordered = candidates.sortedWith(
        compareByDescending<ContentDraftPruneCandidate> {
            if (it.accountId to it.targetKey == preferredKey) 1 else 0
        }.thenByDescending(ContentDraftPruneCandidate::updatedAtMilliseconds)
            .thenBy(ContentDraftPruneCandidate::accountId)
            .thenBy(ContentDraftPruneCandidate::targetKey),
    )
    val accountCounts = mutableMapOf<String, Int>()
    val accountBytes = mutableMapOf<String, Long>()
    var globalCount = 0
    var globalBytes = 0L
    return buildSet {
        for (candidate in ordered) {
            val byteCount = candidate.attachmentByteCount
            if (byteCount !in 8..ContentDraftLimits.maximumAttachmentBytesPerDraft) continue
            val countForAccount = accountCounts[candidate.accountId] ?: 0
            val bytesForAccount = accountBytes[candidate.accountId] ?: 0L
            val fits = countForAccount < ContentDraftLimits.maximumDraftsPerAccount &&
                globalCount < ContentDraftLimits.maximumDraftsGlobally &&
                byteCount <= ContentDraftLimits.maximumAttachmentBytesPerAccount - bytesForAccount &&
                byteCount <= ContentDraftLimits.maximumAttachmentBytesGlobally - globalBytes
            if (!fits) continue
            add(candidate.accountId to candidate.targetKey)
            accountCounts[candidate.accountId] = countForAccount + 1
            accountBytes[candidate.accountId] = bytesForAccount + byteCount
            globalCount += 1
            globalBytes += byteCount
        }
    }
}

@Dao
abstract class BrowsingHistoryDao {
    @Query("SELECT * FROM browsing_history ORDER BY visited_at_ms DESC")
    abstract fun observeAll(): Flow<List<BrowsingHistoryEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    protected abstract suspend fun insert(entity: BrowsingHistoryEntity)

    @Query("DELETE FROM browsing_history WHERE thread_id = :threadId")
    abstract suspend fun remove(threadId: Long)

    @Query("DELETE FROM browsing_history")
    abstract suspend fun clear()

    @Query("DELETE FROM browsing_history WHERE thread_id NOT IN (SELECT thread_id FROM browsing_history ORDER BY visited_at_ms DESC LIMIT :limit)")
    protected abstract suspend fun prune(limit: Int)

    @Transaction
    open suspend fun upsert(entity: BrowsingHistoryEntity, limit: Int = 500) {
        insert(entity)
        prune(limit.coerceIn(0, 500))
    }
}

@Dao
abstract class ReadingPositionDao {
    @Query("SELECT thread_id FROM reading_positions")
    abstract suspend fun threadIds(): List<Long>

    @Query("SELECT * FROM reading_positions WHERE thread_id = :threadId LIMIT 1")
    abstract suspend fun load(threadId: Long): ReadingPositionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    protected abstract suspend fun insert(entity: ReadingPositionEntity)

    @Query("DELETE FROM reading_positions WHERE thread_id = :threadId")
    abstract suspend fun remove(threadId: Long)

    @Query("DELETE FROM reading_positions")
    abstract suspend fun clear()

    @Query("DELETE FROM reading_positions WHERE thread_id NOT IN (SELECT thread_id FROM reading_positions ORDER BY updated_at_ms DESC LIMIT :limit)")
    protected abstract suspend fun prune(limit: Int)

    @Transaction
    open suspend fun upsert(entity: ReadingPositionEntity, limit: Int = 500) {
        insert(entity)
        prune(limit.coerceIn(0, 500))
    }
}

@Dao
abstract class RecentForumDao {
    @Query("SELECT * FROM recent_forums ORDER BY visited_at_ms DESC")
    abstract fun observeAll(): Flow<List<RecentForumEntity>>

    @Query("SELECT * FROM recent_forums WHERE normalized_name = :normalizedName LIMIT 1")
    abstract suspend fun load(normalizedName: String): RecentForumEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    protected abstract suspend fun insert(entity: RecentForumEntity)

    @Query("DELETE FROM recent_forums WHERE normalized_name = :normalizedName")
    abstract suspend fun remove(normalizedName: String)

    @Query("DELETE FROM recent_forums")
    abstract suspend fun clear()

    @Query("DELETE FROM recent_forums WHERE normalized_name NOT IN (SELECT normalized_name FROM recent_forums ORDER BY visited_at_ms DESC LIMIT :limit)")
    protected abstract suspend fun prune(limit: Int)

    @Transaction
    open suspend fun upsert(entity: RecentForumEntity, limit: Int = 30) {
        insert(entity)
        prune(limit.coerceIn(0, 30))
    }
}

@Dao
abstract class SearchHistoryDao {
    @Query("SELECT * FROM search_history ORDER BY searched_at_ms DESC")
    abstract fun observeAll(): Flow<List<SearchHistoryEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    protected abstract suspend fun insert(entity: SearchHistoryEntity)

    @Query("DELETE FROM search_history WHERE keyword = :keyword")
    abstract suspend fun remove(keyword: String)

    @Query("DELETE FROM search_history")
    abstract suspend fun clear()

    @Query("DELETE FROM search_history WHERE keyword NOT IN (SELECT keyword FROM search_history ORDER BY searched_at_ms DESC LIMIT :limit)")
    protected abstract suspend fun prune(limit: Int)

    @Transaction
    open suspend fun upsert(entity: SearchHistoryEntity, limit: Int = 20) {
        insert(entity)
        prune(limit.coerceIn(0, 20))
    }
}

@Dao
abstract class SavedThreadDao {
    @Query("SELECT thread_id, title, author_name, forum_name, saved_at_ms FROM saved_threads ORDER BY saved_at_ms DESC")
    abstract fun observeAll(): Flow<List<SavedThreadMetadata>>

    @Query("SELECT * FROM saved_threads WHERE thread_id = :threadId LIMIT 1")
    abstract suspend fun load(threadId: Long): SavedThreadEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    protected abstract suspend fun insert(entity: SavedThreadEntity)

    @Query("DELETE FROM saved_threads WHERE thread_id = :threadId")
    abstract suspend fun remove(threadId: Long)

    @Query("DELETE FROM saved_threads WHERE thread_id NOT IN (SELECT thread_id FROM saved_threads ORDER BY saved_at_ms DESC LIMIT :limit)")
    protected abstract suspend fun prune(limit: Int)

    @Transaction
    open suspend fun upsert(entity: SavedThreadEntity, limit: Int = 100) {
        require(entity.threadId > 0)
        require(entity.snapshotBlob.size in 1..MAXIMUM_SNAPSHOT_BYTES)
        insert(entity)
        prune(limit.coerceIn(0, 100))
    }

    private companion object {
        const val MAXIMUM_SNAPSHOT_BYTES = 1 * 1_024 * 1_024
    }
}

@Dao
abstract class BlocklistDao {
    @Query("SELECT * FROM blocklist ORDER BY created_at_ms DESC")
    abstract fun observeAll(): Flow<List<BlocklistEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    protected abstract suspend fun insert(entity: BlocklistEntity)

    @Query("DELETE FROM blocklist WHERE kind = :kind AND identity = :identity")
    abstract suspend fun remove(kind: String, identity: String)

    @Query("DELETE FROM blocklist WHERE kind = :kind")
    abstract suspend fun clear(kind: String)

    @Query("DELETE FROM blocklist WHERE kind = :kind AND identity NOT IN (SELECT identity FROM blocklist WHERE kind = :kind ORDER BY created_at_ms DESC LIMIT :limit)")
    protected abstract suspend fun prune(kind: String, limit: Int)

    @Transaction
    open suspend fun upsert(entity: BlocklistEntity, limit: Int = 200) {
        insert(entity)
        prune(entity.kind, limit.coerceIn(0, 200))
    }
}

@Dao
abstract class ContentDraftDao {
    @Query("SELECT * FROM content_drafts ORDER BY updated_at_ms DESC")
    abstract fun observeAll(): Flow<List<ContentDraftEntity>>

    @Query("SELECT * FROM content_drafts ORDER BY updated_at_ms DESC")
    abstract suspend fun loadAll(): List<ContentDraftEntity>

    @Query("SELECT * FROM content_drafts WHERE account_id = :accountId AND target_key = :targetKey LIMIT 1")
    abstract suspend fun load(accountId: String, targetKey: String): ContentDraftEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    protected abstract suspend fun insert(entity: ContentDraftEntity)

    @Query("DELETE FROM content_drafts WHERE account_id = :accountId AND target_key = :targetKey")
    abstract suspend fun remove(accountId: String, targetKey: String)

    @Query("DELETE FROM content_drafts WHERE account_id = :accountId")
    abstract suspend fun clearAccount(accountId: String)

    @Transaction
    open suspend fun upsert(entity: ContentDraftEntity): ContentDraftEntity? {
        require(entity.accountId.isNotBlank())
        require(entity.targetMetadata.size <= 64 * 1_024)
        require(entity.attachmentFileName.matches(DRAFT_ATTACHMENT_FILE_PATTERN))
        require(entity.attachmentByteCount in 8..ContentDraftLimits.maximumAttachmentBytesPerDraft)
        require(entity.attachmentSHA256.matches(Regex("[0-9a-f]{64}")))
        require(entity.imageCount in 0..9)
        val previous = load(entity.accountId, entity.targetKey)
        insert(entity)
        val allDrafts = loadAll()
        val retainedKeys = retainedContentDraftKeys(
            allDrafts.map(ContentDraftEntity::toPruneCandidate),
            preferredKey = entity.accountId to entity.targetKey,
        )
        allDrafts.forEach { candidate ->
            if (candidate.accountId to candidate.targetKey !in retainedKeys) {
                remove(candidate.accountId, candidate.targetKey)
            }
        }
        return previous
    }
}

private val DRAFT_ATTACHMENT_FILE_PATTERN =
    Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.tpdr")

private fun ContentDraftEntity.toPruneCandidate() = ContentDraftPruneCandidate(
    accountId = accountId,
    targetKey = targetKey,
    attachmentByteCount = attachmentByteCount,
    updatedAtMilliseconds = updatedAtMilliseconds,
)
