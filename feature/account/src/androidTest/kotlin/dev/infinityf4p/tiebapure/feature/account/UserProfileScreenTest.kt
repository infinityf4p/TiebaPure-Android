package dev.infinityf4p.tiebapure.feature.account

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import dev.infinityf4p.tiebapure.core.designsystem.TiebaPureTheme
import dev.infinityf4p.tiebapure.core.model.OwnThreadDeletionTarget
import dev.infinityf4p.tiebapure.core.model.ThreadSummary
import dev.infinityf4p.tiebapure.core.model.UserContentVisibility
import dev.infinityf4p.tiebapure.core.model.UserProfile
import dev.infinityf4p.tiebapure.core.model.UserProfileSex
import dev.infinityf4p.tiebapure.core.model.UserSummary
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class UserProfileScreenTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun loadedProfileKeepsThreadLoadingStateUntilThreadsResolve() {
        composeRule.setContent {
            TiebaPureTheme {
                UserProfileScreen(
                    state = UserProfileUiState(
                        profile = profile(),
                        isInitialLoading = true,
                    ),
                    onBack = {},
                )
            }
        }

        composeRule.onNodeWithText("正在加载帖子").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("暂未发布帖子").assertDoesNotExist()
    }

    @Test
    fun failedInitialThreadLoadOffersProfileRefreshRetry() {
        var retryCalls = 0
        composeRule.setContent {
            TiebaPureTheme {
                UserProfileScreen(
                    state = UserProfileUiState(
                        profile = profile(),
                        isInitialLoading = false,
                        errorMessage = "网络连接失败",
                    ),
                    onBack = {},
                    onRetry = { retryCalls += 1 },
                )
            }
        }

        composeRule.onNodeWithText("帖子加载失败").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("重试").performScrollTo().performClick()
        composeRule.runOnIdle { assertEquals(1, retryCalls) }
    }

    @Test
    fun otherUserProfileHidesUnexpectedDeleteAction() {
        composeRule.setContent {
            TiebaPureTheme {
                UserProfileScreen(
                    state = profileState(isCurrentUser = false),
                    onBack = {},
                )
            }
        }

        composeRule.onNodeWithText("测试帖子").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithContentDescription("删除帖子").assertDoesNotExist()
    }

    @Test
    fun currentUserProfileShowsDeleteAction() {
        composeRule.setContent {
            TiebaPureTheme {
                UserProfileScreen(
                    state = profileState(isCurrentUser = true),
                    onBack = {},
                )
            }
        }

        composeRule.onNodeWithContentDescription("删除帖子").performScrollTo().assertIsDisplayed()
    }
}

private fun profile(isCurrentUser: Boolean = false) = UserProfile(
    user = UserSummary(1, "tester", "测试用户", ""),
    isCurrentUser = isCurrentUser,
    isFollowed = false,
    tiebaId = "tester",
    tiebaAge = "1 年",
    sex = UserProfileSex.Unspecified,
    location = null,
    intro = "",
    backgroundUrl = null,
    agreeCount = 0,
    followingCount = 0,
    followerCount = 0,
    threadCount = 0,
    followedForumCount = 0,
    followedForums = emptyList(),
    followedForumsVisibility = UserContentVisibility.Visible,
)

private val profileThread = ThreadSummary(
    id = 22,
    forumId = 11,
    title = "测试帖子",
    author = UserSummary(1, "tester", "测试用户", ""),
    forumName = "测试",
    replyCount = 0,
    viewCount = 0,
    firstPostId = 33uL,
    blocks = emptyList(),
)

private fun profileState(isCurrentUser: Boolean) = UserProfileUiState(
    profile = profile(isCurrentUser),
    threads = listOf(profileThread),
    hasMoreThreads = false,
    deletionTargets = mapOf(
        profileThread.id to OwnThreadDeletionTarget(
            forumId = 11,
            forumName = "测试",
            threadId = profileThread.id,
            firstPostId = 33uL,
        ),
    ),
)
