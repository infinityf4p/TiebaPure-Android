package dev.infinityf4p.tiebapure.core.data

import dev.infinityf4p.tiebapure.core.model.Account
import dev.infinityf4p.tiebapure.core.model.ForumPage
import dev.infinityf4p.tiebapure.core.model.ForumThreadCategory
import dev.infinityf4p.tiebapure.core.model.SearchPage
import dev.infinityf4p.tiebapure.core.model.SearchThreadResult
import dev.infinityf4p.tiebapure.core.model.SearchUserResult
import dev.infinityf4p.tiebapure.core.model.SubpostPage
import dev.infinityf4p.tiebapure.core.model.ThreadPage
import dev.infinityf4p.tiebapure.core.model.ThreadReplySort
import dev.infinityf4p.tiebapure.core.model.ThreadSummary
import dev.infinityf4p.tiebapure.core.model.UserProfile
import dev.infinityf4p.tiebapure.core.model.UserSummary
import dev.infinityf4p.tiebapure.core.model.UserThreadsPage
import dev.infinityf4p.tiebapure.core.network.TiebaReadService

interface HomeRepository {
    suspend fun threads(account: Account? = null, page: Int, loadType: Int): List<ThreadSummary>
}

interface ForumRepository {
    suspend fun threads(
        forumName: String,
        page: Int,
        category: ForumThreadCategory = ForumThreadCategory.ReplyTime,
        account: Account? = null,
    ): ForumPage
}

interface ThreadRepository {
    suspend fun page(
        threadId: Long,
        page: Int,
        forumId: Long? = null,
        postId: ULong? = null,
        onlyThreadAuthor: Boolean = false,
        sort: ThreadReplySort = ThreadReplySort.Ascending,
        account: Account? = null,
    ): ThreadPage

    suspend fun subposts(
        threadId: Long,
        postId: ULong,
        forumId: Long,
        page: Int,
        subpostId: ULong = 0u,
        account: Account? = null,
    ): SubpostPage
}

interface SearchRepository {
    suspend fun threads(
        keyword: String,
        page: Int,
        sortType: Int = 5,
        filterType: Int = 2,
        forumName: String? = null,
    ): SearchPage<SearchThreadResult>

    suspend fun users(keyword: String): SearchPage<SearchUserResult>
}

interface UserRepository {
    suspend fun profile(user: UserSummary, account: Account? = null): UserProfile
    suspend fun threads(userId: Long, page: Int, account: Account? = null): UserThreadsPage
}

class NetworkHomeRepository(private val service: TiebaReadService) : HomeRepository {
    override suspend fun threads(account: Account?, page: Int, loadType: Int) =
        service.home(account, page, loadType)
}

class NetworkForumRepository(private val service: TiebaReadService) : ForumRepository {
    override suspend fun threads(
        forumName: String,
        page: Int,
        category: ForumThreadCategory,
        account: Account?,
    ) = service.forum(account, forumName, page, category)
}

class NetworkThreadRepository(private val service: TiebaReadService) : ThreadRepository {
    override suspend fun page(
        threadId: Long,
        page: Int,
        forumId: Long?,
        postId: ULong?,
        onlyThreadAuthor: Boolean,
        sort: ThreadReplySort,
        account: Account?,
    ) = service.thread(account, threadId, page, forumId, postId, onlyThreadAuthor, sort)

    override suspend fun subposts(
        threadId: Long,
        postId: ULong,
        forumId: Long,
        page: Int,
        subpostId: ULong,
        account: Account?,
    ) = service.subposts(account, threadId, postId, forumId, page, subpostId)
}

class NetworkSearchRepository(private val service: TiebaReadService) : SearchRepository {
    override suspend fun threads(
        keyword: String,
        page: Int,
        sortType: Int,
        filterType: Int,
        forumName: String?,
    ) = service.searchThreads(keyword, page, sortType, filterType, forumName)

    override suspend fun users(keyword: String) = service.searchUsers(keyword)
}

class NetworkUserRepository(private val service: TiebaReadService) : UserRepository {
    override suspend fun profile(user: UserSummary, account: Account?) = service.userProfile(account, user)
    override suspend fun threads(userId: Long, page: Int, account: Account?) = service.userThreads(account, userId, page)
}

data class TiebaRepositories(
    val home: HomeRepository,
    val forum: ForumRepository,
    val thread: ThreadRepository,
    val search: SearchRepository,
    val user: UserRepository,
) {
    companion object {
        fun network(service: TiebaReadService) = TiebaRepositories(
            home = NetworkHomeRepository(service),
            forum = NetworkForumRepository(service),
            thread = NetworkThreadRepository(service),
            search = NetworkSearchRepository(service),
            user = NetworkUserRepository(service),
        )
    }
}
