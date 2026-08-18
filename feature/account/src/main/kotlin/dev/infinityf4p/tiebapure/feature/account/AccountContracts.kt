package dev.infinityf4p.tiebapure.feature.account

import dev.infinityf4p.tiebapure.core.model.Account
import dev.infinityf4p.tiebapure.core.model.AccountThreadFavoritesPage
import dev.infinityf4p.tiebapure.core.model.BrowsingHistoryEntry
import dev.infinityf4p.tiebapure.core.model.MessageKind
import dev.infinityf4p.tiebapure.core.model.MessagePage
import dev.infinityf4p.tiebapure.core.model.OwnThreadDeletionTarget
import dev.infinityf4p.tiebapure.core.model.UserProfile
import dev.infinityf4p.tiebapure.core.model.UserProfileEditRequest
import dev.infinityf4p.tiebapure.core.model.UserRelationshipKind
import dev.infinityf4p.tiebapure.core.model.UserRelationshipPage
import dev.infinityf4p.tiebapure.core.model.UserSummary
import dev.infinityf4p.tiebapure.core.model.UserThreadsPage
import kotlinx.coroutines.flow.Flow

/** Account feature boundaries. Implementations live in core:data. */
interface MeRepository {
    val account: Flow<Account?>
    val browsingHistory: Flow<List<BrowsingHistoryEntry>>
    suspend fun logout()
}

fun interface LoginRepository {
    suspend fun completeLogin(cookies: BaiduLoginCookies): Account
}

interface UserProfileRepository {
    suspend fun loadProfile(user: UserSummary): UserProfile
    suspend fun loadThreads(user: UserSummary, page: Int): UserThreadsPage
    suspend fun setFollow(user: UserSummary, followed: Boolean): Boolean
    suspend fun deleteOwnThread(target: OwnThreadDeletionTarget)
}

fun interface UserRelationshipRepository {
    suspend fun loadUsers(
        user: UserSummary,
        kind: UserRelationshipKind,
        page: Int,
    ): UserRelationshipPage
}

fun interface MessagesRepository {
    suspend fun loadMessages(kind: MessageKind, page: Int): MessagePage
}

interface ThreadFavoritesRepository {
    suspend fun loadFavorites(page: Int): AccountThreadFavoritesPage
    suspend fun threadsWithReadingPosition(): Set<Long>
    suspend fun removeFavorites(threadIds: Set<Long>): ThreadFavoriteRemovalResult
}

data class ThreadFavoriteRemovalResult(
    val removedThreadIds: Set<Long> = emptySet(),
    val outcomeUnknownByThreadId: Map<Long, String> = emptyMap(),
    val failedByThreadId: Map<Long, String> = emptyMap(),
)

interface BrowsingHistoryRepository {
    val entries: Flow<List<BrowsingHistoryEntry>>
    suspend fun removeEntries(threadIds: Set<Long>)
    suspend fun clear()
}

fun interface ProfileEditRepository {
    suspend fun updateProfile(request: UserProfileEditRequest): UserProfile
}

internal fun Throwable.accountReadableMessage(): String =
    message?.trim()?.takeIf(String::isNotEmpty) ?: "操作失败，请稍后重试。"
