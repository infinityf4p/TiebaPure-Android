package dev.infinityf4p.tiebapure.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.infinityf4p.tiebapure.core.model.Forum
import dev.infinityf4p.tiebapure.core.model.ThreadSummary
import dev.infinityf4p.tiebapure.core.model.UserSummary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

data class HomeFeedPage(
    val threads: List<ThreadSummary>,
    val currentPage: Int,
    val hasMore: Boolean,
)

fun interface HomeRepository {
    suspend fun loadFeed(page: Int): HomeFeedPage
}

data class HomeUiState(
    val threads: List<ThreadSummary> = emptyList(),
    val isInitialLoading: Boolean = false,
    val isRefreshing: Boolean = false,
    val isLoadingMore: Boolean = false,
    val refreshCommitVersion: Long = 0L,
    val hasMore: Boolean = true,
    val nextPage: Int = 1,
    val updatingLikeThreadIds: Set<Long> = emptySet(),
    val unknownLikeThreadIds: Set<Long> = emptySet(),
    val errorMessage: String? = null,
) {
    val isBusy: Boolean
        get() = isInitialLoading || isRefreshing || isLoadingMore
    val showsEmptyPageContinuation: Boolean
        get() = threads.isEmpty() && hasMore && !isInitialLoading
}

class HomeViewModel(
    private val repository: HomeRepository,
) : ViewModel() {
    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()
    private var activeRequest: Job? = null
    private var requestGeneration = 0L

    init {
        refresh(isInitial = true)
    }

    fun refresh(isInitial: Boolean = false) {
        val snapshot = _uiState.value
        if (snapshot.isInitialLoading || snapshot.isRefreshing) return
        requestGeneration += 1
        val generation = requestGeneration
        activeRequest?.cancel()
        activeRequest = viewModelScope.launch {
            val refreshStartedAtNanos = System.nanoTime()
            val showsRefreshIndicator = !isInitial || snapshot.threads.isNotEmpty()
            _uiState.update {
                it.copy(
                    isInitialLoading = isInitial && it.threads.isEmpty(),
                    isRefreshing = !isInitial || it.threads.isNotEmpty(),
                    isLoadingMore = false,
                    errorMessage = null,
                )
            }
            val result = runCatching { repository.loadFeed(page = 1) }
            result.exceptionOrNull()?.let { if (it is CancellationException) throw it }
            if (generation != requestGeneration) return@launch
            if (showsRefreshIndicator) {
                val elapsedMillis = (System.nanoTime() - refreshStartedAtNanos) / 1_000_000
                delay(HomeRefreshAnimationPolicy.remainingVisibleMillis(elapsedMillis))
            }
            result
                .onSuccess { page ->
                    if (generation != requestGeneration) return@onSuccess
                    _uiState.update { current ->
                        current.copy(
                            threads = refreshThreads(current.threads, page.threads),
                            isInitialLoading = false,
                            isRefreshing = false,
                            isLoadingMore = false,
                            refreshCommitVersion = if (showsRefreshIndicator) {
                                current.refreshCommitVersion + 1
                            } else {
                                current.refreshCommitVersion
                            },
                            hasMore = page.hasMore,
                            nextPage = page.currentPage + 1,
                            updatingLikeThreadIds = emptySet(),
                            unknownLikeThreadIds = emptySet(),
                            errorMessage = null,
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
                            errorMessage = error.readableMessage(),
                        )
                    }
                }
            if (generation == requestGeneration) activeRequest = null
        }
    }

    fun loadMore() {
        val snapshot = _uiState.value
        if (snapshot.isBusy || !snapshot.hasMore) return
        requestGeneration += 1
        val generation = requestGeneration
        activeRequest = viewModelScope.launch {
            val requestedPage = snapshot.nextPage
            _uiState.update { it.copy(isLoadingMore = true, errorMessage = null) }
            runCatching { repository.loadFeed(requestedPage) }
                .onSuccess { page ->
                    if (generation != requestGeneration) return@onSuccess
                    _uiState.update { current ->
                        current.copy(
                            threads = mergeThreads(current.threads, page.threads),
                            isLoadingMore = false,
                            hasMore = page.hasMore,
                            nextPage = page.currentPage + 1,
                        )
                    }
                }
                .onFailure { error ->
                    if (error is CancellationException) throw error
                    if (generation != requestGeneration) return@onFailure
                    _uiState.update {
                        it.copy(isLoadingMore = false, errorMessage = error.readableMessage())
                    }
                }
            if (generation == requestGeneration) activeRequest = null
        }
    }

    fun beginLikeMutation(threadId: Long): ThreadSummary? {
        val thread = _uiState.value.threads.firstOrNull { it.id == threadId } ?: return null
        if (!_uiState.value.canBeginLikeMutation(threadId)) return null
        _uiState.update {
            it.copy(updatingLikeThreadIds = it.updatingLikeThreadIds + threadId, errorMessage = null)
        }
        return thread
    }

    fun completeLikeMutation(threadId: Long, targetLiked: Boolean) {
        _uiState.update { state ->
            state.copy(
                threads = applyThreadLikeState(state.threads, threadId, targetLiked),
                updatingLikeThreadIds = state.updatingLikeThreadIds - threadId,
                unknownLikeThreadIds = state.unknownLikeThreadIds - threadId,
            )
        }
    }

    fun failLikeMutation(threadId: Long) {
        _uiState.update { it.copy(updatingLikeThreadIds = it.updatingLikeThreadIds - threadId) }
    }

    fun markLikeOutcomeUnknown(threadId: Long, message: String) {
        _uiState.update { it.markingLikeOutcomeUnknown(threadId, message) }
    }

    fun removeForum(forum: Forum) {
        _uiState.update { state ->
            val removedIds = state.threads.asSequence()
                .filter { threadBelongsToForum(it, forum) }
                .mapTo(mutableSetOf(), ThreadSummary::id)
            state.copy(
                threads = state.threads.filterNot { it.id in removedIds },
                updatingLikeThreadIds = state.updatingLikeThreadIds - removedIds,
                unknownLikeThreadIds = state.unknownLikeThreadIds - removedIds,
            )
        }
    }
}

internal fun HomeUiState.canBeginLikeMutation(threadId: Long): Boolean =
    threadId !in updatingLikeThreadIds && threadId !in unknownLikeThreadIds

internal fun HomeUiState.markingLikeOutcomeUnknown(threadId: Long, message: String): HomeUiState = copy(
    errorMessage = message,
    updatingLikeThreadIds = updatingLikeThreadIds - threadId,
    unknownLikeThreadIds = unknownLikeThreadIds + threadId,
)

internal fun threadBelongsToForum(thread: ThreadSummary, forum: Forum): Boolean {
    if (forum.id > 0 && thread.forumId == forum.id) return true
    val expectedName = forum.name.normalizedForumName()
    return expectedName.isNotEmpty() && thread.forumName?.normalizedForumName() == expectedName
}

private fun String.normalizedForumName(): String = trim().removeSuffix("吧").trim().lowercase()

internal fun applyThreadLikeState(
    threads: List<ThreadSummary>,
    threadId: Long,
    targetLiked: Boolean,
): List<ThreadSummary> = threads.map { thread ->
    if (thread.id != threadId || thread.isLiked == targetLiked) thread else thread.copy(
        isLiked = targetLiked,
        likeCount = (thread.likeCount + if (targetLiked) 1 else -1).coerceAtLeast(0),
    )
}

internal fun mergeThreads(
    existing: List<ThreadSummary>,
    incoming: List<ThreadSummary>,
): List<ThreadSummary> {
    val merged = LinkedHashMap<Long, ThreadSummary>(existing.size + incoming.size)
    existing.forEach { merged[it.id] = it }
    incoming.forEach { merged[it.id] = it }
    return merged.values.take(HomeFeedMaximumItemCount)
}

internal fun refreshThreads(
    existing: List<ThreadSummary>,
    incoming: List<ThreadSummary>,
): List<ThreadSummary> {
    val seen = HashSet<Long>(existing.size + incoming.size)
    return (incoming + existing)
        .asSequence()
        .filter { seen.add(it.id) }
        .take(HomeFeedMaximumItemCount)
        .toList()
}

private const val HomeFeedMaximumItemCount = 300

internal object HomeRefreshAnimationPolicy {
    const val minimumVisibleMillis = 600L

    fun remainingVisibleMillis(elapsedMillis: Long): Long =
        (minimumVisibleMillis - elapsedMillis.coerceAtLeast(0L)).coerceAtLeast(0L)
}

internal fun Throwable.readableMessage(): String =
    message?.takeIf(String::isNotBlank) ?: "加载失败，请稍后重试。"

data class HomeCallbacks(
    val canLike: Boolean = false,
    val onOpenSearch: () -> Unit = {},
    val onOpenThread: (ThreadSummary) -> Unit = {},
    val onOpenComments: (ThreadSummary) -> Unit = {},
    val onOpenForum: (Forum) -> Unit = {},
    val onOpenUser: (UserSummary) -> Unit = {},
    val onBlockForum: (Forum) -> Unit = {},
    val onToggleLike: ((ThreadSummary) -> Unit)? = null,
    val onOpenMedia: (ThreadSummary, Int) -> Unit = { _, _ -> },
)
