package dev.infinityf4p.tiebapure.feature.home

import dev.infinityf4p.tiebapure.core.model.Forum
import dev.infinityf4p.tiebapure.core.model.ThreadSummary
import dev.infinityf4p.tiebapure.core.model.UserSummary
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HomePolicyTest {
    private val user = UserSummary(1, "u", "U", "")

    @Test
    fun paginationReplacesDuplicateWithLatestValueWithoutReorderingExistingRows() {
        val first = thread(1, "old")
        val updated = thread(1, "new")
        val second = thread(2, "second")

        assertEquals(listOf(updated, second), mergeThreads(listOf(first), listOf(updated, second)))
    }

    @Test
    fun refreshPrependsNewThreadsKeepsOlderPagesAndPrefersFreshValues() {
        val oldFirst = thread(1, "old")
        val second = thread(2, "second")
        val freshFirst = thread(1, "fresh")
        val newThread = thread(3, "new")

        assertEquals(
            listOf(newThread, freshFirst, second),
            refreshThreads(listOf(oldFirst, second), listOf(newThread, freshFirst)),
        )
    }

    @Test
    fun refreshMotionAndVisibilityMatchIosPolicy() {
        assertEquals(56f, HomeRefreshMotionPolicy.heldContentDistanceDp)
        assertEquals(76f, HomeRefreshMotionPolicy.maximumContentDistanceDp)
        assertEquals(260, HomeRefreshMotionPolicy.reboundDurationMillis)
        assertEquals(0f, HomeRefreshMotionPolicy.gestureContentOffsetDp(-1f))
        assertEquals(28f, HomeRefreshMotionPolicy.gestureContentOffsetDp(0.5f))
        assertEquals(56f, HomeRefreshMotionPolicy.gestureContentOffsetDp(1f))
        assertTrue(HomeRefreshMotionPolicy.gestureContentOffsetDp(1.5f) in 56f..76f)
        assertTrue(HomeRefreshMotionPolicy.gestureContentOffsetDp(4f) < 76f)
        assertEquals(600L, HomeRefreshAnimationPolicy.remainingVisibleMillis(0))
        assertEquals(350L, HomeRefreshAnimationPolicy.remainingVisibleMillis(250))
        assertEquals(0L, HomeRefreshAnimationPolicy.remainingVisibleMillis(900))
    }

    @Test
    fun countFormattingKeepsThreeDigitsAndCompactsFourAndFiveDigits() {
        assertEquals("999", compactCount(999))
        assertEquals("1.2k", compactCount(1_234))
        assertEquals("1.2w", compactCount(12_345))
    }

    @Test
    fun threadTimeUsesRelativeValuesAndRejectsMissingTimestamps() {
        assertEquals(null, compactThreadTime(null, nowEpochSeconds = 10_000))
        assertEquals("刚刚", compactThreadTime(9_970, nowEpochSeconds = 10_000))
        assertEquals("2小时前", compactThreadTime(2_800, nowEpochSeconds = 10_000))
    }

    @Test
    fun likeReducerUpdatesOnlyTargetAndDoesNotDoubleCountSameState() {
        val first = thread(1, "one")
        val second = thread(2, "two")
        val liked = applyThreadLikeState(listOf(first, second), 1, true)

        assertTrue(liked.first().isLiked)
        assertEquals(1, liked.first().likeCount)
        assertEquals(second, liked.last())
        assertEquals(liked, applyThreadLikeState(liked, 1, true))
    }

    @Test
    fun unknownLikeStopsProgressAndBlocksAnotherMutationUntilRefresh() {
        val state = HomeUiState(
            threads = listOf(thread(1, "one")),
            updatingLikeThreadIds = setOf(1),
        ).markingLikeOutcomeUnknown(1, "请刷新核对")

        assertTrue(1L in state.unknownLikeThreadIds)
        assertFalse(1L in state.updatingLikeThreadIds)
        assertFalse(state.canBeginLikeMutation(1))
        assertEquals("请刷新核对", state.errorMessage)
    }

    @Test
    fun blockedForumMatchesByPositiveIdOrNormalizedName() {
        val byId = thread(1, "one").copy(forumId = 7, forumName = "另一个吧")
        val byName = thread(2, "two").copy(forumId = null, forumName = " 测试吧 ")
        val other = thread(3, "three").copy(forumId = 8, forumName = "其他")
        val forum = Forum(7, "测试", "测试吧")

        assertTrue(threadBelongsToForum(byId, forum))
        assertTrue(threadBelongsToForum(byName, forum))
        assertTrue(!threadBelongsToForum(other, forum))
    }

    @Test
    fun emptyFilteredPageCanContinueUntilBackendReportsEnd() {
        assertTrue(HomeUiState(threads = emptyList(), hasMore = true).showsEmptyPageContinuation)
        assertTrue(
            HomeUiState(
                threads = emptyList(),
                hasMore = true,
                isLoadingMore = true,
            ).showsEmptyPageContinuation,
        )
        assertFalse(HomeUiState(threads = emptyList(), hasMore = false).showsEmptyPageContinuation)
        assertFalse(HomeUiState(threads = listOf(thread(1, "one")), hasMore = true).showsEmptyPageContinuation)
    }

    private fun thread(id: Long, title: String) = ThreadSummary(
        id = id,
        title = title,
        author = user,
        replyCount = 0,
        viewCount = 0,
        blocks = emptyList(),
    )
}
