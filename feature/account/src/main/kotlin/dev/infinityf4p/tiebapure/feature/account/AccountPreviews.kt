package dev.infinityf4p.tiebapure.feature.account

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import dev.infinityf4p.tiebapure.core.designsystem.TiebaPureTheme
import dev.infinityf4p.tiebapure.core.model.Account
import dev.infinityf4p.tiebapure.core.model.ContentBlock
import dev.infinityf4p.tiebapure.core.model.Forum
import dev.infinityf4p.tiebapure.core.model.ThreadSummary
import dev.infinityf4p.tiebapure.core.model.UserContentVisibility
import dev.infinityf4p.tiebapure.core.model.UserProfile
import dev.infinityf4p.tiebapure.core.model.UserProfileSex
import dev.infinityf4p.tiebapure.core.model.UserSummary

private val previewUser = UserSummary(
    id = 1024,
    name = "reader_1024",
    displayName = "纸间读者",
    portrait = "",
    level = 8,
    levelName = "长期读者",
)

private val previewThread = ThreadSummary(
    id = 2036,
    title = "一个用于 Android 版页面校准的公开帖子",
    author = previewUser,
    forumName = "TiebaPure",
    replyCount = 42,
    viewCount = 832,
    blocks = listOf(ContentBlock.Text("这里展示用户公开发布的帖子摘要。")),
)

private val previewProfile = UserProfile(
    user = previewUser,
    isCurrentUser = false,
    isFollowed = false,
    tiebaId = "1024",
    tiebaAge = "8.2 年",
    sex = UserProfileSex.Unspecified,
    location = "广东",
    intro = "保持好奇，认真阅读。",
    backgroundUrl = null,
    agreeCount = 2356,
    followingCount = 108,
    followerCount = 264,
    threadCount = 49,
    followedForumCount = 38,
    followedForums = listOf(Forum(1, "tiebapure", "TiebaPure吧", memberCount = 3200)),
    followedForumsVisibility = UserContentVisibility.Visible,
)

@Preview(showBackground = true, widthDp = 390, heightDp = 844)
@Composable
private fun MePreview() {
    TiebaPureTheme {
        MeScreen(
            state = MeUiState(
                loginStatus = AccountLoginStatus.LoggedIn(
                    Account("1024", "reader_1024", "纸间读者", "", "", "", tbs = ""),
                ),
                visibleHistoryCount = 37,
            ),
            onLogin = {},
            onOpen = {},
        )
    }
}

@Preview(showBackground = true, widthDp = 390, heightDp = 844)
@Composable
private fun UserProfilePreview() {
    TiebaPureTheme {
        UserProfileScreen(
            state = UserProfileUiState(profile = previewProfile, threads = listOf(previewThread), isInitialLoading = false),
            onBack = {},
        )
    }
}

@Preview(showBackground = true, widthDp = 390, heightDp = 844)
@Composable
private fun PrivateProfilePreview() {
    TiebaPureTheme {
        UserProfileScreen(
            state = UserProfileUiState(
                profile = previewProfile.copy(followedForums = emptyList(), followedForumsVisibility = UserContentVisibility.Private),
                threadVisibility = UserContentVisibility.Private,
                isInitialLoading = false,
            ),
            onBack = {},
        )
    }
}
