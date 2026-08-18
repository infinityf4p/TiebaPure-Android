package dev.infinityf4p.tiebapure.feature.home

import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.hasStateDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.unit.dp
import dev.infinityf4p.tiebapure.core.designsystem.TiebaPureTheme
import dev.infinityf4p.tiebapure.core.model.ContentBlock
import dev.infinityf4p.tiebapure.core.model.ImageContent
import dev.infinityf4p.tiebapure.core.model.ThreadSummary
import dev.infinityf4p.tiebapure.core.model.UserSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class HomeScreenTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun feedShowsThreadAndForum() {
        val thread = ThreadSummary(
            id = 1,
            title = "可见主题",
            author = UserSummary(1, "u", "作者", ""),
            forumName = "测试",
            forumAvatarUrl = "https://tb1.bdstatic.com/forum-avatar.jpg",
            replyCount = 3,
            viewCount = 9,
            blocks = emptyList(),
        )
        composeRule.setContent {
            TiebaPureTheme {
                HomeScreen(HomeUiState(threads = listOf(thread)), HomeCallbacks(), {}, {})
            }
        }

        composeRule.onNodeWithText("可见主题").assertIsDisplayed()
        composeRule.onNodeWithText("测试吧").assertIsDisplayed()
        composeRule.onNodeWithText("作者").assertIsDisplayed()
    }

    @Test
    fun emptyFilteredPageOffersStableContinueCommand() {
        var loadMoreCalls = 0
        composeRule.setContent {
            TiebaPureTheme {
                HomeScreen(
                    HomeUiState(threads = emptyList(), hasMore = true, nextPage = 2),
                    HomeCallbacks(),
                    onRefresh = {},
                    onLoadMore = { loadMoreCalls += 1 },
                )
            }
        }

        composeRule.onNodeWithTag("home-empty-page-load-more")
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
                HomeScreen(
                    uiState = HomeUiState(
                        threads = listOf(mediaThread(id = 20, title = "缓存主题")),
                        hasMore = false,
                        errorMessage = "刷新失败",
                    ),
                    callbacks = HomeCallbacks(),
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
    fun feedShowsMediaPreviewWithoutHidingText() {
        val thread = mediaThread(id = 21, title = "带图片的主题")
        composeRule.setContent {
            TiebaPureTheme {
                HomeScreen(HomeUiState(threads = listOf(thread), hasMore = false), HomeCallbacks(), {}, {})
            }
        }

        composeRule.onNodeWithText("带图片的主题").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("thread-media-preview-0", useUnmergedTree = true)
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun unknownLikeOutcomeDisablesActionAndExplainsState() {
        val thread = mediaThread(id = 22, title = "点赞状态主题").copy(
            firstPostId = 220u,
            likeCount = 2,
        )
        composeRule.setContent {
            TiebaPureTheme {
                HomeScreen(
                    HomeUiState(
                        threads = listOf(thread),
                        unknownLikeThreadIds = setOf(thread.id),
                        hasMore = false,
                    ),
                    HomeCallbacks(canLike = true, onToggleLike = {}), {}, {},
                )
            }
        }

        composeRule.onNodeWithText("点赞状态主题").performScrollTo()
        composeRule.onNode(hasStateDescription("点赞结果待确认"), useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithContentDescription("点赞，当前2个赞", useUnmergedTree = true)
            .assertIsDisplayed()
            .assertIsNotEnabled()
        composeRule.onNodeWithTag("home-like-22", useUnmergedTree = true)
            .performScrollTo()
    }

    @Test
    fun disabledLikeCapabilityKeepsCountStaticWithoutInvokingMutation() {
        val thread = mediaThread(id = 23, title = "只读点赞主题").copy(firstPostId = 230u, likeCount = 8)
        var toggleCalls = 0
        composeRule.setContent {
            TiebaPureTheme {
                HomeScreen(
                    HomeUiState(threads = listOf(thread), hasMore = false),
                    HomeCallbacks(
                        canLike = false,
                        onToggleLike = { toggleCalls += 1 },
                    ),
                    {},
                    {},
                )
            }
        }

        composeRule.onNodeWithText("只读点赞主题").performScrollTo()
        composeRule.onNodeWithContentDescription("点赞，当前8个赞", useUnmergedTree = true)
            .assertIsDisplayed()
            .assertHasNoClickAction()
        composeRule.runOnIdle { assertEquals(0, toggleCalls) }
    }

    @Test
    fun refreshingHoldsFeedContentDownThenRebounds() {
        val state = mutableStateOf(
            HomeUiState(threads = listOf(mediaThread(id = 24, title = "刷新位移主题")), hasMore = false),
        )
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            TiebaPureTheme {
                HomeScreen(state.value, HomeCallbacks(), {}, {})
            }
        }

        val initialTop = composeRule.onNodeWithTag("home-refresh-content")
            .getUnclippedBoundsInRoot().top
        composeRule.runOnIdle { state.value = state.value.copy(isRefreshing = true) }
        composeRule.mainClock.advanceTimeBy(300)
        val refreshingTop = composeRule.onNodeWithTag("home-refresh-content")
            .getUnclippedBoundsInRoot().top
        assertTrue(refreshingTop >= initialTop + 55.dp)

        composeRule.runOnIdle { state.value = state.value.copy(isRefreshing = false) }
        composeRule.mainClock.advanceTimeBy(300)
        val settledTop = composeRule.onNodeWithTag("home-refresh-content")
            .getUnclippedBoundsInRoot().top
        assertTrue(settledTop <= initialTop + 1.dp)
    }

    @Test
    fun pullGestureTriggersRefreshAndUsesHeldContentPosition() {
        val state = mutableStateOf(
            HomeUiState(threads = listOf(mediaThread(id = 25, title = "手势刷新主题")), hasMore = false),
        )
        var refreshCalls = 0
        composeRule.setContent {
            TiebaPureTheme {
                HomeScreen(
                    uiState = state.value,
                    callbacks = HomeCallbacks(),
                    onRefresh = {
                        refreshCalls += 1
                        state.value = state.value.copy(isRefreshing = true)
                    },
                    onLoadMore = {},
                )
            }
        }

        val initialTop = composeRule.onNodeWithTag("home-refresh-content")
            .getUnclippedBoundsInRoot().top
        composeRule.onNodeWithTag("home-feed").performTouchInput { swipeDown() }
        composeRule.waitUntil(timeoutMillis = 3_000) { refreshCalls == 1 }
        val refreshingTop = composeRule.onNodeWithTag("home-refresh-content")
            .getUnclippedBoundsInRoot().top

        assertTrue(refreshingTop >= initialTop + 55.dp)
    }

    @Test
    fun programmaticRefreshReturnsDeepListToTopBeforeLoadingLatestPage() {
        val refreshRequest = mutableLongStateOf(0L)
        val threads = (0L..16L).map { mediaThread(id = 100 + it, title = "主题$it") }
        val state = mutableStateOf(HomeUiState(threads = threads, hasMore = false))
        var refreshCalls = 0
        composeRule.setContent {
            TiebaPureTheme {
                HomeScreen(
                    uiState = state.value,
                    callbacks = HomeCallbacks(),
                    onRefresh = {
                        refreshCalls += 1
                        state.value = state.value.copy(isRefreshing = true)
                    },
                    onLoadMore = {},
                    programmaticRefreshRequest = refreshRequest.longValue,
                )
            }
        }

        composeRule.onNodeWithTag("home-feed-list").performScrollToIndex(16)
        composeRule.onNodeWithText("主题16").assertIsDisplayed()
        composeRule.runOnIdle { refreshRequest.longValue += 1 }
        composeRule.waitUntil(timeoutMillis = 3_000) { refreshCalls == 1 }

        composeRule.onNodeWithText("主题0").assertIsDisplayed()
        composeRule.runOnIdle {
            state.value = state.value.copy(
                threads = listOf(mediaThread(id = 999, title = "最新主题")) + threads,
                isRefreshing = false,
                refreshCommitVersion = state.value.refreshCommitVersion + 1,
            )
        }
        composeRule.onNodeWithText("最新主题").assertIsDisplayed()
        composeRule.runOnIdle { assertEquals(1, refreshCalls) }
    }
}

private fun mediaThread(id: Long, title: String) = ThreadSummary(
    id = id,
    title = title,
    author = UserSummary(1, "u", "作者", ""),
    replyCount = 3,
    viewCount = 9,
    blocks = listOf(
        ContentBlock.Text("媒体不可用时也保留这段文字"),
        ContentBlock.Image(ImageContent("https://tiebapic.baidu.com/preview.jpg", null, 4, 3, false)),
    ),
)
