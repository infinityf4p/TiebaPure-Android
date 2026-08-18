package dev.infinityf4p.tiebapure.feature.forum

import dev.infinityf4p.tiebapure.core.model.Forum
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
        val result: CompletableDeferred<ForumPage> = CompletableDeferred(),
        val cancelled: CompletableDeferred<Unit> = CompletableDeferred(),
    )

    val calls = mutableListOf<Call>()

    override suspend fun loadThreads(
        forum: Forum,
        page: Int,
        category: ForumThreadCategory,
    ): ForumPage {
        val call = Call(category)
        calls += call
        return try {
            call.result.await()
        } catch (_: CancellationException) {
            call.cancelled.complete(Unit)
            withContext(NonCancellable) { call.result.await() }
        }
    }
}

private fun forumPage(forum: Forum, threadId: Long) = ForumPage(
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
    currentPage = 1,
    hasMore = false,
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
