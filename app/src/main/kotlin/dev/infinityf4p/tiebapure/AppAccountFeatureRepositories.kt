package dev.infinityf4p.tiebapure

import dev.infinityf4p.tiebapure.core.data.AccountMutationRepository
import dev.infinityf4p.tiebapure.core.data.AccountRepository
import dev.infinityf4p.tiebapure.core.data.AuthenticationRepository
import dev.infinityf4p.tiebapure.core.data.BrowsingHistoryEntity
import dev.infinityf4p.tiebapure.core.data.TiebaPureDatabase
import dev.infinityf4p.tiebapure.core.data.TiebaRepositories
import dev.infinityf4p.tiebapure.core.model.Account
import dev.infinityf4p.tiebapure.core.model.BaiduWebCredentials
import dev.infinityf4p.tiebapure.core.model.BrowsingHistoryEntry
import dev.infinityf4p.tiebapure.core.model.ThreadSummary
import dev.infinityf4p.tiebapure.core.model.TiebaContentFilterPolicy
import dev.infinityf4p.tiebapure.core.model.UserRelationshipKind
import dev.infinityf4p.tiebapure.core.model.UserSummary
import dev.infinityf4p.tiebapure.core.model.mutationOutcomeUnknownMessageOrNull
import dev.infinityf4p.tiebapure.feature.account.BaiduLoginCookies
import dev.infinityf4p.tiebapure.feature.account.BrowsingHistoryRepository
import dev.infinityf4p.tiebapure.feature.account.FollowingUpdatesPage
import dev.infinityf4p.tiebapure.feature.account.FollowingUpdatesRepository
import dev.infinityf4p.tiebapure.feature.account.LoginRepository
import dev.infinityf4p.tiebapure.feature.account.MeRepository
import dev.infinityf4p.tiebapure.feature.account.MessagesRepository
import dev.infinityf4p.tiebapure.feature.account.ProfileEditRepository
import dev.infinityf4p.tiebapure.feature.account.ThreadFavoritesRepository
import dev.infinityf4p.tiebapure.feature.account.ThreadFavoriteRemovalResult
import dev.infinityf4p.tiebapure.feature.account.UserProfileRepository
import dev.infinityf4p.tiebapure.feature.account.UserRelationshipRepository
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

class AppAccountFeatureRepositories(
    private val account: StateFlow<Account?>,
    private val saveAccount: suspend (Account) -> Unit,
    private val clearAccount: suspend () -> Unit,
    private val database: TiebaPureDatabase,
    private val repositories: TiebaRepositories,
    private val authenticationRepository: AuthenticationRepository,
    private val accountRepository: AccountRepository,
    private val mutationRepository: AccountMutationRepository,
) {
    private val favoritePostIds = ConcurrentHashMap<Long, ULong>()
    private val historyEntries: Flow<List<BrowsingHistoryEntry>> = combine(
        database.browsingHistoryDao().observeAll(),
        database.blocklistDao().observeAll(),
    ) { entities, blocklist ->
        val snapshot = blocklist.toBlocklistSnapshot()
        entities.map(BrowsingHistoryEntity::toBrowsingHistoryEntry)
            .filter { TiebaContentFilterPolicy.shouldKeep(it.thread, snapshot) }
    }

    val me: MeRepository = object : MeRepository {
        override val account: Flow<Account?> = this@AppAccountFeatureRepositories.account
        override val browsingHistory: Flow<List<BrowsingHistoryEntry>> = historyEntries

        override suspend fun logout() {
            clearAccount()
        }
    }

    val login: LoginRepository = LoginRepository { cookies ->
        val initialAccount = account.value
        val credentials = BaiduWebCredentials(
            bduss = cookies.bduss,
            stoken = cookies.stoken,
            baiduId = cookies.baiduId,
        )
        require(credentials.bduss.isNotBlank() && credentials.stoken.isNotBlank()) {
            "登录凭据不完整，请返回登录页重试。"
        }
        authenticationRepository.validateLogin(credentials).also {
            ensureUnchangedSession(initialAccount)
            saveAccount(it)
        }
    }

    val userProfile: UserProfileRepository = object : UserProfileRepository {
        override suspend fun loadProfile(user: UserSummary) = withAccountSnapshot { snapshot ->
            val blocklist = blocklistSnapshot()
            repositories.user.profile(user, snapshot).let { profile ->
                profile.copy(
                    followedForums = profile.followedForums.filter {
                        TiebaContentFilterPolicy.shouldKeep(it, blocklist)
                    },
                )
            }
        }

        override suspend fun loadThreads(user: UserSummary, page: Int) = withAccountSnapshot { snapshot ->
            val result = repositories.user.threads(user.id, page.coerceAtLeast(1), snapshot)
            val blocklist = blocklistSnapshot()
            val threads = result.threads.filter { TiebaContentFilterPolicy.shouldKeep(it, blocklist) }
            result.copy(
                threads = threads,
                deletionTargetsByThreadId = result.deletionTargetsByThreadId.filterKeys { threadId ->
                    threads.any { it.id == threadId }
                },
            )
        }

        override suspend fun setFollow(user: UserSummary, followed: Boolean): Boolean =
            withRequiredAccount { current ->
                mutationRepository.setUserFollowed(current, user, followed)
                followed
            }

        override suspend fun deleteOwnThread(target: dev.infinityf4p.tiebapure.core.model.OwnThreadDeletionTarget) {
            withRequiredAccount { current ->
                mutationRepository.deleteOwnThread(current, target)
            }
        }
    }

    val relationships: UserRelationshipRepository = UserRelationshipRepository { user, kind, page ->
        withAccountSnapshot { snapshot ->
            accountRepository.relationships(snapshot, user.id, kind, page.coerceAtLeast(1)).let { result ->
                val blocklist = blocklistSnapshot()
                result.copy(users = result.users.filter { TiebaContentFilterPolicy.shouldKeep(it, blocklist) })
            }
        }
    }

    val followingUpdates: FollowingUpdatesRepository = FollowingUpdatesRepository { page ->
        withRequiredAccount { current ->
            val userId = current.uid.toLongOrNull()
                ?: error("当前账号 UID 无效，请重新登录。")
            val requestedPage = page.coerceAtLeast(1)
            val relationships = accountRepository.relationships(
                current,
                userId,
                UserRelationshipKind.Following,
                requestedPage,
            )
            val blocklist = blocklistSnapshot()
            val followedUsers = relationships.users
                .filter { TiebaContentFilterPolicy.shouldKeep(it, blocklist) }
                .distinctBy(UserSummary::id)
            val aggregation = aggregateFollowingUserThreads(followedUsers) { user ->
                repositories.user.threads(user.id, page = 1, account = current).threads
                    .filter { TiebaContentFilterPolicy.shouldKeep(it, blocklist) }
            }
            FollowingUpdatesPage(
                threads = aggregation.threads,
                currentPage = relationships.currentPage,
                followedUserCount = relationships.totalCount,
                hasMore = relationships.hasMore,
                unavailableUserCount = aggregation.unavailableUserCount,
            )
        }
    }

    val messages: MessagesRepository = MessagesRepository { kind, page ->
        withRequiredAccount { current ->
            accountRepository.messages(current, kind, page.coerceAtLeast(1)).let { result ->
                val blocklist = blocklistSnapshot()
                result.copy(messages = result.messages.filter { TiebaContentFilterPolicy.shouldKeep(it, blocklist) })
            }
        }
    }

    val threadFavorites: ThreadFavoritesRepository = object : ThreadFavoritesRepository {
        override suspend fun loadFavorites(page: Int) =
            withRequiredAccount { current ->
                accountRepository.threadFavorites(current, page.coerceAtLeast(1)).let { result ->
                    val blocklist = blocklistSnapshot()
                    result.copy(favorites = result.favorites.filter {
                        TiebaContentFilterPolicy.shouldKeep(it, blocklist)
                    })
                }
            }.also { result ->
                result.favorites.forEach { favorite ->
                    favoritePostIds[favorite.threadId] = favorite.markedPostId ?: 0u
                }
            }

        override suspend fun threadsWithReadingPosition(): Set<Long> =
            database.readingPositionDao().threadIds().toSet()

        override suspend fun removeFavorites(threadIds: Set<Long>): ThreadFavoriteRemovalResult {
            val orderedThreadIds = threadIds.toList().sorted()
            return withRequiredAccount { current ->
                val removed = linkedSetOf<Long>()
                val outcomeUnknown = linkedMapOf<Long, String>()
                val failed = linkedMapOf<Long, String>()
                orderedThreadIds.forEach { threadId ->
                    ensureCurrentSession(current)
                    try {
                        mutationRepository.setThreadFavorite(
                            account = current,
                            threadId = threadId,
                            postId = favoritePostIds[threadId] ?: 0u,
                            favorited = false,
                        )
                        ensureCurrentSession(current)
                        favoritePostIds.remove(threadId)
                        removed += threadId
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Throwable) {
                        ensureCurrentSession(current)
                        val unknown = error.mutationOutcomeUnknownMessageOrNull()
                        if (unknown != null) {
                            outcomeUnknown[threadId] = unknown
                        } else {
                            failed[threadId] = error.message?.trim().takeUnless { it.isNullOrEmpty() }
                                ?: "移除收藏失败，请稍后重试。"
                        }
                    }
                }
                ThreadFavoriteRemovalResult(removed, outcomeUnknown, failed)
            }
        }
    }

    val browsingHistory: BrowsingHistoryRepository = object : BrowsingHistoryRepository {
        override val entries: Flow<List<BrowsingHistoryEntry>> = historyEntries

        override suspend fun removeEntries(threadIds: Set<Long>) {
            threadIds.toList().sorted().forEach { database.browsingHistoryDao().remove(it) }
        }

        override suspend fun clear() {
            database.browsingHistoryDao().clear()
        }
    }

    val profileEdit: ProfileEditRepository = ProfileEditRepository { request ->
        withRequiredAccount { current ->
            mutationRepository.updateOwnProfile(current, request)
            repositories.user.profile(current.toUserSummary(), current)
        }
    }

    private suspend fun <T> withRequiredAccount(block: suspend (Account) -> T): T {
        val snapshot = account.value ?: error("请先登录贴吧账号后再执行此操作。")
        val result = block(snapshot)
        ensureCurrentSession(snapshot)
        return result
    }

    private suspend fun <T> withAccountSnapshot(block: suspend (Account?) -> T): T {
        val snapshot = account.value
        val result = block(snapshot)
        ensureUnchangedSession(snapshot)
        return result
    }

    private fun ensureCurrentSession(expected: Account) {
        val current = account.value ?: error("登录状态已失效，请重新登录。")
        check(current.sessionIdentity() == expected.sessionIdentity()) {
            "登录账号已切换，请重试当前操作。"
        }
    }

    private fun ensureUnchangedSession(expected: Account?) {
        val current = account.value
        check(current?.sessionIdentity() == expected?.sessionIdentity()) {
            "登录状态已变化，请重试。"
        }
    }

    private suspend fun blocklistSnapshot() =
        database.blocklistDao().observeAll().first().toBlocklistSnapshot()
}

internal data class FollowingUpdateAggregation(
    val threads: List<ThreadSummary>,
    val unavailableUserCount: Int,
)

internal suspend fun aggregateFollowingUserThreads(
    users: List<UserSummary>,
    parallelism: Int = 4,
    loadThreads: suspend (UserSummary) -> List<ThreadSummary>,
): FollowingUpdateAggregation {
    require(parallelism > 0) { "parallelism must be positive" }
    val distinctUsers = users.filter { it.id > 0 }.distinctBy(UserSummary::id)
    if (distinctUsers.isEmpty()) return FollowingUpdateAggregation(emptyList(), 0)

    val semaphore = Semaphore(minOf(parallelism, distinctUsers.size))
    val loaded = supervisorScope {
        distinctUsers.map { user ->
            async {
                semaphore.withPermit {
                    try {
                        loadThreads(user)
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: Exception) {
                        null
                    }
                }
            }
        }.awaitAll()
    }
    if (loaded.all { it == null }) {
        error("关注用户帖子加载失败，请稍后重试。")
    }
    return FollowingUpdateAggregation(
        threads = loaded.filterNotNull().flatten()
            .distinctBy(ThreadSummary::id)
            .sortedWith(
                compareByDescending<ThreadSummary> { it.createdAtEpochSeconds ?: Long.MIN_VALUE }
                    .thenByDescending(ThreadSummary::id),
            ),
        unavailableUserCount = loaded.count { it == null },
    )
}

private fun BrowsingHistoryEntity.toBrowsingHistoryEntry(): BrowsingHistoryEntry = BrowsingHistoryEntry(
    thread = ThreadSummary(
        id = threadId,
        title = title,
        author = UserSummary(
            id = 0,
            name = authorName,
            displayName = authorName,
            portrait = "",
        ),
        forumName = forumName,
        replyCount = 0,
        viewCount = 0,
        blocks = emptyList(),
    ),
    visitedAtEpochMilliseconds = visitedAtMilliseconds,
)

private fun Account.toUserSummary(): UserSummary = UserSummary(
    id = uid.toLongOrNull() ?: error("当前账号 UID 无效，请重新登录。"),
    name = name,
    displayName = displayName,
    portrait = portrait,
)
