package dev.infinityf4p.tiebapure.core.data

import dev.infinityf4p.tiebapure.core.model.Account
import dev.infinityf4p.tiebapure.core.model.AccountThreadFavoritesPage
import dev.infinityf4p.tiebapure.core.model.BaiduWebCredentials
import dev.infinityf4p.tiebapure.core.model.ContentSubmissionReceipt
import dev.infinityf4p.tiebapure.core.model.ContentSubmissionRequest
import dev.infinityf4p.tiebapure.core.model.Forum
import dev.infinityf4p.tiebapure.core.model.ForumMembership
import dev.infinityf4p.tiebapure.core.model.ForumSignResult
import dev.infinityf4p.tiebapure.core.model.MessageKind
import dev.infinityf4p.tiebapure.core.model.MessagePage
import dev.infinityf4p.tiebapure.core.model.OwnThreadDeletionTarget
import dev.infinityf4p.tiebapure.core.model.TiebaLikeObjectType
import dev.infinityf4p.tiebapure.core.model.UserProfileEditRequest
import dev.infinityf4p.tiebapure.core.model.UserRelationshipKind
import dev.infinityf4p.tiebapure.core.model.UserRelationshipPage
import dev.infinityf4p.tiebapure.core.model.UserSummary
import dev.infinityf4p.tiebapure.core.network.TiebaAccountReadService
import dev.infinityf4p.tiebapure.core.network.TiebaAuthenticationService
import dev.infinityf4p.tiebapure.core.network.TiebaWriteService

interface AuthenticationRepository {
    suspend fun validateLogin(credentials: BaiduWebCredentials): Account
}

interface AccountRepository {
    suspend fun followedForums(account: Account): List<Forum>
    suspend fun relationships(
        account: Account?,
        userId: Long,
        kind: UserRelationshipKind,
        page: Int,
    ): UserRelationshipPage
    suspend fun messages(account: Account, kind: MessageKind, page: Int): MessagePage
    suspend fun threadFavorites(account: Account, page: Int): AccountThreadFavoritesPage
    suspend fun resolveForumId(forumName: String): Long
    suspend fun forumMembership(account: Account, forumId: Long): ForumMembership
}

interface AccountMutationRepository {
    suspend fun activateSession(account: Account)
    suspend fun invalidateAndDrain(account: Account)
    suspend fun setUserFollowed(account: Account, user: UserSummary, followed: Boolean)
    suspend fun setForumFollowed(account: Account, forum: Forum, followed: Boolean): ForumMembership
    suspend fun setPostLiked(
        account: Account,
        threadId: Long,
        postId: ULong,
        objectType: TiebaLikeObjectType,
        liked: Boolean,
    )
    suspend fun setThreadFavorite(account: Account, threadId: Long, postId: ULong, favorited: Boolean)
    suspend fun signForum(account: Account, forum: Forum): ForumSignResult
    suspend fun updateOwnProfile(account: Account, request: UserProfileEditRequest)
    suspend fun deleteOwnThread(account: Account, target: OwnThreadDeletionTarget)
    suspend fun submitContent(account: Account, request: ContentSubmissionRequest): ContentSubmissionReceipt
}

class NetworkAuthenticationRepository(
    private val service: TiebaAuthenticationService,
) : AuthenticationRepository {
    override suspend fun validateLogin(credentials: BaiduWebCredentials) = service.validateLogin(credentials)
}

class NetworkAccountRepository(
    private val service: TiebaAccountReadService,
) : AccountRepository {
    override suspend fun followedForums(account: Account) = service.followedForums(account)
    override suspend fun relationships(account: Account?, userId: Long, kind: UserRelationshipKind, page: Int) =
        service.relationships(account, userId, kind, page)
    override suspend fun messages(account: Account, kind: MessageKind, page: Int) = service.messages(account, kind, page)
    override suspend fun threadFavorites(account: Account, page: Int) = service.threadFavorites(account, page)
    override suspend fun resolveForumId(forumName: String) = service.resolveForumId(forumName)
    override suspend fun forumMembership(account: Account, forumId: Long) = service.forumMembership(account, forumId)
}

class NetworkAccountMutationRepository(
    private val service: TiebaWriteService,
) : AccountMutationRepository {
    override suspend fun activateSession(account: Account) = service.activateSession(account)
    override suspend fun invalidateAndDrain(account: Account) = service.invalidateAndDrain(account)
    override suspend fun setUserFollowed(account: Account, user: UserSummary, followed: Boolean) =
        service.setUserFollowed(account, user, followed)
    override suspend fun setForumFollowed(account: Account, forum: Forum, followed: Boolean) =
        service.setForumFollowed(account, forum, followed)
    override suspend fun setPostLiked(
        account: Account, threadId: Long, postId: ULong, objectType: TiebaLikeObjectType, liked: Boolean,
    ) = service.setPostLiked(account, threadId, postId, objectType, liked)
    override suspend fun setThreadFavorite(account: Account, threadId: Long, postId: ULong, favorited: Boolean) =
        service.setThreadFavorite(account, threadId, postId, favorited)
    override suspend fun signForum(account: Account, forum: Forum) = service.signForum(account, forum)
    override suspend fun updateOwnProfile(account: Account, request: UserProfileEditRequest) =
        service.updateOwnProfile(account, request)
    override suspend fun deleteOwnThread(account: Account, target: OwnThreadDeletionTarget) =
        service.deleteOwnThread(account, target)
    override suspend fun submitContent(account: Account, request: ContentSubmissionRequest) =
        service.submitContent(account, request)
}
