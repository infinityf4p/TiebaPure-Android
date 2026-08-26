package dev.infinityf4p.tiebapure

import dev.infinityf4p.tiebapure.core.model.ThreadSummary
import dev.infinityf4p.tiebapure.core.model.UserSummary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FollowingUpdatesAggregationTest {
    @Test
    fun keepsSuccessfulUsersAndSortsUniqueThreads() = runTest {
        val users = listOf(user(1), user(2), user(3), user(1))

        val result = aggregateFollowingUserThreads(users, parallelism = 2) { user ->
            when (user.id) {
                1L -> listOf(thread(10, 10), thread(20, 30))
                2L -> error("用户不可用")
                else -> listOf(thread(10, 10), thread(30, 20))
            }
        }

        assertEquals(listOf(20L, 30L, 10L), result.threads.map(ThreadSummary::id))
        assertEquals(1, result.unavailableUserCount)
    }

    @Test
    fun failsWhenEveryFollowedUserRequestFails() = runTest {
        val error = runCatching {
            aggregateFollowingUserThreads(listOf(user(1), user(2))) { error("网络失败") }
        }.exceptionOrNull()

        assertTrue(error is IllegalStateException)
        assertEquals("关注用户帖子加载失败，请稍后重试。", error?.message)
    }

    @Test
    fun preservesCancellation() = runTest {
        val error = runCatching {
            aggregateFollowingUserThreads(listOf(user(1))) { throw CancellationException("cancelled") }
        }.exceptionOrNull()

        assertTrue(error is CancellationException)
    }
}

private fun user(id: Long) = UserSummary(id, "user$id", "用户$id", "")

private fun thread(id: Long, createdAt: Long) = ThreadSummary(
    id = id,
    title = "帖子$id",
    author = user(id),
    replyCount = 0,
    viewCount = 0,
    createdAtEpochSeconds = createdAt,
    blocks = emptyList(),
)
