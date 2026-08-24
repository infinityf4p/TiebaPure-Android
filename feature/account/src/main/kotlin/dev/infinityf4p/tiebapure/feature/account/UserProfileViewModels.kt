package dev.infinityf4p.tiebapure.feature.account

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.infinityf4p.tiebapure.core.model.OwnThreadDeletionTarget
import dev.infinityf4p.tiebapure.core.model.ThreadSummary
import dev.infinityf4p.tiebapure.core.model.UserContentVisibility
import dev.infinityf4p.tiebapure.core.model.UserProfile
import dev.infinityf4p.tiebapure.core.model.UserRelationshipKind
import dev.infinityf4p.tiebapure.core.model.UserSummary
import dev.infinityf4p.tiebapure.core.model.mutationOutcomeUnknownMessageOrNull
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class UserProfileTab { Threads, Forums }

data class UserProfileUiState(
    val profile: UserProfile? = null,
    val selectedTab: UserProfileTab = UserProfileTab.Threads,
    val threads: List<ThreadSummary> = emptyList(),
    val threadVisibility: UserContentVisibility = UserContentVisibility.Visible,
    val deletionTargets: Map<Long, OwnThreadDeletionTarget> = emptyMap(),
    val isInitialLoading: Boolean = false,
    val isRefreshing: Boolean = false,
    val isLoadingMore: Boolean = false,
    val isMutatingFollow: Boolean = false,
    val isFollowOutcomeUnknown: Boolean = false,
    val isDeletingThreadId: Long? = null,
    val unknownDeletionThreadIds: Set<Long> = emptySet(),
    val hasMoreThreads: Boolean = true,
    val nextThreadPage: Int = 1,
    val errorMessage: String? = null,
    val actionError: String? = null,
) {
    val isBusy: Boolean
        get() = isInitialLoading || isRefreshing || isLoadingMore
}

class UserProfileViewModel internal constructor(
    private val user: UserSummary,
    private val repository: UserProfileRepository,
    coroutineScope: CoroutineScope?,
) : ViewModel() {
    constructor(user: UserSummary, repository: UserProfileRepository) : this(
        user,
        repository,
        coroutineScope = null,
    )

    private val _uiState = MutableStateFlow(UserProfileUiState())
    val uiState: StateFlow<UserProfileUiState> = _uiState.asStateFlow()
    private val modelScope = coroutineScope ?: viewModelScope
    private var requestGeneration = 0

    init {
        refresh()
    }

    fun selectTab(tab: UserProfileTab) {
        _uiState.update { it.copy(selectedTab = tab) }
    }

    fun refresh() {
        if (_uiState.value.isBusy) return
        val generation = ++requestGeneration
        _uiState.update {
            it.copy(
                isInitialLoading = it.profile == null,
                isRefreshing = it.profile != null,
                errorMessage = null,
            )
        }
        modelScope.launch {
            try {
                val profile = repository.loadProfile(user)
                if (generation != requestGeneration) return@launch
                _uiState.update { it.copy(profile = profile) }
                val page = repository.loadThreads(user, page = 1)
                if (generation != requestGeneration) return@launch
                _uiState.value = _uiState.value.copy(
                    profile = profile,
                    threads = page.threads.distinctBy(ThreadSummary::id),
                    threadVisibility = page.visibility,
                    deletionTargets = page.deletionTargetsByThreadId.takeIf { profile.isCurrentUser }.orEmpty(),
                    isInitialLoading = false,
                    isRefreshing = false,
                    hasMoreThreads = page.hasMore,
                    nextThreadPage = page.currentPage + 1,
                    errorMessage = null,
                    isFollowOutcomeUnknown = false,
                    unknownDeletionThreadIds = emptySet(),
                )
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                if (generation != requestGeneration) return@launch
                _uiState.update {
                    it.copy(
                        isInitialLoading = false,
                        isRefreshing = false,
                        errorMessage = error.accountReadableMessage(),
                    )
                }
            }
        }
    }

    fun loadMoreThreads() {
        val snapshot = _uiState.value
        if (snapshot.isBusy || !snapshot.hasMoreThreads || snapshot.threadVisibility == UserContentVisibility.Private) return
        val generation = requestGeneration
        modelScope.launch {
            _uiState.update { it.copy(isLoadingMore = true, errorMessage = null) }
            runCatching { repository.loadThreads(user, snapshot.nextThreadPage) }
                .onSuccess { page ->
                    if (generation != requestGeneration) return@onSuccess
                    _uiState.update { current ->
                        current.copy(
                            threads = mergeProfileThreads(current.threads, page.threads),
                            deletionTargets = if (current.profile?.isCurrentUser == true) {
                                current.deletionTargets + page.deletionTargetsByThreadId
                            } else {
                                emptyMap()
                            },
                            threadVisibility = page.visibility,
                            isLoadingMore = false,
                            hasMoreThreads = page.hasMore,
                            nextThreadPage = page.currentPage + 1,
                        )
                    }
                }
                .onFailure { error ->
                    if (error is CancellationException) throw error
                    if (generation != requestGeneration) return@onFailure
                    _uiState.update { it.copy(isLoadingMore = false, errorMessage = error.accountReadableMessage()) }
                }
        }
    }

    fun toggleFollow() {
        val snapshot = _uiState.value
        val profile = snapshot.profile ?: return
        if (profile.isCurrentUser || snapshot.isMutatingFollow || snapshot.isFollowOutcomeUnknown) return
        val target = !profile.isFollowed
        val optimistic = profile.withFollow(target)
        _uiState.update { it.copy(profile = optimistic, isMutatingFollow = true, actionError = null) }
        modelScope.launch {
            runCatching { repository.setFollow(profile.user, target) }
                .onSuccess { followed ->
                    _uiState.update { current ->
                        val value = current.profile ?: return@update current.copy(isMutatingFollow = false)
                        current.copy(
                            profile = if (value.isFollowed == followed) value else value.withFollow(followed),
                            isMutatingFollow = false,
                        )
                    }
                }
                .onFailure { error ->
                    if (error is CancellationException) throw error
                    val unknown = error.mutationOutcomeUnknownMessageOrNull()
                    _uiState.update {
                        if (unknown != null) {
                            it.copy(
                                isMutatingFollow = false,
                                isFollowOutcomeUnknown = true,
                                actionError = unknown,
                            )
                        } else {
                            it.copy(
                                profile = profile,
                                isMutatingFollow = false,
                                actionError = error.accountReadableMessage(),
                            )
                        }
                    }
                }
        }
    }

    fun deleteThread(threadId: Long) {
        val state = _uiState.value
        if (state.profile?.isCurrentUser != true) return
        val target = state.deletionTargets[threadId]?.takeIf {
            it.threadId == threadId && it.forumId > 0 && it.firstPostId > 0uL && it.forumName.isNotBlank()
        } ?: return
        if (state.isDeletingThreadId != null || threadId in state.unknownDeletionThreadIds) return
        _uiState.update { it.copy(isDeletingThreadId = threadId, actionError = null) }
        modelScope.launch {
            runCatching { repository.deleteOwnThread(target) }
                .onSuccess {
                    _uiState.update {
                        it.copy(
                            threads = it.threads.filterNot { thread -> thread.id == threadId },
                            deletionTargets = it.deletionTargets - threadId,
                            isDeletingThreadId = null,
                            profile = it.profile?.copy(threadCount = (it.profile.threadCount - 1).coerceAtLeast(0)),
                        )
                    }
                }
                .onFailure { error ->
                    if (error is CancellationException) throw error
                    val unknown = error.mutationOutcomeUnknownMessageOrNull()
                    _uiState.update {
                        it.copy(
                            isDeletingThreadId = null,
                            unknownDeletionThreadIds = if (unknown != null) {
                                it.unknownDeletionThreadIds + threadId
                            } else {
                                it.unknownDeletionThreadIds
                            },
                            actionError = unknown ?: error.accountReadableMessage(),
                        )
                    }
                }
        }
    }

    fun clearActionError() {
        _uiState.update { it.copy(actionError = null) }
    }
}

data class UserRelationshipsUiState(
    val users: List<UserSummary> = emptyList(),
    val totalCount: Int = 0,
    val isInitialLoading: Boolean = false,
    val isRefreshing: Boolean = false,
    val isLoadingMore: Boolean = false,
    val hasMore: Boolean = true,
    val nextPage: Int = 1,
    val errorMessage: String? = null,
) {
    val isBusy: Boolean
        get() = isInitialLoading || isRefreshing || isLoadingMore
}

class UserRelationshipsViewModel internal constructor(
    private val user: UserSummary,
    val kind: UserRelationshipKind,
    private val repository: UserRelationshipRepository,
    coroutineScope: CoroutineScope?,
) : ViewModel() {
    constructor(
        user: UserSummary,
        kind: UserRelationshipKind,
        repository: UserRelationshipRepository,
    ) : this(user, kind, repository, coroutineScope = null)

    private val _uiState = MutableStateFlow(UserRelationshipsUiState())
    val uiState: StateFlow<UserRelationshipsUiState> = _uiState.asStateFlow()
    private val modelScope = coroutineScope ?: viewModelScope
    private var requestGeneration = 0

    init {
        refresh()
    }

    fun refresh() {
        if (_uiState.value.isBusy) return
        val generation = ++requestGeneration
        _uiState.update {
            it.copy(
                isInitialLoading = it.users.isEmpty(),
                isRefreshing = it.users.isNotEmpty(),
                errorMessage = null,
            )
        }
        modelScope.launch {
            load(page = 1, replace = true, generation = generation)
        }
    }

    fun loadMore() {
        val state = _uiState.value
        if (state.isBusy || !state.hasMore) return
        val generation = requestGeneration
        modelScope.launch {
            _uiState.update { it.copy(isLoadingMore = true, errorMessage = null) }
            load(page = state.nextPage, replace = false, generation = generation)
        }
    }

    private suspend fun load(page: Int, replace: Boolean, generation: Int) {
        runCatching { repository.loadUsers(user, kind, page) }
            .onSuccess { result ->
                if (generation != requestGeneration) return@onSuccess
                _uiState.update { current ->
                    current.copy(
                        users = if (replace) result.users.distinctBy(UserSummary::id)
                        else mergeUsers(current.users, result.users),
                        totalCount = result.totalCount,
                        isInitialLoading = false,
                        isRefreshing = false,
                        isLoadingMore = false,
                        hasMore = result.hasMore,
                        nextPage = result.currentPage + 1,
                    )
                }
            }
            .onFailure { error ->
                if (error is CancellationException) throw error
                if (generation != requestGeneration) return@onFailure
                _uiState.update {
                    it.copy(
                        isInitialLoading = false,
                        isRefreshing = false,
                        isLoadingMore = false,
                        errorMessage = error.accountReadableMessage(),
                    )
                }
            }
    }
}

internal fun UserProfile.withFollow(followed: Boolean): UserProfile {
    if (isFollowed == followed) return this
    return copy(
        isFollowed = followed,
        followerCount = (followerCount + if (followed) 1 else -1).coerceAtLeast(0),
    )
}

internal fun mergeProfileThreads(existing: List<ThreadSummary>, incoming: List<ThreadSummary>): List<ThreadSummary> =
    (existing + incoming).distinctBy(ThreadSummary::id)

internal fun mergeUsers(existing: List<UserSummary>, incoming: List<UserSummary>): List<UserSummary> =
    (existing + incoming).distinctBy(UserSummary::id)
