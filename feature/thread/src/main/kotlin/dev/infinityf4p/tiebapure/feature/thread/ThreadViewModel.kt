package dev.infinityf4p.tiebapure.feature.thread

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dev.infinityf4p.tiebapure.core.model.Post
import dev.infinityf4p.tiebapure.core.model.Subpost
import dev.infinityf4p.tiebapure.core.model.TiebaLikeObjectType
import dev.infinityf4p.tiebapure.core.model.ThreadPage
import dev.infinityf4p.tiebapure.core.model.ThreadReplySort
import dev.infinityf4p.tiebapure.core.model.mutationOutcomeUnknownMessageOrNull
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ThreadUiState(
    val page: ThreadPage? = null,
    val posts: List<Post> = emptyList(),
    val sort: ThreadReplySort = ThreadReplySort.Hot,
    val onlyThreadAuthor: Boolean = false,
    val isInitialLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val isLoadingMore: Boolean = false,
    val errorMessage: String? = null,
    val subposts: SubpostUiState? = null,
    val readingPositionToRestore: ThreadReadingPosition? = null,
    val updatingLikeTargets: Set<ThreadLikeTarget> = emptySet(),
    val unknownLikeTargets: Set<ThreadLikeTarget> = emptySet(),
    val isUpdatingCollection: Boolean = false,
    val isCollectionOutcomeUnknown: Boolean = false,
    val actionErrorMessage: String? = null,
)

data class SubpostUiState(
    val parent: Post,
    val items: List<Subpost> = emptyList(),
    val currentPage: Int = 0,
    val hasMore: Boolean = true,
    val isLoading: Boolean = true,
    val errorMessage: String? = null,
)

private data class ThreadRequestKey(
    val sort: ThreadReplySort,
    val onlyThreadAuthor: Boolean,
    val page: Int,
)

private enum class ThreadLoadOperation { Refresh, LoadMore }

private data class SubpostRequestKey(
    val parentPostId: ULong,
    val page: Int,
)

class ThreadViewModel(
    private val threadId: Long,
    private val repository: ThreadRepository,
    initialSort: ThreadReplySort = ThreadReplySort.Hot,
    initialPostId: ULong? = null,
    restoreReadingPosition: Boolean = true,
) : ViewModel() {
    private val mutableState = MutableStateFlow(ThreadUiState(sort = initialSort))
    val state: StateFlow<ThreadUiState> = mutableState.asStateFlow()
    private val collectionChanges = Channel<Boolean>(Channel.BUFFERED)
    val confirmedCollectionChanges: Flow<Boolean> = collectionChanges.receiveAsFlow()

    private var threadJob: Job? = null
    private var subpostJob: Job? = null
    private var readingPositionJob: Job? = null
    private var threadRequestGeneration = 0
    private var activeThreadRequestKey: ThreadRequestKey? = null
    private var subpostRequestGeneration = 0
    private var activeSubpostRequestKey: SubpostRequestKey? = null
    private var didResolveReadingPosition = false
    private var pendingRestorePosition: ThreadReadingPosition? = null
    private var lastPersistedReadingPosition: ThreadReadingPosition? = null
    private var pendingReadingPosition: ThreadReadingPosition? = null
    private var pendingInitialPostId = initialPostId?.takeIf { it > 0uL }
    private var failedLoadOperation: ThreadLoadOperation? = null

    init {
        require(threadId > 0) { "threadId must be positive" }
        if (pendingInitialPostId != null) didResolveReadingPosition = true
        if (!restoreReadingPosition) didResolveReadingPosition = true
        loadFirstPage(showRefresh = false)
    }

    fun refresh() = loadFirstPage(showRefresh = true)

    fun selectSort(sort: ThreadReplySort) {
        if (sort == mutableState.value.sort) return
        mutableState.update { it.copy(sort = sort) }
        loadFirstPage(showRefresh = false, discardCurrentContent = true)
    }

    fun setOnlyThreadAuthor(enabled: Boolean) {
        if (enabled == mutableState.value.onlyThreadAuthor) return
        mutableState.update { it.copy(onlyThreadAuthor = enabled) }
        loadFirstPage(showRefresh = false, discardCurrentContent = true)
    }

    fun loadMore() {
        val snapshot = mutableState.value
        val page = snapshot.page ?: return
        if (!page.hasMore || snapshot.isLoadingMore || snapshot.isRefreshing || snapshot.isInitialLoading) return

        failedLoadOperation = null
        val key = ThreadRequestKey(snapshot.sort, snapshot.onlyThreadAuthor, page.currentPage + 1)
        val generation = beginThreadRequest(key)
        threadJob = viewModelScope.launch {
            updateThreadStateIfCurrent(generation, key) {
                it.copy(isLoadingMore = true, errorMessage = null)
            }
            runCatching {
                repository.threadPage(
                    threadId = threadId,
                    page = key.page,
                    sort = snapshot.sort,
                    onlyThreadAuthor = snapshot.onlyThreadAuthor,
                ).also { ensureCurrentThreadRequest(generation, key) }
            }.onSuccess { nextPage ->
                updateThreadStateIfCurrent(generation, key) { current ->
                    current.copy(
                        page = mergeThreadMetadata(current.page, nextPage),
                        posts = mergePosts(current.posts, nextPage.posts, nextPage.mainPost?.id),
                        isLoadingMore = false,
                    )
                }
            }.onFailure { error ->
                if (error is CancellationException) throw error
                if (!isCurrentThreadRequest(generation, key)) return@onFailure
                failedLoadOperation = ThreadLoadOperation.LoadMore
                updateThreadStateIfCurrent(generation, key) {
                    it.copy(isLoadingMore = false, errorMessage = error.userFacingMessage())
                }
            }
        }
    }

    fun retry() {
        when (failedLoadOperation) {
            ThreadLoadOperation.LoadMore -> loadMore()
            ThreadLoadOperation.Refresh, null -> refresh()
        }
    }

    fun openSubposts(parent: Post) {
        mutableState.update { it.copy(subposts = SubpostUiState(parent = parent)) }
        loadSubposts(page = 1)
    }

    fun closeSubposts() {
        invalidateSubpostRequest()
        mutableState.update { it.copy(subposts = null) }
    }

    fun loadMoreSubposts() {
        val current = mutableState.value.subposts ?: return
        if (current.isLoading || !current.hasMore) return
        loadSubposts(current.currentPage + 1)
    }

    fun retrySubposts() {
        val current = mutableState.value.subposts ?: return
        loadSubposts(if (current.items.isEmpty()) 1 else current.currentPage + 1)
    }

    fun readingPositionRestored() {
        mutableState.update { it.copy(readingPositionToRestore = null) }
    }

    fun visibleReadingPositionChanged(position: ThreadReadingPosition) {
        if (position.postId == 0uL || position.floor <= 1) return
        pendingReadingPosition = position
        readingPositionJob?.cancel()
        readingPositionJob = viewModelScope.launch {
            delay(READING_POSITION_DEBOUNCE_MILLISECONDS)
            persistPendingReadingPosition()
        }
    }

    fun flushReadingPosition() {
        val position = pendingReadingPosition ?: return
        readingPositionJob?.cancel()
        readingPositionJob = null
        pendingReadingPosition = null
        repository.scheduleReadingPositionSave(threadId, position)
    }

    fun toggleThreadLike() {
        val page = mutableState.value.page ?: return
        val postId = page.mainPost?.id?.takeIf { it > 0uL } ?: page.thread.firstPostId ?: return
        toggleLike(
            target = ThreadLikeTarget(postId, TiebaLikeObjectType.Thread),
            currentlyLiked = page.thread.isLiked,
        )
    }

    fun togglePostLike(post: Post) {
        toggleLike(
            target = ThreadLikeTarget(post.id, post.likeObjectType),
            currentlyLiked = post.isLiked,
        )
    }

    fun toggleSubpostLike(subpost: Subpost) {
        toggleLike(
            target = ThreadLikeTarget(subpost.id, TiebaLikeObjectType.Subpost),
            currentlyLiked = subpost.isLiked,
        )
    }

    fun toggleCollection() {
        val snapshot = mutableState.value
        val page = snapshot.page ?: return
        if (snapshot.isUpdatingCollection || snapshot.isCollectionOutcomeUnknown) return
        val markedPostId = pendingReadingPosition?.postId
            ?: lastPersistedReadingPosition?.postId
            ?: page.mainPost?.id?.takeIf { it > 0uL }
            ?: page.thread.firstPostId
            ?: return
        val targetState = !page.isCollected
        mutableState.update {
            it.copy(
                page = it.page?.copy(isCollected = targetState),
                isUpdatingCollection = true,
                actionErrorMessage = null,
            )
        }
        viewModelScope.launch {
            try {
                repository.setCollected(threadId, markedPostId, targetState)
                mutableState.update { state ->
                    state.copy(page = state.page?.copy(isCollected = targetState))
                }
                collectionChanges.trySend(targetState)
            } catch (error: CancellationException) {
                rollbackCollection(targetState)
                throw error
            } catch (error: Throwable) {
                val unknown = error.mutationOutcomeUnknownMessageOrNull()
                if (unknown != null) {
                    mutableState.update {
                        it.copy(isCollectionOutcomeUnknown = true, actionErrorMessage = unknown)
                    }
                } else {
                    rollbackCollection(targetState, error.userFacingMessage())
                }
            } finally {
                mutableState.update { it.copy(isUpdatingCollection = false) }
            }
        }
    }

    fun clearActionError() {
        mutableState.update { it.copy(actionErrorMessage = null) }
    }

    private fun loadFirstPage(
        showRefresh: Boolean,
        discardCurrentContent: Boolean = false,
    ) {
        val requestState = mutableState.value
        failedLoadOperation = null
        val key = ThreadRequestKey(requestState.sort, requestState.onlyThreadAuthor, page = 1)
        val generation = beginThreadRequest(key)
        val showsRefreshIndicator = showRefresh && requestState.page != null
        updateThreadStateIfCurrent(generation, key) {
            it.copy(
                page = if (discardCurrentContent) null else it.page,
                posts = if (discardCurrentContent) emptyList() else it.posts,
                isInitialLoading = !showsRefreshIndicator,
                isRefreshing = showsRefreshIndicator,
                isLoadingMore = false,
                errorMessage = null,
                readingPositionToRestore = if (discardCurrentContent) null else it.readingPositionToRestore,
            )
        }
        threadJob = viewModelScope.launch {
            runCatching {
                val explicitPostId = pendingInitialPostId
                val readingPosition = if (explicitPostId == null) {
                    resolveReadingPositionIfNeeded(generation, key)
                } else {
                    null
                }
                ensureCurrentThreadRequest(generation, key)
                val targetPostId = explicitPostId ?: readingPosition?.postId
                var requestSort = if (targetPostId != null) ThreadReplySort.Ascending else requestState.sort
                if (requestSort != mutableState.value.sort && isCurrentThreadRequest(generation, key)) {
                    updateThreadStateIfCurrent(generation, key) { it.copy(sort = requestSort) }
                }
                var discardedReadingPosition = false
                var firstPage = when {
                    explicitPostId != null -> try {
                        repository.threadPageAround(
                            threadId = threadId,
                            postId = explicitPostId,
                            sort = requestSort,
                            onlyThreadAuthor = requestState.onlyThreadAuthor,
                        ).also { ensureCurrentThreadRequest(generation, key) }
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Throwable) {
                        throw ExplicitPostLocationException(error)
                    }
                    readingPosition != null -> try {
                        repository.threadPageAround(
                            threadId = threadId,
                            postId = readingPosition.postId,
                            sort = requestSort,
                            onlyThreadAuthor = requestState.onlyThreadAuthor,
                        ).also { ensureCurrentThreadRequest(generation, key) }
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: Throwable) {
                        ensureCurrentThreadRequest(generation, key)
                        discardReadingPosition(generation, key)
                        discardedReadingPosition = true
                        requestSort = requestState.sort
                        updateThreadStateIfCurrent(generation, key) { it.copy(sort = requestSort) }
                        repository.threadPage(
                            threadId = threadId,
                            page = 1,
                            sort = requestSort,
                            onlyThreadAuthor = requestState.onlyThreadAuthor,
                        ).also { ensureCurrentThreadRequest(generation, key) }
                    }
                    else -> repository.threadPage(
                        threadId = threadId,
                        page = 1,
                        sort = requestSort,
                        onlyThreadAuthor = requestState.onlyThreadAuthor,
                    ).also { ensureCurrentThreadRequest(generation, key) }
                }
                var resolved = resolveMainPost(firstPage, requestState.page, generation, key)
                ensureCurrentThreadRequest(generation, key)
                val restorePosition = when {
                    explicitPostId != null -> {
                        if (!resolved.containsTargetPost(explicitPostId)) throw ExplicitPostLocationException()
                        ThreadReadingPosition(explicitPostId, 0)
                    }
                    readingPosition != null && !discardedReadingPosition -> {
                        if (resolved.containsReadingPosition(readingPosition)) {
                            readingPosition
                        } else {
                            discardReadingPosition(generation, key)
                            requestSort = requestState.sort
                            if (requestSort != mutableState.value.sort && isCurrentThreadRequest(generation, key)) {
                                updateThreadStateIfCurrent(generation, key) { it.copy(sort = requestSort) }
                            }
                            firstPage = repository.threadPage(
                                threadId = threadId,
                                page = 1,
                                sort = requestSort,
                                onlyThreadAuthor = requestState.onlyThreadAuthor,
                            ).also { ensureCurrentThreadRequest(generation, key) }
                            resolved = resolveMainPost(firstPage, requestState.page, generation, key)
                            null
                        }
                    }
                    else -> null
                }
                resolved to restorePosition
            }.onSuccess { (firstPage, readingPosition) ->
                if (!isCurrentThreadRequest(generation, key)) return@onSuccess
                pendingRestorePosition = null
                pendingInitialPostId = null
                updateThreadStateIfCurrent(generation, key) {
                    it.copy(
                        page = firstPage,
                        posts = mergePosts(emptyList(), firstPage.posts, firstPage.mainPost?.id),
                        isInitialLoading = false,
                        isRefreshing = false,
                        readingPositionToRestore = readingPosition,
                        unknownLikeTargets = emptySet(),
                        isCollectionOutcomeUnknown = false,
                    )
                }
            }.onFailure { error ->
                if (error is CancellationException) throw error
                if (!isCurrentThreadRequest(generation, key)) return@onFailure
                failedLoadOperation = ThreadLoadOperation.Refresh
                updateThreadStateIfCurrent(generation, key) {
                    it.copy(
                        isInitialLoading = false,
                        isRefreshing = false,
                        errorMessage = error.userFacingMessage(),
                    )
                }
            }
        }
    }

    private suspend fun resolveReadingPositionIfNeeded(
        generation: Int,
        key: ThreadRequestKey,
    ): ThreadReadingPosition? {
        if (didResolveReadingPosition) return pendingRestorePosition
        val position = try {
            repository.loadReadingPosition(threadId)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Throwable) {
            null
        }
        ensureCurrentThreadRequest(generation, key)
        didResolveReadingPosition = true
        position?.also {
            lastPersistedReadingPosition = it
            pendingRestorePosition = it
        }
        return position
    }

    private suspend fun discardReadingPosition(generation: Int, key: ThreadRequestKey) {
        ensureCurrentThreadRequest(generation, key)
        repository.removeReadingPosition(threadId)
        ensureCurrentThreadRequest(generation, key)
        pendingRestorePosition = null
        lastPersistedReadingPosition = null
    }

    private suspend fun resolveMainPost(
        firstPage: ThreadPage,
        previousPage: ThreadPage?,
        generation: Int,
        key: ThreadRequestKey,
    ): ThreadPage {
        var page = firstPage
        val fallback = repository.mainPostFallback(threadId) ?: ThreadMainPostFallback.from(page.thread)
        if (ThreadPageMainPostPolicy.needsRecovery(page)) {
            try {
                val recovery = repository.threadPage(
                    threadId = threadId,
                    page = 1,
                    sort = ThreadReplySort.Ascending,
                    onlyThreadAuthor = false,
                )
                ensureCurrentThreadRequest(generation, key)
                ThreadPageMainPostPolicy.mainPost(recovery)?.let { recovered ->
                    page = page.copy(mainPost = recovered, mainPostIsSummaryFallback = false)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                if (fallback == null && previousPage?.mainPost == null) throw error
            }
        }
        var resolved = ThreadPageMainPostPolicy.mergeWithPrevious(page, previousPage)
        resolved = ThreadPageMainPostPolicy.applyFallback(resolved, fallback, threadId)
        if (ThreadPageMainPostPolicy.needsRecovery(resolved)) {
            throw IllegalStateException("暂时无法获取主楼内容，请稍后刷新重试。")
        }
        return resolved
    }

    private suspend fun persistPendingReadingPosition() {
        val position = pendingReadingPosition ?: return
        if (position == lastPersistedReadingPosition) {
            pendingReadingPosition = null
            return
        }
        try {
            repository.saveReadingPosition(threadId, position)
            lastPersistedReadingPosition = position
            if (pendingReadingPosition == position) pendingReadingPosition = null
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            mutableState.update { it.copy(actionErrorMessage = error.userFacingMessage()) }
        }
    }

    private fun toggleLike(target: ThreadLikeTarget, currentlyLiked: Boolean) {
        if (target in mutableState.value.updatingLikeTargets || target in mutableState.value.unknownLikeTargets) return
        val targetState = !currentlyLiked
        mutableState.update {
            applyLikeState(it, target, targetState).copy(
                updatingLikeTargets = it.updatingLikeTargets + target,
                actionErrorMessage = null,
            )
        }
        viewModelScope.launch {
            try {
                repository.setLiked(threadId, target, targetState)
                mutableState.update { applyLikeState(it, target, targetState) }
            } catch (error: CancellationException) {
                mutableState.update { applyLikeState(it, target, currentlyLiked) }
                throw error
            } catch (error: Throwable) {
                val unknown = error.mutationOutcomeUnknownMessageOrNull()
                mutableState.update { state ->
                    if (unknown != null) {
                        state.copy(
                            unknownLikeTargets = state.unknownLikeTargets + target,
                            actionErrorMessage = unknown,
                        )
                    } else {
                        applyLikeState(state, target, currentlyLiked).copy(
                            actionErrorMessage = error.userFacingMessage(),
                        )
                    }
                }
            } finally {
                mutableState.update {
                    it.copy(updatingLikeTargets = it.updatingLikeTargets - target)
                }
            }
        }
    }

    private fun rollbackCollection(optimisticState: Boolean, message: String? = null) {
        mutableState.update { state ->
            val page = state.page
            state.copy(
                page = if (page?.isCollected == optimisticState) {
                    page.copy(isCollected = !optimisticState)
                } else {
                    page
                },
                actionErrorMessage = message,
            )
        }
    }

    private fun loadSubposts(page: Int) {
        val snapshot = mutableState.value.subposts ?: return
        val key = SubpostRequestKey(snapshot.parent.id, page)
        val generation = beginSubpostRequest(key)
        subpostJob = viewModelScope.launch {
            updateSubpostStateIfCurrent(generation, key) { state ->
                state.copy(subposts = state.subposts?.copy(isLoading = true, errorMessage = null))
            }
            runCatching { repository.subpostPage(snapshot.parent, page) }
                .onSuccess { response ->
                    updateSubpostStateIfCurrent(generation, key) { state ->
                        val active = state.subposts ?: return@updateSubpostStateIfCurrent state
                        state.copy(
                            subposts = active.copy(
                                items = mergeSubposts(
                                    if (page == 1) emptyList() else active.items,
                                    response.subposts,
                                ),
                                currentPage = response.currentPage,
                                hasMore = response.hasMore,
                                isLoading = false,
                            ),
                        )
                    }
                }
                .onFailure { error ->
                    if (error is CancellationException) throw error
                    updateSubpostStateIfCurrent(generation, key) { state ->
                        state.copy(
                            subposts = state.subposts?.copy(
                                isLoading = false,
                                errorMessage = error.userFacingMessage(),
                            ),
                        )
                    }
                }
        }
    }

    private fun beginThreadRequest(key: ThreadRequestKey): Int {
        val generation = ++threadRequestGeneration
        activeThreadRequestKey = key
        threadJob?.cancel()
        return generation
    }

    private fun isCurrentThreadRequest(generation: Int, key: ThreadRequestKey): Boolean =
        generation == threadRequestGeneration && key == activeThreadRequestKey

    private fun ensureCurrentThreadRequest(generation: Int, key: ThreadRequestKey) {
        if (!isCurrentThreadRequest(generation, key)) throw CancellationException("Thread request was superseded")
    }

    private inline fun updateThreadStateIfCurrent(
        generation: Int,
        key: ThreadRequestKey,
        transform: (ThreadUiState) -> ThreadUiState,
    ) {
        if (!isCurrentThreadRequest(generation, key)) return
        mutableState.update { state ->
            if (isCurrentThreadRequest(generation, key)) transform(state) else state
        }
    }

    private fun beginSubpostRequest(key: SubpostRequestKey): Int {
        val generation = ++subpostRequestGeneration
        activeSubpostRequestKey = key
        subpostJob?.cancel()
        return generation
    }

    private fun invalidateSubpostRequest() {
        subpostRequestGeneration += 1
        activeSubpostRequestKey = null
        subpostJob?.cancel()
        subpostJob = null
    }

    private fun isCurrentSubpostRequest(generation: Int, key: SubpostRequestKey): Boolean =
        generation == subpostRequestGeneration && key == activeSubpostRequestKey &&
            mutableState.value.subposts?.parent?.id == key.parentPostId

    private inline fun updateSubpostStateIfCurrent(
        generation: Int,
        key: SubpostRequestKey,
        transform: (ThreadUiState) -> ThreadUiState,
    ) {
        if (!isCurrentSubpostRequest(generation, key)) return
        mutableState.update { state ->
            if (isCurrentSubpostRequest(generation, key)) transform(state) else state
        }
    }

    companion object {
        internal const val READING_POSITION_DEBOUNCE_MILLISECONDS = 700L

        fun factory(
            threadId: Long,
            repository: ThreadRepository,
            initialSort: ThreadReplySort = ThreadReplySort.Hot,
            initialPostId: ULong? = null,
            restoreReadingPosition: Boolean = true,
        ): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    require(modelClass.isAssignableFrom(ThreadViewModel::class.java))
                    return ThreadViewModel(threadId, repository, initialSort, initialPostId, restoreReadingPosition) as T
                }
            }
    }
}

internal fun mergePosts(
    current: List<Post>,
    incoming: List<Post>,
    mainPostId: ULong?,
): List<Post> = buildList {
    val ids = mutableSetOf<ULong>()
    (current + incoming).forEach { post ->
        if (post.id != mainPostId && ids.add(post.id)) add(post)
    }
}

private fun mergeSubposts(current: List<Subpost>, incoming: List<Subpost>): List<Subpost> =
    (current + incoming).distinctBy(Subpost::id)

private fun mergeThreadMetadata(current: ThreadPage?, next: ThreadPage): ThreadPage = next.copy(
    thread = current?.thread?.let { previous ->
        next.thread.copy(isLiked = previous.isLiked, likeCount = previous.likeCount)
    } ?: next.thread,
    mainPost = current?.mainPost ?: next.mainPost,
    mainPostIsSummaryFallback = current?.mainPostIsSummaryFallback ?: next.mainPostIsSummaryFallback,
    isCollected = current?.isCollected ?: next.isCollected,
)

private fun ThreadPage.containsTargetPost(postId: ULong): Boolean =
    mainPost?.id == postId || posts.any { it.id == postId }

private fun ThreadPage.containsReadingPosition(position: ThreadReadingPosition): Boolean =
    containsTargetPost(position.postId)

private class ExplicitPostLocationException(cause: Throwable? = null) :
    IllegalStateException("未能定位到搜索结果中的回复，可能已被删除或不可见，请重试。", cause)

private val Post.likeObjectType: TiebaLikeObjectType
    get() = if (floor == 1) TiebaLikeObjectType.Thread else TiebaLikeObjectType.Post

private fun applyLikeState(
    state: ThreadUiState,
    target: ThreadLikeTarget,
    liked: Boolean,
): ThreadUiState {
    val page = state.page?.let { current ->
        when (target.objectType) {
            TiebaLikeObjectType.Thread -> current.copy(
                thread = if (current.thread.isLiked == liked) {
                    current.thread
                } else {
                    current.thread.copy(
                        isLiked = liked,
                        likeCount = updatedLikeCount(current.thread.likeCount, liked),
                    )
                },
                mainPost = current.mainPost?.updateLikeIfMatches(target.postId, liked),
            )
            TiebaLikeObjectType.Post -> current.copy(
                mainPost = current.mainPost?.updateLikeIfMatches(target.postId, liked),
            )
            TiebaLikeObjectType.Subpost -> current.copy(
                mainPost = current.mainPost?.updatePreviewSubpostLikeIfMatches(target.postId, liked),
            )
        }
    }
    val posts = when (target.objectType) {
        TiebaLikeObjectType.Thread,
        TiebaLikeObjectType.Post,
        -> state.posts.map { it.updateLikeIfMatches(target.postId, liked) }
        TiebaLikeObjectType.Subpost -> state.posts.map {
            it.updatePreviewSubpostLikeIfMatches(target.postId, liked)
        }
    }
    val subposts = state.subposts?.let { current ->
        when (target.objectType) {
            TiebaLikeObjectType.Thread,
            TiebaLikeObjectType.Post,
            -> current.copy(parent = current.parent.updateLikeIfMatches(target.postId, liked))
            TiebaLikeObjectType.Subpost -> current.copy(
                items = current.items.map { it.updateLikeIfMatches(target.postId, liked) },
            )
        }
    }
    return state.copy(page = page, posts = posts, subposts = subposts)
}

private fun Post.updateLikeIfMatches(postId: ULong, liked: Boolean): Post =
    if (id != postId || isLiked == liked) this else copy(
        isLiked = liked,
        likeCount = updatedLikeCount(likeCount, liked),
    )

private fun Post.updatePreviewSubpostLikeIfMatches(postId: ULong, liked: Boolean): Post = copy(
    previewSubposts = previewSubposts.map { it.updateLikeIfMatches(postId, liked) },
)

private fun Subpost.updateLikeIfMatches(postId: ULong, liked: Boolean): Subpost =
    if (id != postId || isLiked == liked) this else copy(
        isLiked = liked,
        likeCount = updatedLikeCount(likeCount, liked),
    )

private fun updatedLikeCount(current: Int, liked: Boolean): Int =
    (current + if (liked) 1 else -1).coerceAtLeast(0)

private fun Throwable.userFacingMessage(): String = message?.takeIf(String::isNotBlank) ?: "加载失败，请稍后重试"
