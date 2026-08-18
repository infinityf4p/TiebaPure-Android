package dev.infinityf4p.tiebapure.feature.search

import dev.infinityf4p.tiebapure.core.model.ContentBlock
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
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SearchViewModelTest {
    @Test
    fun changingFilterCancelsBusyRequestAndLoadsNewGeneration() = withViewModel { viewModel, repository ->
        viewModel.updateInput("测试")
        viewModel.submit()
        assertTrue(viewModel.uiState.value.isBusy)

        viewModel.selectFilter(SearchFilter.Threads)

        assertEquals(2, repository.calls.size)
        assertEquals(SearchFilter.Threads, repository.calls.last().filter)
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

    private fun withViewModel(block: (SearchViewModel, ControllableSearchRepository) -> Unit) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val repository = ControllableSearchRepository()
            block(SearchViewModel(repository, scope), repository)
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

private fun page(threadId: Long): SearchPage = SearchPage(
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
    currentPage = 1,
    hasMore = false,
)

private fun List<SearchItem>.threadIds(): List<Long> =
    mapNotNull { (it as? SearchItem.ThreadResult)?.thread?.id }
