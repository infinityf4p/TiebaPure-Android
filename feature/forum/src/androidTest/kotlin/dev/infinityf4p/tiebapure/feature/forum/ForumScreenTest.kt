package dev.infinityf4p.tiebapure.feature.forum

import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import dev.infinityf4p.tiebapure.core.designsystem.TiebaPureTheme
import dev.infinityf4p.tiebapure.core.model.ContentBlock
import dev.infinityf4p.tiebapure.core.model.Forum
import dev.infinityf4p.tiebapure.core.model.ImageContent
import dev.infinityf4p.tiebapure.core.model.ThreadSummary
import dev.infinityf4p.tiebapure.core.model.UserSummary
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class ForumScreenTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun hubSeparatesRecentAndFollowedSections() {
        val forum = Forum(1, "测试", "测试吧")
        composeRule.setContent {
            TiebaPureTheme {
                ForumHubScreen(ForumHubUiState(listOf(forum), listOf(forum)), {}, {}, {}, {})
            }
        }
        composeRule.onNodeWithText("最近浏览").assertIsDisplayed()
        composeRule.onNodeWithText("关注贴吧").assertIsDisplayed()
    }

    @Test
    fun emptyFilteredPageOffersStableContinueCommand() {
        var loadMoreCalls = 0
        composeRule.setContent {
            TiebaPureTheme {
                ForumThreadsScreen(
                    uiState = ForumThreadsUiState(
                        forum = Forum(1, "测试", "测试吧"),
                        threads = emptyList(),
                        hasMore = true,
                        nextPage = 2,
                    ),
                    callbacks = ForumThreadsCallbacks(),
                    onSelectCategory = {},
                    onTogglePinned = {},
                    onRefresh = {},
                    onLoadMore = { loadMoreCalls += 1 },
                    onToggleForumFollow = {},
                    onDismissForumActionError = {},
                )
            }
        }

        composeRule.onNodeWithTag("forum-empty-page-load-more")
            .assertIsDisplayed()
            .assertHeightIsEqualTo(48.dp)
            .performClick()
        composeRule.runOnIdle { assertEquals(1, loadMoreCalls) }
    }

    @Test
    fun invalidAuthorHasNoProfileClickAction() {
        val thread = ThreadSummary(
            id = 9,
            title = "无有效作者",
            author = UserSummary(0, "", "未知用户", ""),
            replyCount = 0,
            viewCount = 0,
            blocks = emptyList(),
        )
        composeRule.setContent {
            TiebaPureTheme {
                ForumThreadsScreen(
                    uiState = ForumThreadsUiState(
                        forum = Forum(1, "测试", "测试吧"),
                        threads = listOf(thread),
                        hasMore = false,
                    ),
                    callbacks = ForumThreadsCallbacks(),
                    onSelectCategory = {},
                    onTogglePinned = {},
                    onRefresh = {},
                    onLoadMore = {},
                    onToggleForumFollow = {},
                    onDismissForumActionError = {},
                )
            }
        }

        composeRule.onNodeWithText("无有效作者").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("forum-thread-author-9", useUnmergedTree = true)
            .performScrollTo()
            .assertIsDisplayed()
            .assertHasNoClickAction()
    }

    @Test
    fun threadListShowsMediaPreview() {
        val thread = ThreadSummary(
            id = 10,
            title = "吧页媒体主题",
            author = UserSummary(1, "u", "作者", ""),
            replyCount = 0,
            viewCount = 0,
            blocks = listOf(ContentBlock.Image(ImageContent("https://tiebapic.baidu.com/forum.jpg", null, 1, 1, false))),
        )
        composeRule.setContent {
            TiebaPureTheme {
                ForumThreadsScreen(
                    uiState = ForumThreadsUiState(Forum(1, "测试", "测试吧"), threads = listOf(thread), hasMore = false),
                    callbacks = ForumThreadsCallbacks(),
                    onSelectCategory = {},
                    onTogglePinned = {},
                    onRefresh = {},
                    onLoadMore = {},
                    onToggleForumFollow = {},
                    onDismissForumActionError = {},
                )
            }
        }

        composeRule.onNodeWithText("吧页媒体主题").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("thread-media-preview-0", useUnmergedTree = true)
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun forumThreadShowsSharedReplyAndLikeStats() {
        val thread = ThreadSummary(
            id = 11,
            title = "统计主题",
            author = UserSummary(1, "u", "作者", ""),
            replyCount = 12,
            viewCount = 30,
            likeCount = 7,
            blocks = emptyList(),
        )
        composeRule.setContent {
            TiebaPureTheme {
                ForumThreadsScreen(
                    uiState = ForumThreadsUiState(Forum(1, "测试", "测试吧"), threads = listOf(thread), hasMore = false),
                    callbacks = ForumThreadsCallbacks(),
                    onSelectCategory = {}, onTogglePinned = {}, onRefresh = {}, onLoadMore = {},
                    onToggleForumFollow = {}, onDismissForumActionError = {},
                )
            }
        }

        composeRule.onNodeWithText("统计主题").performScrollTo()
        composeRule.onNodeWithContentDescription("评论，当前12条评论", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithContentDescription("点赞，当前7个赞", useUnmergedTree = true).assertIsDisplayed()
    }
}
