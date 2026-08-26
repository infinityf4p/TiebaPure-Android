package dev.infinityf4p.tiebapure.core.network

import dev.infinityf4p.tiebapure.core.model.Account
import dev.infinityf4p.tiebapure.core.model.ForumInfo
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
import tieba.Personalized
import tieba.frsPage.FrsPage
import tieba.pbFloor.PbFloorResponseOuterClass
import tieba.pbPage.PbPageResponseOuterClass
import tiebapure.profile.UserProfile as ProfileProtocol

interface TiebaReadService {
    suspend fun home(account: Account?, page: Int, loadType: Int): List<ThreadSummary>
    suspend fun forum(account: Account?, forumName: String, page: Int, category: ForumThreadCategory): ForumPage
    suspend fun forumInfo(forumName: String): ForumInfo
    suspend fun thread(
        account: Account?, threadId: Long, page: Int, forumId: Long? = null, postId: ULong? = null,
        onlyThreadAuthor: Boolean = false, sort: ThreadReplySort = ThreadReplySort.Ascending,
    ): ThreadPage
    suspend fun subposts(
        account: Account?, threadId: Long, postId: ULong, forumId: Long, page: Int, subpostId: ULong = 0u,
    ): SubpostPage
    suspend fun searchThreads(
        keyword: String, page: Int, sortType: Int = 5, filterType: Int = 2, forumName: String? = null,
    ): SearchPage<SearchThreadResult>
    suspend fun searchUsers(keyword: String): SearchPage<SearchUserResult>
    suspend fun userProfile(account: Account?, user: UserSummary): UserProfile
    suspend fun userThreads(account: Account?, userId: Long, page: Int): UserThreadsPage
}

class DefaultTiebaReadService(
    private val transport: TiebaTransport,
    private val requestBuilder: TiebaRequestBuilder,
    private val protoFactory: TiebaProtoRequestFactory = TiebaProtoRequestFactory(requestBuilder),
) : TiebaReadService {
    override suspend fun home(account: Account?, page: Int, loadType: Int): List<ThreadSummary> {
        val request = requestBuilder.protobufRequest(
            endpoint = TiebaEndpoint.Personalized,
            message = protoFactory.personalized(account, page, loadType),
            account = account,
            includeStoken = false,
            headers = mapOf("X-BD-DATA-TYPE" to "protobuf", "Cookie" to "ka=open"),
        )
        return TiebaProtoMapper.personalized(transport.protobuf(request, Personalized.PersonalizedResponse.parser()))
    }

    override suspend fun forum(
        account: Account?, forumName: String, page: Int, category: ForumThreadCategory,
    ): ForumPage {
        val name = forumName.trim()
        if (name.isEmpty()) throw TiebaMutationException.InvalidForumName
        if (account == null) {
            val request = TiebaReadRequestFactory.forumThreads(name, page, category, requestBuilder)
            return TiebaJsonMapper.forum(transport.text(request), name, page)
        }
        val request = requestBuilder.protobufRequest(
            TiebaEndpoint.ForumPage,
            protoFactory.forumThreads(account, name, page, category),
            account,
            includeStoken = true,
            headers = mapOf("X-BD-DATA-TYPE" to "protobuf"),
        )
        return TiebaProtoMapper.forum(transport.protobuf(request, FrsPage.FrsPageResponse.parser()), name, page)
    }

    override suspend fun forumInfo(forumName: String): ForumInfo {
        val name = forumName.trim().removeSuffix("吧").trim()
        if (name.isEmpty()) throw TiebaMutationException.InvalidForumName
        val request = TiebaReadRequestFactory.forumInfo(name, requestBuilder)
        return TiebaJsonMapper.forumInfo(transport.text(request))
    }

    override suspend fun thread(
        account: Account?, threadId: Long, page: Int, forumId: Long?, postId: ULong?,
        onlyThreadAuthor: Boolean, sort: ThreadReplySort,
    ): ThreadPage {
        val request = requestBuilder.protobufRequest(
            TiebaEndpoint.ThreadPage,
            protoFactory.threadPage(account, threadId, page, forumId, postId, onlyThreadAuthor, sort),
            account,
            includeStoken = true,
        )
        return TiebaProtoMapper.threadPage(transport.protobuf(request, PbPageResponseOuterClass.PbPageResponse.parser()))
    }

    override suspend fun subposts(
        account: Account?, threadId: Long, postId: ULong, forumId: Long, page: Int, subpostId: ULong,
    ): SubpostPage {
        val request = requestBuilder.protobufRequest(
            TiebaEndpoint.Subposts,
            protoFactory.subposts(account, threadId, postId, forumId, page, subpostId),
            account,
            includeStoken = false,
        )
        val response = transport.protobuf(request, PbFloorResponseOuterClass.PbFloorResponse.parser())
        return TiebaProtoMapper.subposts(response, threadId, page)
    }

    override suspend fun searchThreads(
        keyword: String, page: Int, sortType: Int, filterType: Int, forumName: String?,
    ): SearchPage<SearchThreadResult> {
        val request = TiebaReadRequestFactory.searchThreads(
            keyword, page, requestBuilder, sortType, filterType, forumName,
        )
        return TiebaJsonMapper.searchThreads(transport.text(request))
    }

    override suspend fun searchUsers(keyword: String): SearchPage<SearchUserResult> =
        TiebaJsonMapper.searchUsers(transport.text(TiebaReadRequestFactory.searchUser(keyword, requestBuilder)))

    override suspend fun userProfile(account: Account?, user: UserSummary): UserProfile {
        val context = protoFactory.userProfile(account, user)
        val request = requestBuilder.protobufRequest(
            TiebaEndpoint.UserProfile,
            context.message,
            account,
            includeStoken = false,
            headers = mapOf("X-BD-DATA-TYPE" to "protobuf"),
        )
        return TiebaProtoMapper.userProfile(
            transport.protobuf(request, ProfileProtocol.UserProfileResponse.parser()), user, context.isCurrentUser,
        )
    }

    override suspend fun userThreads(account: Account?, userId: Long, page: Int): UserThreadsPage {
        val request = requestBuilder.protobufRequest(
            TiebaEndpoint.UserThreads,
            protoFactory.userThreads(account, userId, page),
            account,
            includeStoken = true,
            headers = mapOf("X-BD-DATA-TYPE" to "protobuf"),
        )
        return TiebaProtoMapper.userThreads(
            transport.protobuf(request, ProfileProtocol.UserThreadsResponse.parser()),
            page,
            isCurrentUser = account?.uid?.toLongOrNull() == userId,
        )
    }
}
