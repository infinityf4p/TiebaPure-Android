package dev.infinityf4p.tiebapure.feature.account

import dev.infinityf4p.tiebapure.core.model.OwnThreadDeletionTarget
import dev.infinityf4p.tiebapure.core.model.ThreadSummary
import dev.infinityf4p.tiebapure.core.model.UserContentVisibility
import dev.infinityf4p.tiebapure.core.model.UserProfile
import dev.infinityf4p.tiebapure.core.model.UserProfileSex
import dev.infinityf4p.tiebapure.core.model.UserRelationshipKind
import dev.infinityf4p.tiebapure.core.model.UserRelationshipPage
import dev.infinityf4p.tiebapure.core.model.UserSummary
import dev.infinityf4p.tiebapure.core.model.UserThreadsPage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UserProfileViewModelTest {
    @Test
    fun constructionStartsInitialRequest() = withModelScope { scope ->
        assertFalse(UserProfileUiState().isInitialLoading)
        val repository = ControllableUserProfileRepository()

        val viewModel = UserProfileViewModel(targetUser, repository, scope)

        assertEquals(1, repository.profileCalls.size)
        assertTrue(viewModel.uiState.value.isInitialLoading)
        assertTrue(viewModel.uiState.value.isBusy)
        assertFalse(viewModel.uiState.value.isRefreshing)
        assertNull(viewModel.uiState.value.errorMessage)
    }

    @Test
    fun successfulInitialRequestPopulatesStateAndLaterRefreshUsesRefreshState() = withModelScope { scope ->
        val repository = ControllableUserProfileRepository()
        val viewModel = UserProfileViewModel(targetUser, repository, scope)
        val profile = profile()

        repository.profileCalls.single().result.complete(profile)

        assertEquals(profile, viewModel.uiState.value.profile)
        assertTrue(viewModel.uiState.value.isInitialLoading)
        assertEquals(1, repository.threadCalls.size)

        repository.threadCalls.single().result.complete(threadsPage())

        assertFalse(viewModel.uiState.value.isBusy)
        assertFalse(viewModel.uiState.value.hasMoreThreads)
        assertEquals(2, viewModel.uiState.value.nextThreadPage)

        viewModel.refresh()

        assertEquals(2, repository.profileCalls.size)
        assertFalse(viewModel.uiState.value.isInitialLoading)
        assertTrue(viewModel.uiState.value.isRefreshing)
    }

    @Test
    fun failedInitialRequestClearsLoadingAndExposesError() = withModelScope { scope ->
        val repository = ControllableUserProfileRepository()
        val viewModel = UserProfileViewModel(targetUser, repository, scope)

        repository.profileCalls.single().result.completeExceptionally(IllegalStateException("profile failed"))

        assertFalse(viewModel.uiState.value.isBusy)
        assertNull(viewModel.uiState.value.profile)
        assertEquals("profile failed", viewModel.uiState.value.errorMessage)
        assertTrue(repository.threadCalls.isEmpty())
    }

    @Test
    fun retryAfterInitialFailureReturnsToInitialLoadingAndCanSucceed() = withModelScope { scope ->
        val repository = ControllableUserProfileRepository()
        val viewModel = UserProfileViewModel(targetUser, repository, scope)
        repository.profileCalls.single().result.completeExceptionally(IllegalStateException("profile failed"))

        viewModel.refresh()

        assertEquals(2, repository.profileCalls.size)
        assertTrue(viewModel.uiState.value.isInitialLoading)
        assertFalse(viewModel.uiState.value.isRefreshing)
        assertNull(viewModel.uiState.value.errorMessage)

        repository.profileCalls.last().result.complete(profile())
        repository.threadCalls.single().result.complete(threadsPage())

        assertFalse(viewModel.uiState.value.isBusy)
        assertNull(viewModel.uiState.value.errorMessage)
    }

    @Test
    fun refreshWhileRequestIsActiveDoesNotStartDuplicateRequests() = withModelScope { scope ->
        val repository = ControllableUserProfileRepository()
        val viewModel = UserProfileViewModel(targetUser, repository, scope)

        viewModel.refresh()
        viewModel.refresh()

        assertEquals(1, repository.profileCalls.size)

        repository.profileCalls.single().result.complete(profile())
        assertEquals(1, repository.threadCalls.size)

        viewModel.refresh()

        assertEquals(1, repository.profileCalls.size)
        repository.threadCalls.single().result.complete(threadsPage())
        assertFalse(viewModel.uiState.value.isBusy)
    }

    @Test
    fun followUsesResolvedProfileUserWhenNavigationUserHasNoPortrait() = withModelScope { scope ->
        val navigationUser = targetUser.copy(portrait = "")
        val resolvedUser = navigationUser.copy(portrait = "resolved-portrait")
        val repository = ControllableUserProfileRepository()
        val viewModel = UserProfileViewModel(navigationUser, repository, scope)

        repository.profileCalls.single().result.complete(profile().copy(user = resolvedUser))
        repository.threadCalls.single().result.complete(threadsPage())
        viewModel.toggleFollow()

        val call = repository.followCalls.single()
        assertEquals(resolvedUser, call.user)
        assertTrue(call.followed)

        call.result.complete(true)

        assertTrue(viewModel.uiState.value.profile?.isFollowed == true)
        assertFalse(viewModel.uiState.value.isMutatingFollow)
    }

    @Test
    fun otherUserProfileDiscardsUnexpectedDeletionTargets() = withModelScope { scope ->
        val repository = ControllableUserProfileRepository()
        val viewModel = UserProfileViewModel(targetUser, repository, scope)

        repository.profileCalls.single().result.complete(profile())
        repository.threadCalls.single().result.complete(threadsPageWithDeletionTarget())

        assertTrue(viewModel.uiState.value.deletionTargets.isEmpty())
        viewModel.deleteThread(profileThread.id)
        assertTrue(repository.deletionCalls.isEmpty())
    }

    @Test
    fun currentUserProfileKeepsValidDeletionTargets() = withModelScope { scope ->
        val repository = ControllableUserProfileRepository()
        val viewModel = UserProfileViewModel(targetUser, repository, scope)

        repository.profileCalls.single().result.complete(profile().copy(isCurrentUser = true))
        repository.threadCalls.single().result.complete(threadsPageWithDeletionTarget())

        assertEquals(deletionTarget, viewModel.uiState.value.deletionTargets[profileThread.id])
    }
}

class UserRelationshipsViewModelTest {
    @Test
    fun constructionStartsInitialRequest() = withModelScope { scope ->
        assertFalse(UserRelationshipsUiState().isInitialLoading)
        val repository = ControllableUserRelationshipRepository()

        val viewModel = UserRelationshipsViewModel(
            targetUser,
            UserRelationshipKind.Followers,
            repository,
            scope,
        )

        assertEquals(1, repository.calls.size)
        assertTrue(viewModel.uiState.value.isInitialLoading)
        assertTrue(viewModel.uiState.value.isBusy)
        assertFalse(viewModel.uiState.value.isRefreshing)
        assertNull(viewModel.uiState.value.errorMessage)
    }

    @Test
    fun successfulInitialRequestPopulatesStateAndLaterRefreshUsesRefreshState() = withModelScope { scope ->
        val repository = ControllableUserRelationshipRepository()
        val viewModel = UserRelationshipsViewModel(
            targetUser,
            UserRelationshipKind.Followers,
            repository,
            scope,
        )

        repository.calls.single().result.complete(relationshipPage())

        assertEquals(listOf(relatedUser), viewModel.uiState.value.users)
        assertEquals(1, viewModel.uiState.value.totalCount)
        assertFalse(viewModel.uiState.value.isBusy)
        assertFalse(viewModel.uiState.value.hasMore)

        viewModel.refresh()

        assertEquals(2, repository.calls.size)
        assertFalse(viewModel.uiState.value.isInitialLoading)
        assertTrue(viewModel.uiState.value.isRefreshing)
    }

    @Test
    fun failedInitialRequestClearsLoadingAndExposesError() = withModelScope { scope ->
        val repository = ControllableUserRelationshipRepository()
        val viewModel = UserRelationshipsViewModel(
            targetUser,
            UserRelationshipKind.Following,
            repository,
            scope,
        )

        repository.calls.single().result.completeExceptionally(IllegalStateException("relationships failed"))

        assertFalse(viewModel.uiState.value.isBusy)
        assertTrue(viewModel.uiState.value.users.isEmpty())
        assertEquals("relationships failed", viewModel.uiState.value.errorMessage)
    }

    @Test
    fun retryAfterInitialFailureReturnsToInitialLoadingAndCanSucceed() = withModelScope { scope ->
        val repository = ControllableUserRelationshipRepository()
        val viewModel = UserRelationshipsViewModel(
            targetUser,
            UserRelationshipKind.Following,
            repository,
            scope,
        )
        repository.calls.single().result.completeExceptionally(IllegalStateException("relationships failed"))

        viewModel.refresh()

        assertEquals(2, repository.calls.size)
        assertTrue(viewModel.uiState.value.isInitialLoading)
        assertFalse(viewModel.uiState.value.isRefreshing)
        assertNull(viewModel.uiState.value.errorMessage)

        repository.calls.last().result.complete(relationshipPage())

        assertFalse(viewModel.uiState.value.isBusy)
        assertEquals(listOf(relatedUser), viewModel.uiState.value.users)
        assertNull(viewModel.uiState.value.errorMessage)
    }

    @Test
    fun refreshWhileRequestIsActiveDoesNotStartDuplicateRequests() = withModelScope { scope ->
        val repository = ControllableUserRelationshipRepository()
        val viewModel = UserRelationshipsViewModel(
            targetUser,
            UserRelationshipKind.Following,
            repository,
            scope,
        )

        viewModel.refresh()
        viewModel.refresh()

        assertEquals(1, repository.calls.size)
        repository.calls.single().result.complete(relationshipPage())
        assertFalse(viewModel.uiState.value.isBusy)
    }
}

private class ControllableUserProfileRepository : UserProfileRepository {
    data class ProfileCall(
        val user: UserSummary,
        val result: CompletableDeferred<UserProfile> = CompletableDeferred(),
    )

    data class ThreadCall(
        val user: UserSummary,
        val page: Int,
        val result: CompletableDeferred<UserThreadsPage> = CompletableDeferred(),
    )

    data class FollowCall(
        val user: UserSummary,
        val followed: Boolean,
        val result: CompletableDeferred<Boolean> = CompletableDeferred(),
    )

    val profileCalls = mutableListOf<ProfileCall>()
    val threadCalls = mutableListOf<ThreadCall>()
    val followCalls = mutableListOf<FollowCall>()
    val deletionCalls = mutableListOf<OwnThreadDeletionTarget>()

    override suspend fun loadProfile(user: UserSummary): UserProfile {
        val call = ProfileCall(user)
        profileCalls += call
        return call.result.await()
    }

    override suspend fun loadThreads(user: UserSummary, page: Int): UserThreadsPage {
        val call = ThreadCall(user, page)
        threadCalls += call
        return call.result.await()
    }

    override suspend fun setFollow(user: UserSummary, followed: Boolean): Boolean {
        val call = FollowCall(user, followed)
        followCalls += call
        return call.result.await()
    }

    override suspend fun deleteOwnThread(target: OwnThreadDeletionTarget) {
        deletionCalls += target
    }
}

private class ControllableUserRelationshipRepository : UserRelationshipRepository {
    data class Call(
        val user: UserSummary,
        val kind: UserRelationshipKind,
        val page: Int,
        val result: CompletableDeferred<UserRelationshipPage> = CompletableDeferred(),
    )

    val calls = mutableListOf<Call>()

    override suspend fun loadUsers(
        user: UserSummary,
        kind: UserRelationshipKind,
        page: Int,
    ): UserRelationshipPage {
        val call = Call(user, kind, page)
        calls += call
        return call.result.await()
    }
}

private inline fun withModelScope(block: (CoroutineScope) -> Unit) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    try {
        block(scope)
    } finally {
        scope.cancel()
    }
}

private val targetUser = UserSummary(
    id = 1,
    name = "target",
    displayName = "Target user",
    portrait = "target",
)

private val relatedUser = UserSummary(
    id = 2,
    name = "related",
    displayName = "Related user",
    portrait = "related",
)

private fun profile() = UserProfile(
    user = targetUser,
    isCurrentUser = false,
    isFollowed = false,
    tiebaId = "target",
    tiebaAge = "1 year",
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

private fun threadsPage() = UserThreadsPage(
    threads = emptyList(),
    currentPage = 1,
    hasMore = false,
    visibility = UserContentVisibility.Visible,
)

private val profileThread = ThreadSummary(
    id = 22,
    forumId = 11,
    title = "Profile thread",
    author = targetUser,
    forumName = "test",
    replyCount = 0,
    viewCount = 0,
    firstPostId = 33uL,
    blocks = emptyList(),
)

private val deletionTarget = OwnThreadDeletionTarget(
    forumId = 11,
    forumName = "test",
    threadId = 22,
    firstPostId = 33uL,
)

private fun threadsPageWithDeletionTarget() = threadsPage().copy(
    threads = listOf(profileThread),
    deletionTargetsByThreadId = mapOf(profileThread.id to deletionTarget),
)

private fun relationshipPage() = UserRelationshipPage(
    users = listOf(relatedUser),
    currentPage = 1,
    totalCount = 1,
    hasMore = false,
)
