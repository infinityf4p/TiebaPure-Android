package dev.infinityf4p.tiebapure.core.model

data class UserSummary(
    val id: Long,
    val name: String,
    val displayName: String,
    val portrait: String,
    val level: Int? = null,
    val levelName: String? = null,
    val ipAddress: String? = null,
) {
    val resolvedDisplayName: String
        get() = displayName.ifBlank { name }.ifBlank { if (id == 0L) "未知用户" else "用户$id" }
}

data class ThreadSummary(
    val id: Long,
    val forumId: Long? = null,
    val title: String,
    val author: UserSummary,
    val forumName: String? = null,
    val forumAvatarUrl: String? = null,
    val replyCount: Int,
    val viewCount: Int,
    val likeCount: Int = 0,
    val firstPostId: ULong? = null,
    val isLiked: Boolean = false,
    val createdAtEpochSeconds: Long? = null,
    val lastReplyAtEpochSeconds: Long? = null,
    val blocks: List<ContentBlock>,
    val isTop: Boolean = false,
    val isGood: Boolean = false,
    val hasVideo: Boolean = false,
) {
    val textPreview: String
        get() = blocks.mapNotNull(ContentBlock::plainText).joinToString("")

    val mediaBlocks: List<ContentBlock>
        get() = blocks.filter { it is ContentBlock.Image || it is ContentBlock.Video }

    fun forumRoute(): Forum? {
        val routeName = forumName?.trim()?.takeIf(String::isNotEmpty) ?: return null
        return Forum(
            id = forumId ?: 0,
            name = routeName,
            displayName = if (routeName.endsWith("吧")) routeName else "${routeName}吧",
            avatarUrl = forumAvatarUrl,
        )
    }
}

enum class ThreadReplySort(val protocolValue: Int) {
    Hot(2),
    Ascending(0),
    Descending(1),
}

enum class TiebaLikeObjectType(val protocolValue: Int) {
    Thread(3),
    Post(1),
    Subpost(2),
}

data class Post(
    val id: ULong,
    val threadId: Long,
    val floor: Int,
    val author: UserSummary,
    val ipAddress: String?,
    val createdAtEpochSeconds: Long?,
    val blocks: List<ContentBlock>,
    val subpostCount: Int,
    val likeCount: Int,
    val isLiked: Boolean = false,
    val previewSubposts: List<Subpost>,
) {
    val contentPreview: String
        get() = blocks.mapNotNull(ContentBlock::plainText).joinToString("")
}

data class Subpost(
    val id: ULong,
    val floor: Int,
    val author: UserSummary,
    val ipAddress: String?,
    val blocks: List<ContentBlock>,
    val createdAtEpochSeconds: Long?,
    val likeCount: Int,
    val isLiked: Boolean = false,
)

data class ThreadPage(
    val thread: ThreadSummary,
    val forum: Forum,
    val mainPost: Post?,
    val posts: List<Post>,
    val currentPage: Int,
    val totalPage: Int,
    val hasMore: Boolean,
    val mainPostIsSummaryFallback: Boolean = false,
    val isCollected: Boolean = false,
)

data class SubpostPage(
    val parentPost: Post,
    val subposts: List<Subpost>,
    val currentPage: Int,
    val totalPage: Int,
    val hasMore: Boolean,
)
