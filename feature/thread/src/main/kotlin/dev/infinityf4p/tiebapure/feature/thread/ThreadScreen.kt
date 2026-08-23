package dev.infinityf4p.tiebapure.feature.thread

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Bookmark
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.DownloadDone
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.infinityf4p.tiebapure.core.designsystem.ReaderCard
import dev.infinityf4p.tiebapure.core.designsystem.ReaderSectionBand
import dev.infinityf4p.tiebapure.core.designsystem.readerFontFamily
import dev.infinityf4p.tiebapure.core.media.AvatarImage
import dev.infinityf4p.tiebapure.core.media.ImageGallery
import dev.infinityf4p.tiebapure.core.media.ImageSaveAction
import dev.infinityf4p.tiebapure.core.media.VideoPlayer
import dev.infinityf4p.tiebapure.core.media.VoicePlaybackCoordinator
import dev.infinityf4p.tiebapure.core.model.ImageContent
import dev.infinityf4p.tiebapure.core.model.Forum
import dev.infinityf4p.tiebapure.core.model.Post
import dev.infinityf4p.tiebapure.core.model.ReadingPreferences
import dev.infinityf4p.tiebapure.core.model.Subpost
import dev.infinityf4p.tiebapure.core.model.ThreadReplySort
import dev.infinityf4p.tiebapure.core.model.VideoContent
import kotlinx.coroutines.flow.distinctUntilChanged

sealed interface ThreadReplyTarget {
    data object Thread : ThreadReplyTarget
    data class Floor(val post: Post) : ThreadReplyTarget
    data class Nested(val parent: Post, val subpost: Subpost) : ThreadReplyTarget
}

enum class ThreadInitialDestination { Replies }

data class ThreadCapabilities(
    val canReply: Boolean = true,
    val canLike: Boolean = true,
    val canCollect: Boolean = true,
    val canRefresh: Boolean = true,
    val canFilterReplies: Boolean = true,
    val alwaysShowSubpostOpenAction: Boolean = false,
)

private data class GallerySelection(val images: List<ImageContent>, val index: Int)

@Composable
fun ThreadRoute(
    threadId: Long,
    initialPostId: ULong? = null,
    initialDestination: ThreadInitialDestination? = null,
    viewModelSessionKey: String = "guest",
    repository: ThreadRepository,
    onBack: () -> Unit,
    onReply: (ThreadReplyTarget) -> Unit,
    onUserClick: (Long) -> Unit,
    onLinkClick: (String) -> Unit,
    onShare: (String) -> Unit,
    onSave: (() -> Unit)? = null,
    isSaving: Boolean = false,
    isSaved: Boolean = false,
    onDownloadImage: (ImageContent) -> Unit,
    onSaveImage: ImageSaveAction? = null,
    readingPreferences: ReadingPreferences = ReadingPreferences(),
    capabilities: ThreadCapabilities = ThreadCapabilities(),
    onForumClick: ((Forum) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val viewModel: ThreadViewModel = viewModel(
        key = "thread-$threadId-${initialPostId ?: 0uL}-${initialDestination?.name ?: "default"}-$viewModelSessionKey",
        factory = ThreadViewModel.factory(
            threadId,
            repository,
            readingPreferences.defaultReplySort,
            initialPostId,
            restoreReadingPosition = initialDestination == null,
        ),
    )
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current.applicationContext
    val voicePlayback = remember(context) { VoicePlaybackCoordinator.shared(context) }

    DisposableEffect(viewModel, voicePlayback) {
        onDispose {
            viewModel.flushReadingPosition()
            voicePlayback.stop()
        }
    }

    CompositionLocalProvider(LocalReadingPreferences provides readingPreferences) {
        ThreadScreen(
            state = state,
            initialDestination = initialDestination,
            capabilities = capabilities,
            onBack = onBack,
            onForumClick = onForumClick,
            onRefresh = viewModel::refresh,
            onLoadMore = viewModel::loadMore,
            onRetry = viewModel::retry,
            onSort = viewModel::selectSort,
            onOnlyAuthor = viewModel::setOnlyThreadAuthor,
            onReply = onReply,
            onUserClick = onUserClick,
            onLinkClick = onLinkClick,
            onShare = { onShare(buildThreadShareUrl(threadId)) },
            onSave = onSave,
            isSaving = isSaving,
            isSaved = isSaved,
            onOpenSubposts = viewModel::openSubposts,
            onCloseSubposts = viewModel::closeSubposts,
            onLoadMoreSubposts = viewModel::loadMoreSubposts,
            onRetrySubposts = viewModel::retrySubposts,
            onToggleThreadLike = viewModel::toggleThreadLike,
            onTogglePostLike = viewModel::togglePostLike,
            onToggleSubpostLike = viewModel::toggleSubpostLike,
            onToggleCollection = viewModel::toggleCollection,
            onReadingPositionChanged = viewModel::visibleReadingPositionChanged,
            onReadingPositionRestored = viewModel::readingPositionRestored,
            onActionErrorShown = viewModel::clearActionError,
            onDownloadImage = onDownloadImage,
            onSaveImage = onSaveImage,
            modifier = modifier,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThreadScreen(
    state: ThreadUiState,
    initialDestination: ThreadInitialDestination? = null,
    capabilities: ThreadCapabilities = ThreadCapabilities(),
    onBack: () -> Unit,
    onForumClick: ((Forum) -> Unit)? = null,
    onRefresh: () -> Unit,
    onLoadMore: () -> Unit,
    onRetry: () -> Unit,
    onSort: (ThreadReplySort) -> Unit,
    onOnlyAuthor: (Boolean) -> Unit,
    onReply: (ThreadReplyTarget) -> Unit,
    onUserClick: (Long) -> Unit,
    onLinkClick: (String) -> Unit,
    onShare: () -> Unit,
    onSave: (() -> Unit)? = null,
    isSaving: Boolean = false,
    isSaved: Boolean = false,
    onOpenSubposts: (Post) -> Unit,
    onCloseSubposts: () -> Unit,
    onLoadMoreSubposts: () -> Unit,
    onRetrySubposts: () -> Unit,
    onToggleThreadLike: () -> Unit,
    onTogglePostLike: (Post) -> Unit,
    onToggleSubpostLike: (Subpost) -> Unit,
    onToggleCollection: () -> Unit,
    onReadingPositionChanged: (ThreadReadingPosition) -> Unit,
    onReadingPositionRestored: () -> Unit,
    onActionErrorShown: () -> Unit,
    onDownloadImage: (ImageContent) -> Unit,
    onSaveImage: ImageSaveAction? = null,
    modifier: Modifier = Modifier,
) {
    var gallery by remember { mutableStateOf<GallerySelection?>(null) }
    var video by remember { mutableStateOf<VideoContent?>(null) }
    val actionVisibility = capabilities.actionVisibility(hasPage = state.page != null)
    val listState = rememberLazyListState()
    val snackbarHostState = remember { SnackbarHostState() }
    var appliedInitialDestination by rememberSaveable(initialDestination) { mutableStateOf(false) }

    LaunchedEffect(state.actionErrorMessage) {
        state.actionErrorMessage?.let { message ->
            snackbarHostState.showSnackbar(message)
            onActionErrorShown()
        }
    }

    LaunchedEffect(state.readingPositionToRestore, state.posts.map(Post::id)) {
        val position = state.readingPositionToRestore ?: return@LaunchedEffect
        val postIndex = state.posts.indexOfFirst {
            it.id == position.postId || it.floor == position.floor
        }
        when {
            state.page?.mainPost?.id == position.postId -> {
                listState.scrollToItem(0)
                onReadingPositionRestored()
            }
            postIndex >= 0 -> {
                listState.scrollToItem(postIndex + THREAD_REPLY_ITEM_OFFSET)
                onReadingPositionRestored()
            }
        }
    }

    LaunchedEffect(initialDestination, state.page, appliedInitialDestination) {
        if (initialDestination == ThreadInitialDestination.Replies && state.page != null && !appliedInitialDestination) {
            listState.scrollToItem(THREAD_REPLY_FILTER_ITEM_INDEX)
            appliedInitialDestination = true
        }
    }

    LaunchedEffect(listState, state.posts.map(Post::id), state.readingPositionToRestore) {
        if (state.readingPositionToRestore != null) return@LaunchedEffect
        snapshotFlow {
            if (listState.isScrollInProgress) return@snapshotFlow null
            if (listState.firstVisibleItemIndex < THREAD_REPLY_ITEM_OFFSET) return@snapshotFlow null
            listState.layoutInfo.visibleItemsInfo
                .mapNotNull { item -> (item.key as? String)?.postIdFromListKey() }
                .lastOrNull()
        }
            .distinctUntilChanged()
            .collect { postId ->
                val post = state.posts.firstOrNull { it.id == postId } ?: return@collect
                onReadingPositionChanged(ThreadReadingPosition(post.id, post.floor))
            }
    }

    LaunchedEffect(listState, state.posts.size, state.page?.hasMore) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
            .distinctUntilChanged()
            .collect { lastVisible ->
                if (state.page?.hasMore == true && lastVisible >= listState.layoutInfo.totalItemsCount - 3) {
                    onLoadMore()
                }
            }
    }

    Scaffold(
        modifier = modifier.testTag("thread-screen"),
        // The app container already owns safeDrawing. Consuming it again leaves a navigation-bar-height gap.
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = {
                    ThreadForumTitle(
                        forum = state.page?.forum,
                        onForumClick = onForumClick,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    val page = state.page
                    if (actionVisibility.showCollectAction) {
                        IconButton(
                            onClick = onToggleCollection,
                            modifier = Modifier.testTag("thread-collect-action"),
                            enabled = page != null && !state.isUpdatingCollection && !state.isCollectionOutcomeUnknown,
                        ) {
                            if (state.isUpdatingCollection) {
                                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                            } else {
                                Icon(
                                    if (page?.isCollected == true) Icons.Outlined.Bookmark else Icons.Outlined.BookmarkBorder,
                                    contentDescription = if (page?.isCollected == true) "取消收藏" else "收藏帖子",
                                    tint = if (page?.isCollected == true) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    onSave?.let { save ->
                        IconButton(
                            onClick = save,
                            enabled = !isSaving,
                            modifier = Modifier.testTag("thread-save-action"),
                        ) {
                            if (isSaving) {
                                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                            } else {
                                Icon(
                                    if (isSaved) Icons.Outlined.DownloadDone else Icons.Outlined.Download,
                                    contentDescription = if (isSaved) "更新本地保存" else "保存到本地",
                                )
                            }
                        }
                    }
                    IconButton(
                        onClick = onShare,
                        modifier = Modifier.testTag("thread-share-action"),
                    ) {
                        Icon(Icons.Outlined.Share, contentDescription = "分享帖子")
                    }
                    if (capabilities.canRefresh) {
                        IconButton(onClick = onRefresh, enabled = !state.isRefreshing) {
                            Icon(Icons.Outlined.Refresh, contentDescription = "刷新")
                        }
                    }
                },
                windowInsets = WindowInsets(0, 0, 0, 0),
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            if (actionVisibility.showReplyActions) {
                Surface(
                    modifier = Modifier.testTag("thread-bottom-bar"),
                    color = MaterialTheme.colorScheme.surface,
                ) {
                    Column {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        Surface(
                            onClick = { onReply(ThreadReplyTarget.Thread) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("thread-reply-bar")
                                .padding(horizontal = 16.dp, vertical = 8.dp)
                                .heightIn(min = 48.dp),
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerLow,
                        ) {
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 48.dp)
                                    .padding(horizontal = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(Icons.Outlined.Edit, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("回复帖子", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        },
    ) { contentPadding ->
        PullToRefreshBox(
            isRefreshing = state.isRefreshing,
            onRefresh = onRefresh,
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding)
                .testTag("thread-content"),
        ) {
            when {
                state.isInitialLoading && state.page == null -> LoadingState()
                state.page == null -> ErrorState(state.errorMessage, onRetry)
                else -> LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.surfaceContainerLow),
                ) {
                    item(key = "main") {
                        MainPost(
                            state = state,
                            onReply = onReply,
                            onUserClick = onUserClick,
                            onLinkClick = onLinkClick,
                            onImagesClick = { images, index -> gallery = GallerySelection(images, index) },
                            onVideoClick = { video = it },
                            onOpenSubposts = onOpenSubposts,
                            alwaysShowSubpostOpenAction = capabilities.alwaysShowSubpostOpenAction,
                            onToggleLike = onToggleThreadLike,
                            capabilities = capabilities,
                        )
                    }
                    if (capabilities.canFilterReplies) {
                        item(key = "filters") {
                            ReplyFilters(state.sort, state.onlyThreadAuthor, onSort, onOnlyAuthor)
                        }
                    }
                    items(items = state.posts, key = { "post-${it.id}" }) { post ->
                        val metadataPlacement = threadPostMetadataPlacement(
                            isMainPost = false,
                            hasPreviewSubposts = post.previewSubposts.isNotEmpty(),
                        )
                        ReaderCard(
                            cornerRadius = 0.dp,
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                                start = 16.dp,
                                top = 12.dp,
                                end = 16.dp,
                                bottom = metadataPlacement.cardBottomPadding.dp,
                            ),
                        ) {
                            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            AuthorLine(
                                author = post.author,
                                floor = post.floor,
                                likeCount = post.likeCount,
                                isLiked = post.isLiked,
                                isLikeUpdating = ThreadLikeTarget(post.id, post.likeObjectType) in state.updatingLikeTargets,
                                onToggleLike = if (!capabilities.canLike ||
                                    ThreadLikeTarget(post.id, post.likeObjectType) in state.unknownLikeTargets
                                ) null else ({ onTogglePostLike(post) }),
                                onUserClick = onUserClick,
                                isThreadAuthor = post.author.id > 0L && post.author.id == state.page.thread.author.id,
                            )
                            PostBody(
                                post = post,
                                onReply = if (capabilities.canReply) {
                                    { onReply(ThreadReplyTarget.Floor(it)) }
                                } else {
                                    null
                                },
                                onUserClick = onUserClick,
                                onLinkClick = onLinkClick,
                                onImagesClick = { images, index -> gallery = GallerySelection(images, index) },
                                onVideoClick = { video = it },
                                onOpenSubposts = onOpenSubposts,
                                showSubpostOpenAction = capabilities.alwaysShowSubpostOpenAction,
                                modifier = Modifier.padding(start = 48.dp),
                                threadAuthorId = state.page.thread.author.id,
                            )
                            }
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                    item(key = "footer") {
                        ThreadFooter(state, onLoadMore, onRetry)
                    }
                }
            }
        }
    }

    state.subposts?.let { nested ->
        ModalBottomSheet(
            onDismissRequest = onCloseSubposts,
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ) {
            SubpostSheet(
                state = nested,
                capabilities = capabilities,
                threadAuthorId = state.page?.thread?.author?.id,
                onClose = onCloseSubposts,
                onReplyParent = if (capabilities.canReply) {
                    { onReply(ThreadReplyTarget.Floor(it)) }
                } else {
                    null
                },
                onReply = if (capabilities.canReply) {
                    { onReply(ThreadReplyTarget.Nested(nested.parent, it)) }
                } else {
                    null
                },
                onUserClick = onUserClick,
                onLinkClick = onLinkClick,
                onImagesClick = { images, index -> gallery = GallerySelection(images, index) },
                onVideoClick = { video = it },
                onLoadMore = onLoadMoreSubposts,
                onRetry = onRetrySubposts,
                updatingLikeTargets = state.updatingLikeTargets,
                unknownLikeTargets = state.unknownLikeTargets,
                onTogglePostLike = onTogglePostLike,
                onToggleSubpostLike = onToggleSubpostLike,
            )
        }
    }

    gallery?.let { selection ->
        ImageGallery(
            images = selection.images,
            initialPage = selection.index,
            onDismiss = { gallery = null },
            onDownload = onDownloadImage,
            saveAction = onSaveImage,
        )
    }
    video?.let { selected -> VideoPlayer(selected, onDismiss = { video = null }) }
}

internal fun buildThreadShareUrl(threadId: Long): String {
    require(threadId > 0) { "threadId must be positive" }
    return "https://tieba.baidu.com/p/$threadId"
}

@Composable
private fun MainPost(
    state: ThreadUiState,
    onReply: (ThreadReplyTarget) -> Unit,
    onUserClick: (Long) -> Unit,
    onLinkClick: (String) -> Unit,
    onImagesClick: (List<ImageContent>, Int) -> Unit,
    onVideoClick: (VideoContent) -> Unit,
    onOpenSubposts: (Post) -> Unit,
    alwaysShowSubpostOpenAction: Boolean,
    onToggleLike: () -> Unit,
    capabilities: ThreadCapabilities,
) {
    val page = state.page ?: return
    ReaderCard(cornerRadius = 0.dp) {
      Column(Modifier.fillMaxWidth()) {
        val mainPostId = page.mainPost?.id ?: page.thread.firstPostId
        val mainAuthor = page.mainPost?.author ?: page.thread.author
        AuthorLine(
            author = mainAuthor,
            floor = 1,
            likeCount = page.thread.likeCount,
            isLiked = page.thread.isLiked,
            isLikeUpdating = mainPostId != null && ThreadLikeTarget(
                mainPostId,
                dev.infinityf4p.tiebapure.core.model.TiebaLikeObjectType.Thread,
            ) in state.updatingLikeTargets,
            onToggleLike = if (
                !capabilities.canLike || mainPostId == null || ThreadLikeTarget(
                    mainPostId,
                    dev.infinityf4p.tiebapure.core.model.TiebaLikeObjectType.Thread,
                ) in state.unknownLikeTargets
            ) null else onToggleLike,
            onUserClick = onUserClick,
            isThreadAuthor = mainAuthor.id > 0L && mainAuthor.id == page.thread.author.id,
            isMainPost = true,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            page.thread.title,
            style = MaterialTheme.typography.titleLarge.copy(
                fontFamily = readerFontFamily(LocalReadingPreferences.current.fontFamily),
            ),
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(12.dp))
        if (page.mainPostIsSummaryFallback) {
            ReaderSectionBand(Modifier.padding(bottom = 8.dp)) {
                Row(
                    Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Outlined.Info,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "主楼完整内容暂不可用，以下为来源页摘要。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        page.mainPost?.let { mainPost ->
            PostBody(
                post = mainPost,
                onReply = if (capabilities.canReply) {
                    { onReply(ThreadReplyTarget.Floor(it)) }
                } else {
                    null
                },
                onUserClick = onUserClick,
                onLinkClick = onLinkClick,
                onImagesClick = onImagesClick,
                onVideoClick = onVideoClick,
                onOpenSubposts = onOpenSubposts,
                showSubpostOpenAction = alwaysShowSubpostOpenAction,
                isMainPost = true,
                threadAuthorId = page.thread.author.id,
            )
        } ?: RichContent(
            blocks = page.thread.blocks,
            onLinkClick = onLinkClick,
            onUserClick = onUserClick,
            onImagesClick = onImagesClick,
            onVideoClick = onVideoClick,
        )
      }
    }
}

@Composable
private fun ReplyFilters(
    sort: ThreadReplySort,
    onlyAuthor: Boolean,
    onSort: (ThreadReplySort) -> Unit,
    onOnlyAuthor: (Boolean) -> Unit,
) {
    val preferences = LocalReadingPreferences.current
    val metrics = readerScaledTextMetrics(
        baseFontSize = MaterialTheme.typography.labelLarge.fontSize.value,
        baseLineHeight = MaterialTheme.typography.labelLarge.lineHeight.value,
        fontScale = preferences.fontSize.scale,
        lineSpacingMultiplier = preferences.lineSpacing.multiplier,
    )
    val textStyle = MaterialTheme.typography.labelLarge.copy(
        fontSize = metrics.fontSize.sp,
        lineHeight = metrics.lineHeight.sp,
    )
    val controlHeight = replyFilterControlHeight(metrics).dp
    ReaderSectionBand {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val stacked = replyFiltersUseStackedLayout(maxWidth.value, preferences.fontSize.scale)
            val authorFilters: @Composable () -> Unit = {
              Row(verticalAlignment = Alignment.CenterVertically) {
                CompactFilterButton(
                    text = "全部回复",
                    selected = !onlyAuthor,
                    height = controlHeight,
                    textStyle = textStyle,
                    onClick = { onOnlyAuthor(false) },
                )
                CompactFilterButton(
                    text = "只看楼主",
                    selected = onlyAuthor,
                    height = controlHeight,
                    textStyle = textStyle,
                    onClick = { onOnlyAuthor(true) },
                )
              }
            }
            val sortFilters: @Composable () -> Unit = {
              Surface(
                  shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
                  color = MaterialTheme.colorScheme.surfaceContainerHigh,
              ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ThreadReplySort.entries.forEach { value ->
                        CompactFilterButton(
                            text = value.displayName,
                            selected = sort == value,
                            height = controlHeight,
                            textStyle = textStyle,
                            onClick = { onSort(value) },
                            selectedSurface = true,
                        )
                    }
                }
              }
            }
            if (stacked) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    authorFilters()
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { sortFilters() }
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    authorFilters()
                    Spacer(Modifier.weight(1f))
                    sortFilters()
                }
            }
        }
    }
}

internal fun replyFiltersUseStackedLayout(availableWidthDp: Float, fontScale: Float): Boolean =
    availableWidthDp < 304f * fontScale.coerceAtLeast(1f)

@Composable
private fun CompactFilterButton(
    text: String,
    selected: Boolean,
    height: androidx.compose.ui.unit.Dp,
    textStyle: androidx.compose.ui.text.TextStyle,
    onClick: () -> Unit,
    selectedSurface: Boolean = false,
) {
    Surface(
        onClick = onClick,
        modifier = Modifier.height(height),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(if (selectedSurface) 7.dp else 0.dp),
        color = if (selected && selectedSurface) MaterialTheme.colorScheme.surface else androidx.compose.ui.graphics.Color.Transparent,
        contentColor = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = if (selectedSurface) 9.dp else 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = text,
                style = textStyle,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun ThreadFooter(
    state: ThreadUiState,
    onLoadMore: () -> Unit,
    onRetry: () -> Unit,
) {
    Box(Modifier.fillMaxWidth().height(64.dp), contentAlignment = Alignment.Center) {
        when (
            threadFooterContent(
                hasPage = state.page != null,
                isLoadingMore = state.isLoadingMore,
                hasMore = state.page?.hasMore == true,
                hasError = state.errorMessage != null,
            )
        ) {
            ThreadFooterContent.Loading -> CircularProgressIndicator()
            ThreadFooterContent.Error -> TextButton(onClick = onRetry) { Text("加载失败，点击重试") }
            ThreadFooterContent.LoadMore -> TextButton(onClick = onLoadMore) { Text("加载更多回复") }
            ThreadFooterContent.End -> Text(
                "没有更多回复了",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            null -> Unit
        }
    }
}

@Composable
private fun ThreadForumTitle(
    forum: Forum?,
    onForumClick: ((Forum) -> Unit)?,
) {
    if (forum == null) {
        Text("帖子", maxLines = 1)
        return
    }

    val forumName = forum.displayName.ifBlank { forum.name }.ifBlank { "贴吧" }
    val navigableForum = normalizedThreadForumRoute(forum)
    val forumClick = onForumClick?.let { callback ->
        navigableForum?.let { target -> { callback(target) } }
    }
    Row(
        modifier = Modifier
            .heightIn(min = 48.dp)
            .testTag("thread-forum-title")
            .then(
                if (forumClick == null) Modifier else Modifier.clickable(onClick = forumClick),
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AvatarImage(
            url = forum.avatarUrl,
            name = forumName,
            modifier = Modifier.size(28.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            forumName,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (forumClick != null) {
            Spacer(Modifier.width(2.dp))
            Icon(
                Icons.Outlined.ChevronRight,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SubpostSheet(
    state: SubpostUiState,
    capabilities: ThreadCapabilities,
    threadAuthorId: Long?,
    onClose: () -> Unit,
    onReplyParent: ((Post) -> Unit)?,
    onReply: ((Subpost) -> Unit)?,
    onUserClick: (Long) -> Unit,
    onLinkClick: (String) -> Unit,
    onImagesClick: (List<ImageContent>, Int) -> Unit,
    onVideoClick: (VideoContent) -> Unit,
    onLoadMore: () -> Unit,
    onRetry: () -> Unit,
    updatingLikeTargets: Set<ThreadLikeTarget>,
    unknownLikeTargets: Set<ThreadLikeTarget>,
    onTogglePostLike: (Post) -> Unit,
    onToggleSubpostLike: (Subpost) -> Unit,
) {
    LazyColumn(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        item(key = "title") {
            ReaderCard(cornerRadius = 0.dp, contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 16.dp, end = 8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "${state.parent.floor}楼的回复（${maxOf(state.parent.subpostCount, state.items.size)}条）",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = onClose) {
                        Icon(Icons.Outlined.Close, contentDescription = "关闭楼中楼")
                    }
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
        item(key = "parent") {
            ReaderCard(cornerRadius = 0.dp) {
              Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                AuthorLine(
                    author = state.parent.author,
                    floor = state.parent.floor,
                    likeCount = state.parent.likeCount,
                    isLiked = state.parent.isLiked,
                    isLikeUpdating = ThreadLikeTarget(
                        state.parent.id,
                        state.parent.likeObjectType,
                    ) in updatingLikeTargets,
                    onToggleLike = if (
                        !capabilities.canLike ||
                        ThreadLikeTarget(state.parent.id, state.parent.likeObjectType) in unknownLikeTargets
                    ) null else ({ onTogglePostLike(state.parent) }),
                    onUserClick = onUserClick,
                    isThreadAuthor = threadAuthorId != null && threadAuthorId > 0L && state.parent.author.id == threadAuthorId,
                )
                PostBody(
                    post = state.parent,
                    onReply = onReplyParent,
                    onUserClick = onUserClick,
                    onLinkClick = onLinkClick,
                    onImagesClick = onImagesClick,
                    onVideoClick = onVideoClick,
                    onOpenSubposts = {},
                    modifier = Modifier.padding(start = 48.dp),
                    threadAuthorId = threadAuthorId,
                    showSubpostPreview = false,
                )
              }
            }
            ReaderSectionBand(Modifier.height(12.dp)) {}
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
        items(state.items, key = { "subpost-${it.id}" }) { item ->
            SubpostRow(
                subpost = item,
                onReply = onReply,
                onUserClick = onUserClick,
                onLinkClick = onLinkClick,
                onImagesClick = onImagesClick,
                onVideoClick = onVideoClick,
                isLikeUpdating = ThreadLikeTarget(
                    item.id,
                    dev.infinityf4p.tiebapure.core.model.TiebaLikeObjectType.Subpost,
                ) in updatingLikeTargets,
                onToggleLike = if (
                    !capabilities.canLike || ThreadLikeTarget(
                        item.id,
                        dev.infinityf4p.tiebapure.core.model.TiebaLikeObjectType.Subpost,
                    ) in unknownLikeTargets
                ) null else ({ onToggleSubpostLike(item) }),
                threadAuthorId = threadAuthorId,
            )
        }
        item(key = "nested-footer") {
            Box(Modifier.fillMaxWidth().height(64.dp), contentAlignment = Alignment.Center) {
                when {
                    state.isLoading -> CircularProgressIndicator()
                    state.errorMessage != null -> TextButton(onClick = onRetry) { Text("加载失败，点击重试") }
                    state.hasMore -> TextButton(onClick = onLoadMore) { Text("加载更多回复") }
                    state.items.isEmpty() -> Text("暂无楼中楼回复", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun LoadingState() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
}

@Composable
private fun ErrorState(message: String?, onRetry: () -> Unit) {
    Column(
        Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(message ?: "帖子加载失败", color = MaterialTheme.colorScheme.onSurfaceVariant)
        TextButton(onClick = onRetry) { Text("重试") }
    }
}

private val ThreadReplySort.displayName: String
    get() = when (this) {
        ThreadReplySort.Hot -> "热门"
        ThreadReplySort.Ascending -> "正序"
        ThreadReplySort.Descending -> "倒序"
    }

private const val THREAD_REPLY_ITEM_OFFSET = 2
private const val THREAD_REPLY_FILTER_ITEM_INDEX = 1

private val Post.likeObjectType: dev.infinityf4p.tiebapure.core.model.TiebaLikeObjectType
    get() = if (floor == 1) {
        dev.infinityf4p.tiebapure.core.model.TiebaLikeObjectType.Thread
    } else {
        dev.infinityf4p.tiebapure.core.model.TiebaLikeObjectType.Post
    }

private fun String.postIdFromListKey(): ULong? =
    removePrefix("post-").takeIf { it.length != length }?.toULongOrNull()
