package dev.infinityf4p.tiebapure.feature.thread

import dev.infinityf4p.tiebapure.core.model.Post
import dev.infinityf4p.tiebapure.core.model.SubpostPage
import dev.infinityf4p.tiebapure.core.model.TiebaLikeObjectType
import dev.infinityf4p.tiebapure.core.model.ThreadPage
import dev.infinityf4p.tiebapure.core.model.ThreadReplySort
import dev.infinityf4p.tiebapure.core.model.ThreadSummary

data class ThreadReadingPosition(
    val postId: ULong,
    val floor: Int,
)

data class ThreadLikeTarget(
    val postId: ULong,
    val objectType: TiebaLikeObjectType,
)

data class ThreadMainPostFallback(
    val threadId: Long,
    val postId: ULong?,
    val author: dev.infinityf4p.tiebapure.core.model.UserSummary,
    val createdAtEpochSeconds: Long?,
    val blocks: List<dev.infinityf4p.tiebapure.core.model.ContentBlock>,
    val likeCount: Int,
    val isLiked: Boolean,
) {
    fun postFor(requestedThreadId: Long): Post? {
        if (requestedThreadId != threadId || blocks.isEmpty()) return null
        return Post(
            id = postId ?: 0uL,
            threadId = threadId,
            floor = 1,
            author = author,
            ipAddress = author.ipAddress,
            createdAtEpochSeconds = createdAtEpochSeconds,
            blocks = blocks,
            subpostCount = 0,
            likeCount = likeCount,
            isLiked = isLiked,
            previewSubposts = emptyList(),
        )
    }

    companion object {
        fun from(thread: ThreadSummary?): ThreadMainPostFallback? {
            thread ?: return null
            if (thread.id <= 0 || thread.blocks.isEmpty()) return null
            return ThreadMainPostFallback(
                threadId = thread.id,
                postId = thread.firstPostId,
                author = thread.author,
                createdAtEpochSeconds = thread.createdAtEpochSeconds,
                blocks = thread.blocks,
                likeCount = thread.likeCount,
                isLiked = thread.isLiked,
            )
        }
    }
}

object ThreadPageMainPostPolicy {
    fun mainPost(page: ThreadPage): Post? = page.mainPost ?: page.posts.firstOrNull { it.floor == 1 }

    fun needsRecovery(page: ThreadPage): Boolean = mainPost(page) == null

    fun withResolvedMainPost(page: ThreadPage): ThreadPage =
        mainPost(page)?.let { page.copy(mainPost = it, mainPostIsSummaryFallback = false) } ?: page

    fun mergeWithPrevious(page: ThreadPage, previous: ThreadPage?): ThreadPage {
        val incoming = mainPost(page)
        val retained = incoming ?: previous?.mainPost
        return page.copy(
            mainPost = retained,
            mainPostIsSummaryFallback = when {
                incoming != null -> false
                retained != null -> previous?.mainPostIsSummaryFallback == true
                else -> false
            },
        )
    }

    fun applyFallback(
        page: ThreadPage,
        fallback: ThreadMainPostFallback?,
        threadId: Long,
    ): ThreadPage {
        val existingMainPost = mainPost(page)
        if (existingMainPost != null) {
            return if (page.mainPost != null) page
            else page.copy(mainPost = existingMainPost, mainPostIsSummaryFallback = false)
        }
        val fallbackPost = fallback?.postFor(threadId) ?: return page
        return page.copy(mainPost = fallbackPost, mainPostIsSummaryFallback = true)
    }
}

interface ThreadRepository {
    fun mainPostFallback(threadId: Long): ThreadMainPostFallback? = null

    suspend fun threadPage(
        threadId: Long,
        page: Int,
        sort: ThreadReplySort,
        onlyThreadAuthor: Boolean,
    ): ThreadPage

    suspend fun subpostPage(
        parentPost: Post,
        page: Int,
    ): SubpostPage

    suspend fun threadPageAround(
        threadId: Long,
        postId: ULong,
        sort: ThreadReplySort,
        onlyThreadAuthor: Boolean,
    ): ThreadPage = threadPage(
        threadId = threadId,
        page = 1,
        sort = sort,
        onlyThreadAuthor = onlyThreadAuthor,
    )

    suspend fun loadReadingPosition(threadId: Long): ThreadReadingPosition? = null

    suspend fun saveReadingPosition(threadId: Long, position: ThreadReadingPosition) = Unit

    /** Hands the final visible position to a scope that outlives this screen. */
    fun scheduleReadingPositionSave(threadId: Long, position: ThreadReadingPosition) = Unit

    suspend fun removeReadingPosition(threadId: Long) = Unit

    suspend fun setLiked(threadId: Long, target: ThreadLikeTarget, liked: Boolean) {
        throw UnsupportedOperationException("点赞功能暂不可用")
    }

    suspend fun setCollected(threadId: Long, markedPostId: ULong, collected: Boolean) {
        throw UnsupportedOperationException("收藏功能暂不可用")
    }
}
