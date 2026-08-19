package dev.infinityf4p.tiebapure

import dev.infinityf4p.tiebapure.core.data.ThreadRepository
import dev.infinityf4p.tiebapure.core.model.Account
import dev.infinityf4p.tiebapure.core.model.Forum
import dev.infinityf4p.tiebapure.core.model.SubpostPage
import dev.infinityf4p.tiebapure.core.model.ThreadPage
import dev.infinityf4p.tiebapure.core.model.ThreadReplySort
import dev.infinityf4p.tiebapure.core.model.ThreadSummary
import dev.infinityf4p.tiebapure.core.model.UserSummary
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class AppThreadPageLookupTest {
    @Test
    fun postTargetLookupUsesFirstPublicPageAndPreservesTargetOptions() = runTest {
        val expected = threadPage()
        val repository = RecordingThreadRepository(expected)
        val account = account()

        val actual = repository.pageAroundPost(
            threadId = 42,
            postId = 99u,
            onlyThreadAuthor = true,
            sort = ThreadReplySort.Hot,
            account = account,
        )

        assertSame(expected, actual)
        assertEquals(
            ThreadPageRequest(
                threadId = 42,
                page = 1,
                forumId = null,
                postId = 99u,
                onlyThreadAuthor = true,
                sort = ThreadReplySort.Hot,
                account = account,
            ),
            repository.request,
        )
    }

    private fun threadPage() = ThreadPage(
        thread = ThreadSummary(
            id = 42,
            title = "Thread",
            author = UserSummary(7, "user", "User", ""),
            replyCount = 0,
            viewCount = 0,
            blocks = emptyList(),
        ),
        forum = Forum(1, "forum", "Forum"),
        mainPost = null,
        posts = emptyList(),
        currentPage = 1,
        totalPage = 1,
        hasMore = false,
    )

    private fun account() = Account(
        uid = "7",
        name = "user",
        displayName = "User",
        portrait = "portrait",
        bduss = "bduss",
        stoken = "stoken",
        tbs = "tbs",
    )
}

private data class ThreadPageRequest(
    val threadId: Long,
    val page: Int,
    val forumId: Long?,
    val postId: ULong?,
    val onlyThreadAuthor: Boolean,
    val sort: ThreadReplySort,
    val account: Account?,
)

private class RecordingThreadRepository(
    private val response: ThreadPage,
) : ThreadRepository {
    var request: ThreadPageRequest? = null
        private set

    override suspend fun page(
        threadId: Long,
        page: Int,
        forumId: Long?,
        postId: ULong?,
        onlyThreadAuthor: Boolean,
        sort: ThreadReplySort,
        account: Account?,
    ): ThreadPage {
        request = ThreadPageRequest(
            threadId,
            page,
            forumId,
            postId,
            onlyThreadAuthor,
            sort,
            account,
        )
        return response
    }

    override suspend fun subposts(
        threadId: Long,
        postId: ULong,
        forumId: Long,
        page: Int,
        subpostId: ULong,
        account: Account?,
    ): SubpostPage = error("Not used")
}
