package dev.infinityf4p.tiebapure

import dev.infinityf4p.tiebapure.core.data.AccountMutationRepository
import dev.infinityf4p.tiebapure.core.data.AccountRepository
import dev.infinityf4p.tiebapure.core.data.ForumRepository
import dev.infinityf4p.tiebapure.core.data.HomeRepository
import dev.infinityf4p.tiebapure.core.data.ThreadRepository
import dev.infinityf4p.tiebapure.core.data.TiebaRepositories
import dev.infinityf4p.tiebapure.core.data.UserRepository
import dev.infinityf4p.tiebapure.core.model.Account
import dev.infinityf4p.tiebapure.core.model.AccountSessionIdentity
import dev.infinityf4p.tiebapure.core.model.ContentSubmissionRequest
import dev.infinityf4p.tiebapure.core.model.Forum
import dev.infinityf4p.tiebapure.core.model.MessageKind
import dev.infinityf4p.tiebapure.core.model.OwnThreadDeletionTarget
import dev.infinityf4p.tiebapure.core.model.TiebaLikeObjectType
import dev.infinityf4p.tiebapure.core.model.UserProfileEditRequest
import dev.infinityf4p.tiebapure.core.model.UserRelationshipKind
import dev.infinityf4p.tiebapure.core.model.UserSummary
import dev.infinityf4p.tiebapure.core.network.ContentSubmissionException
import dev.infinityf4p.tiebapure.core.network.TiebaApiException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class SessionExpirationNotice(
    val id: Long,
    val message: String = "登录状态已失效，已退出当前账号，请重新登录。",
)

class SessionExpirationCoordinator(
    private val currentAccount: () -> Account?,
    private val logOut: suspend (Account) -> Unit,
) {
    private val mutex = Mutex()
    private val handledSessions = linkedSetOf<AccountSessionIdentity>()
    private val mutableNotice = MutableStateFlow<SessionExpirationNotice?>(null)
    private var nextNoticeId = 0L

    val notice: StateFlow<SessionExpirationNotice?> = mutableNotice.asStateFlow()

    suspend fun report(account: Account) = withContext(NonCancellable) {
        val identity = account.sessionIdentity()
        val shouldHandle = mutex.withLock {
            currentAccount()?.sessionIdentity() == identity && handledSessions.add(identity)
        }
        if (!shouldHandle) return@withContext

        val message = runCatching { logOut(account) }
            .fold(
                onSuccess = { "登录状态已失效，已退出当前账号，请重新登录。" },
                onFailure = { "登录状态已失效，本机登录信息已清除，请重新登录。" },
            )
        mutex.withLock {
            nextNoticeId += 1
            mutableNotice.value = SessionExpirationNotice(nextNoticeId, message)
            while (handledSessions.size > 8) {
                handledSessions.remove(handledSessions.first())
            }
        }
    }

    fun dismissNotice() {
        mutableNotice.value = null
    }
}

private suspend fun <T> monitorSession(
    account: Account?,
    report: suspend (Account) -> Unit,
    request: suspend () -> T,
): T = try {
    request()
} catch (error: Throwable) {
    if (account != null && error.isSessionExpiration()) report(account)
    throw error
}

private fun Throwable.isSessionExpiration(): Boolean =
    this is TiebaApiException.SessionExpired || this is ContentSubmissionException.SessionExpired

fun TiebaRepositories.monitorSessions(report: suspend (Account) -> Unit): TiebaRepositories {
    val base = this
    return copy(
        home = object : HomeRepository {
            override suspend fun threads(account: Account?, page: Int, loadType: Int) =
                monitorSession(account, report) { base.home.threads(account, page, loadType) }
        },
        forum = object : ForumRepository {
            override suspend fun threads(
                forumName: String,
                page: Int,
                category: dev.infinityf4p.tiebapure.core.model.ForumThreadCategory,
                account: Account?,
            ) = monitorSession(account, report) { base.forum.threads(forumName, page, category, account) }

            override suspend fun info(forumName: String) = base.forum.info(forumName)
        },
        thread = object : ThreadRepository {
            override suspend fun page(
                threadId: Long,
                page: Int,
                forumId: Long?,
                postId: ULong?,
                onlyThreadAuthor: Boolean,
                sort: dev.infinityf4p.tiebapure.core.model.ThreadReplySort,
                account: Account?,
            ) = monitorSession(account, report) {
                base.thread.page(threadId, page, forumId, postId, onlyThreadAuthor, sort, account)
            }

            override suspend fun subposts(
                threadId: Long,
                postId: ULong,
                forumId: Long,
                page: Int,
                subpostId: ULong,
                account: Account?,
            ) = monitorSession(account, report) {
                base.thread.subposts(threadId, postId, forumId, page, subpostId, account)
            }
        },
        user = object : UserRepository {
            override suspend fun profile(user: UserSummary, account: Account?) =
                monitorSession(account, report) { base.user.profile(user, account) }

            override suspend fun threads(userId: Long, page: Int, account: Account?) =
                monitorSession(account, report) { base.user.threads(userId, page, account) }
        },
    )
}

fun AccountRepository.monitorSessions(report: suspend (Account) -> Unit): AccountRepository {
    val base = this
    return object : AccountRepository {
        override suspend fun followedForums(account: Account) =
            monitorSession(account, report) { base.followedForums(account) }

        override suspend fun relationships(
            account: Account?,
            userId: Long,
            kind: UserRelationshipKind,
            page: Int,
        ) = monitorSession(account, report) { base.relationships(account, userId, kind, page) }

        override suspend fun messages(account: Account, kind: MessageKind, page: Int) =
            monitorSession(account, report) { base.messages(account, kind, page) }

        override suspend fun threadFavorites(account: Account, page: Int) =
            monitorSession(account, report) { base.threadFavorites(account, page) }

        override suspend fun resolveForumId(forumName: String) = base.resolveForumId(forumName)

        override suspend fun forumMembership(account: Account, forumId: Long) =
            monitorSession(account, report) { base.forumMembership(account, forumId) }
    }
}

fun AccountMutationRepository.monitorSessions(
    report: suspend (Account) -> Unit,
): AccountMutationRepository {
    val base = this
    return object : AccountMutationRepository {
        override suspend fun activateSession(account: Account) = base.activateSession(account)
        override suspend fun invalidateAndDrain(account: Account) = base.invalidateAndDrain(account)

        override suspend fun setUserFollowed(account: Account, user: UserSummary, followed: Boolean) =
            monitorSession(account, report) { base.setUserFollowed(account, user, followed) }

        override suspend fun setForumFollowed(account: Account, forum: Forum, followed: Boolean) =
            monitorSession(account, report) { base.setForumFollowed(account, forum, followed) }

        override suspend fun setPostLiked(
            account: Account,
            threadId: Long,
            postId: ULong,
            objectType: TiebaLikeObjectType,
            liked: Boolean,
        ) = monitorSession(account, report) {
            base.setPostLiked(account, threadId, postId, objectType, liked)
        }

        override suspend fun setThreadFavorite(
            account: Account,
            threadId: Long,
            postId: ULong,
            favorited: Boolean,
        ) = monitorSession(account, report) {
            base.setThreadFavorite(account, threadId, postId, favorited)
        }

        override suspend fun signForum(account: Account, forum: Forum) =
            monitorSession(account, report) { base.signForum(account, forum) }

        override suspend fun updateOwnProfile(account: Account, request: UserProfileEditRequest) =
            monitorSession(account, report) { base.updateOwnProfile(account, request) }

        override suspend fun deleteOwnThread(account: Account, target: OwnThreadDeletionTarget) =
            monitorSession(account, report) { base.deleteOwnThread(account, target) }

        override suspend fun submitContent(account: Account, request: ContentSubmissionRequest) =
            monitorSession(account, report) { base.submitContent(account, request) }
    }
}
