package dev.infinityf4p.tiebapure.feature.account

import dev.infinityf4p.tiebapure.core.model.Account
import dev.infinityf4p.tiebapure.core.model.ThreadSummary
import dev.infinityf4p.tiebapure.core.model.UserSummary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FollowingUpdatesViewModelTest {
    @Test
    fun constructorLoadsAndSortsNewestThreadsFirst() {
        withScope { scope ->
            val repository = QueuedFollowingUpdatesRepository(
                listOf(Result.success(page(1, hasMore = true, threads = listOf(thread(1, 10), thread(2, 30))))),
            )

            val viewModel = FollowingUpdatesViewModel(account(), repository, scope)

            assertEquals(listOf(2L, 1L), viewModel.uiState.value.threads.map(ThreadSummary::id))
            assertEquals(2, viewModel.uiState.value.nextPage)
            assertTrue(viewModel.uiState.value.hasMore)
            assertFalse(viewModel.uiState.value.isBusy)
            assertEquals(listOf(1), repository.calls)
        }
    }

    @Test
    fun loadMoreFailureRetriesSamePageAndMergesWithoutDuplicates() {
        withScope { scope ->
            val repository = QueuedFollowingUpdatesRepository(
                listOf(
                    Result.success(page(1, hasMore = true, threads = listOf(thread(1, 10)))),
                    Result.failure(IllegalStateException("下一页失败")),
                    Result.success(page(2, hasMore = false, threads = listOf(thread(1, 10), thread(2, 20)))),
                ),
            )
            val viewModel = FollowingUpdatesViewModel(account(), repository, scope)

            viewModel.loadMore()

            assertEquals(FollowingUpdatesFailedOperation.LoadMore, viewModel.uiState.value.failedOperation)
            assertEquals("下一页失败", viewModel.uiState.value.errorMessage)
            assertEquals(2, viewModel.uiState.value.nextPage)

            viewModel.retry()

            assertEquals(listOf(1, 2, 2), repository.calls)
            assertEquals(listOf(2L, 1L), viewModel.uiState.value.threads.map(ThreadSummary::id))
            assertFalse(viewModel.uiState.value.hasMore)
            assertEquals(null, viewModel.uiState.value.errorMessage)
        }
    }

    @Test
    fun refreshFailureKeepsExistingThreadsAndRetriesRefresh() {
        withScope { scope ->
            val repository = QueuedFollowingUpdatesRepository(
                listOf(
                    Result.success(page(1, hasMore = false, threads = listOf(thread(1, 10)), followedUserCount = 5)),
                    Result.failure(IllegalStateException("刷新失败")),
                    Result.success(page(1, hasMore = false, threads = listOf(thread(2, 20)), followedUserCount = 2)),
                ),
            )
            val viewModel = FollowingUpdatesViewModel(account(), repository, scope)

            viewModel.refresh()

            assertEquals(listOf(1L), viewModel.uiState.value.threads.map(ThreadSummary::id))
            assertEquals(FollowingUpdatesFailedOperation.Refresh, viewModel.uiState.value.failedOperation)

            viewModel.retry()

            assertEquals(listOf(1, 1, 1), repository.calls)
            assertEquals(listOf(2L), viewModel.uiState.value.threads.map(ThreadSummary::id))
            assertEquals(2, viewModel.uiState.value.followedUserCount)
            assertEquals(null, viewModel.uiState.value.errorMessage)
        }
    }

    @Test
    fun loggedOutAccountDoesNotLoad() {
        withScope { scope ->
            val repository = QueuedFollowingUpdatesRepository(
                listOf(Result.success(page(1, hasMore = false, threads = emptyList()))),
            )

            val viewModel = FollowingUpdatesViewModel(null, repository, scope)

            assertFalse(viewModel.uiState.value.isLoggedIn)
            assertTrue(repository.calls.isEmpty())
        }
    }

    @Test
    fun duplicateRefreshAndLoadMoreAreIgnoredDuringInitialLoad() {
        withScope { scope ->
            val repository = BlockingFollowingUpdatesRepository()
            val viewModel = FollowingUpdatesViewModel(account(), repository, scope)

            viewModel.refresh()
            viewModel.loadMore()

            assertEquals(listOf(1), repository.calls)
            assertTrue(viewModel.uiState.value.isInitialLoading)

            repository.result.complete(page(1, hasMore = false, threads = emptyList()))

            assertFalse(viewModel.uiState.value.isBusy)
        }
    }

    private fun withScope(block: (CoroutineScope) -> Unit) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            block(scope)
        } finally {
            scope.cancel()
        }
    }
}

private class BlockingFollowingUpdatesRepository : FollowingUpdatesRepository {
    val result = CompletableDeferred<FollowingUpdatesPage>()
    val calls = mutableListOf<Int>()

    override suspend fun loadPage(page: Int): FollowingUpdatesPage {
        calls += page
        return result.await()
    }
}

private class QueuedFollowingUpdatesRepository(
    results: List<Result<FollowingUpdatesPage>>,
) : FollowingUpdatesRepository {
    private val results = ArrayDeque(results)
    val calls = mutableListOf<Int>()

    override suspend fun loadPage(page: Int): FollowingUpdatesPage {
        calls += page
        return results.removeFirst().getOrThrow()
    }
}

private fun page(
    currentPage: Int,
    hasMore: Boolean,
    threads: List<ThreadSummary>,
    followedUserCount: Int = 2,
) = FollowingUpdatesPage(
    threads = threads,
    currentPage = currentPage,
    followedUserCount = followedUserCount,
    hasMore = hasMore,
)

private fun thread(id: Long, createdAt: Long) = ThreadSummary(
    id = id,
    title = "帖子$id",
    author = UserSummary(id, "user$id", "用户$id", ""),
    replyCount = 0,
    viewCount = 0,
    createdAtEpochSeconds = createdAt,
    blocks = emptyList(),
)

private fun account() = Account(
    uid = "1",
    name = "tester",
    displayName = "测试用户",
    portrait = "",
    bduss = "bduss",
    stoken = "stoken",
    tbs = "tbs",
)
