package dev.infinityf4p.tiebapure.feature.account

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.infinityf4p.tiebapure.core.model.Account
import dev.infinityf4p.tiebapure.core.model.ThreadSummary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class FollowingUpdatesFailedOperation { Refresh, LoadMore }

data class FollowingUpdatesUiState(
    val isLoggedIn: Boolean,
    val threads: List<ThreadSummary> = emptyList(),
    val followedUserCount: Int = 0,
    val unavailableUserCount: Int = 0,
    val isInitialLoading: Boolean = false,
    val isRefreshing: Boolean = false,
    val isLoadingMore: Boolean = false,
    val hasMore: Boolean = true,
    val nextPage: Int = 1,
    val errorMessage: String? = null,
    val failedOperation: FollowingUpdatesFailedOperation? = null,
) {
    val isBusy: Boolean
        get() = isInitialLoading || isRefreshing || isLoadingMore
}

class FollowingUpdatesViewModel internal constructor(
    account: Account?,
    private val repository: FollowingUpdatesRepository,
    coroutineScope: CoroutineScope?,
) : ViewModel() {
    constructor(account: Account?, repository: FollowingUpdatesRepository) : this(
        account,
        repository,
        coroutineScope = null,
    )

    private val _uiState = MutableStateFlow(FollowingUpdatesUiState(isLoggedIn = account != null))
    val uiState: StateFlow<FollowingUpdatesUiState> = _uiState.asStateFlow()
    private val modelScope = coroutineScope ?: viewModelScope
    private var requestGeneration = 0
    private var requestJob: Job? = null

    init {
        if (account != null) refresh(initial = true)
    }

    fun refresh(initial: Boolean = false) {
        val snapshot = _uiState.value
        if (!snapshot.isLoggedIn || snapshot.isInitialLoading || snapshot.isRefreshing) return
        requestJob?.cancel()
        val generation = ++requestGeneration
        _uiState.update {
            it.copy(
                isInitialLoading = (initial || it.threads.isEmpty()) && it.threads.isEmpty(),
                isRefreshing = it.threads.isNotEmpty(),
                isLoadingMore = false,
                errorMessage = null,
                failedOperation = null,
            )
        }
        requestJob = modelScope.launch {
            load(page = 1, replace = true, generation = generation)
        }
    }

    fun loadMore() {
        val snapshot = _uiState.value
        if (!snapshot.isLoggedIn || snapshot.isBusy || !snapshot.hasMore) return
        val generation = ++requestGeneration
        _uiState.update {
            it.copy(
                isLoadingMore = true,
                errorMessage = null,
                failedOperation = null,
            )
        }
        requestJob = modelScope.launch {
            load(page = snapshot.nextPage, replace = false, generation = generation)
        }
    }

    fun retry() {
        when (_uiState.value.failedOperation) {
            FollowingUpdatesFailedOperation.LoadMore -> loadMore()
            FollowingUpdatesFailedOperation.Refresh, null -> refresh(initial = _uiState.value.threads.isEmpty())
        }
    }

    private suspend fun load(page: Int, replace: Boolean, generation: Int) {
        try {
            val result = repository.loadPage(page)
            if (generation != requestGeneration) return
            _uiState.update { current ->
                current.copy(
                    threads = if (replace) mergeFollowingUpdateThreads(emptyList(), result.threads)
                    else mergeFollowingUpdateThreads(current.threads, result.threads),
                    followedUserCount = if (replace) result.followedUserCount
                    else maxOf(current.followedUserCount, result.followedUserCount),
                    unavailableUserCount = if (replace) result.unavailableUserCount
                    else current.unavailableUserCount + result.unavailableUserCount,
                    isInitialLoading = false,
                    isRefreshing = false,
                    isLoadingMore = false,
                    hasMore = result.hasMore,
                    nextPage = result.currentPage + 1,
                    errorMessage = null,
                    failedOperation = null,
                )
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            if (generation != requestGeneration) return
            _uiState.update {
                it.copy(
                    isInitialLoading = false,
                    isRefreshing = false,
                    isLoadingMore = false,
                    errorMessage = error.accountReadableMessage(),
                    failedOperation = if (replace) {
                        FollowingUpdatesFailedOperation.Refresh
                    } else {
                        FollowingUpdatesFailedOperation.LoadMore
                    },
                )
            }
        }
    }
}

internal fun mergeFollowingUpdateThreads(
    existing: List<ThreadSummary>,
    incoming: List<ThreadSummary>,
): List<ThreadSummary> = (existing + incoming)
    .distinctBy(ThreadSummary::id)
    .sortedWith(
        compareByDescending<ThreadSummary> { it.createdAtEpochSeconds ?: Long.MIN_VALUE }
            .thenByDescending(ThreadSummary::id),
    )
