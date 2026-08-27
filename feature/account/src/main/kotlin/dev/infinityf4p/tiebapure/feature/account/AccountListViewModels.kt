package dev.infinityf4p.tiebapure.feature.account

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.infinityf4p.tiebapure.core.model.Account
import dev.infinityf4p.tiebapure.core.model.AccountThreadFavorite
import dev.infinityf4p.tiebapure.core.model.BrowsingHistoryEntry
import dev.infinityf4p.tiebapure.core.model.MessageKind
import dev.infinityf4p.tiebapure.core.model.TiebaMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Calendar

data class MessagesUiState(
    val isLoggedIn: Boolean,
    val kind: MessageKind = MessageKind.Reply,
    val messages: List<TiebaMessage> = emptyList(),
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

private data class MessagesRequestKey(
    val kind: MessageKind,
    val page: Int,
)

class MessagesViewModel internal constructor(
    account: Account?,
    private val repository: MessagesRepository,
    coroutineScope: CoroutineScope?,
) : ViewModel() {
    constructor(account: Account?, repository: MessagesRepository) : this(
        account,
        repository,
        coroutineScope = null,
    )

    private val _uiState = MutableStateFlow(MessagesUiState(isLoggedIn = account != null))
    val uiState: StateFlow<MessagesUiState> = _uiState.asStateFlow()
    private val modelScope = coroutineScope ?: viewModelScope
    private var requestGeneration = 0
    private var requestJob: Job? = null

    init {
        if (account != null) refresh(initial = true)
    }

    fun selectKind(kind: MessageKind) {
        if (kind == MessageKind.Agree || kind == _uiState.value.kind) return
        invalidateActiveRequest()
        _uiState.update {
            it.copy(
                kind = kind,
                messages = emptyList(),
                isInitialLoading = false,
                isRefreshing = false,
                isLoadingMore = false,
                hasMore = true,
                nextPage = 1,
                errorMessage = null,
            )
        }
        refresh(initial = true)
    }

    fun refresh(initial: Boolean = false) {
        val snapshot = _uiState.value
        if (!snapshot.isLoggedIn) return
        invalidateActiveRequest()
        val generation = ++requestGeneration
        val key = MessagesRequestKey(snapshot.kind, page = 1)
        _uiState.update {
            it.copy(
                isInitialLoading = initial && it.messages.isEmpty(),
                isRefreshing = !initial || it.messages.isNotEmpty(),
                isLoadingMore = false,
                errorMessage = null,
            )
        }
        requestJob = modelScope.launch { load(key, replace = true, generation = generation) }
    }

    fun loadMore() {
        val state = _uiState.value
        if (!state.isLoggedIn || state.isBusy || !state.hasMore) return
        val generation = ++requestGeneration
        val key = MessagesRequestKey(state.kind, state.nextPage)
        _uiState.update { it.copy(isLoadingMore = true, errorMessage = null) }
        requestJob = modelScope.launch { load(key, replace = false, generation = generation) }
    }

    private suspend fun load(key: MessagesRequestKey, replace: Boolean, generation: Int) {
        runCatching { repository.loadMessages(key.kind, key.page) }
            .onSuccess { result ->
                if (generation != requestGeneration || key != currentRequestKey(key.page)) return@onSuccess
                _uiState.update { current ->
                    current.copy(
                        messages = if (replace) result.messages.distinctBy(TiebaMessage::id)
                        else mergeMessages(current.messages, result.messages),
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
                if (generation != requestGeneration || key != currentRequestKey(key.page)) return@onFailure
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

    private fun invalidateActiveRequest() {
        requestGeneration += 1
        requestJob?.cancel()
        requestJob = null
    }

    private fun currentRequestKey(page: Int): MessagesRequestKey =
        MessagesRequestKey(_uiState.value.kind, page)
}

enum class FavoriteProgressFilter(val title: String) {
    All("全部"),
    HasProgress("有进度"),
    NoProgress("无进度"),
}

data class FavoriteItem(
    val favorite: AccountThreadFavorite,
    val hasReadingPosition: Boolean,
)

data class ThreadFavoritesUiState(
    val isLoggedIn: Boolean,
    val favorites: List<AccountThreadFavorite> = emptyList(),
    val threadsWithReadingPosition: Set<Long> = emptySet(),
    val searchText: String = "",
    val progressFilter: FavoriteProgressFilter = FavoriteProgressFilter.All,
    val selectedThreadIds: Set<Long> = emptySet(),
    val isSelecting: Boolean = false,
    val isInitialLoading: Boolean = false,
    val isRefreshing: Boolean = false,
    val isLoadingMore: Boolean = false,
    val isRemoving: Boolean = false,
    val hasMore: Boolean = true,
    val nextPage: Int = 1,
    val errorMessage: String? = null,
    val unknownRemovalThreadIds: Set<Long> = emptySet(),
) {
    val visibleFavorites: List<FavoriteItem>
        get() = filterFavorites(favorites, threadsWithReadingPosition, searchText, progressFilter)
    val isBusy: Boolean
        get() = isInitialLoading || isRefreshing || isLoadingMore || isRemoving
}

class ThreadFavoritesViewModel internal constructor(
    account: Account?,
    private val repository: ThreadFavoritesRepository,
    coroutineScope: CoroutineScope?,
) : ViewModel() {
    constructor(account: Account?, repository: ThreadFavoritesRepository) : this(
        account,
        repository,
        coroutineScope = null,
    )

    private val _uiState = MutableStateFlow(ThreadFavoritesUiState(isLoggedIn = account != null))
    val uiState: StateFlow<ThreadFavoritesUiState> = _uiState.asStateFlow()
    private val modelScope = coroutineScope ?: viewModelScope
    private val externallyUncollectedThreadIds = mutableSetOf<Long>()
    private var requestGeneration = 0

    init {
        if (account != null) refresh(initial = true)
    }

    fun setSearchText(value: String) {
        _uiState.update { it.copy(searchText = value).retainingVisibleSelection() }
    }

    fun setProgressFilter(value: FavoriteProgressFilter) {
        _uiState.update { it.copy(progressFilter = value).retainingVisibleSelection() }
    }

    fun setSelecting(selecting: Boolean) {
        _uiState.update { it.copy(isSelecting = selecting, selectedThreadIds = if (selecting) it.selectedThreadIds else emptySet()) }
    }

    fun toggleSelection(threadId: Long) {
        _uiState.update { state ->
            val selected = state.selectedThreadIds.toMutableSet().apply {
                if (!add(threadId)) remove(threadId)
            }
            state.copy(isSelecting = true, selectedThreadIds = selected)
        }
    }

    fun onCollectionChanged(threadId: Long, collected: Boolean) {
        if (threadId <= 0 || !_uiState.value.isLoggedIn) return
        if (collected) {
            externallyUncollectedThreadIds -= threadId
        } else {
            externallyUncollectedThreadIds += threadId
            _uiState.update { state ->
                val remainingSelection = state.selectedThreadIds - threadId
                state.copy(
                    favorites = state.favorites.filterNot { it.threadId == threadId },
                    threadsWithReadingPosition = state.threadsWithReadingPosition - threadId,
                    selectedThreadIds = remainingSelection,
                    isSelecting = remainingSelection.isNotEmpty(),
                    unknownRemovalThreadIds = state.unknownRemovalThreadIds - threadId,
                )
            }
        }
        refresh()
    }

    fun refresh(initial: Boolean = false) {
        val state = _uiState.value
        if (!state.isLoggedIn || state.isBusy) return
        val generation = ++requestGeneration
        modelScope.launch {
            _uiState.update {
                it.copy(
                    isInitialLoading = initial && it.favorites.isEmpty(),
                    isRefreshing = !initial || it.favorites.isNotEmpty(),
                    errorMessage = null,
                )
            }
            runCatching { repository.loadFavorites(1) to repository.threadsWithReadingPosition() }
                .onSuccess { (page, positions) ->
                    if (generation != requestGeneration) return@onSuccess
                    _uiState.update {
                        it.copy(
                            favorites = page.favorites
                                .filterNot { favorite -> favorite.threadId in externallyUncollectedThreadIds }
                                .distinctBy(AccountThreadFavorite::threadId),
                            threadsWithReadingPosition = positions,
                            isInitialLoading = false,
                            isRefreshing = false,
                            hasMore = page.hasMore,
                            nextPage = page.currentPage + 1,
                            unknownRemovalThreadIds = emptySet(),
                        ).retainingVisibleSelection()
                    }
                }
                .onFailure { error ->
                    if (error is CancellationException) throw error
                    if (generation != requestGeneration) return@onFailure
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

    fun loadMore() {
        val state = _uiState.value
        if (!state.isLoggedIn || state.isBusy || !state.hasMore) return
        val generation = requestGeneration
        modelScope.launch {
            _uiState.update { it.copy(isLoadingMore = true, errorMessage = null) }
            runCatching { repository.loadFavorites(state.nextPage) }
                .onSuccess { page ->
                    if (generation != requestGeneration) return@onSuccess
                    _uiState.update {
                        it.copy(
                            favorites = mergeFavorites(
                                it.favorites,
                                page.favorites.filterNot { favorite ->
                                    favorite.threadId in externallyUncollectedThreadIds
                                },
                            ),
                            isLoadingMore = false,
                            hasMore = page.hasMore,
                            nextPage = page.currentPage + 1,
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

    fun removeSelected() {
        val ids = _uiState.value.selectedThreadIds
        if (ids.isEmpty() || _uiState.value.isBusy || ids.any { it in _uiState.value.unknownRemovalThreadIds }) return
        modelScope.launch {
            _uiState.update { it.copy(isRemoving = true, errorMessage = null) }
            runCatching { repository.removeFavorites(ids) }
                .onSuccess { result ->
                    _uiState.update { it.applyingRemovalResult(result) }
                }
                .onFailure { error ->
                    if (error is CancellationException) throw error
                    _uiState.update {
                        it.copy(
                            isRemoving = false,
                            errorMessage = error.accountReadableMessage(),
                        )
                    }
                }
        }
    }
}

internal fun ThreadFavoritesUiState.applyingRemovalResult(
    result: ThreadFavoriteRemovalResult,
): ThreadFavoritesUiState {
    val remainingSelection = selectedThreadIds - result.removedThreadIds
    val unknownIds = result.outcomeUnknownByThreadId.keys
    val message = buildList {
        if (unknownIds.isNotEmpty()) {
            add("${unknownIds.size} 条收藏的操作结果无法确认，请刷新核对，应用不会自动重试。")
        }
        if (result.failedByThreadId.isNotEmpty()) {
            add("${result.failedByThreadId.size} 条收藏移除失败，可重新尝试。")
        }
    }.joinToString("\n").ifBlank { null }
    return copy(
        favorites = favorites.filterNot { it.threadId in result.removedThreadIds },
        threadsWithReadingPosition = threadsWithReadingPosition - result.removedThreadIds,
        selectedThreadIds = remainingSelection,
        isSelecting = remainingSelection.isNotEmpty(),
        isRemoving = false,
        unknownRemovalThreadIds = unknownRemovalThreadIds + unknownIds,
        errorMessage = message,
    )
}

enum class HistoryDateFilter(val title: String) {
    All("全部"),
    Today("今天"),
    LastSevenDays("近 7 天"),
}

data class BrowsingHistoryUiState(
    val entries: List<BrowsingHistoryEntry> = emptyList(),
    val searchText: String = "",
    val dateFilter: HistoryDateFilter = HistoryDateFilter.All,
    val todayStartEpochMilliseconds: Long = startOfTodayEpochMilliseconds(),
    val selectedThreadIds: Set<Long> = emptySet(),
    val isSelecting: Boolean = false,
    val isLoading: Boolean = true,
    val isMutating: Boolean = false,
    val errorMessage: String? = null,
) {
    val visibleEntries: List<BrowsingHistoryEntry>
        get() = filterHistory(entries, searchText, dateFilter, todayStartEpochMilliseconds)
}

class BrowsingHistoryViewModel(
    private val repository: BrowsingHistoryRepository,
) : ViewModel() {
    private val _uiState = MutableStateFlow(BrowsingHistoryUiState())
    val uiState: StateFlow<BrowsingHistoryUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            repository.entries
                .catch { error ->
                    if (error is CancellationException) throw error
                    _uiState.update { it.copy(isLoading = false, errorMessage = error.accountReadableMessage()) }
                }
                .collect { entries ->
                    _uiState.update {
                        it.copy(entries = entries, isLoading = false, errorMessage = null).retainingVisibleHistorySelection()
                    }
                }
        }
    }

    fun setSearchText(value: String) {
        _uiState.update { it.copy(searchText = value).retainingVisibleHistorySelection() }
    }

    fun setDateFilter(value: HistoryDateFilter) {
        _uiState.update { it.copy(dateFilter = value).retainingVisibleHistorySelection() }
    }

    fun setSelecting(selecting: Boolean) {
        _uiState.update { it.copy(isSelecting = selecting, selectedThreadIds = if (selecting) it.selectedThreadIds else emptySet()) }
    }

    fun toggleSelection(threadId: Long) {
        _uiState.update { state ->
            val selected = state.selectedThreadIds.toMutableSet().apply {
                if (!add(threadId)) remove(threadId)
            }
            state.copy(isSelecting = true, selectedThreadIds = selected)
        }
    }

    fun removeSelected() {
        val ids = _uiState.value.selectedThreadIds
        if (ids.isEmpty() || _uiState.value.isMutating) return
        mutate { repository.removeEntries(ids) }
    }

    fun clear() {
        if (_uiState.value.isMutating || _uiState.value.entries.isEmpty()) return
        mutate(repository::clear)
    }

    private fun mutate(action: suspend () -> Unit) {
        viewModelScope.launch {
            _uiState.update { it.copy(isMutating = true, errorMessage = null) }
            runCatching { action() }
                .onSuccess {
                    _uiState.update { it.copy(isMutating = false, selectedThreadIds = emptySet(), isSelecting = false) }
                }
                .onFailure { error ->
                    if (error is CancellationException) throw error
                    _uiState.update { it.copy(isMutating = false, errorMessage = error.accountReadableMessage()) }
                }
        }
    }
}

internal fun filterFavorites(
    favorites: List<AccountThreadFavorite>,
    threadsWithReadingPosition: Set<Long>,
    searchText: String,
    progressFilter: FavoriteProgressFilter,
): List<FavoriteItem> {
    val query = searchText.trim().lowercase()
    return favorites.mapNotNull { favorite ->
        val hasProgress = favorite.threadId in threadsWithReadingPosition
        val matchesProgress = when (progressFilter) {
            FavoriteProgressFilter.All -> true
            FavoriteProgressFilter.HasProgress -> hasProgress
            FavoriteProgressFilter.NoProgress -> !hasProgress
        }
        val matchesSearch = query.isEmpty() || listOf(
            favorite.title,
            favorite.authorDisplayName,
            favorite.forumName,
            favorite.threadId.toString(),
        ).any { query in it.lowercase() }
        if (matchesProgress && matchesSearch) FavoriteItem(favorite, hasProgress) else null
    }
}

internal fun filterHistory(
    entries: List<BrowsingHistoryEntry>,
    searchText: String,
    dateFilter: HistoryDateFilter,
    todayStartEpochMilliseconds: Long,
    tomorrowStartEpochMilliseconds: Long = shiftedLocalDayStart(todayStartEpochMilliseconds, 1),
    sevenDayStartEpochMilliseconds: Long = shiftedLocalDayStart(todayStartEpochMilliseconds, -6),
): List<BrowsingHistoryEntry> {
    val query = searchText.trim().lowercase()
    return entries.filter { entry ->
        val thread = entry.thread
        val matchesSearch = query.isEmpty() || listOfNotNull(
            thread.title,
            thread.author.resolvedDisplayName,
            thread.forumName,
            thread.id.toString(),
        ).any { query in it.lowercase() }
        val matchesDate = when (dateFilter) {
            HistoryDateFilter.All -> true
            HistoryDateFilter.Today -> entry.visitedAtEpochMilliseconds in todayStartEpochMilliseconds until tomorrowStartEpochMilliseconds
            HistoryDateFilter.LastSevenDays -> entry.visitedAtEpochMilliseconds in sevenDayStartEpochMilliseconds until tomorrowStartEpochMilliseconds
        }
        matchesSearch && matchesDate
    }
}

internal fun mergeMessages(existing: List<TiebaMessage>, incoming: List<TiebaMessage>): List<TiebaMessage> =
    (existing + incoming).distinctBy(TiebaMessage::id)

internal fun mergeFavorites(
    existing: List<AccountThreadFavorite>,
    incoming: List<AccountThreadFavorite>,
): List<AccountThreadFavorite> = (existing + incoming).distinctBy(AccountThreadFavorite::threadId)

private fun ThreadFavoritesUiState.retainingVisibleSelection(): ThreadFavoritesUiState {
    val visible = visibleFavorites.mapTo(mutableSetOf()) { it.favorite.threadId }
    return copy(selectedThreadIds = selectedThreadIds intersect visible)
}

private fun BrowsingHistoryUiState.retainingVisibleHistorySelection(): BrowsingHistoryUiState {
    val visible = visibleEntries.mapTo(mutableSetOf()) { it.thread.id }
    return copy(selectedThreadIds = selectedThreadIds intersect visible)
}

private fun startOfTodayEpochMilliseconds(): Long = Calendar.getInstance().run {
    set(Calendar.HOUR_OF_DAY, 0)
    set(Calendar.MINUTE, 0)
    set(Calendar.SECOND, 0)
    set(Calendar.MILLISECOND, 0)
    timeInMillis
}

private fun shiftedLocalDayStart(startEpochMilliseconds: Long, dayDelta: Int): Long =
    Calendar.getInstance().run {
        timeInMillis = startEpochMilliseconds
        add(Calendar.DAY_OF_YEAR, dayDelta)
        timeInMillis
    }
