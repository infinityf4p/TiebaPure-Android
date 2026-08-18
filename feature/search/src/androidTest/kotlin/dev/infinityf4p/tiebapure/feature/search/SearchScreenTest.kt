package dev.infinityf4p.tiebapure.feature.search

import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import dev.infinityf4p.tiebapure.core.designsystem.TiebaPureTheme
import dev.infinityf4p.tiebapure.core.model.ContentBlock
import dev.infinityf4p.tiebapure.core.model.ImageContent
import dev.infinityf4p.tiebapure.core.model.ThreadSummary
import dev.infinityf4p.tiebapure.core.model.UserSummary
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class SearchScreenTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun emptyQueryShowsHistory() {
        composeRule.setContent {
            TiebaPureTheme {
                SearchScreen(
                    SearchUiState(history = listOf("历史词")), SearchCallbacks(), {}, {}, {}, {}, {}, {}, {}, {}, {}, {},
                )
            }
        }
        composeRule.onNodeWithText("搜索历史").assertIsDisplayed()
        composeRule.onNodeWithText("历史词").assertIsDisplayed()
    }

    @Test
    fun emptyFilteredPageOffersStableContinueCommand() {
        var loadMoreCalls = 0
        composeRule.setContent {
            TiebaPureTheme {
                SearchScreen(
                    uiState = SearchUiState(
                        input = "测试",
                        submittedKeyword = "测试",
                        items = emptyList(),
                        hasMore = true,
                        nextPage = 2,
                    ),
                    callbacks = SearchCallbacks(),
                    onInputChanged = {},
                    onSubmit = {},
                    onClearQuery = {},
                    onSelectHistory = {},
                    onRemoveHistory = {},
                    onClearHistory = {},
                    onSelectFilter = {},
                    onSelectSort = {},
                    onRefresh = {},
                    onLoadMore = { loadMoreCalls += 1 },
                )
            }
        }

        composeRule.onNodeWithTag("search-empty-page-load-more")
            .assertIsDisplayed()
            .assertHeightIsEqualTo(48.dp)
            .performClick()
        composeRule.runOnIdle { assertEquals(1, loadMoreCalls) }
    }

    @Test
    fun cachedErrorUsesRetryInsteadOfLoadMore() {
        var retryCalls = 0
        var loadMoreCalls = 0
        val thread = ThreadSummary(
            id = 11,
            title = "缓存搜索结果",
            author = UserSummary(1, "u", "作者", ""),
            replyCount = 0,
            viewCount = 0,
            blocks = emptyList(),
        )
        composeRule.setContent {
            TiebaPureTheme {
                SearchScreen(
                    uiState = SearchUiState(
                        input = "测试",
                        submittedKeyword = "测试",
                        items = listOf(SearchItem.ThreadResult(thread)),
                        hasMore = false,
                        errorMessage = "刷新失败",
                    ),
                    callbacks = SearchCallbacks(),
                    onInputChanged = {},
                    onSubmit = {},
                    onClearQuery = {},
                    onSelectHistory = {},
                    onRemoveHistory = {},
                    onClearHistory = {},
                    onSelectFilter = {},
                    onSelectSort = {},
                    onRefresh = {},
                    onLoadMore = { loadMoreCalls += 1 },
                    onRetry = { retryCalls += 1 },
                )
            }
        }

        composeRule.onNodeWithText("加载失败，点击重试").performScrollTo().performClick()
        composeRule.runOnIdle {
            assertEquals(1, retryCalls)
            assertEquals(0, loadMoreCalls)
        }
    }

    @Test
    fun searchResultShowsMediaPreview() {
        val thread = ThreadSummary(
            id = 12,
            title = "搜索媒体主题",
            author = UserSummary(1, "u", "作者", ""),
            forumName = "测试",
            forumAvatarUrl = "https://tb1.bdstatic.com/search-forum.jpg",
            replyCount = 0,
            viewCount = 0,
            likeCount = 6,
            blocks = listOf(ContentBlock.Image(ImageContent("https://tiebapic.baidu.com/search.jpg", null, 1, 1, false))),
        )
        composeRule.setContent {
            TiebaPureTheme {
                SearchScreen(
                    uiState = SearchUiState(
                        input = "媒体",
                        submittedKeyword = "媒体",
                        items = listOf(SearchItem.ThreadResult(thread)),
                        hasMore = false,
                    ),
                    callbacks = SearchCallbacks(),
                    onInputChanged = {}, onSubmit = {}, onClearQuery = {}, onSelectHistory = {},
                    onRemoveHistory = {}, onClearHistory = {}, onSelectFilter = {}, onSelectSort = {},
                    onRefresh = {}, onLoadMore = {},
                )
            }
        }

        composeRule.onNodeWithText("搜索媒体主题").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("thread-media-preview-0", useUnmergedTree = true)
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithText("测试吧").assertIsDisplayed()
        composeRule.onNodeWithText("作者").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("点赞，当前6个赞", useUnmergedTree = true).assertIsDisplayed()
    }
}
