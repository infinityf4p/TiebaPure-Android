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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.infinityf4p.tiebapure.core.designsystem.TiebaPureTheme
import dev.infinityf4p.tiebapure.core.model.ContentBlock
import dev.infinityf4p.tiebapure.core.model.Forum
import dev.infinityf4p.tiebapure.core.model.ForumInfo
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
    fun forumTitleExpandsInlineInfoAndPanelClickCollapsesIt() {
        var state by mutableStateOf(
            ForumThreadsUiState(
                forum = Forum(1, "测试", "测试吧"),
                forumInfo = testForumInfo,
                threads = emptyList(),
                hasMore = false,
            ),
        )
        composeRule.setContent {
            TiebaPureTheme {
                ForumThreadsScreen(
                    uiState = state,
                    callbacks = ForumThreadsCallbacks(),
                    onSelectCategory = {},
                    onTogglePinned = {},
                    onRefresh = {},
                    onLoadMore = {},
                    onToggleForumFollow = {},
                    onDismissForumActionError = {},
                    onToggleForumInfo = {
                        state = state.copy(showsForumInfo = !state.showsForumInfo)
                    },
                )
            }
        }

        composeRule.onNodeWithTag("forum-info-panel").assertDoesNotExist()
        composeRule.onNodeWithTag("forum-title").performClick()
        composeRule.onNodeWithTag("forum-info-panel").assertIsDisplayed()
        composeRule.onNodeWithText("53.6 万成员 · 128.4 万帖子").assertIsDisplayed()
        composeRule.onNodeWithText("用于测试的贴吧简介").assertIsDisplayed()
        composeRule.onNodeWithText("兴趣 · 软件").assertIsDisplayed()

        composeRule.onNodeWithTag("forum-info-panel").performClick()
        composeRule.onNodeWithTag("forum-info-panel").assertDoesNotExist()
    }

    @Test
    fun forumInfoRetryDoesNotCollapsePanel() {
        var collapseCalls = 0
        var retryCalls = 0
        composeRule.setContent {
            TiebaPureTheme {
                ForumThreadsScreen(
                    uiState = ForumThreadsUiState(
                        forum = Forum(1, "测试", "测试吧"),
                        showsForumInfo = true,
                        forumInfoError = "贴吧资料加载失败，请稍后重试。",
                        hasMore = false,
                    ),
                    callbacks = ForumThreadsCallbacks(),
                    onSelectCategory = {},
                    onTogglePinned = {},
                    onRefresh = {},
                    onLoadMore = {},
                    onToggleForumFollow = {},
                    onDismissForumActionError = {},
                    onToggleForumInfo = { collapseCalls += 1 },
                    onRetryForumInfo = { retryCalls += 1 },
                )
            }
        }

        composeRule.onNodeWithTag("forum-info-retry").performClick()
        composeRule.runOnIdle {
            assertEquals(0, collapseCalls)
            assertEquals(1, retryCalls)
        }
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
    fun cachedErrorUsesRetryInsteadOfLoadMore() {
        var retryCalls = 0
        var loadMoreCalls = 0
        composeRule.setContent {
            TiebaPureTheme {
                ForumThreadsScreen(
                    uiState = ForumThreadsUiState(
                        forum = Forum(1, "测试", "测试吧"),
                        threads = listOf(testThread(id = 8, title = "缓存主题")),
                        hasMore = false,
                        errorMessage = "刷新失败",
                    ),
                    callbacks = ForumThreadsCallbacks(),
                    onSelectCategory = {},
                    onTogglePinned = {},
                    onRefresh = {},
                    onLoadMore = { loadMoreCalls += 1 },
                    onRetry = { retryCalls += 1 },
                    onToggleForumFollow = {},
                    onDismissForumActionError = {},
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

private fun testThread(id: Long, title: String) = ThreadSummary(
    id = id,
    title = title,
    author = UserSummary(id, "user-$id", "用户$id", ""),
    replyCount = 0,
    viewCount = 0,
    blocks = emptyList(),
)

private val testForumInfo = ForumInfo(
    forumId = 1,
    memberCount = 536_000,
    postCount = 1_284_000,
    threadCount = 160_000,
    introduction = "用于测试的贴吧简介",
    primaryCategory = "兴趣",
    secondaryCategory = "软件",
)
