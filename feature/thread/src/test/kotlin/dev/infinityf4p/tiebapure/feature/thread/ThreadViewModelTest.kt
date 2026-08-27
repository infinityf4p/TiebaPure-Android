package dev.infinityf4p.tiebapure.feature.thread

import dev.infinityf4p.tiebapure.core.model.ContentBlock
import dev.infinityf4p.tiebapure.core.model.Forum
import dev.infinityf4p.tiebapure.core.model.MutationOutcomeUnknown
import dev.infinityf4p.tiebapure.core.model.Post
import dev.infinityf4p.tiebapure.core.model.Subpost
import dev.infinityf4p.tiebapure.core.model.SubpostPage
import dev.infinityf4p.tiebapure.core.model.ThreadPage
import dev.infinityf4p.tiebapure.core.model.ThreadReplySort
import dev.infinityf4p.tiebapure.core.model.ThreadSummary
import dev.infinityf4p.tiebapure.core.model.TiebaLikeObjectType
import dev.infinityf4p.tiebapure.core.model.UserSummary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ThreadViewModelTest {
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
    fun firstPageExcludesMainPostAndPaginationDeduplicates() = runTest(dispatcher) {
        val repository = FixtureThreadRepository()
        val viewModel = ThreadViewModel(42, repository)
        advanceUntilIdle()

        assertEquals(listOf(2uL, 3uL), viewModel.state.value.posts.map(Post::id))
        assertFalse(viewModel.state.value.isInitialLoading)

        viewModel.loadMore()
        advanceUntilIdle()

        assertEquals(listOf(2uL, 3uL, 4uL), viewModel.state.value.posts.map(Post::id))
        assertEquals(2, viewModel.state.value.page?.currentPage)
    }

    @Test
    fun changingSortReloadsFirstPageWithNewFilter() = runTest(dispatcher) {
        val repository = FixtureThreadRepository()
        val viewModel = ThreadViewModel(42, repository)
        advanceUntilIdle()

        viewModel.selectSort(ThreadReplySort.Descending)
        advanceUntilIdle()

        assertEquals(ThreadReplySort.Descending, repository.threadRequests.last().sort)
        assertEquals(1, repository.threadRequests.last().page)
        assertEquals(ThreadReplySort.Descending, viewModel.state.value.sort)
    }

    @Test
    fun failedFilterReloadClearsPreviousRepliesAndRetriesSelectedFilter() = runTest(dispatcher) {
        val repository = ControllableThreadRepository()
        val viewModel = ThreadViewModel(42, repository)
        runCurrent()
        repository.threadCalls.single().result.complete(controllableThreadPage(replyId = 2u))
        advanceUntilIdle()
        assertEquals(listOf(2uL), viewModel.state.value.posts.map(Post::id))

        viewModel.selectSort(ThreadReplySort.Descending)

        assertEquals(ThreadReplySort.Descending, viewModel.state.value.sort)
        assertNull(viewModel.state.value.page)
        assertTrue(viewModel.state.value.posts.isEmpty())
        assertTrue(viewModel.state.value.isInitialLoading)
        runCurrent()
        repository.threadCalls.last().result.completeExceptionally(IllegalStateException("筛选加载失败"))
        advanceUntilIdle()

        assertNull(viewModel.state.value.page)
        assertTrue(viewModel.state.value.posts.isEmpty())
        assertFalse(viewModel.state.value.isInitialLoading)
        assertEquals("筛选加载失败", viewModel.state.value.errorMessage)

        viewModel.refresh()

        assertTrue(viewModel.state.value.isInitialLoading)
        assertFalse(viewModel.state.value.isRefreshing)
        assertNull(viewModel.state.value.errorMessage)
        runCurrent()
        val retry = repository.threadCalls.last()
        assertEquals(ThreadReplySort.Descending, retry.sort)
        retry.result.complete(controllableThreadPage(replyId = 90u))
        advanceUntilIdle()

        assertEquals(listOf(90uL), viewModel.state.value.posts.map(Post::id))
        assertFalse(viewModel.state.value.isInitialLoading)
        assertNull(viewModel.state.value.errorMessage)
    }

    @Test
    fun rapidFilterChangesIgnoreSupersededInitialResults() = runTest(dispatcher) {
        val repository = ControllableThreadRepository()
        val viewModel = ThreadViewModel(42, repository)
        runCurrent()
        val initial = repository.threadCalls.single()

        viewModel.selectSort(ThreadReplySort.Descending)
        runCurrent()
        val sorted = repository.threadCalls.last()
        viewModel.setOnlyThreadAuthor(true)
        runCurrent()
        val filtered = repository.threadCalls.last()

        assertTrue(initial.cancelled.isCompleted)
        assertTrue(sorted.cancelled.isCompleted)
        assertEquals(ThreadReplySort.Descending, filtered.sort)
        assertTrue(filtered.onlyAuthor)

        filtered.result.complete(controllableThreadPage(replyId = 30u))
        runCurrent()
        sorted.result.complete(controllableThreadPage(replyId = 20u))
        initial.result.complete(controllableThreadPage(replyId = 10u))
        advanceUntilIdle()

        assertEquals(ThreadReplySort.Descending, viewModel.state.value.sort)
        assertTrue(viewModel.state.value.onlyThreadAuthor)
        assertEquals(listOf(30uL), viewModel.state.value.posts.map(Post::id))
        assertFalse(viewModel.state.value.isInitialLoading)
    }

    @Test
    fun newerRefreshWinsWhenRequestKeysAreIdentical() = runTest(dispatcher) {
        val repository = ControllableThreadRepository()
        val viewModel = ThreadViewModel(42, repository)
        runCurrent()
        repository.threadCalls.single().result.complete(controllableThreadPage(replyId = 2u))
        advanceUntilIdle()

        viewModel.refresh()

        assertEquals(listOf(2uL), viewModel.state.value.posts.map(Post::id))
        assertFalse(viewModel.state.value.isInitialLoading)
        assertTrue(viewModel.state.value.isRefreshing)
        runCurrent()
        val oldRefresh = repository.threadCalls.last()
        viewModel.refresh()
        runCurrent()
        val newRefresh = repository.threadCalls.last()

        assertTrue(oldRefresh.cancelled.isCompleted)
        newRefresh.result.complete(controllableThreadPage(replyId = 40u))
        runCurrent()
        oldRefresh.result.complete(controllableThreadPage(replyId = 50u))
        advanceUntilIdle()

        assertEquals(listOf(40uL), viewModel.state.value.posts.map(Post::id))
        assertFalse(viewModel.state.value.isRefreshing)
    }

    @Test
    fun cachedRefreshFailureKeepsRepliesAndRetriesFirstPage() = runTest(dispatcher) {
        val repository = ControllableThreadRepository()
        val viewModel = ThreadViewModel(42, repository)
        runCurrent()
        repository.threadCalls.single().result.complete(controllableThreadPage(replyId = 2u, hasMore = false))
        advanceUntilIdle()

        viewModel.refresh()
        runCurrent()
        repository.threadCalls.last().result.completeExceptionally(IllegalStateException("刷新失败"))
        advanceUntilIdle()

        assertEquals(listOf(2uL), viewModel.state.value.posts.map(Post::id))
        assertEquals("刷新失败", viewModel.state.value.errorMessage)
        assertFalse(viewModel.state.value.isRefreshing)

        viewModel.retry()
        runCurrent()
        val retry = repository.threadCalls.last()
        assertEquals(1, retry.page)
        retry.result.complete(controllableThreadPage(replyId = 3u, hasMore = false))
        advanceUntilIdle()

        assertEquals(listOf(3uL), viewModel.state.value.posts.map(Post::id))
        assertNull(viewModel.state.value.errorMessage)
    }

    @Test
    fun paginationFailureKeepsRepliesAndRetriesNextPage() = runTest(dispatcher) {
        val repository = ControllableThreadRepository()
        val viewModel = ThreadViewModel(42, repository)
        runCurrent()
        repository.threadCalls.single().result.complete(controllableThreadPage(replyId = 2u, hasMore = true))
        advanceUntilIdle()

        viewModel.loadMore()
        runCurrent()
        repository.threadCalls.last().result.completeExceptionally(IllegalStateException("分页失败"))
        advanceUntilIdle()

        assertEquals(listOf(2uL), viewModel.state.value.posts.map(Post::id))
        assertEquals("分页失败", viewModel.state.value.errorMessage)
        assertFalse(viewModel.state.value.isLoadingMore)

        viewModel.retry()
        runCurrent()
        val retry = repository.threadCalls.last()
        assertEquals(2, retry.page)
        retry.result.complete(controllableThreadPage(replyId = 3u, page = 2, hasMore = false))
        advanceUntilIdle()

        assertEquals(listOf(2uL, 3uL), viewModel.state.value.posts.map(Post::id))
        assertNull(viewModel.state.value.errorMessage)
    }

    @Test
    fun stalePaginationCannotAppendAfterFilterReload() = runTest(dispatcher) {
        val repository = ControllableThreadRepository()
        val viewModel = ThreadViewModel(42, repository)
        runCurrent()
        repository.threadCalls.single().result.complete(
            controllableThreadPage(replyId = 2u, hasMore = true),
        )
        advanceUntilIdle()

        viewModel.loadMore()
        runCurrent()
        val pagination = repository.threadCalls.last()
        assertEquals(2, pagination.page)

        viewModel.selectSort(ThreadReplySort.Descending)
        runCurrent()
        val reload = repository.threadCalls.last()
        assertTrue(pagination.cancelled.isCompleted)

        reload.result.complete(controllableThreadPage(replyId = 60u))
        runCurrent()
        pagination.result.complete(controllableThreadPage(replyId = 70u, page = 2))
        advanceUntilIdle()

        assertEquals(ThreadReplySort.Descending, viewModel.state.value.sort)
        assertEquals(1, viewModel.state.value.page?.currentPage)
        assertEquals(listOf(60uL), viewModel.state.value.posts.map(Post::id))
        assertFalse(viewModel.state.value.isLoadingMore)
    }

    @Test
    fun configuredDefaultSortIsUsedForTheFirstRequest() = runTest(dispatcher) {
        val repository = FixtureThreadRepository()

        val viewModel = ThreadViewModel(42, repository, ThreadReplySort.Descending)
        advanceUntilIdle()

        assertEquals(ThreadReplySort.Descending, viewModel.state.value.sort)
        assertEquals(ThreadReplySort.Descending, repository.threadRequests.single().sort)
    }

    @Test
    fun missingMainPostIsRecoveredFromAscendingFirstPage() = runTest(dispatcher) {
        val repository = FixtureThreadRepository().apply { missingMainResponses = 1 }

        val viewModel = ThreadViewModel(42, repository)
        advanceUntilIdle()

        assertEquals(1uL, viewModel.state.value.page?.mainPost?.id)
        assertEquals(
            listOf(ThreadReplySort.Hot, ThreadReplySort.Ascending),
            repository.threadRequests.map { it.sort },
        )
        assertFalse(viewModel.state.value.page?.mainPostIsSummaryFallback == true)
    }

    @Test
    fun failedRecoveryUsesSourceSummaryFallback() = runTest(dispatcher) {
        val repository = FixtureThreadRepository().apply {
            missingMainResponses = Int.MAX_VALUE
            failRecovery = true
            sourceFallback = ThreadMainPostFallback.from(thread)
        }

        val viewModel = ThreadViewModel(42, repository)
        advanceUntilIdle()

        assertEquals("主楼正文", viewModel.state.value.page?.mainPost?.contentPreview)
        assertTrue(viewModel.state.value.page?.mainPostIsSummaryFallback == true)
        assertNull(viewModel.state.value.errorMessage)
    }

    @Test
    fun retainedSummaryFallbackRemainsMarkedAfterRefresh() = runTest(dispatcher) {
        val repository = FixtureThreadRepository().apply {
            missingMainResponses = Int.MAX_VALUE
            failRecovery = true
            sourceFallback = ThreadMainPostFallback.from(thread)
        }
        val viewModel = ThreadViewModel(42, repository)
        advanceUntilIdle()
        assertTrue(viewModel.state.value.page?.mainPostIsSummaryFallback == true)

        viewModel.refresh()
        advanceUntilIdle()

        assertTrue(viewModel.state.value.page?.mainPostIsSummaryFallback == true)
        assertEquals("主楼正文", viewModel.state.value.page?.mainPost?.contentPreview)
    }

    @Test
    fun missingMainWithoutRecoveryOrFallbackIsExplicitError() = runTest(dispatcher) {
        val repository = FixtureThreadRepository().apply {
            missingMainResponses = Int.MAX_VALUE
            failRecovery = true
            pageThreadHasSummary = false
        }

        val viewModel = ThreadViewModel(42, repository)
        advanceUntilIdle()

        assertNull(viewModel.state.value.page)
        assertEquals("主楼恢复失败", viewModel.state.value.errorMessage)
    }

    @Test
    fun explicitSearchPostOverridesSavedReadingPosition() = runTest(dispatcher) {
        val repository = FixtureThreadRepository().apply {
            readingPosition = ThreadReadingPosition(postId = 3uL, floor = 3)
        }

        val viewModel = ThreadViewModel(42, repository, initialPostId = 88uL)
        advanceUntilIdle()

        assertEquals(88uL, repository.aroundRequests.single().postId)
        assertEquals(ThreadReadingPosition(88uL, 0), viewModel.state.value.readingPositionToRestore)
    }

    @Test
    fun missingExplicitSearchPostShowsLocationErrorAndDoesNotRestore() = runTest(dispatcher) {
        val repository = FixtureThreadRepository().apply { aroundTargetMissing = true }

        val viewModel = ThreadViewModel(42, repository, initialPostId = 88uL)
        advanceUntilIdle()

        assertNull(viewModel.state.value.page)
        assertNull(viewModel.state.value.readingPositionToRestore)
        assertEquals("未能定位到搜索结果中的回复，可能已被删除或不可见，请重试。", viewModel.state.value.errorMessage)
        assertTrue(repository.removedReadingPositions.isEmpty())
    }

    @Test
    fun explicitRepliesDestinationSkipsSavedReadingPosition() = runTest(dispatcher) {
        val repository = FixtureThreadRepository().apply {
            readingPosition = ThreadReadingPosition(postId = 3uL, floor = 3)
        }

        val viewModel = ThreadViewModel(42, repository, restoreReadingPosition = false)
        advanceUntilIdle()

        assertTrue(repository.aroundRequests.isEmpty())
        assertNull(viewModel.state.value.readingPositionToRestore)
        assertEquals(listOf(1), repository.threadRequests.map { it.page })
    }

    @Test
    fun subpostPaginationIsIndependentAndClosingClearsState() = runTest(dispatcher) {
        val repository = FixtureThreadRepository()
        val viewModel = ThreadViewModel(42, repository)
        advanceUntilIdle()
        val parent = viewModel.state.value.posts.first()

        viewModel.openSubposts(parent)
        advanceUntilIdle()
        assertEquals(listOf(21uL, 22uL), viewModel.state.value.subposts?.items?.map(Subpost::id))
        assertTrue(viewModel.state.value.subposts?.hasMore == true)

        viewModel.loadMoreSubposts()
        advanceUntilIdle()
        assertEquals(listOf(21uL, 22uL, 23uL), viewModel.state.value.subposts?.items?.map(Subpost::id))

        viewModel.closeSubposts()
        assertNull(viewModel.state.value.subposts)
    }

    @Test
    fun staleSubpostFailureCannotPolluteNewParent() = runTest(dispatcher) {
        val repository = ControllableThreadRepository()
        val viewModel = ThreadViewModel(42, repository)
        runCurrent()
        repository.threadCalls.single().result.complete(
            controllableThreadPage(replyId = 2u, additionalReplyId = 3u),
        )
        advanceUntilIdle()
        val firstParent = viewModel.state.value.posts[0]
        val secondParent = viewModel.state.value.posts[1]

        viewModel.openSubposts(firstParent)
        runCurrent()
        val oldCall = repository.subpostCalls.single()
        viewModel.openSubposts(secondParent)
        runCurrent()
        val newCall = repository.subpostCalls.last()

        assertTrue(oldCall.cancelled.isCompleted)
        oldCall.result.completeExceptionally(IllegalStateException("旧楼层加载失败"))
        runCurrent()

        assertEquals(secondParent.id, viewModel.state.value.subposts?.parent?.id)
        assertTrue(viewModel.state.value.subposts?.isLoading == true)
        assertNull(viewModel.state.value.subposts?.errorMessage)

        newCall.result.complete(controllableSubpostPage(secondParent, 31u))
        advanceUntilIdle()

        assertEquals(listOf(31uL), viewModel.state.value.subposts?.items?.map(Subpost::id))
        assertFalse(viewModel.state.value.subposts?.isLoading == true)
    }

    @Test
    fun savedPositionLoadsTargetPageInAscendingOrder() = runTest(dispatcher) {
        val repository = FixtureThreadRepository().apply {
            readingPosition = ThreadReadingPosition(postId = 3u, floor = 3)
        }

        val viewModel = ThreadViewModel(42, repository)
        advanceUntilIdle()

        assertEquals(listOf(3uL), repository.aroundRequests.map { it.postId })
        assertEquals(ThreadReplySort.Ascending, repository.aroundRequests.single().sort)
        assertEquals(ThreadReplySort.Ascending, viewModel.state.value.sort)
        assertEquals(ThreadReadingPosition(3u, 3), viewModel.state.value.readingPositionToRestore)
    }

    @Test
    fun changingSortAfterRestoreDoesNotLoadAroundSavedPositionAgain() = runTest(dispatcher) {
        val repository = FixtureThreadRepository().apply {
            readingPosition = ThreadReadingPosition(postId = 3u, floor = 3)
        }
        val viewModel = ThreadViewModel(42, repository)
        advanceUntilIdle()
        assertEquals(1, repository.aroundRequests.size)

        viewModel.readingPositionRestored()
        viewModel.selectSort(ThreadReplySort.Descending)
        advanceUntilIdle()

        assertEquals(1, repository.aroundRequests.size)
        assertEquals(ThreadReplySort.Descending, repository.threadRequests.last().sort)
        assertEquals(ThreadReplySort.Descending, viewModel.state.value.sort)
    }

    @Test
    fun failedSavedTargetClearsPositionAndFallsBackToFirstPage() = runTest(dispatcher) {
        val repository = FixtureThreadRepository().apply {
            readingPosition = ThreadReadingPosition(postId = 3u, floor = 3)
            aroundFailuresRemaining = 1
        }
        val viewModel = ThreadViewModel(42, repository)
        advanceUntilIdle()

        assertEquals(42L, viewModel.state.value.page?.thread?.id)
        assertEquals(1, repository.aroundRequests.size)
        assertEquals(listOf(42L), repository.removedReadingPositions)
        assertNull(viewModel.state.value.readingPositionToRestore)
        assertEquals(ThreadReplySort.Hot, viewModel.state.value.sort)
    }

    @Test
    fun savedTargetMissingFromReturnedPageIsClearedAndNotMarkedRestored() = runTest(dispatcher) {
        val repository = FixtureThreadRepository().apply {
            readingPosition = ThreadReadingPosition(postId = 99u, floor = 99)
            aroundTargetMissing = true
        }

        val viewModel = ThreadViewModel(42, repository)
        advanceUntilIdle()

        assertEquals(listOf(42L), repository.removedReadingPositions)
        assertNull(viewModel.state.value.readingPositionToRestore)
        assertEquals(ThreadReplySort.Hot, viewModel.state.value.sort)
        assertEquals(42L, viewModel.state.value.page?.thread?.id)
    }

    @Test
    fun readingPositionDebouncePersistsOnlyLatestVisibleFloor() = runTest(dispatcher) {
        val repository = FixtureThreadRepository()
        val viewModel = ThreadViewModel(42, repository)
        advanceUntilIdle()

        viewModel.visibleReadingPositionChanged(ThreadReadingPosition(2u, 2))
        advanceTimeBy(ThreadViewModel.READING_POSITION_DEBOUNCE_MILLISECONDS - 1)
        assertTrue(repository.savedPositions.isEmpty())

        viewModel.visibleReadingPositionChanged(ThreadReadingPosition(3u, 3))
        advanceTimeBy(ThreadViewModel.READING_POSITION_DEBOUNCE_MILLISECONDS)
        runCurrent()

        assertEquals(listOf(ThreadReadingPosition(3u, 3)), repository.savedPositions)
    }

    @Test
    fun failedPostLikeRollsBackOptimisticStateAndReportsError() = runTest(dispatcher) {
        val repository = FixtureThreadRepository().apply {
            likeFailure = IllegalStateException("登录后才能点赞")
        }
        val viewModel = ThreadViewModel(42, repository)
        advanceUntilIdle()
        val post = viewModel.state.value.posts.first()

        viewModel.togglePostLike(post)
        assertTrue(viewModel.state.value.posts.first().isLiked)
        assertEquals(1, viewModel.state.value.posts.first().likeCount)
        advanceUntilIdle()

        assertFalse(viewModel.state.value.posts.first().isLiked)
        assertEquals(0, viewModel.state.value.posts.first().likeCount)
        assertEquals("登录后才能点赞", viewModel.state.value.actionErrorMessage)
        assertEquals(
            ThreadLikeTarget(post.id, TiebaLikeObjectType.Post),
            repository.likeCalls.single().target,
        )
    }

    @Test
    fun unknownPostLikeKeepsOptimisticStateAndPreventsRetryUntilRefresh() = runTest(dispatcher) {
        val repository = FixtureThreadRepository().apply {
            likeFailure = UnknownMutation("点赞结果无法确认")
        }
        val viewModel = ThreadViewModel(42, repository)
        advanceUntilIdle()
        val post = viewModel.state.value.posts.first()

        viewModel.togglePostLike(post)
        advanceUntilIdle()

        assertTrue(viewModel.state.value.posts.first().isLiked)
        assertEquals(1, viewModel.state.value.posts.first().likeCount)
        assertEquals(1, repository.likeCalls.size)
        viewModel.togglePostLike(viewModel.state.value.posts.first())
        advanceUntilIdle()
        assertEquals(1, repository.likeCalls.size)
        assertEquals("点赞结果无法确认", viewModel.state.value.actionErrorMessage)

        repository.likeFailure = null
        viewModel.refresh()
        advanceUntilIdle()
        assertTrue(viewModel.state.value.unknownLikeTargets.isEmpty())
    }

    @Test
    fun loadingMoreDoesNotRevertSuccessfulThreadLike() = runTest(dispatcher) {
        val repository = FixtureThreadRepository()
        val viewModel = ThreadViewModel(42, repository)
        advanceUntilIdle()

        viewModel.toggleThreadLike()
        advanceUntilIdle()
        viewModel.loadMore()
        advanceUntilIdle()

        assertTrue(viewModel.state.value.page?.thread?.isLiked == true)
        assertEquals(1, viewModel.state.value.page?.thread?.likeCount)
    }

    @Test
    fun successfulUnfavoriteEmitsConfirmedCollectionState() = runTest(dispatcher) {
        val repository = FixtureThreadRepository().apply { isCollected = true }
        val viewModel = ThreadViewModel(42, repository)
        advanceUntilIdle()
        val result = backgroundScope.async { viewModel.confirmedCollectionChanges.first() }
        runCurrent()

        viewModel.toggleCollection()
        advanceUntilIdle()

        assertFalse(result.await())
        assertFalse(viewModel.state.value.page?.isCollected == true)
    }

    @Test
    fun failedCollectionRollsBackOptimisticStateAndReportsError() = runTest(dispatcher) {
        val repository = FixtureThreadRepository().apply {
            collectionFailure = IllegalStateException("收藏失败")
        }
        val viewModel = ThreadViewModel(42, repository)
        advanceUntilIdle()
        val result = backgroundScope.async { viewModel.confirmedCollectionChanges.first() }
        runCurrent()

        viewModel.toggleCollection()
        assertTrue(viewModel.state.value.page?.isCollected == true)
        advanceUntilIdle()

        assertFalse(viewModel.state.value.page?.isCollected == true)
        assertFalse(result.isCompleted)
        assertEquals("收藏失败", viewModel.state.value.actionErrorMessage)
        assertEquals(1uL, repository.collectionCalls.single().markedPostId)
    }

    @Test
    fun unknownCollectionKeepsOptimisticStateAndPreventsRetryUntilRefresh() = runTest(dispatcher) {
        val repository = FixtureThreadRepository().apply {
            collectionFailure = UnknownMutation("收藏结果无法确认")
        }
        val viewModel = ThreadViewModel(42, repository)
        advanceUntilIdle()
        val result = backgroundScope.async { viewModel.confirmedCollectionChanges.first() }
        runCurrent()

        viewModel.toggleCollection()
        advanceUntilIdle()

        assertTrue(viewModel.state.value.page?.isCollected == true)
        assertTrue(viewModel.state.value.isCollectionOutcomeUnknown)
        assertFalse(result.isCompleted)
        assertEquals(1, repository.collectionCalls.size)
        viewModel.toggleCollection()
        advanceUntilIdle()
        assertEquals(1, repository.collectionCalls.size)

        repository.collectionFailure = null
        viewModel.refresh()
        advanceUntilIdle()
        assertFalse(viewModel.state.value.isCollectionOutcomeUnknown)
    }

    @Test
    fun flushHandsFinalReadingPositionToRepositoryImmediately() = runTest(dispatcher) {
        val repository = FixtureThreadRepository()
        val viewModel = ThreadViewModel(42, repository)
        advanceUntilIdle()

        viewModel.visibleReadingPositionChanged(ThreadReadingPosition(3u, 3))
        viewModel.flushReadingPosition()

        assertEquals(listOf(ThreadReadingPosition(3u, 3)), repository.savedPositions)
        advanceUntilIdle()
        assertEquals(1, repository.savedPositions.size)
    }
}

private class ControllableThreadRepository : ThreadRepository {
    data class ThreadCall(
        val page: Int,
        val sort: ThreadReplySort,
        val onlyAuthor: Boolean,
        val result: CompletableDeferred<ThreadPage> = CompletableDeferred(),
        val cancelled: CompletableDeferred<Unit> = CompletableDeferred(),
    )

    data class SubpostCall(
        val parent: Post,
        val page: Int,
        val result: CompletableDeferred<SubpostPage> = CompletableDeferred(),
        val cancelled: CompletableDeferred<Unit> = CompletableDeferred(),
    )

    val threadCalls = mutableListOf<ThreadCall>()
    val subpostCalls = mutableListOf<SubpostCall>()

    override suspend fun threadPage(
        threadId: Long,
        page: Int,
        sort: ThreadReplySort,
        onlyThreadAuthor: Boolean,
    ): ThreadPage {
        val call = ThreadCall(page, sort, onlyThreadAuthor)
        threadCalls += call
        return call.result.awaitIgnoringCancellation(call.cancelled)
    }

    override suspend fun subpostPage(parentPost: Post, page: Int): SubpostPage {
        val call = SubpostCall(parentPost, page)
        subpostCalls += call
        return call.result.awaitIgnoringCancellation(call.cancelled)
    }
}

private suspend fun <T> CompletableDeferred<T>.awaitIgnoringCancellation(
    cancelled: CompletableDeferred<Unit>,
): T = try {
    await()
} catch (error: CancellationException) {
    cancelled.complete(Unit)
    withContext(NonCancellable) { await() }
}

private fun controllableThreadPage(
    replyId: ULong,
    additionalReplyId: ULong? = null,
    page: Int = 1,
    hasMore: Boolean = false,
): ThreadPage {
    val author = UserSummary(7, "author", "测试用户", "")
    val mainPost = controllablePost(1u, 1, author)
    return ThreadPage(
        thread = ThreadSummary(
            id = 42,
            title = "测试帖子",
            author = author,
            forumName = "测试",
            replyCount = 3,
            viewCount = 10,
            blocks = listOf(ContentBlock.Text("主楼正文")),
        ),
        forum = Forum(9, "测试", "测试吧"),
        mainPost = mainPost,
        posts = listOfNotNull(
            mainPost.takeIf { page == 1 },
            controllablePost(replyId, replyId.toInt(), author),
            additionalReplyId?.let { controllablePost(it, it.toInt(), author) },
        ),
        currentPage = page,
        totalPage = if (hasMore) page + 1 else page,
        hasMore = hasMore,
    )
}

private fun controllableSubpostPage(parent: Post, id: ULong): SubpostPage = SubpostPage(
    parentPost = parent,
    subposts = listOf(
        Subpost(
            id = id,
            floor = id.toInt(),
            author = parent.author,
            ipAddress = null,
            blocks = listOf(ContentBlock.Text("回复$id")),
            createdAtEpochSeconds = null,
            likeCount = 0,
        ),
    ),
    currentPage = 1,
    totalPage = 1,
    hasMore = false,
)

private fun controllablePost(id: ULong, floor: Int, author: UserSummary) = Post(
    id = id,
    threadId = 42,
    floor = floor,
    author = author,
    ipAddress = "广东",
    createdAtEpochSeconds = 1_700_000_000,
    blocks = listOf(ContentBlock.Text("第${floor}楼")),
    subpostCount = 1,
    likeCount = 0,
    previewSubposts = emptyList(),
)

private class FixtureThreadRepository : ThreadRepository {
    data class ThreadRequest(val page: Int, val sort: ThreadReplySort, val onlyAuthor: Boolean)
    data class AroundRequest(val postId: ULong, val sort: ThreadReplySort, val onlyAuthor: Boolean)
    data class LikeCall(val target: ThreadLikeTarget, val liked: Boolean)
    data class CollectionCall(val markedPostId: ULong, val collected: Boolean)

    val threadRequests = mutableListOf<ThreadRequest>()
    val aroundRequests = mutableListOf<AroundRequest>()
    val savedPositions = mutableListOf<ThreadReadingPosition>()
    val removedReadingPositions = mutableListOf<Long>()
    val likeCalls = mutableListOf<LikeCall>()
    val collectionCalls = mutableListOf<CollectionCall>()
    var readingPosition: ThreadReadingPosition? = null
    var likeFailure: Throwable? = null
    var collectionFailure: Throwable? = null
    var aroundFailuresRemaining: Int = 0
    var aroundTargetMissing: Boolean = false
    var missingMainResponses: Int = 0
    var failRecovery: Boolean = false
    var sourceFallback: ThreadMainPostFallback? = null
    var pageThreadHasSummary: Boolean = true
    var isCollected: Boolean = false

    override fun mainPostFallback(threadId: Long): ThreadMainPostFallback? = sourceFallback

    override suspend fun threadPage(
        threadId: Long,
        page: Int,
        sort: ThreadReplySort,
        onlyThreadAuthor: Boolean,
    ): ThreadPage {
        threadRequests += ThreadRequest(page, sort, onlyThreadAuthor)
        if (failRecovery && sort == ThreadReplySort.Ascending && threadRequests.size > 1) {
            throw IllegalStateException("主楼恢复失败")
        }
        val mainPost = post(1u, floor = 1)
        val replies = if (page == 1) {
            listOf(mainPost, post(2u, 2), post(3u, 3))
        } else {
            listOf(post(3u, 3), post(4u, 4))
        }
        return ThreadPage(
            thread = if (pageThreadHasSummary) thread else thread.copy(blocks = emptyList()),
            forum = forum,
            mainPost = mainPost.takeUnless { missingMainResponses > 0 },
            posts = if (missingMainResponses > 0) replies.filter { it.floor != 1 } else replies,
            currentPage = page,
            totalPage = 2,
            hasMore = page < 2,
            isCollected = isCollected,
        ).also { if (missingMainResponses > 0) missingMainResponses -= 1 }
    }

    override suspend fun subpostPage(parentPost: Post, page: Int): SubpostPage = SubpostPage(
        parentPost = parentPost,
        subposts = if (page == 1) {
            listOf(subpost(21u), subpost(22u))
        } else {
            listOf(subpost(22u), subpost(23u))
        },
        currentPage = page,
        totalPage = 2,
        hasMore = page < 2,
    )

    override suspend fun threadPageAround(
        threadId: Long,
        postId: ULong,
        sort: ThreadReplySort,
        onlyThreadAuthor: Boolean,
    ): ThreadPage {
        aroundRequests += AroundRequest(postId, sort, onlyThreadAuthor)
        if (aroundFailuresRemaining > 0) {
            aroundFailuresRemaining -= 1
            throw IllegalStateException("目标页加载失败")
        }
        val result = threadPage(threadId, 1, sort, onlyThreadAuthor)
        if (aroundTargetMissing || result.mainPost?.id == postId || result.posts.any { it.id == postId }) {
            return result
        }
        return result.copy(posts = result.posts + post(postId, floor = postId.toInt()))
    }

    override suspend fun loadReadingPosition(threadId: Long): ThreadReadingPosition? = readingPosition

    override suspend fun saveReadingPosition(threadId: Long, position: ThreadReadingPosition) {
        savedPositions += position
    }

    override fun scheduleReadingPositionSave(threadId: Long, position: ThreadReadingPosition) {
        savedPositions += position
    }

    override suspend fun removeReadingPosition(threadId: Long) {
        removedReadingPositions += threadId
        readingPosition = null
    }

    override suspend fun setLiked(threadId: Long, target: ThreadLikeTarget, liked: Boolean) {
        likeCalls += LikeCall(target, liked)
        likeFailure?.let { throw it }
    }

    override suspend fun setCollected(threadId: Long, markedPostId: ULong, collected: Boolean) {
        collectionCalls += CollectionCall(markedPostId, collected)
        collectionFailure?.let { throw it }
        isCollected = collected
    }

    private val author = UserSummary(7, "author", "测试用户", "")
    private val forum = Forum(9, "测试", "测试吧")
    val thread = ThreadSummary(
        id = 42,
        title = "测试帖子",
        author = author,
        forumName = forum.name,
        replyCount = 4,
        viewCount = 10,
        blocks = listOf(ContentBlock.Text("主楼正文")),
    )

    private fun post(id: ULong, floor: Int) = Post(
        id = id,
        threadId = 42,
        floor = floor,
        author = author,
        ipAddress = "广东",
        createdAtEpochSeconds = 1_700_000_000,
        blocks = listOf(ContentBlock.Text("第${floor}楼")),
        subpostCount = 3,
        likeCount = 0,
        previewSubposts = emptyList(),
    )

    private fun subpost(id: ULong) = Subpost(
        id = id,
        floor = id.toInt(),
        author = author,
        ipAddress = null,
        blocks = listOf(ContentBlock.Text("回复$id")),
        createdAtEpochSeconds = null,
        likeCount = 0,
    )
}

private class UnknownMutation(
    override val outcomeUnknownMessage: String,
) : RuntimeException(outcomeUnknownMessage), MutationOutcomeUnknown
