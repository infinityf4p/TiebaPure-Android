package dev.infinityf4p.tiebapure.core.model

enum class SearchKind { Threads, Users }

data class SearchThreadResult(
    val thread: ThreadSummary,
    val matchedText: String,
    /** The concrete reply matched by search. It is not the thread's first post ID. */
    val matchedPostId: ULong? = null,
)

data class SearchUserResult(
    val user: UserSummary,
    val isExactMatch: Boolean,
)

data class SearchPage<T>(
    val results: List<T>,
    val currentPage: Int,
    val hasMore: Boolean,
)

data class BrowsingHistoryEntry(
    val thread: ThreadSummary,
    val visitedAtEpochMilliseconds: Long,
)

data class ThreadFavorite(
    val thread: ThreadSummary,
    val savedAtEpochMilliseconds: Long,
)

/** A collection stored by Baidu, distinct from the app's on-device favorites. */
data class AccountThreadFavorite(
    val threadId: Long,
    val forumId: Long,
    val forumName: String,
    val title: String,
    val authorDisplayName: String,
    val replyCount: Int,
    val lastReplyAtEpochSeconds: Long?,
    val markedPostId: ULong?,
)

data class AccountThreadFavoritesPage(
    val favorites: List<AccountThreadFavorite>,
    val currentPage: Int,
    val hasMore: Boolean,
)

data class ForumSignResult(
    val forumId: Long,
    val forumName: String,
    val wasAlreadySigned: Boolean,
    val bonusPoints: Int,
    val continuousDays: Int,
    val rank: Int,
)

enum class MessageKind { Reply, Mention, Agree }

data class TiebaMessage(
    val id: String,
    val kind: MessageKind,
    val sender: UserSummary,
    val threadId: Long?,
    val postId: ULong?,
    val text: String,
    val createdAtEpochSeconds: Long?,
    val isRead: Boolean,
    val threadTitle: String = "",
    val forumName: String? = null,
    val isFloorReply: Boolean = false,
)

data class MessagePage(
    val messages: List<TiebaMessage>,
    val currentPage: Int,
    val hasMore: Boolean,
)
