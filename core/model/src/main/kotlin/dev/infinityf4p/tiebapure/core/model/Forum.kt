package dev.infinityf4p.tiebapure.core.model

data class Forum(
    val id: Long,
    val name: String,
    val displayName: String,
    val avatarUrl: String? = null,
    val memberCount: Int = 0,
    val threadCount: Int = 0,
)

enum class ForumThreadCategory(
    val sortType: Int,
    val goodClassifyId: Int?,
) {
    ReplyTime(sortType = 0, goodClassifyId = null),
    PublishTime(sortType = 1, goodClassifyId = null),
    Featured(sortType = -1, goodClassifyId = 0),
}

data class ForumPage(
    val forum: Forum,
    val threads: List<ThreadSummary>,
    val currentPage: Int,
    val hasMore: Boolean,
)

data class ForumMembership(
    val forumId: Long,
    val isFollowed: Boolean,
)
