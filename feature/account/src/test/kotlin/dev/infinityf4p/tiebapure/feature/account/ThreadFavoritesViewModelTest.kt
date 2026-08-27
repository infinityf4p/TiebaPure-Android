package dev.infinityf4p.tiebapure.feature.account

import dev.infinityf4p.tiebapure.core.model.Account
import dev.infinityf4p.tiebapure.core.model.AccountThreadFavorite
import dev.infinityf4p.tiebapure.core.model.AccountThreadFavoritesPage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ThreadFavoritesViewModelTest {
    @Test
    fun confirmedUnfavoriteRemovesImmediatelyAndFiltersStaleRefresh() = withFavoritesScope { scope ->
        val repository = ControllableThreadFavoritesRepository()
        val viewModel = ThreadFavoritesViewModel(account(), repository, scope)
        val first = favorite(1)
        val second = favorite(2)

        repository.calls.single().result.complete(page(first, second))

        assertEquals(listOf(1L, 2L), viewModel.uiState.value.favorites.map(AccountThreadFavorite::threadId))
        assertEquals(setOf(1L, 2L), viewModel.uiState.value.threadsWithReadingPosition)

        viewModel.onCollectionChanged(threadId = 1, collected = false)

        assertEquals(listOf(2L), viewModel.uiState.value.favorites.map(AccountThreadFavorite::threadId))
        assertEquals(setOf(2L), viewModel.uiState.value.threadsWithReadingPosition)
        assertTrue(viewModel.uiState.value.isRefreshing)
        assertEquals(listOf(1, 1), repository.calls.map { it.page })

        repository.calls.last().result.complete(page(first, second))

        assertEquals(listOf(2L), viewModel.uiState.value.favorites.map(AccountThreadFavorite::threadId))
        assertFalse(viewModel.uiState.value.isBusy)
    }
}

private class ControllableThreadFavoritesRepository : ThreadFavoritesRepository {
    data class Call(
        val page: Int,
        val result: CompletableDeferred<AccountThreadFavoritesPage> = CompletableDeferred(),
    )

    val calls = mutableListOf<Call>()

    override suspend fun loadFavorites(page: Int): AccountThreadFavoritesPage {
        val call = Call(page)
        calls += call
        return call.result.await()
    }

    override suspend fun threadsWithReadingPosition(): Set<Long> = setOf(1L, 2L)

    override suspend fun removeFavorites(threadIds: Set<Long>): ThreadFavoriteRemovalResult =
        ThreadFavoriteRemovalResult(removedThreadIds = threadIds)
}

private inline fun withFavoritesScope(block: (CoroutineScope) -> Unit) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    try {
        block(scope)
    } finally {
        scope.cancel()
    }
}

private fun account() = Account(
    uid = "1",
    name = "tester",
    displayName = "测试用户",
    portrait = "",
    bduss = "bduss",
    stoken = "stoken",
    tbs = "tbs",
)

private fun favorite(threadId: Long) = AccountThreadFavorite(
    threadId = threadId,
    forumId = 1,
    forumName = "测试吧",
    title = "帖子$threadId",
    authorDisplayName = "用户$threadId",
    replyCount = 0,
    lastReplyAtEpochSeconds = null,
    markedPostId = threadId.toULong(),
)

private fun page(vararg favorites: AccountThreadFavorite) = AccountThreadFavoritesPage(
    favorites = favorites.toList(),
    currentPage = 1,
    hasMore = false,
)
