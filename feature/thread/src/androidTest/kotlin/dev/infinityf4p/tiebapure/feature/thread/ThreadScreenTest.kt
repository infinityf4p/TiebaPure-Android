package dev.infinityf4p.tiebapure.feature.thread

import androidx.compose.ui.test.assertAll
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import dev.infinityf4p.tiebapure.core.designsystem.TiebaPureTheme
import dev.infinityf4p.tiebapure.core.model.ContentBlock
import dev.infinityf4p.tiebapure.core.model.Forum
import dev.infinityf4p.tiebapure.core.model.Post
import dev.infinityf4p.tiebapure.core.model.Subpost
import dev.infinityf4p.tiebapure.core.model.ThreadPage
import dev.infinityf4p.tiebapure.core.model.ThreadSummary
import dev.infinityf4p.tiebapure.core.model.UserSummary
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class ThreadScreenTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun disabledCapabilitiesRemoveWriteEntrancesButKeepReadOnlyMetadata() {
        val parent = replyPost(
            id = 2uL,
            floor = 2,
            previewSubposts = listOf(subpost(21uL)),
            subpostCount = 2,
        )
        val state = threadState(
            posts = listOf(parent),
            subposts = SubpostUiState(
                parent = parent,
                items = listOf(subpost(21uL)),
                hasMore = false,
                isLoading = false,
            ),
        )

        composeRule.setContent {
            TiebaPureTheme {
                TestThreadScreen(
                    state = state,
                    capabilities = ThreadCapabilities(
                        canReply = false,
                        canLike = false,
                        canCollect = false,
                    ),
                )
            }
        }

        composeRule.onNodeWithTag("thread-reply-bar").assertDoesNotExist()
        composeRule.onNodeWithTag("thread-collect-action").assertDoesNotExist()
        composeRule.onAllNodesWithTag("thread-metadata-reply", useUnmergedTree = true)
            .assertCountEquals(0)
        composeRule.onAllNodesWithTag("thread-like-action", useUnmergedTree = true)
            .assertAll(hasClickAction().not())
        composeRule.onAllNodesWithTag("thread-post-body-${parent.id}", useUnmergedTree = true)
            .assertAll(hasClickAction().not())
        composeRule.onAllNodesWithTag("thread-subpost-body-21", useUnmergedTree = true)
            .assertAll(hasClickAction().not())
        composeRule.onAllNodesWithText("北京", substring = true)
            .assertCountEquals(4)
    }

    @Test
    fun forumTitleUsesForumAvatarAndInvokesNavigationCallback() {
        var selectedForum: Forum? = null
        val state = threadState()
        composeRule.setContent {
            TiebaPureTheme {
                TestThreadScreen(state = state, onForumClick = { selectedForum = it })
            }
        }

        composeRule.onNodeWithTag("thread-forum-title")
            .assertIsDisplayed()
            .assertHeightIsEqualTo(48.dp)
            .performClick()

        composeRule.runOnIdle { assertEquals(state.page?.forum, selectedForum) }
    }

    @Test
    fun resolvedEmptyReplyListShowsTerminalMessage() {
        composeRule.setContent {
            TiebaPureTheme { TestThreadScreen(state = threadState(posts = emptyList())) }
        }

        composeRule.onNodeWithText("没有更多回复了")
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun cachedErrorFooterUsesRetryInsteadOfPagination() {
        var retryCalls = 0
        var loadMoreCalls = 0
        composeRule.setContent {
            TiebaPureTheme {
                TestThreadScreen(
                    state = threadState().copy(errorMessage = "刷新失败"),
                    onLoadMore = { loadMoreCalls += 1 },
                    onRetry = { retryCalls += 1 },
                )
            }
        }

        composeRule.onNodeWithText("加载失败，点击重试")
            .performScrollTo()
            .assertIsDisplayed()
            .performClick()

        composeRule.runOnIdle {
            assertEquals(1, retryCalls)
            assertEquals(0, loadMoreCalls)
        }
    }

    @Test
    fun openAllRepliesKeepsCompactVisualWithinFortyEightDpTouchTarget() {
        val parent = replyPost(
            id = 2uL,
            floor = 2,
            previewSubposts = listOf(subpost(21uL)),
            subpostCount = 2,
        )
        composeRule.setContent {
            TiebaPureTheme { TestThreadScreen(state = threadState(posts = listOf(parent))) }
        }

        composeRule.onNodeWithTag("thread-subpost-open-all", useUnmergedTree = true)
            .performScrollTo()
            .assertHeightIsEqualTo(48.dp)
        composeRule.onNodeWithTag("thread-subpost-open-all-visual", useUnmergedTree = true)
            .assertHeightIsEqualTo(30.dp)
    }

    @Test
    fun replyDisabledContentReachesThreadSafeAreaBottomWithoutGrayInsetStrip() {
        composeRule.setContent {
            TiebaPureTheme {
                TestThreadScreen(
                    state = threadState(),
                    capabilities = ThreadCapabilities(canReply = false),
                )
            }
        }

        val screenBottom = composeRule.onNodeWithTag("thread-screen")
            .fetchSemanticsNode().boundsInRoot.bottom
        val contentBottom = composeRule.onNodeWithTag("thread-content")
            .fetchSemanticsNode().boundsInRoot.bottom

        assertEquals(screenBottom, contentBottom, 0.5f)
        composeRule.onNodeWithTag("thread-bottom-bar").assertDoesNotExist()
    }

    @Test
    fun replyEnabledBottomBarOwnsTheSafeAreaBottomWithoutExtraInset() {
        composeRule.setContent {
            TiebaPureTheme {
                TestThreadScreen(
                    state = threadState(),
                    capabilities = ThreadCapabilities(canReply = true),
                )
            }
        }

        val screenBottom = composeRule.onNodeWithTag("thread-screen")
            .fetchSemanticsNode().boundsInRoot.bottom
        val contentBottom = composeRule.onNodeWithTag("thread-content")
            .fetchSemanticsNode().boundsInRoot.bottom
        val bottomBarBounds = composeRule.onNodeWithTag("thread-bottom-bar")
            .fetchSemanticsNode().boundsInRoot

        assertEquals(screenBottom, bottomBarBounds.bottom, 0.5f)
        assertEquals(contentBottom, bottomBarBounds.top, 0.5f)
    }
}

@androidx.compose.runtime.Composable
private fun TestThreadScreen(
    state: ThreadUiState,
    capabilities: ThreadCapabilities = ThreadCapabilities(),
    onForumClick: ((Forum) -> Unit)? = null,
    onLoadMore: () -> Unit = {},
    onRetry: () -> Unit = {},
) {
    ThreadScreen(
        state = state,
        capabilities = capabilities,
        onBack = {},
        onForumClick = onForumClick,
        onRefresh = {},
        onLoadMore = onLoadMore,
        onRetry = onRetry,
        onSort = {},
        onOnlyAuthor = {},
        onReply = {},
        onUserClick = {},
        onLinkClick = {},
        onOpenSubposts = {},
        onCloseSubposts = {},
        onLoadMoreSubposts = {},
        onRetrySubposts = {},
        onToggleThreadLike = {},
        onTogglePostLike = {},
        onToggleSubpostLike = {},
        onToggleCollection = {},
        onReadingPositionChanged = {},
        onReadingPositionRestored = {},
        onActionErrorShown = {},
        onDownloadImage = {},
    )
}

private val author = UserSummary(
    id = 1,
    name = "author",
    displayName = "作者",
    portrait = "",
    ipAddress = "IP属地：北京",
)

private fun threadState(
    posts: List<Post> = emptyList(),
    subposts: SubpostUiState? = null,
): ThreadUiState {
    val mainPost = replyPost(id = 1uL, floor = 1)
    val thread = ThreadSummary(
        id = 7,
        forumId = 8,
        title = "测试帖子",
        author = author,
        forumName = "测试",
        replyCount = posts.size,
        viewCount = 10,
        likeCount = 3,
        firstPostId = mainPost.id,
        blocks = mainPost.blocks,
    )
    val page = ThreadPage(
        thread = thread,
        forum = Forum(8, "测试", "测试吧"),
        mainPost = mainPost,
        posts = posts,
        currentPage = 1,
        totalPage = 1,
        hasMore = false,
    )
    return ThreadUiState(
        page = page,
        posts = posts,
        isInitialLoading = false,
        subposts = subposts,
    )
}

private fun replyPost(
    id: ULong,
    floor: Int,
    previewSubposts: List<Subpost> = emptyList(),
    subpostCount: Int = previewSubposts.size,
) = Post(
    id = id,
    threadId = 7,
    floor = floor,
    author = author,
    ipAddress = "IP属地：北京",
    createdAtEpochSeconds = 1_700_000_000,
    blocks = listOf(ContentBlock.Text("正文")),
    subpostCount = subpostCount,
    likeCount = 2,
    previewSubposts = previewSubposts,
)

private fun subpost(id: ULong) = Subpost(
    id = id,
    floor = 1,
    author = author,
    ipAddress = "IP属地：北京",
    blocks = listOf(ContentBlock.Text("楼中楼正文")),
    createdAtEpochSeconds = 1_700_000_000,
    likeCount = 1,
)
