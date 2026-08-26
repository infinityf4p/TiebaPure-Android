package dev.infinityf4p.tiebapure.feature.search

import dev.infinityf4p.tiebapure.core.model.ContentBlock
import dev.infinityf4p.tiebapure.core.model.Forum
import dev.infinityf4p.tiebapure.core.model.ThreadSummary
import dev.infinityf4p.tiebapure.core.model.UserSummary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withContext
import org.junit.Test
import java.io.IOException
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SearchViewModelTest {
    @Test
    fun changingFilterCancelsBusyRequestAndLoadsNewGeneration() = withViewModel { viewModel, repository ->
        viewModel.updateInput("测试")
        viewModel.submit()
        assertTrue(viewModel.uiState.value.isBusy)

        viewModel.selectFilter(SearchFilter.Forums)

        assertEquals(2, repository.calls.size)
        assertEquals(SearchFilter.Forums, repository.calls.last().filter)
        assertTrue(repository.calls.first().cancelled.isCompleted)

        repository.calls.last().result.complete(page(threadId = 2))

        assertFalse(viewModel.uiState.value.isBusy)
        assertEquals(listOf(2L), viewModel.uiState.value.items.threadIds())
    }

    @Test
    fun changingSortCancelsBusyRequestWithoutLeavingLoadingStuck() = withViewModel { viewModel, repository ->
        viewModel.updateInput("测试")
        viewModel.submit()
        viewModel.selectSort(SearchSort.Oldest)

        assertEquals(SearchSort.Oldest, repository.calls.last().sort)
        assertTrue(repository.calls.first().cancelled.isCompleted)

        repository.calls.last().result.complete(page(threadId = 3))

        assertFalse(viewModel.uiState.value.isBusy)
        assertEquals(listOf(3L), viewModel.uiState.value.items.threadIds())
    }

    @Test
    fun submittingNewQuerySupersedesBusyRequestAndIgnoresOldResult() = withViewModel { viewModel, repository ->
        repository.nextCallIgnoresCancellation = true
        viewModel.updateInput("旧关键词")
        viewModel.submit()
        val oldCall = repository.calls.single()

        viewModel.updateInput("新关键词")
        viewModel.submit()
        val newCall = repository.calls.last()

        assertEquals("新关键词", newCall.keyword)
        assertTrue(oldCall.cancelled.isCompleted)
        newCall.result.complete(page(threadId = 4))
        oldCall.result.complete(page(threadId = 1))

        assertEquals("新关键词", viewModel.uiState.value.submittedKeyword)
        assertEquals(listOf(4L), viewModel.uiState.value.items.threadIds())
        assertFalse(viewModel.uiState.value.isBusy)
    }

    @Test
    fun retryRepeatsRefreshWhenCachedPageHasNoMoreItems() = withViewModel { viewModel, repository ->
        viewModel.updateInput("测试")
        viewModel.submit()
        repository.calls.single().result.complete(page(threadId = 1, hasMore = false))

        viewModel.refresh()
        repository.calls.last().result.completeExceptionally(IllegalStateException("刷新失败"))

        assertEquals(listOf(1, 1), repository.calls.map(ControllableSearchRepository.Call::page))
        assertEquals(listOf(1L), viewModel.uiState.value.items.threadIds())
        viewModel.retry()

        assertEquals(listOf(1, 1, 1), repository.calls.map(ControllableSearchRepository.Call::page))
        repository.calls.last().result.complete(page(threadId = 2, hasMore = false))
    }

    @Test
    fun retryRepeatsFailedPaginationPage() = withViewModel { viewModel, repository ->
        viewModel.updateInput("测试")
        viewModel.submit()
        repository.calls.single().result.complete(page(threadId = 1, hasMore = true))

        viewModel.loadMore()
        repository.calls.last().result.completeExceptionally(IllegalStateException("分页失败"))
        viewModel.retry()

        assertEquals(listOf(1, 2, 2), repository.calls.map(ControllableSearchRepository.Call::page))
        repository.calls.last().result.complete(page(threadId = 2, currentPage = 2, hasMore = false))
    }

    @Test
    fun failureDoesNotExposeRawTransportDetails() = withViewModel { viewModel, repository ->
        viewModel.updateInput("测试")
        viewModel.submit()
        repository.calls.single().result.completeExceptionally(
            IOException("unexpected end of stream on https://tieba.baidu.com/f/search/res?kw=test"),
        )

        assertEquals(SEARCH_FAILURE_MESSAGE, viewModel.uiState.value.errorMessage)
        assertFalse(viewModel.uiState.value.errorMessage.orEmpty().contains("tieba.baidu.com"))
    }

    @Test
    fun forumOnlySearchKeepsExistingFiltersAndRejectsForumSearch() = withViewModel(
        searchScope = SearchScope.ForumOnly(Forum(1, "测试", "测试吧")),
    ) { viewModel, repository ->
        assertEquals(SearchFilter.All, viewModel.uiState.value.filter)

        viewModel.updateInput("关键词")
        viewModel.submit()
        assertEquals(SearchFilter.All, repository.calls.single().filter)

        viewModel.selectFilter(SearchFilter.Forums)

        assertEquals(SearchFilter.All, viewModel.uiState.value.filter)
        assertEquals(1, repository.calls.size)
    }

    private fun withViewModel(
        searchScope: SearchScope = SearchScope.Global,
        block: (SearchViewModel, ControllableSearchRepository) -> Unit,
    ) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val repository = ControllableSearchRepository()
            block(SearchViewModel(repository, scope, searchScope), repository)
        } finally {
            scope.cancel()
        }
    }
}

private class ControllableSearchRepository : SearchRepository {
    data class Call(
        val keyword: String,
        val filter: SearchFilter,
        val sort: SearchSort,
        val page: Int,
        val ignoresCancellation: Boolean,
        val result: CompletableDeferred<SearchPage> = CompletableDeferred(),
        val cancelled: CompletableDeferred<Unit> = CompletableDeferred(),
    )

    val calls = mutableListOf<Call>()
    var nextCallIgnoresCancellation = false

    override suspend fun search(
        keyword: String,
        scope: SearchScope,
        filter: SearchFilter,
        sort: SearchSort,
        page: Int,
    ): SearchPage {
        val call = Call(keyword, filter, sort, page, nextCallIgnoresCancellation)
        nextCallIgnoresCancellation = false
        calls += call
        return try {
            call.result.await()
        } catch (error: CancellationException) {
            call.cancelled.complete(Unit)
            if (!call.ignoresCancellation) throw error
            withContext(NonCancellable) { call.result.await() }
        }
    }

    override suspend fun history(): List<String> = emptyList()
    override suspend fun recordHistory(keyword: String) = Unit
    override suspend fun removeHistory(keyword: String) = Unit
    override suspend fun clearHistory() = Unit
}

private fun page(
    threadId: Long,
    currentPage: Int = 1,
    hasMore: Boolean = false,
): SearchPage = SearchPage(
    items = listOf(
        SearchItem.ThreadResult(
            ThreadSummary(
                id = threadId,
                title = "结果 $threadId",
                author = UserSummary(threadId, "user-$threadId", "用户 $threadId", ""),
                forumName = "测试",
                replyCount = 0,
                viewCount = 0,
                blocks = listOf(ContentBlock.Text("结果")),
            ),
        ),
    ),
    currentPage = currentPage,
    hasMore = hasMore,
)

private fun List<SearchItem>.threadIds(): List<Long> =
    mapNotNull { (it as? SearchItem.ThreadResult)?.thread?.id }
