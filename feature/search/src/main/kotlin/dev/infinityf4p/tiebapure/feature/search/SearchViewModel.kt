package dev.infinityf4p.tiebapure.feature.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.infinityf4p.tiebapure.core.model.Forum
import dev.infinityf4p.tiebapure.core.model.ThreadSummary
import dev.infinityf4p.tiebapure.core.model.UserSummary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface SearchScope {
    data object Global : SearchScope
    data class ForumOnly(val forum: Forum) : SearchScope
}

enum class SearchFilter(val protocolValue: Int) {
    All(2),
    Threads(1),
}

enum class SearchSort(val protocolValue: Int) {
    Oldest(0),
    Relevance(2),
    Latest(5),
}

sealed interface SearchItem {
    val stableId: String

    data class ThreadResult(
        val thread: ThreadSummary,
        val postId: ULong? = null,
    ) : SearchItem {
        override val stableId: String = "thread-${thread.id}-${postId ?: 0uL}"
    }

    data class UserResult(val user: UserSummary) : SearchItem {
        override val stableId: String = "user-${user.id}"
    }
}

data class SearchPage(
    val items: List<SearchItem>,
    val currentPage: Int,
    val hasMore: Boolean,
)

interface SearchRepository {
    suspend fun search(
        keyword: String,
        scope: SearchScope,
        filter: SearchFilter,
        sort: SearchSort,
        page: Int,
    ): SearchPage

    suspend fun history(): List<String>
    suspend fun recordHistory(keyword: String)
    suspend fun removeHistory(keyword: String)
    suspend fun clearHistory()
}

data class SearchUiState(
    val scope: SearchScope = SearchScope.Global,
    val input: String = "",
    val submittedKeyword: String = "",
    val history: List<String> = emptyList(),
    val filter: SearchFilter = SearchFilter.All,
    val sort: SearchSort = SearchSort.Latest,
    val items: List<SearchItem> = emptyList(),
    val isInitialLoading: Boolean = false,
    val isRefreshing: Boolean = false,
    val isLoadingMore: Boolean = false,
    val hasMore: Boolean = true,
    val nextPage: Int = 1,
    val errorMessage: String? = null,
) {
    val showsHistory: Boolean
        get() = submittedKeyword.isBlank()
    val isBusy: Boolean
        get() = isInitialLoading || isRefreshing || isLoadingMore
    val showsEmptyPageContinuation: Boolean
        get() = submittedKeyword.isNotBlank() && items.isEmpty() && hasMore && !isInitialLoading
}

private data class SearchRequestKey(
    val keyword: String,
    val filter: SearchFilter,
    val sort: SearchSort,
    val page: Int,
)

private enum class SearchLoadOperation {
    Refresh,
    LoadMore,
}

internal const val SEARCH_FAILURE_MESSAGE = "请检查网络连接后重试。"

class SearchViewModel private constructor(
    private val repository: SearchRepository,
    private val scope: SearchScope = SearchScope.Global,
    initialKeyword: String = "",
    coroutineScope: CoroutineScope?,
) : ViewModel() {
    constructor(
        repository: SearchRepository,
        scope: SearchScope = SearchScope.Global,
        initialKeyword: String = "",
    ) : this(repository, scope, initialKeyword, coroutineScope = null)

    internal constructor(
        repository: SearchRepository,
        coroutineScope: CoroutineScope,
    ) : this(repository, SearchScope.Global, initialKeyword = "", coroutineScope)

    private val _uiState = MutableStateFlow(
        SearchUiState(scope = scope, input = initialKeyword, submittedKeyword = initialKeyword.trim()),
    )
    val uiState: StateFlow<SearchUiState> = _uiState.asStateFlow()
    private val modelScope = coroutineScope ?: viewModelScope
    private var generation = 0
    private var requestJob: Job? = null
    private var failedLoadOperation: SearchLoadOperation? = null

    init {
        modelScope.launch {
            runCatching { repository.history() }
                .onSuccess { values -> _uiState.update { it.copy(history = normalizeHistory(values)) } }
            if (_uiState.value.submittedKeyword.isNotBlank()) refresh(initial = true)
        }
    }

    fun updateInput(value: String) {
        _uiState.update { it.copy(input = value) }
    }

    fun submit() {
        val keyword = _uiState.value.input.trim()
        if (keyword.isEmpty()) return
        invalidateActiveRequest()
        _uiState.update {
            it.copy(
                input = keyword,
                submittedKeyword = keyword,
                items = emptyList(),
                isInitialLoading = false,
                isRefreshing = false,
                isLoadingMore = false,
                nextPage = 1,
                hasMore = true,
                errorMessage = null,
                history = normalizeHistory(listOf(keyword) + it.history),
            )
        }
        modelScope.launch { repository.recordHistory(keyword) }
        refresh(initial = true)
    }

    fun clearQuery() {
        invalidateActiveRequest()
        _uiState.update {
            it.copy(
                input = "",
                submittedKeyword = "",
                items = emptyList(),
                isInitialLoading = false,
                isRefreshing = false,
                isLoadingMore = false,
                nextPage = 1,
                hasMore = true,
                errorMessage = null,
            )
        }
    }

    fun selectHistory(keyword: String) {
        updateInput(keyword)
        submit()
    }

    fun removeHistory(keyword: String) {
        _uiState.update { it.copy(history = it.history.filterNot { value -> value == keyword }) }
        modelScope.launch { repository.removeHistory(keyword) }
    }

    fun clearHistory() {
        _uiState.update { it.copy(history = emptyList()) }
        modelScope.launch { repository.clearHistory() }
    }

    fun selectFilter(value: SearchFilter) {
        if (_uiState.value.filter == value) return
        invalidateActiveRequest()
        _uiState.update {
            it.copy(
                filter = value,
                items = emptyList(),
                isInitialLoading = false,
                isRefreshing = false,
                isLoadingMore = false,
                nextPage = 1,
                hasMore = true,
                errorMessage = null,
            )
        }
        if (_uiState.value.submittedKeyword.isNotBlank()) refresh(initial = true)
    }

    fun selectSort(value: SearchSort) {
        if (_uiState.value.sort == value) return
        invalidateActiveRequest()
        _uiState.update {
            it.copy(
                sort = value,
                items = emptyList(),
                isInitialLoading = false,
                isRefreshing = false,
                isLoadingMore = false,
                nextPage = 1,
                hasMore = true,
                errorMessage = null,
            )
        }
        if (_uiState.value.submittedKeyword.isNotBlank()) refresh(initial = true)
    }

    fun refresh(initial: Boolean = false) {
        val snapshot = _uiState.value
        if (snapshot.submittedKeyword.isBlank()) return
        invalidateActiveRequest()
        val requestGeneration = ++generation
        val key = SearchRequestKey(snapshot.submittedKeyword, snapshot.filter, snapshot.sort, page = 1)
        _uiState.update {
            it.copy(
                isInitialLoading = initial && it.items.isEmpty(),
                isRefreshing = !initial || it.items.isNotEmpty(),
                isLoadingMore = false,
                errorMessage = null,
            )
        }
        requestJob = modelScope.launch { fetch(key, requestGeneration, replace = true) }
    }

    fun loadMore() {
        val snapshot = _uiState.value
        if (snapshot.submittedKeyword.isBlank() || snapshot.isBusy || !snapshot.hasMore) return
        failedLoadOperation = null
        val requestGeneration = ++generation
        val key = SearchRequestKey(snapshot.submittedKeyword, snapshot.filter, snapshot.sort, snapshot.nextPage)
        _uiState.update { it.copy(isLoadingMore = true, errorMessage = null) }
        requestJob = modelScope.launch { fetch(key, requestGeneration, replace = false) }
    }

    fun retry() {
        when (failedLoadOperation) {
            SearchLoadOperation.LoadMore -> loadMore()
            SearchLoadOperation.Refresh, null -> refresh()
        }
    }

    private suspend fun fetch(key: SearchRequestKey, requestGeneration: Int, replace: Boolean) {
        runCatching { repository.search(key.keyword, scope, key.filter, key.sort, key.page) }
            .onSuccess { result ->
                if (requestGeneration != generation || key != currentKey(key.page)) return@onSuccess
                failedLoadOperation = null
                _uiState.update { current ->
                    current.copy(
                        items = if (replace) result.items.distinctBy(SearchItem::stableId)
                        else mergeSearchItems(current.items, result.items),
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
                if (requestGeneration != generation || key != currentKey(key.page)) return@onFailure
                failedLoadOperation = if (replace) {
                    SearchLoadOperation.Refresh
                } else {
                    SearchLoadOperation.LoadMore
                }
                _uiState.update {
                    it.copy(
                        isInitialLoading = false,
                        isRefreshing = false,
                        isLoadingMore = false,
                        errorMessage = SEARCH_FAILURE_MESSAGE,
                    )
                }
            }
    }

    private fun invalidateActiveRequest() {
        failedLoadOperation = null
        generation += 1
        requestJob?.cancel()
        requestJob = null
    }

    private fun currentKey(page: Int): SearchRequestKey = _uiState.value.let {
        SearchRequestKey(it.submittedKeyword, it.filter, it.sort, page)
    }
}

internal fun normalizeHistory(values: List<String>): List<String> =
    values.map(String::trim).filter(String::isNotEmpty).distinct().take(20)

internal fun mergeSearchItems(existing: List<SearchItem>, incoming: List<SearchItem>): List<SearchItem> {
    val values = LinkedHashMap<String, SearchItem>(existing.size + incoming.size)
    existing.forEach { values[it.stableId] = it }
    incoming.forEach { values[it.stableId] = it }
    return values.values.toList()
}

data class SearchCallbacks(
    val onBack: () -> Unit = {},
    val onOpenThread: (SearchItem.ThreadResult) -> Unit = {},
    val onOpenUser: (UserSummary) -> Unit = {},
    val onOpenForum: (Forum) -> Unit = {},
    val onOpenMedia: (ThreadSummary, Int) -> Unit = { _, _ -> },
)
