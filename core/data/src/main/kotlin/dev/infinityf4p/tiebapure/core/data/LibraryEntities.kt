package dev.infinityf4p.tiebapure.core.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "browsing_history")
data class BrowsingHistoryEntity(
    @PrimaryKey @ColumnInfo(name = "thread_id") val threadId: Long,
    val title: String,
    @ColumnInfo(name = "author_name") val authorName: String,
    @ColumnInfo(name = "forum_name") val forumName: String?,
    @ColumnInfo(name = "visited_at_ms") val visitedAtMilliseconds: Long,
)

@Entity(tableName = "reading_positions")
data class ReadingPositionEntity(
    @PrimaryKey @ColumnInfo(name = "thread_id") val threadId: Long,
    @ColumnInfo(name = "post_id") val postId: String?,
    val floor: Int?,
    @ColumnInfo(name = "updated_at_ms") val updatedAtMilliseconds: Long,
)

@Entity(tableName = "recent_forums")
data class RecentForumEntity(
    @PrimaryKey @ColumnInfo(name = "normalized_name") val normalizedName: String,
    @ColumnInfo(name = "forum_id") val forumId: Long,
    val name: String,
    @ColumnInfo(name = "display_name") val displayName: String,
    @ColumnInfo(name = "avatar_url") val avatarUrl: String?,
    @ColumnInfo(name = "visited_at_ms") val visitedAtMilliseconds: Long,
)

@Entity(tableName = "search_history")
data class SearchHistoryEntity(
    @PrimaryKey val keyword: String,
    @ColumnInfo(name = "searched_at_ms") val searchedAtMilliseconds: Long,
)

@Entity(
    tableName = "saved_threads",
    indices = [Index("saved_at_ms")],
)
data class SavedThreadEntity(
    @PrimaryKey @ColumnInfo(name = "thread_id") val threadId: Long,
    val title: String,
    @ColumnInfo(name = "author_name") val authorName: String,
    @ColumnInfo(name = "forum_name") val forumName: String,
    @ColumnInfo(name = "saved_at_ms") val savedAtMilliseconds: Long,
    @ColumnInfo(name = "snapshot_blob", typeAffinity = ColumnInfo.BLOB) val snapshotBlob: ByteArray,
) {
    override fun equals(other: Any?): Boolean = other is SavedThreadEntity &&
        threadId == other.threadId && title == other.title && authorName == other.authorName &&
        forumName == other.forumName && savedAtMilliseconds == other.savedAtMilliseconds &&
        snapshotBlob.contentEquals(other.snapshotBlob)

    override fun hashCode(): Int = listOf(
        threadId,
        title,
        authorName,
        forumName,
        savedAtMilliseconds,
        snapshotBlob.contentHashCode(),
    ).hashCode()
}

data class SavedThreadMetadata(
    @ColumnInfo(name = "thread_id") val threadId: Long,
    val title: String,
    @ColumnInfo(name = "author_name") val authorName: String,
    @ColumnInfo(name = "forum_name") val forumName: String,
    @ColumnInfo(name = "saved_at_ms") val savedAtMilliseconds: Long,
)

@Entity(
    tableName = "blocklist",
    primaryKeys = ["kind", "identity"],
    indices = [Index("kind")],
)
data class BlocklistEntity(
    val kind: String,
    val identity: String,
    val value: String,
    @ColumnInfo(name = "numeric_id") val numericId: Long?,
    @ColumnInfo(name = "created_at_ms") val createdAtMilliseconds: Long,
)

@Entity(
    tableName = "content_drafts",
    primaryKeys = ["account_id", "target_key"],
    indices = [Index("account_id"), Index("updated_at_ms")],
)
data class ContentDraftEntity(
    @ColumnInfo(name = "account_id") val accountId: String,
    @ColumnInfo(name = "target_key") val targetKey: String,
    val title: String,
    val body: String,
    @ColumnInfo(name = "target_metadata", typeAffinity = ColumnInfo.BLOB) val targetMetadata: ByteArray,
    @ColumnInfo(name = "attachment_file_name") val attachmentFileName: String,
    @ColumnInfo(name = "attachment_byte_count") val attachmentByteCount: Long,
    @ColumnInfo(name = "attachment_sha256") val attachmentSHA256: String,
    @ColumnInfo(name = "image_count") val imageCount: Int,
    @ColumnInfo(name = "updated_at_ms") val updatedAtMilliseconds: Long,
) {
    override fun equals(other: Any?): Boolean = other is ContentDraftEntity &&
        accountId == other.accountId && targetKey == other.targetKey && title == other.title &&
        body == other.body && targetMetadata.contentEquals(other.targetMetadata) &&
        attachmentFileName == other.attachmentFileName && attachmentByteCount == other.attachmentByteCount &&
        attachmentSHA256 == other.attachmentSHA256 && imageCount == other.imageCount &&
        updatedAtMilliseconds == other.updatedAtMilliseconds

    override fun hashCode(): Int = listOf(
        accountId,
        targetKey,
        title,
        body,
        targetMetadata.contentHashCode(),
        attachmentFileName,
        attachmentByteCount,
        attachmentSHA256,
        imageCount,
        updatedAtMilliseconds,
    ).hashCode()
}
