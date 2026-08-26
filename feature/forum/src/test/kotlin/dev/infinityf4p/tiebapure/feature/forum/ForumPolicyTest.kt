package dev.infinityf4p.tiebapure.feature.forum

import dev.infinityf4p.tiebapure.core.model.Forum
import dev.infinityf4p.tiebapure.core.model.ForumInfo
import dev.infinityf4p.tiebapure.core.model.ForumMembership
import dev.infinityf4p.tiebapure.core.model.MutationOutcomeUnknown
import dev.infinityf4p.tiebapure.core.model.ForumPage
import dev.infinityf4p.tiebapure.core.model.ForumThreadCategory
import dev.infinityf4p.tiebapure.core.model.ThreadSummary
import dev.infinityf4p.tiebapure.core.model.UserSummary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ForumPolicyTest {
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
    fun forumInputNormalizesOneDisplaySuffix() {
        assertEquals("测试", normalizedForum(" 测试吧 ")?.name)
        assertEquals("测试吧", normalizedForum(" 测试吧 ")?.displayName)
    }

    @Test
    fun emptyForumInputDoesNotCreateRoute() {
        assertEquals(null, normalizedForum("  "))
    }

    @Test
    fun forumThreadMetadataUsesCategoryTimestampAndAction() {
        val thread = ThreadSummary(
            id = 1,
            title = "主题",
            author = UserSummary(1, "u", "作者", ""),
            replyCount = 0,
            viewCount = 0,
            createdAtEpochSeconds = 9_880,
            lastReplyAtEpochSeconds = 2_800,
            blocks = emptyList(),
        )

        assertEquals("2分钟前 · 发布", forumThreadMetadata(thread, ForumThreadCategory.PublishTime, 10_000))
        assertEquals("2小时前 · 回复", forumThreadMetadata(thread, ForumThreadCategory.ReplyTime, 10_000))
    }

    @Test
    fun forumInfoCountsUseCompactChineseUnits() {
        assertEquals("0", compactForumInfoCount(-1))
        assertEquals("9999", compactForumInfoCount(9_999))
        assertEquals("1 万", compactForumInfoCount(10_000))
        assertEquals("53.6 万", compactForumInfoCount(536_999))
        assertEquals("128.4 万", compactForumInfoCount(1_284_999))
    }

    @Test
    fun forumPageReplacesPlaceholderForumWithResolvedForum() {
        val placeholder = Forum(0, "测试", "测试吧")
        val resolved = Forum(42, "测试", "测试吧")

        val state = applyForumPage(
            current = ForumThreadsUiState(forum = placeholder, isInitialLoading = true),
            result = ForumPage(
                forum = resolved,
                threads = emptyList(),
                currentPage = 1,
                hasMore = false,
            ),
            replace = true,
        )

        assertEquals(resolved, state.forum)
        assertEquals(2, state.nextPage)
        assertEquals(false, state.isInitialLoading)
    }

    @Test
    fun lateForumPageCannotReplacePreviouslyResolvedPositiveId() {
        val state = applyForumPage(
            current = ForumThreadsUiState(forum = Forum(42, "测试", "测试吧")),
            result = ForumPage(
                forum = Forum(77, "测试", "测试吧"),
                threads = emptyList(),
                currentPage = 1,
                hasMore = false,
            ),
            replace = true,
        )

        assertEquals(42, state.forum.id)
    }

    @Test
    fun hubObservesRecentForumsRecordedOutsideTheHub() = runTest(dispatcher) {
        val repository = ObservableForumHubRepository()
        val viewModel = ForumHubViewModel(repository)
        advanceUntilIdle()

        repository.recordRecent(Forum(42, "测试", "测试吧"))
        runCurrent()

        assertEquals(listOf("测试"), viewModel.uiState.value.recentForums.map(Forum::name))
    }

    @Test
    fun interactionPortResolvesForumIdBeforeMembership() = runTest(dispatcher) {
        val port = FakeForumInteractionPort(resolvedForumId = 42)
        val viewModel = ForumThreadsViewModel(
            forum = Forum(0, "测试", "测试吧"),
            repository = EmptyForumThreadsRepository(),
            interactionPort = port,
        )

        advanceUntilIdle()

        assertEquals(42, viewModel.uiState.value.forum.id)
        assertEquals(ForumMembership(42, false), viewModel.uiState.value.forumMembership)
        assertEquals(listOf("resolve:测试", "membership:42"), port.calls)
    }

    @Test
    fun forumInfoLoadsOnlyWhenExpandedAndReusesSuccessfulResult() = runTest(dispatcher) {
        val infoRepository = ControllableForumInfoRepository()
        val viewModel = ForumThreadsViewModel(
            forum = Forum(42, "测试", "测试吧"),
            repository = EmptyForumThreadsRepository(),
            infoRepository = infoRepository,
        )
        advanceUntilIdle()

        assertTrue(infoRepository.calls.isEmpty())
        assertFalse(viewModel.uiState.value.showsForumInfo)

        viewModel.toggleForumInfo()
        runCurrent()
        assertTrue(viewModel.uiState.value.showsForumInfo)
        assertTrue(viewModel.uiState.value.isLoadingForumInfo)
        assertEquals(1, infoRepository.calls.size)

        val info = testForumInfo(forumId = 77)
        infoRepository.calls.single().result.complete(info)
        advanceUntilIdle()
        assertEquals(info, viewModel.uiState.value.forumInfo)
        assertEquals(77, viewModel.uiState.value.forum.id)
        assertFalse(viewModel.uiState.value.isLoadingForumInfo)

        viewModel.toggleForumInfo()
        viewModel.toggleForumInfo()
        runCurrent()
        assertTrue(viewModel.uiState.value.showsForumInfo)
        assertEquals(1, infoRepository.calls.size)
    }

    @Test
    fun failedForumInfoUsesSafeMessageAndCanRetry() = runTest(dispatcher) {
        val infoRepository = ControllableForumInfoRepository()
        val viewModel = ForumThreadsViewModel(
            forum = Forum(42, "测试", "测试吧"),
            repository = EmptyForumThreadsRepository(),
            infoRepository = infoRepository,
        )
        advanceUntilIdle()

        viewModel.toggleForumInfo()
        runCurrent()
        infoRepository.calls.single().result.completeExceptionally(
            IllegalStateException("https://example.com/private?token=secret"),
        )
        advanceUntilIdle()

        assertEquals("贴吧资料加载失败，请稍后重试。", viewModel.uiState.value.forumInfoError)
        assertFalse(viewModel.uiState.value.forumInfoError.orEmpty().contains("https://"))

        viewModel.retryForumInfo()
        runCurrent()
        assertEquals(2, infoRepository.calls.size)
        assertTrue(viewModel.uiState.value.isLoadingForumInfo)
        infoRepository.calls.last().result.complete(testForumInfo())
        advanceUntilIdle()
        assertEquals(null, viewModel.uiState.value.forumInfoError)
    }

    @Test
    fun forumFollowOptimisticallyUpdatesAndCompletesOnce() = runTest(dispatcher) {
        val mutation = CompletableDeferred<ForumMembership>()
        val port = FakeForumInteractionPort(resolvedForumId = 42, mutationResult = mutation)
        val viewModel = ForumThreadsViewModel(
            forum = Forum(42, "测试", "测试吧"),
            repository = EmptyForumThreadsRepository(),
            interactionPort = port,
        )
        advanceUntilIdle()

        viewModel.toggleForumFollow()
        runCurrent()
        assertTrue(viewModel.uiState.value.forumMembership?.isFollowed == true)
        assertTrue(viewModel.uiState.value.isUpdatingForumFollow)

        viewModel.toggleForumFollow()
        runCurrent()
        assertEquals(1, port.mutationCalls)

        mutation.complete(ForumMembership(42, true))
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.forumMembership?.isFollowed == true)
        assertFalse(viewModel.uiState.value.isUpdatingForumFollow)
    }

    @Test
    fun failedForumFollowRollsBackOptimisticState() = runTest(dispatcher) {
        val port = FakeForumInteractionPort(
            resolvedForumId = 42,
            mutationFailure = IllegalStateException("关注失败"),
        )
        val viewModel = ForumThreadsViewModel(
            forum = Forum(42, "测试", "测试吧"),
            repository = EmptyForumThreadsRepository(),
            interactionPort = port,
        )
        advanceUntilIdle()

        viewModel.toggleForumFollow()
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.forumMembership?.isFollowed == true)
        assertFalse(viewModel.uiState.value.isUpdatingForumFollow)
        assertEquals("关注失败", viewModel.uiState.value.forumActionError)
    }

    @Test
    fun unknownForumFollowKeepsOptimisticStateAndPreventsRetryUntilRefresh() = runTest(dispatcher) {
        val port = FakeForumInteractionPort(
            resolvedForumId = 42,
            mutationFailure = UnknownForumMutation("关注结果无法确认"),
        )
        val viewModel = ForumThreadsViewModel(
            forum = Forum(42, "测试", "测试吧"),
            repository = EmptyForumThreadsRepository(),
            interactionPort = port,
        )
        advanceUntilIdle()

        viewModel.toggleForumFollow()
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.forumMembership?.isFollowed == true)
        assertTrue(viewModel.uiState.value.isForumFollowOutcomeUnknown)
        assertEquals(1, port.mutationCalls)
        viewModel.toggleForumFollow()
        advanceUntilIdle()
        assertEquals(1, port.mutationCalls)
        assertEquals("关注结果无法确认", viewModel.uiState.value.forumActionError)
    }

    @Test
    fun changingCategorySupersedesBusyRequestAndIgnoresOldResult() = runTest(dispatcher) {
        val repository = ControllableForumThreadsRepository()
        val forum = Forum(42, "测试", "测试吧")
        val viewModel = ForumThreadsViewModel(forum, repository)
        runCurrent()
        val oldCall = repository.calls.single()

        viewModel.selectCategory(ForumThreadCategory.PublishTime)
        runCurrent()
        val newCall = repository.calls.last()

        assertEquals(2, repository.calls.size)
        assertEquals(ForumThreadCategory.PublishTime, newCall.category)
        assertTrue(oldCall.cancelled.isCompleted)
        newCall.result.complete(forumPage(forum, threadId = 2))
        runCurrent()
        oldCall.result.complete(forumPage(forum, threadId = 1))
        advanceUntilIdle()

        assertEquals(ForumThreadCategory.PublishTime, viewModel.uiState.value.category)
        assertEquals(listOf(2L), viewModel.uiState.value.threads.map(ThreadSummary::id))
        assertFalse(viewModel.uiState.value.isBusy)
    }

    @Test
    fun openingForumRecordsPlaceholderAndResolvedMetadata() = runTest(dispatcher) {
        val repository = ControllableForumThreadsRepository()
        val visits = mutableListOf<Forum>()
        val viewModel = ForumThreadsViewModel(
            forum = Forum(0, "测试", "测试吧"),
            repository = repository,
            visitRecorder = ForumVisitRecorder { visits += it },
        )
        runCurrent()

        repository.calls.single().result.complete(
            forumPage(
                forum = Forum(42, "测试", "测试吧", avatarUrl = "https://example.com/forum.jpg"),
                threadId = 1,
            ),
        )
        advanceUntilIdle()

        assertEquals(listOf(0L, 42L), visits.map(Forum::id))
        assertEquals("https://example.com/forum.jpg", visits.last().avatarUrl)
        assertEquals(42, viewModel.uiState.value.forum.id)
    }

    @Test
    fun retryRepeatsRefreshWhenCachedPageHasNoMoreItems() = runTest(dispatcher) {
        val repository = ControllableForumThreadsRepository()
        val forum = Forum(42, "测试", "测试吧")
        val viewModel = ForumThreadsViewModel(forum, repository)
        runCurrent()
        repository.calls.single().result.complete(forumPage(forum, threadId = 1, hasMore = false))
        advanceUntilIdle()

        viewModel.refresh()
        runCurrent()
        repository.calls.last().result.completeExceptionally(IllegalStateException("刷新失败"))
        advanceUntilIdle()

        assertEquals(listOf(1, 1), repository.calls.map(ControllableForumThreadsRepository.Call::page))
        assertEquals(listOf(1L), viewModel.uiState.value.threads.map(ThreadSummary::id))
        viewModel.retry()
        runCurrent()

        assertEquals(listOf(1, 1, 1), repository.calls.map(ControllableForumThreadsRepository.Call::page))
        repository.calls.last().result.complete(forumPage(forum, threadId = 2, hasMore = false))
        advanceUntilIdle()
    }

    @Test
    fun retryRepeatsFailedPaginationPage() = runTest(dispatcher) {
        val repository = ControllableForumThreadsRepository()
        val forum = Forum(42, "测试", "测试吧")
        val viewModel = ForumThreadsViewModel(forum, repository)
        runCurrent()
        repository.calls.single().result.complete(forumPage(forum, threadId = 1, hasMore = true))
        advanceUntilIdle()

        viewModel.loadMore()
        runCurrent()
        repository.calls.last().result.completeExceptionally(IllegalStateException("分页失败"))
        advanceUntilIdle()
        viewModel.retry()
        runCurrent()

        assertEquals(listOf(1, 2, 2), repository.calls.map(ControllableForumThreadsRepository.Call::page))
        repository.calls.last().result.complete(
            forumPage(forum, threadId = 2, currentPage = 2, hasMore = false),
        )
        advanceUntilIdle()
    }

    @Test
    fun unresolvedForumIdCannotCreateThread() {
        assertFalse(
            canCreateThread(
                forum = Forum(0, "测试", "测试吧"),
                capabilities = ForumThreadsCapabilities(canCreateThread = true),
            ),
        )
        assertTrue(
            canCreateThread(
                forum = Forum(42, "测试", "测试吧"),
                capabilities = ForumThreadsCapabilities(canCreateThread = true),
            ),
        )
    }

    @Test
    fun loginRequiredAndUnsupportedFollowStatesAreDisabled() {
        assertFalse(
            ForumThreadsUiState(
                forum = Forum(42, "测试", "测试吧"),
                followAvailability = ForumFollowAvailability.LoginRequired,
                forumMembership = ForumMembership(42, false),
            ).canRequestForumFollow,
        )
        assertFalse(
            ForumThreadsUiState(
                forum = Forum(42, "测试", "测试吧"),
                followAvailability = ForumFollowAvailability.Unsupported,
                forumMembership = ForumMembership(42, false),
            ).canRequestForumFollow,
        )
    }

    @Test
    fun emptyFilteredPageCanContinueAndInvalidAuthorCannotOpenProfile() {
        assertTrue(
            ForumThreadsUiState(
                forum = Forum(42, "测试", "测试吧"),
                threads = emptyList(),
                hasMore = true,
            ).showsEmptyPageContinuation,
        )
        assertFalse(
            ForumThreadsUiState(
                forum = Forum(42, "测试", "测试吧"),
                threads = emptyList(),
                hasMore = false,
            ).showsEmptyPageContinuation,
        )
        assertFalse(canOpenForumThreadAuthor(UserSummary(0, "", "未知用户", "")))
        assertFalse(canOpenForumThreadAuthor(UserSummary(-1, "", "未知用户", "")))
        assertTrue(canOpenForumThreadAuthor(UserSummary(7, "valid", "有效用户", "")))
    }
}

private class ObservableForumHubRepository : ForumHubRepository {
    private val recent = MutableStateFlow<List<Forum>>(emptyList())

    override suspend fun followedForums(): List<Forum> = emptyList()
    override fun recentForums(): Flow<List<Forum>> = recent

    override suspend fun recordRecent(forum: Forum) {
        recent.value = listOf(forum) + recent.value.filterNot { it.name == forum.name }
    }

    override suspend fun removeRecent(forum: Forum) {
        recent.value = recent.value.filterNot { it.name == forum.name }
    }

    override suspend fun clearRecent() {
        recent.value = emptyList()
    }
}

private class EmptyForumThreadsRepository : ForumThreadsRepository {
    override suspend fun loadThreads(
        forum: Forum,
        page: Int,
        category: ForumThreadCategory,
    ) = ForumPage(forum = forum, threads = emptyList(), currentPage = page, hasMore = false)
}

private class ControllableForumThreadsRepository : ForumThreadsRepository {
    data class Call(
        val category: ForumThreadCategory,
        val page: Int,
        val result: CompletableDeferred<ForumPage> = CompletableDeferred(),
        val cancelled: CompletableDeferred<Unit> = CompletableDeferred(),
    )

    val calls = mutableListOf<Call>()

    override suspend fun loadThreads(
        forum: Forum,
        page: Int,
        category: ForumThreadCategory,
    ): ForumPage {
        val call = Call(category, page)
        calls += call
        return try {
            call.result.await()
        } catch (_: CancellationException) {
            call.cancelled.complete(Unit)
            withContext(NonCancellable) { call.result.await() }
        }
    }
}

private class ControllableForumInfoRepository : ForumInfoRepository {
    data class Call(
        val forum: Forum,
        val result: CompletableDeferred<ForumInfo> = CompletableDeferred(),
    )

    val calls = mutableListOf<Call>()

    override suspend fun loadInfo(forum: Forum): ForumInfo {
        val call = Call(forum)
        calls += call
        return call.result.await()
    }
}

private fun testForumInfo(forumId: Long = 42) = ForumInfo(
    forumId = forumId,
    memberCount = 536_000,
    postCount = 1_284_000,
    threadCount = 160_000,
    introduction = "用于测试的贴吧简介",
    primaryCategory = "兴趣",
    secondaryCategory = "软件",
)

private fun forumPage(
    forum: Forum,
    threadId: Long,
    currentPage: Int = 1,
    hasMore: Boolean = false,
) = ForumPage(
    forum = forum,
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

private class FakeForumInteractionPort(
    private val resolvedForumId: Long,
    private val mutationResult: CompletableDeferred<ForumMembership>? = null,
    private val mutationFailure: Throwable? = null,
) : ForumInteractionPort {
    override val followAvailability = ForumFollowAvailability.Available
    val calls = mutableListOf<String>()
    var mutationCalls = 0

    override suspend fun resolveForumId(forum: Forum): Long {
        calls += "resolve:${forum.name}"
        return resolvedForumId
    }

    override suspend fun forumMembership(forum: Forum): ForumMembership {
        calls += "membership:${forum.id}"
        return ForumMembership(forum.id, false)
    }

    override suspend fun setForumFollowed(forum: Forum, followed: Boolean): ForumMembership {
        mutationCalls += 1
        mutationFailure?.let { throw it }
        return mutationResult?.await() ?: ForumMembership(forum.id, followed)
    }
}

private class UnknownForumMutation(
    override val outcomeUnknownMessage: String,
) : RuntimeException(outcomeUnknownMessage), MutationOutcomeUnknown
