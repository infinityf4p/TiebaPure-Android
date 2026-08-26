package dev.infinityf4p.tiebapure.core.data

import dev.infinityf4p.tiebapure.core.model.Account
import dev.infinityf4p.tiebapure.core.model.Forum
import dev.infinityf4p.tiebapure.core.model.ForumInfo
import dev.infinityf4p.tiebapure.core.model.ForumPage
import dev.infinityf4p.tiebapure.core.model.ForumThreadCategory
import dev.infinityf4p.tiebapure.core.model.Post
import dev.infinityf4p.tiebapure.core.model.SearchPage
import dev.infinityf4p.tiebapure.core.model.SearchThreadResult
import dev.infinityf4p.tiebapure.core.model.SearchUserResult
import dev.infinityf4p.tiebapure.core.model.SubpostPage
import dev.infinityf4p.tiebapure.core.model.ThreadPage
import dev.infinityf4p.tiebapure.core.model.ThreadReplySort
import dev.infinityf4p.tiebapure.core.model.ThreadSummary
import dev.infinityf4p.tiebapure.core.model.UserContentVisibility
import dev.infinityf4p.tiebapure.core.model.UserProfile
import dev.infinityf4p.tiebapure.core.model.UserProfileSex
import dev.infinityf4p.tiebapure.core.model.UserSummary
import dev.infinityf4p.tiebapure.core.model.UserThreadsPage
import dev.infinityf4p.tiebapure.core.network.TiebaReadService
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class TiebaRepositoriesTest {
    @Test
    fun forumRepositoryPreservesPageCategoryAndAnonymousSession() = runBlocking {
        val service = FakeReadService()
        val repository = NetworkForumRepository(service)

        repository.threads("测试", page = 4, category = ForumThreadCategory.Featured)

        assertEquals(ForumCall(null, "测试", 4, ForumThreadCategory.Featured), service.forumCall)
    }

    @Test
    fun forumRepositoryLoadsPublicForumInfo() = runBlocking {
        val service = FakeReadService()
        val repository = NetworkForumRepository(service)

        val result = repository.info("测试")

        assertEquals("测试", service.forumInfoName)
        assertEquals(forumInfo, result)
    }

    @Test
    fun threadRepositoryPreservesTargetAndSort() = runBlocking {
        val service = FakeReadService()
        val repository = NetworkThreadRepository(service)

        repository.page(
            threadId = 99,
            page = 2,
            forumId = 7,
            postId = 123u,
            onlyThreadAuthor = true,
            sort = ThreadReplySort.Hot,
        )

        assertEquals(ThreadCall(99, 2, 7, 123u, true, ThreadReplySort.Hot), service.threadCall)
    }

    private class FakeReadService : TiebaReadService {
        var forumCall: ForumCall? = null
        var forumInfoName: String? = null
        var threadCall: ThreadCall? = null

        override suspend fun home(account: Account?, page: Int, loadType: Int) = emptyList<ThreadSummary>()
        override suspend fun forum(account: Account?, forumName: String, page: Int, category: ForumThreadCategory): ForumPage {
            forumCall = ForumCall(account, forumName, page, category)
            return ForumPage(forum, emptyList(), page, false)
        }
        override suspend fun forumInfo(forumName: String): ForumInfo {
            forumInfoName = forumName
            return forumInfo
        }
        override suspend fun thread(
            account: Account?, threadId: Long, page: Int, forumId: Long?, postId: ULong?,
            onlyThreadAuthor: Boolean, sort: ThreadReplySort,
        ): ThreadPage {
            threadCall = ThreadCall(threadId, page, forumId, postId, onlyThreadAuthor, sort)
            return ThreadPage(thread, forum, null, emptyList(), page, page, false)
        }
        override suspend fun subposts(
            account: Account?, threadId: Long, postId: ULong, forumId: Long, page: Int, subpostId: ULong,
        ): SubpostPage = SubpostPage(post, emptyList(), page, page, false)
        override suspend fun searchThreads(
            keyword: String, page: Int, sortType: Int, filterType: Int, forumName: String?,
        ) = SearchPage<SearchThreadResult>(emptyList(), page, false)
        override suspend fun searchUsers(keyword: String) = SearchPage<SearchUserResult>(emptyList(), 1, false)
        override suspend fun userProfile(account: Account?, user: UserSummary) = UserProfile(
            user, false, false, "", "", UserProfileSex.Unspecified, null, "", null,
            0, 0, 0, 0, 0, emptyList(), UserContentVisibility.Visible,
        )
        override suspend fun userThreads(account: Account?, userId: Long, page: Int) =
            UserThreadsPage(emptyList(), page, false, UserContentVisibility.Visible)
    }

    private data class ForumCall(val account: Account?, val forumName: String, val page: Int, val category: ForumThreadCategory)
    private data class ThreadCall(
        val threadId: Long, val page: Int, val forumId: Long?, val postId: ULong?,
        val onlyThreadAuthor: Boolean, val sort: ThreadReplySort,
    )

    private companion object {
        val user = UserSummary(1, "user", "User", "")
        val forum = Forum(1, "测试", "测试吧")
        val forumInfo = ForumInfo(1, 2, 3, 4, "简介", "分类", "子分类")
        val thread = ThreadSummary(1, 1, "title", user, "测试", replyCount = 0, viewCount = 0, blocks = emptyList())
        val post = Post(1u, 1, 1, user, null, null, emptyList(), 0, 0, previewSubposts = emptyList())
    }
}
