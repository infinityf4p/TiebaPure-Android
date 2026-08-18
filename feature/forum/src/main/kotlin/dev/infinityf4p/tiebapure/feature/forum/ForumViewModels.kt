package dev.infinityf4p.tiebapure.feature.forum

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.infinityf4p.tiebapure.core.model.Forum
import dev.infinityf4p.tiebapure.core.model.ForumMembership
import dev.infinityf4p.tiebapure.core.model.ForumPage
import dev.infinityf4p.tiebapure.core.model.ForumThreadCategory
import dev.infinityf4p.tiebapure.core.model.ThreadSummary
import dev.infinityf4p.tiebapure.core.model.UserSummary
import dev.infinityf4p.tiebapure.core.model.mutationOutcomeUnknownMessageOrNull
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

interface ForumHubRepository {
    suspend fun followedForums(): List<Forum>
    suspend fun recentForums(): List<Forum>
    suspend fun recordRecent(forum: Forum)
    suspend fun removeRecent(forum: Forum)
    suspend fun clearRecent()
}

data class ForumHubUiState(
    val followedForums: List<Forum> = emptyList(),
    val recentForums: List<Forum> = emptyList(),
    val isLoading: Boolean = false,
    val isRefreshing: Boolean = false,
    val errorMessage: String? = null,
)

class ForumHubViewModel(
    private val repository: ForumHubRepository,
) : ViewModel() {
    private val _uiState = MutableStateFlow(ForumHubUiState())
    val uiState: StateFlow<ForumHubUiState> = _uiState.asStateFlow()

    init {
        refresh(initial = true)
    }

    fun refresh(initial: Boolean = false) {
        if (_uiState.value.isLoading || _uiState.value.isRefreshing) return
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isLoading = initial && it.followedForums.isEmpty() && it.recentForums.isEmpty(),
                    isRefreshing = !initial,
                    errorMessage = null,
                )
            }
            runCatching { repository.followedForums() to repository.recentForums() }
                .onSuccess { (followed, recent) ->
                    _uiState.value = ForumHubUiState(
                        followedForums = followed.distinctBy(Forum::name),
                        recentForums = recent.distinctBy(Forum::name),
                    )
                }
                .onFailure { error ->
                    if (error is CancellationException) throw error
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            isRefreshing = false,
                            errorMessage = error.message ?: "加载贴吧失败，请稍后重试。",
                        )
                    }
                }
        }
    }

    fun opened(forum: Forum) {
        viewModelScope.launch { repository.recordRecent(forum) }
    }

    fun removeRecent(forum: Forum) {
        viewModelScope.launch {
            repository.removeRecent(forum)
            _uiState.update { it.copy(recentForums = it.recentForums.filterNot { value -> value.name == forum.name }) }
        }
    }

    fun clearRecent() {
        viewModelScope.launch {
            repository.clearRecent()
            _uiState.update { it.copy(recentForums = emptyList()) }
        }
    }
}

fun interface ForumThreadsRepository {
    suspend fun loadThreads(
        forum: Forum,
        page: Int,
        category: ForumThreadCategory,
    ): ForumPage
}

/**
 * Account-aware operations used by a forum page. Implementations own account/session validation;
 * this feature only serializes local UI requests and ignores stale generations.
 */
interface ForumInteractionPort {
    val followAvailability: ForumFollowAvailability

    suspend fun resolveForumId(forum: Forum): Long
    suspend fun forumMembership(forum: Forum): ForumMembership
    suspend fun setForumFollowed(forum: Forum, followed: Boolean): ForumMembership
}

enum class ForumFollowAvailability {
    Available,
    LoginRequired,
    Unsupported,
}

object UnavailableForumInteractionPort : ForumInteractionPort {
    override val followAvailability = ForumFollowAvailability.Unsupported

    override suspend fun resolveForumId(forum: Forum): Long = forum.id

    override suspend fun forumMembership(forum: Forum): ForumMembership =
        error("当前未提供贴吧关注状态。")

    override suspend fun setForumFollowed(forum: Forum, followed: Boolean): ForumMembership =
        error("当前未提供贴吧关注功能。")
}

data class ForumThreadsUiState(
    val forum: Forum,
    val category: ForumThreadCategory = ForumThreadCategory.ReplyTime,
    val threads: List<ThreadSummary> = emptyList(),
    val showsPinned: Boolean = false,
    val isInitialLoading: Boolean = false,
    val isRefreshing: Boolean = false,
    val isLoadingMore: Boolean = false,
    val hasMore: Boolean = true,
    val nextPage: Int = 1,
    val errorMessage: String? = null,
    val followAvailability: ForumFollowAvailability = ForumFollowAvailability.Unsupported,
    val forumMembership: ForumMembership? = null,
    val isResolvingForumId: Boolean = false,
    val isLoadingMembership: Boolean = false,
    val isUpdatingForumFollow: Boolean = false,
    val isForumFollowOutcomeUnknown: Boolean = false,
    val forumActionError: String? = null,
) {
    val pinnedThreads: List<ThreadSummary>
        get() = threads.filter(ThreadSummary::isTop)
    val visibleThreads: List<ThreadSummary>
        get() = if (showsPinned) threads else threads.filterNot(ThreadSummary::isTop)
    val isBusy: Boolean
        get() = isInitialLoading || isRefreshing || isLoadingMore
    val showsEmptyPageContinuation: Boolean
        get() = threads.isEmpty() && hasMore && !isInitialLoading
    val canRequestForumFollow: Boolean
        get() = followAvailability == ForumFollowAvailability.Available &&
            forum.id > 0 &&
            !isResolvingForumId &&
            !isLoadingMembership &&
            !isUpdatingForumFollow
}

private data class ForumThreadsRequestKey(
    val category: ForumThreadCategory,
    val page: Int,
)

private enum class ForumThreadsLoadOperation {
    Refresh,
    LoadMore,
}

class ForumThreadsViewModel(
    forum: Forum,
    private val repository: ForumThreadsRepository,
    private val interactionPort: ForumInteractionPort = UnavailableForumInteractionPort,
) : ViewModel() {
    private val _uiState = MutableStateFlow(
        ForumThreadsUiState(
            forum = forum,
            followAvailability = interactionPort.followAvailability,
        ),
    )
    val uiState: StateFlow<ForumThreadsUiState> = _uiState.asStateFlow()
    private var requestGeneration = 0
    private var requestJob: Job? = null
    private var interactionGeneration = 0
    private var failedLoadOperation: ForumThreadsLoadOperation? = null

    init {
        refresh(initial = true)
        refreshForumInteraction()
    }

    fun selectCategory(category: ForumThreadCategory) {
        if (_uiState.value.category == category) return
        invalidateActiveRequest()
        _uiState.update {
            it.copy(
                category = category,
                threads = emptyList(),
                isInitialLoading = false,
                isRefreshing = false,
                isLoadingMore = false,
                nextPage = 1,
                hasMore = true,
                errorMessage = null,
            )
        }
        refresh(initial = true)
    }

    fun togglePinned() {
        _uiState.update { it.copy(showsPinned = !it.showsPinned) }
    }

    fun refresh(initial: Boolean = false) {
        val snapshot = _uiState.value
        invalidateActiveRequest()
        val generation = ++requestGeneration
        val key = ForumThreadsRequestKey(snapshot.category, page = 1)
        _uiState.update {
            it.copy(
                isInitialLoading = initial && it.threads.isEmpty(),
                isRefreshing = !initial || it.threads.isNotEmpty(),
                isLoadingMore = false,
                errorMessage = null,
            )
        }
        requestJob = viewModelScope.launch {
            fetch(key = key, snapshot = snapshot, generation = generation, replace = true)
        }
    }

    fun loadMore() {
        val snapshot = _uiState.value
        if (snapshot.isBusy || !snapshot.hasMore) return
        failedLoadOperation = null
        val generation = ++requestGeneration
        val key = ForumThreadsRequestKey(snapshot.category, snapshot.nextPage)
        _uiState.update { it.copy(isLoadingMore = true, errorMessage = null) }
        requestJob = viewModelScope.launch {
            fetch(key = key, snapshot = snapshot, generation = generation, replace = false)
        }
    }

    fun retry() {
        when (failedLoadOperation) {
            ForumThreadsLoadOperation.LoadMore -> loadMore()
            ForumThreadsLoadOperation.Refresh, null -> refresh()
        }
    }

    /** Re-evaluates login/capability state and reloads membership when available. */
    fun refreshForumInteraction() {
        val current = _uiState.value
        if (current.isResolvingForumId || current.isLoadingMembership || current.isUpdatingForumFollow) return
        val generation = ++interactionGeneration
        val availability = interactionPort.followAvailability
        _uiState.update {
            it.copy(
                followAvailability = availability,
                forumMembership = if (availability == ForumFollowAvailability.Available) it.forumMembership else null,
                isForumFollowOutcomeUnknown = false,
                isResolvingForumId = it.forum.id <= 0,
                isLoadingMembership = availability == ForumFollowAvailability.Available && it.forum.id > 0,
                forumActionError = null,
            )
        }
        viewModelScope.launch {
            resolveForumAndLoadMembership(generation, availability)
        }
    }

    fun toggleForumFollow() {
        val snapshot = _uiState.value
        if (!snapshot.canRequestForumFollow || snapshot.isForumFollowOutcomeUnknown) return
        val previous = snapshot.forumMembership
        if (previous == null) {
            refreshForumInteraction()
            return
        }
        val target = !previous.isFollowed
        val requestedForum = snapshot.forum
        val generation = ++interactionGeneration
        _uiState.update {
            it.copy(
                forumMembership = ForumMembership(requestedForum.id, target),
                isUpdatingForumFollow = true,
                forumActionError = null,
            )
        }
        viewModelScope.launch {
            runCatching { interactionPort.setForumFollowed(requestedForum, target) }
                .onSuccess { membership ->
                    if (generation != interactionGeneration) return@onSuccess
                    _uiState.update {
                        it.copy(
                            forum = it.forum.withResolvedId(membership.forumId),
                            forumMembership = membership.withFallbackForumId(requestedForum.id),
                            isUpdatingForumFollow = false,
                        )
                    }
                }
                .onFailure { error ->
                    if (error is CancellationException) throw error
                    if (generation != interactionGeneration) return@onFailure
                    val unknown = error.mutationOutcomeUnknownMessageOrNull()
                    _uiState.update {
                        if (unknown != null) {
                            it.copy(
                                isUpdatingForumFollow = false,
                                isForumFollowOutcomeUnknown = true,
                                forumActionError = unknown,
                            )
                        } else {
                            it.copy(
                                forumMembership = previous,
                                isUpdatingForumFollow = false,
                                forumActionError = error.message ?: "更新贴吧关注状态失败，请稍后重试。",
                            )
                        }
                    }
                }
        }
    }

    fun dismissForumActionError() {
        _uiState.update { it.copy(forumActionError = null) }
    }

    private suspend fun fetch(
        key: ForumThreadsRequestKey,
        snapshot: ForumThreadsUiState,
        generation: Int,
        replace: Boolean,
    ) {
        runCatching { repository.loadThreads(snapshot.forum, key.page, key.category) }
            .onSuccess { result ->
                if (generation != requestGeneration || key != currentRequestKey(key.page)) return@onSuccess
                failedLoadOperation = null
                _uiState.update { current ->
                    applyForumPage(current, result, replace)
                }
                val interaction = _uiState.value
                if (interaction.forum.id > 0 &&
                    interaction.followAvailability == ForumFollowAvailability.Available &&
                    interaction.forumMembership == null &&
                    !interaction.isResolvingForumId &&
                    !interaction.isLoadingMembership
                ) {
                    refreshForumInteraction()
                }
            }
            .onFailure { error ->
                if (error is CancellationException) throw error
                if (generation != requestGeneration || key != currentRequestKey(key.page)) return@onFailure
                failedLoadOperation = if (replace) {
                    ForumThreadsLoadOperation.Refresh
                } else {
                    ForumThreadsLoadOperation.LoadMore
                }
                _uiState.update {
                    it.copy(
                        isInitialLoading = false,
                        isRefreshing = false,
                        isLoadingMore = false,
                        errorMessage = error.message ?: "加载帖子失败，请稍后重试。",
                    )
                }
            }
    }

    private fun invalidateActiveRequest() {
        failedLoadOperation = null
        requestGeneration += 1
        requestJob?.cancel()
        requestJob = null
    }

    private fun currentRequestKey(page: Int): ForumThreadsRequestKey =
        ForumThreadsRequestKey(_uiState.value.category, page)

    private suspend fun resolveForumAndLoadMembership(
        generation: Int,
        availability: ForumFollowAvailability,
    ) {
        var resolutionError: Throwable? = null
        val initialForum = _uiState.value.forum
        if (initialForum.id <= 0) {
            runCatching { interactionPort.resolveForumId(initialForum) }
                .onSuccess { forumId ->
                    if (generation == interactionGeneration && forumId > 0) {
                        _uiState.update { it.copy(forum = it.forum.withResolvedId(forumId)) }
                    }
                }
                .onFailure { error ->
                    if (error is CancellationException) throw error
                    resolutionError = error
                }
        }
        if (generation != interactionGeneration) return

        val resolvedForum = _uiState.value.forum
        if (resolvedForum.id <= 0 || availability != ForumFollowAvailability.Available) {
            _uiState.update {
                it.copy(
                    isResolvingForumId = false,
                    isLoadingMembership = false,
                    forumActionError = resolutionError?.message
                        ?: if (availability == ForumFollowAvailability.Available) {
                            "未能确认贴吧 ID，无法加载关注状态或发布新主题。"
                        } else {
                            null
                        },
                )
            }
            return
        }

        _uiState.update { it.copy(isResolvingForumId = false, isLoadingMembership = true) }
        runCatching { interactionPort.forumMembership(resolvedForum) }
            .onSuccess { membership ->
                if (generation != interactionGeneration) return@onSuccess
                _uiState.update {
                    it.copy(
                        forum = it.forum.withResolvedId(membership.forumId),
                        forumMembership = membership.withFallbackForumId(resolvedForum.id),
                        isLoadingMembership = false,
                    )
                }
            }
            .onFailure { error ->
                if (error is CancellationException) throw error
                if (generation != interactionGeneration) return@onFailure
                _uiState.update {
                    it.copy(
                        isLoadingMembership = false,
                        forumActionError = error.message ?: "加载贴吧关注状态失败，请稍后重试。",
                    )
                }
            }
    }
}

internal fun applyForumPage(
    current: ForumThreadsUiState,
    result: ForumPage,
    replace: Boolean,
): ForumThreadsUiState {
    val merged = if (replace) {
        result.threads.distinctBy(ThreadSummary::id)
    } else {
        mergeForumThreads(current.threads, result.threads)
    }
    return current.copy(
        forum = result.forum.copy(
            id = current.forum.id.takeIf { it > 0 } ?: result.forum.id,
        ),
        threads = merged,
        isInitialLoading = false,
        isRefreshing = false,
        isLoadingMore = false,
        hasMore = result.hasMore,
        nextPage = result.currentPage + 1,
    )
}

internal fun canCreateThread(
    forum: Forum,
    capabilities: ForumThreadsCapabilities,
): Boolean = capabilities.canCreateThread && forum.id > 0

internal fun canOpenForumThreadAuthor(author: UserSummary): Boolean = author.id > 0

private fun Forum.withResolvedId(resolvedId: Long): Forum =
    if (resolvedId > 0 && id != resolvedId) copy(id = resolvedId) else this

private fun ForumMembership.withFallbackForumId(fallbackForumId: Long): ForumMembership =
    if (forumId > 0) this else copy(forumId = fallbackForumId)

internal fun mergeForumThreads(existing: List<ThreadSummary>, incoming: List<ThreadSummary>): List<ThreadSummary> {
    val values = LinkedHashMap<Long, ThreadSummary>(existing.size + incoming.size)
    existing.forEach { values[it.id] = it }
    incoming.forEach { values[it.id] = it }
    return values.values.toList()
}
