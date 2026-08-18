package dev.infinityf4p.tiebapure.feature.account

import dev.infinityf4p.tiebapure.core.model.AccountThreadFavorite
import dev.infinityf4p.tiebapure.core.model.BrowsingHistoryEntry
import dev.infinityf4p.tiebapure.core.model.ContentBlock
import dev.infinityf4p.tiebapure.core.model.ThreadSummary
import dev.infinityf4p.tiebapure.core.model.UserSummary
import dev.infinityf4p.tiebapure.core.model.UserProfileSex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AccountPoliciesTest {
    @Test
    fun favoriteFilterMatchesSearchAndReadingProgress() {
        val favorites = listOf(
            favorite(1, "Compose 性能记录", "Android吧", "甲"),
            favorite(2, "SwiftUI 交互记录", "iOS吧", "乙"),
        )

        val result = filterFavorites(
            favorites = favorites,
            threadsWithReadingPosition = setOf(1),
            searchText = "android",
            progressFilter = FavoriteProgressFilter.HasProgress,
        )

        assertEquals(listOf(1L), result.map { it.favorite.threadId })
        assertTrue(result.single().hasReadingPosition)
    }

    @Test
    fun favoriteRemovalAppliesConfirmedItemsAndLocksOnlyUnknownItems() {
        val initial = ThreadFavoritesUiState(
            isLoggedIn = true,
            favorites = listOf(
                favorite(1, "已确认", "Android吧", "甲"),
                favorite(2, "结果未知", "Android吧", "乙"),
                favorite(3, "明确失败", "Android吧", "丙"),
            ),
            threadsWithReadingPosition = setOf(1, 2, 3),
            selectedThreadIds = setOf(1, 2, 3),
            isSelecting = true,
            isRemoving = true,
        )

        val result = initial.applyingRemovalResult(
            ThreadFavoriteRemovalResult(
                removedThreadIds = setOf(1),
                outcomeUnknownByThreadId = mapOf(2L to "请刷新核对"),
                failedByThreadId = mapOf(3L to "服务繁忙"),
            ),
        )

        assertEquals(listOf(2L, 3L), result.favorites.map(AccountThreadFavorite::threadId))
        assertEquals(setOf(2L, 3L), result.selectedThreadIds)
        assertEquals(setOf(2L), result.unknownRemovalThreadIds)
        assertEquals(setOf(2L, 3L), result.threadsWithReadingPosition)
        assertTrue(result.isSelecting)
        assertFalse(result.isRemoving)
        assertTrue(result.errorMessage.orEmpty().contains("结果无法确认"))
        assertTrue(result.errorMessage.orEmpty().contains("移除失败"))
    }

    @Test
    fun historyTodayAndSevenDayFiltersUseClosedDayBounds() {
        val today = 10 * DAY
        val entries = listOf(
            history(1, today),
            history(2, today - 6 * DAY),
            history(3, today - 7 * DAY),
            history(4, today + DAY),
        )

        assertEquals(
            listOf(1L),
            filterHistory(entries, "", HistoryDateFilter.Today, today, today + DAY, today - 6 * DAY).map { it.thread.id },
        )
        assertEquals(
            listOf(1L, 2L),
            filterHistory(entries, "", HistoryDateFilter.LastSevenDays, today, today + DAY, today - 6 * DAY).map { it.thread.id },
        )
    }

    @Test
    fun historySearchMatchesTitleAuthorForumAndId() {
        val entries = listOf(history(42, 0, title = "阅读器", author = "纸间读者", forum = "客户端吧"))

        assertEquals(1, filterHistory(entries, "纸间", HistoryDateFilter.All, 0, DAY, -6 * DAY).size)
        assertEquals(1, filterHistory(entries, "客户端", HistoryDateFilter.All, 0, DAY, -6 * DAY).size)
        assertEquals(1, filterHistory(entries, "42", HistoryDateFilter.All, 0, DAY, -6 * DAY).size)
        assertTrue(filterHistory(entries, "无结果", HistoryDateFilter.All, 0, DAY, -6 * DAY).isEmpty())
    }

    @Test
    fun pageMergersKeepStableOrderAndReplaceDuplicate() {
        val first = thread(1, "旧值")
        val replacement = thread(1, "新值")
        val second = thread(2, "第二条")

        val threads = mergeProfileThreads(listOf(first), listOf(replacement, second))
        val users = mergeUsers(listOf(first.author), listOf(first.author.copy(displayName = "更新"), second.author))

        assertEquals(listOf(1L, 2L), threads.map(ThreadSummary::id))
        // Feature pagination treats the first occurrence as canonical until a full refresh.
        assertEquals("旧值", threads.first().title)
        assertEquals(2, users.size)
    }

    @Test
    fun followReducerNeverProducesNegativeFollowerCount() {
        val profile = previewProfile(isFollowed = true, followers = 0)
        val unfollowed = profile.withFollow(false)

        assertFalse(unfollowed.isFollowed)
        assertEquals(0, unfollowed.followerCount)
        assertEquals(unfollowed, unfollowed.withFollow(false))
    }

    @Test
    fun nicknameValidationRejectsBlankAndMultilineValues() {
        assertEquals("昵称不能为空", validateNickname("  "))
        assertEquals("昵称不能包含换行", validateNickname("第一行\n第二行"))
        assertNull(validateNickname("纸间读者"))
    }

    @Test
    fun profileCountsMatchIosChineseCompaction() {
        assertEquals("9999", compactProfileCount(9_999))
        assertEquals("1万", compactProfileCount(10_000))
        assertEquals("1.2万", compactProfileCount(12_999))
        assertEquals("12万", compactProfileCount(120_000))
        assertEquals("0", compactProfileCount(-1))
    }

    @Test
    fun profileMetadataKeepsIdentityAndDetailsSeparate() {
        val profile = previewProfile(isFollowed = false, followers = 3).copy(
            sex = UserProfileSex.Female,
            tiebaId = "reader",
            tiebaAge = "12 年",
            location = "IP属地 广东",
        )

        assertEquals("女 · ID reader", profileIdentityMetadata(profile))
        assertEquals("吧龄 12 年 · IP属地 广东", profileDetailMetadata(profile))
    }

    @Test
    fun libraryEmptyCopyDistinguishesNoDataFromNoFilterMatch() {
        assertEquals(
            "暂无帖子收藏" to "在帖子页点击右上角的收藏按钮后，会显示在这里。",
            favoriteEmptyState(ThreadFavoritesUiState(isLoggedIn = true)),
        )
        assertEquals(
            "没有匹配的浏览历史" to "尝试调整搜索内容或时间范围。",
            historyEmptyState(
                BrowsingHistoryUiState(
                    entries = listOf(history(1, 0)),
                    searchText = "missing",
                ),
            ),
        )
    }

    private fun favorite(id: Long, title: String, forum: String, author: String) = AccountThreadFavorite(
        threadId = id,
        forumId = id,
        forumName = forum,
        title = title,
        authorDisplayName = author,
        replyCount = 1,
        lastReplyAtEpochSeconds = null,
        markedPostId = null,
    )

    private fun history(
        id: Long,
        visitedAt: Long,
        title: String = "帖子$id",
        author: String = "用户$id",
        forum: String = "贴吧$id",
    ) = BrowsingHistoryEntry(thread(id, title, author, forum), visitedAt)

    private fun thread(id: Long, title: String, author: String = "用户$id", forum: String = "贴吧$id") = ThreadSummary(
        id = id,
        title = title,
        author = UserSummary(id, author, author, ""),
        forumName = forum,
        replyCount = 0,
        viewCount = 0,
        blocks = listOf(ContentBlock.Text("正文")),
    )

    private fun previewProfile(isFollowed: Boolean, followers: Int) = dev.infinityf4p.tiebapure.core.model.UserProfile(
        user = UserSummary(1, "user", "用户", ""),
        isCurrentUser = false,
        isFollowed = isFollowed,
        tiebaId = "1",
        tiebaAge = "1 年",
        sex = dev.infinityf4p.tiebapure.core.model.UserProfileSex.Unspecified,
        location = null,
        intro = "",
        backgroundUrl = null,
        agreeCount = 0,
        followingCount = 0,
        followerCount = followers,
        threadCount = 0,
        followedForumCount = 0,
        followedForums = emptyList(),
        followedForumsVisibility = dev.infinityf4p.tiebapure.core.model.UserContentVisibility.Visible,
    )

    private companion object {
        const val DAY = 86_400_000L
    }
}
