package dev.infinityf4p.tiebapure.feature.home

import dev.infinityf4p.tiebapure.core.model.ThreadSummary
import dev.infinityf4p.tiebapure.core.model.UserSummary
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun retryRepeatsRefreshWhenCachedPageHasNoMoreItems() = runTest(dispatcher) {
        val repository = ControllableHomeRepository()
        val viewModel = HomeViewModel(repository)
        runCurrent()
        repository.calls.single().result.complete(homePage(threadId = 1, hasMore = false))
        advanceUntilIdle()

        viewModel.refresh()
        runCurrent()
        repository.calls.last().result.completeExceptionally(IllegalStateException("刷新失败"))
        advanceUntilIdle()

        assertEquals(listOf(1, 1), repository.calls.map(ControllableHomeRepository.Call::page))
        assertEquals(listOf(1L), viewModel.uiState.value.threads.map(ThreadSummary::id))
        viewModel.retry()
        runCurrent()

        assertEquals(listOf(1, 1, 1), repository.calls.map(ControllableHomeRepository.Call::page))
        repository.calls.last().result.complete(homePage(threadId = 2, hasMore = false))
        advanceUntilIdle()
    }

    @Test
    fun retryRepeatsFailedPaginationPage() = runTest(dispatcher) {
        val repository = ControllableHomeRepository()
        val viewModel = HomeViewModel(repository)
        runCurrent()
        repository.calls.single().result.complete(homePage(threadId = 1, hasMore = true))
        advanceUntilIdle()

        viewModel.loadMore()
        runCurrent()
        repository.calls.last().result.completeExceptionally(IllegalStateException("分页失败"))
        advanceUntilIdle()
        viewModel.retry()
        runCurrent()

        assertEquals(listOf(1, 2, 2), repository.calls.map(ControllableHomeRepository.Call::page))
        repository.calls.last().result.complete(homePage(threadId = 2, currentPage = 2, hasMore = false))
        advanceUntilIdle()
    }
}

private class ControllableHomeRepository : HomeRepository {
    data class Call(
        val page: Int,
        val result: CompletableDeferred<HomeFeedPage> = CompletableDeferred(),
    )

    val calls = mutableListOf<Call>()

    override suspend fun loadFeed(page: Int): HomeFeedPage {
        val call = Call(page)
        calls += call
        return call.result.await()
    }
}

private fun homePage(
    threadId: Long,
    currentPage: Int = 1,
    hasMore: Boolean,
) = HomeFeedPage(
    threads = listOf(
        ThreadSummary(
            id = threadId,
            title = "帖子$threadId",
            author = UserSummary(threadId, "user-$threadId", "用户$threadId", ""),
            replyCount = 0,
            viewCount = 0,
            blocks = emptyList(),
        ),
    ),
    currentPage = currentPage,
    hasMore = hasMore,
)
