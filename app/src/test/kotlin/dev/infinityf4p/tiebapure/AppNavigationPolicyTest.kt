package dev.infinityf4p.tiebapure

import dev.infinityf4p.tiebapure.core.data.RecentForumEntity
import dev.infinityf4p.tiebapure.core.model.Account
import dev.infinityf4p.tiebapure.core.model.ContentSubmissionKind
import dev.infinityf4p.tiebapure.core.model.Forum
import dev.infinityf4p.tiebapure.core.model.ThreadSummary
import dev.infinityf4p.tiebapure.core.model.UserSummary
import dev.infinityf4p.tiebapure.feature.search.SearchItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppNavigationPolicyTest {
    @Test
    fun viewModelSessionKeyChangesWithoutExposingCredentials() {
        val first = account("bduss-one", "stoken-one")
        val second = account("bduss-two", "stoken-two")

        val firstKey = sessionViewModelKey(first)
        val secondKey = sessionViewModelKey(second)

        assertEquals("guest", sessionViewModelKey(null))
        assertTrue(firstKey.matches(Regex("[0-9a-f]{32}")))
        assertFalse(firstKey.contains(first.bduss))
        assertFalse(firstKey.contains(first.stoken))
        assertFalse(firstKey == secondKey)
        assertEquals(firstKey, sessionViewModelKey(first.copy(tbs = "refreshed")))
    }

    @Test
    fun composerDestinationUsesFocusedNavigationSurface() {
        assertTrue(isComposerDestinationRoute("compose/{kind}/{forumId}"))
        assertFalse(isComposerDestinationRoute("thread/{threadId}"))
        assertFalse(isComposerDestinationRoute(null))
    }

    @Test
    fun compactRootNavigationIsHiddenForThreadAndComposerOnly() {
        assertFalse(shouldShowCompactRootNavigation("thread/{threadId}?postId={postId}"))
        assertFalse(shouldShowCompactRootNavigation("compose/{kind}/{forumId}"))
        assertTrue(shouldShowCompactRootNavigation("search?query={query}"))
        assertTrue(shouldShowCompactRootNavigation("user/{userId}/{userName}"))
        assertTrue(shouldShowCompactRootNavigation("home"))
        assertTrue(shouldShowCompactRootNavigation(null))
    }

    @Test
    fun homeReselectRefreshesOnlyWhenFeedIsActuallyVisibleAtRoot() {
        assertTrue(shouldRequestHomeRefresh("home", "home", "home"))
        assertTrue(shouldRequestHomeRefresh("home", "home", "home", "home"))
        assertFalse(shouldRequestHomeRefresh("home", "home", "thread/7"))
        assertFalse(shouldRequestHomeRefresh("home", "home", "home", "thread/7"))
        assertFalse(shouldRequestHomeRefresh("forums", "home", "forums"))
        assertFalse(shouldRequestHomeRefresh("home", "forums", "home"))
    }

    @Test
    fun blankUserNameGetsNonEmptyRouteSegment() {
        val route = buildUserRoute(UserSummary(42, "", "", "")) { "encoded($it)" }

        assertEquals("user/42/encoded(用户42)", route)
    }

    @Test
    fun forumRouteUsesDisplayNameFallbackAndRejectsMissingIdentity() {
        assertEquals(
            "forum/encoded(测试)",
            buildForumRoute(Forum(7, "", "测试吧")) { "encoded($it)" },
        )
        assertEquals(null, buildForumRoute(Forum(7, "", "")) { it })
    }

    @Test
    fun recentForumPlaceholderPreservesResolvedMetadata() {
        val existing = RecentForumEntity(
            normalizedName = "测试",
            forumId = 42,
            name = "测试",
            displayName = "测试吧",
            avatarUrl = "https://example.com/forum.jpg",
            visitedAtMilliseconds = 1,
        )

        val updated = recentForumEntity(
            forum = Forum(0, "测试", ""),
            existing = existing,
            visitedAtMilliseconds = 2,
        )

        assertEquals(42L, updated?.forumId)
        assertEquals("测试吧", updated?.displayName)
        assertEquals("https://example.com/forum.jpg", updated?.avatarUrl)
        assertEquals(2L, updated?.visitedAtMilliseconds)
        assertEquals(
            "显示名",
            recentForumEntity(
                forum = Forum(0, "", "显示名吧"),
                visitedAtMilliseconds = 3,
            )?.name,
        )
    }

    @Test
    fun contentSubmissionSwitchesStayIndependentAtEveryEntryPoint() {
        assertTrue(contentSubmissionEnabled(ContentSubmissionKind.NewThread, true, false))
        assertFalse(contentSubmissionEnabled(ContentSubmissionKind.NewThread, false, true))
        assertTrue(contentSubmissionEnabled(ContentSubmissionKind.ThreadReply, false, true))
        assertFalse(contentSubmissionEnabled(ContentSubmissionKind.SubpostReply, true, false))
    }

    @Test
    fun searchThreadIsRememberedBeforeNavigation() {
        val thread = thread(7, firstPostId = 99uL)
        val events = mutableListOf<String>()

        dispatchSearchThreadNavigation(
            result = SearchItem.ThreadResult(thread, postId = 99uL),
            rememberThreadSummary = { events += "remember:${it.id}" },
            navigate = { events += "navigate:$it" },
        )

        assertEquals(
            listOf(
                "remember:7",
                "navigate:thread/7?postId=99&initialDestination=",
            ),
            events,
        )
    }

    @Test
    fun searchReplyNavigatesWithoutCachingMatchedReplyAsMainPost() {
        val events = mutableListOf<String>()

        dispatchSearchThreadNavigation(
            result = SearchItem.ThreadResult(thread(7, firstPostId = 11uL), postId = 99uL),
            rememberThreadSummary = { events += "remember:${it.id}" },
            navigate = { events += "navigate:$it" },
        )

        assertEquals(
            listOf("navigate:thread/7?postId=99&initialDestination="),
            events,
        )
    }

    @Test
    fun searchMatchWithoutConfirmedFirstPostNavigatesWithoutCaching() {
        val events = mutableListOf<String>()

        dispatchSearchThreadNavigation(
            result = SearchItem.ThreadResult(thread(7), postId = 99uL),
            rememberThreadSummary = { events += "remember:${it.id}" },
            navigate = { events += "navigate:$it" },
        )

        assertEquals(
            listOf("navigate:thread/7?postId=99&initialDestination="),
            events,
        )
    }

    private fun thread(id: Long, firstPostId: ULong? = null) = ThreadSummary(
        id = id,
        title = "帖子$id",
        author = UserSummary(id, "user-$id", "用户$id", ""),
        replyCount = 0,
        viewCount = 0,
        firstPostId = firstPostId,
        blocks = emptyList(),
    )

    private fun account(bduss: String, stoken: String) = Account(
        uid = "42",
        name = "tester",
        displayName = "Tester",
        portrait = "portrait",
        bduss = bduss,
        stoken = stoken,
        tbs = "tbs",
    )
}
