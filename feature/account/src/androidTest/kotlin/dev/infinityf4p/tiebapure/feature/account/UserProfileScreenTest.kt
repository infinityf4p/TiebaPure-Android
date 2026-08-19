package dev.infinityf4p.tiebapure.feature.account

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import dev.infinityf4p.tiebapure.core.designsystem.TiebaPureTheme
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
}

private fun profile() = UserProfile(
    user = UserSummary(1, "tester", "测试用户", ""),
    isCurrentUser = false,
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
