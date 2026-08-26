package dev.infinityf4p.tiebapure.feature.search

import dev.infinityf4p.tiebapure.core.model.Forum
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SearchPolicyTest {
    @Test
    fun globalSearchDefaultsToThreadsWhileForumSearchKeepsExistingOrder() {
        assertEquals(SearchFilter.Threads, SearchUiState().filter)
        assertEquals(
            listOf(SearchFilter.Threads, SearchFilter.Forums, SearchFilter.All),
            SearchScope.Global.availableFilters,
        )
        val forumScope = SearchScope.ForumOnly(Forum(1, "测试", "测试吧"))
        assertEquals(SearchFilter.All, SearchUiState(scope = forumScope).filter)
        assertEquals(listOf(SearchFilter.All, SearchFilter.Threads), forumScope.availableFilters)
    }

    @Test
    fun emptyFilteredPageCanContinueOnlyForSubmittedSearchWithMorePages() {
        assertTrue(
            SearchUiState(
                submittedKeyword = "测试",
                items = emptyList(),
                hasMore = true,
            ).showsEmptyPageContinuation,
        )
        assertTrue(
            SearchUiState(
                submittedKeyword = "测试",
                items = emptyList(),
                isLoadingMore = true,
                hasMore = true,
            ).showsEmptyPageContinuation,
        )
        assertFalse(SearchUiState(submittedKeyword = "测试", items = emptyList(), hasMore = false).showsEmptyPageContinuation)
        assertFalse(SearchUiState(submittedKeyword = "", items = emptyList(), hasMore = true).showsEmptyPageContinuation)
    }

    @Test
    fun historyIsTrimmedDeduplicatedAndBounded() {
        val values = List(25) { " value-$it " } + "value-1"
        val result = normalizeHistory(values)
        assertEquals(20, result.size)
        assertEquals("value-0", result.first())
        assertEquals(1, result.count { it == "value-1" })
    }

    @Test
    fun searchThreadTimeUsesRelativeValuesAndRejectsMissingTimestamps() {
        val now = 500_000L
        assertEquals(null, compactSearchThreadTime(null, nowEpochSeconds = now))
        assertEquals("5分钟前", compactSearchThreadTime(now - 300, nowEpochSeconds = now))
        assertEquals("2天前", compactSearchThreadTime(now - 2 * 86_400, nowEpochSeconds = now))
    }

    @Test
    fun forumCountsUseReadableChineseUnits() {
        assertEquals("0", compactForumSearchCount(-1))
        assertEquals("9999", compactForumSearchCount(9_999))
        assertEquals("53.6 万", compactForumSearchCount(536_999))
        assertEquals("128.4 万", compactForumSearchCount(1_284_999))
    }
}
