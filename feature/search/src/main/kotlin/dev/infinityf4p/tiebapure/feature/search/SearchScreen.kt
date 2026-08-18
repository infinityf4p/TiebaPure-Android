package dev.infinityf4p.tiebapure.feature.search

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Clear
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.ThumbUp
import androidx.compose.material.icons.outlined.ThumbUpOffAlt
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.infinityf4p.tiebapure.core.designsystem.ReaderCard
import dev.infinityf4p.tiebapure.core.designsystem.ReaderInteractionStats
import dev.infinityf4p.tiebapure.core.designsystem.ReaderSectionBand
import dev.infinityf4p.tiebapure.core.designsystem.ThreadMediaPreview
import dev.infinityf4p.tiebapure.core.designsystem.TiebaPureTheme
import dev.infinityf4p.tiebapure.core.media.AvatarImage
import dev.infinityf4p.tiebapure.core.media.RemoteImage
import dev.infinityf4p.tiebapure.core.model.ContentBlock
import dev.infinityf4p.tiebapure.core.model.Forum
import dev.infinityf4p.tiebapure.core.model.ReaderMediaLoadingPolicy
import dev.infinityf4p.tiebapure.core.model.ThreadSummary
import dev.infinityf4p.tiebapure.core.model.UserSummary
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun SearchRoute(
    viewModel: SearchViewModel,
    callbacks: SearchCallbacks = SearchCallbacks(),
    mediaLoadingPolicy: ReaderMediaLoadingPolicy = ReaderMediaLoadingPolicy.Automatic,
    modifier: Modifier = Modifier,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    SearchScreen(
        uiState = uiState,
        callbacks = callbacks,
        onInputChanged = viewModel::updateInput,
        onSubmit = viewModel::submit,
        onClearQuery = viewModel::clearQuery,
        onSelectHistory = viewModel::selectHistory,
        onRemoveHistory = viewModel::removeHistory,
        onClearHistory = viewModel::clearHistory,
        onSelectFilter = viewModel::selectFilter,
        onSelectSort = viewModel::selectSort,
        onRefresh = { viewModel.refresh() },
        onLoadMore = viewModel::loadMore,
        onRetry = viewModel::retry,
        mediaLoadingPolicy = mediaLoadingPolicy,
        modifier = modifier,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    uiState: SearchUiState,
    callbacks: SearchCallbacks,
    onInputChanged: (String) -> Unit,
    onSubmit: () -> Unit,
    onClearQuery: () -> Unit,
    onSelectHistory: (String) -> Unit,
    onRemoveHistory: (String) -> Unit,
    onClearHistory: () -> Unit,
    onSelectFilter: (SearchFilter) -> Unit,
    onSelectSort: (SearchSort) -> Unit,
    onRefresh: () -> Unit,
    onLoadMore: () -> Unit,
    onRetry: () -> Unit = onRefresh,
    mediaLoadingPolicy: ReaderMediaLoadingPolicy = ReaderMediaLoadingPolicy.Automatic,
    modifier: Modifier = Modifier,
) {
    val focusRequester = remember { FocusRequester() }
    Column(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 4.dp, end = 12.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(callbacks.onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回") }
            OutlinedTextField(
                value = uiState.input,
                onValueChange = onInputChanged,
                singleLine = true,
                placeholder = { Text(uiState.scope.prompt) },
                leadingIcon = { Icon(Icons.Outlined.Search, null) },
                trailingIcon = if (uiState.input.isNotEmpty()) {
                    { IconButton(onClearQuery) { Icon(Icons.Outlined.Close, "清空搜索") } }
                } else null,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSubmit() }),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.weight(1f).height(48.dp).focusRequester(focusRequester).testTag("search-input"),
            )
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        if (uiState.showsHistory) {
            SearchHistory(uiState.history, onSelectHistory, onRemoveHistory, onClearHistory)
        } else {
            SearchResultsContent(
                uiState,
                callbacks,
                onSelectFilter,
                onSelectSort,
                onRefresh,
                onLoadMore,
                onRetry,
                mediaLoadingPolicy,
            )
        }
    }
}

@Composable
private fun SearchHistory(
    history: List<String>,
    onSelect: (String) -> Unit,
    onRemove: (String) -> Unit,
    onClear: () -> Unit,
) {
    LazyColumn(Modifier.fillMaxSize().testTag("search-history")) {
        item {
            Row(Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.History, null, Modifier.size(20.dp))
                Text("搜索历史", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 8.dp))
                Spacer(Modifier.weight(1f))
                if (history.isNotEmpty()) TextButton(onClick = onClear) { Text("清空") }
            }
        }
        if (history.isEmpty()) {
            item {
                Box(Modifier.fillMaxWidth().height(120.dp), Alignment.Center) {
                    Text("暂无搜索历史", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        } else itemsIndexed(history, key = { _, item -> item }) { _, keyword ->
            Row(
                modifier = Modifier.fillMaxWidth().height(48.dp).clickable { onSelect(keyword) }.padding(start = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(keyword, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                IconButton({ onRemove(keyword) }) { Icon(Icons.Outlined.Clear, "删除$keyword") }
            }
            HorizontalDivider(Modifier.padding(start = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchResultsContent(
    state: SearchUiState,
    callbacks: SearchCallbacks,
    onSelectFilter: (SearchFilter) -> Unit,
    onSelectSort: (SearchSort) -> Unit,
    onRefresh: () -> Unit,
    onLoadMore: () -> Unit,
    onRetry: () -> Unit,
    mediaLoadingPolicy: ReaderMediaLoadingPolicy,
) {
    PullToRefreshBox(
        isRefreshing = state.isRefreshing,
        onRefresh = onRefresh,
        modifier = Modifier.fillMaxSize().testTag("search-results"),
    ) {
        LazyColumn(Modifier.fillMaxSize()) {
            item(key = "controls") {
                ReaderSectionBand(Modifier.testTag("search-result-controls")) {
                    SearchControls(state.filter, state.sort, onSelectFilter, onSelectSort)
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
            when {
                state.isInitialLoading -> item { Box(Modifier.fillMaxWidth().height(180.dp), Alignment.Center) { CircularProgressIndicator() } }
                state.errorMessage != null && state.items.isEmpty() -> item {
                    Box(Modifier.fillMaxWidth().height(132.dp), Alignment.Center) {
                        if (state.showsEmptyPageContinuation) {
                            Text(state.errorMessage, color = MaterialTheme.colorScheme.error)
                        } else {
                            TextButton(onClick = onRetry) { Text("${state.errorMessage} 点击重试") }
                        }
                    }
                }
                state.items.isEmpty() -> item {
                    Column(
                        Modifier.fillMaxWidth().height(132.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Text(if (state.hasMore) "当前页暂无可显示结果" else "没有结果", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "可调整范围或排序后重试。",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }
                else -> itemsIndexed(state.items, key = { _, item -> item.stableId }) { index, item ->
                    when (item) {
                        is SearchItem.ThreadResult -> SearchThreadRow(
                            item,
                            callbacks,
                            mediaLoadingPolicy,
                            Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                        )
                        is SearchItem.UserResult -> SearchUserRow(
                            item.user,
                            callbacks.onOpenUser,
                            Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                        )
                    }
                    if (index >= state.items.lastIndex - 4 && state.hasMore) {
                        LaunchedEffect(item.stableId) { onLoadMore() }
                    }
                }
            }
            if (state.showsEmptyPageContinuation) {
                item(key = "empty-page-continuation") {
                    EmptyPageContinuation(
                        isLoading = state.isRefreshing || state.isLoadingMore,
                        error = state.errorMessage,
                        onRetry = onRetry,
                        onLoadMore = onLoadMore,
                    )
                }
            }
            if (state.items.isNotEmpty()) {
                item(key = "footer") {
                    Box(Modifier.fillMaxWidth().height(64.dp), Alignment.Center) {
                        when {
                            state.isLoadingMore -> CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                            state.errorMessage != null -> TextButton(onClick = onRetry) { Text("加载失败，点击重试") }
                            state.hasMore -> TextButton(onClick = onLoadMore) { Text("加载更多结果") }
                            !state.hasMore -> Text("没有更多了", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyPageContinuation(
    isLoading: Boolean,
    error: String?,
    onRetry: () -> Unit,
    onLoadMore: () -> Unit,
) {
    val modifier = Modifier
        .fillMaxWidth()
        .height(48.dp)
        .testTag("search-empty-page-load-more")
    if (isLoading) {
        Box(modifier, contentAlignment = Alignment.Center) {
            CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
        }
    } else {
        TextButton(onClick = if (error == null) onLoadMore else onRetry, modifier = modifier) {
            Text(if (error == null) "继续加载" else "加载失败，点击重试")
        }
    }
}

@Composable
private fun SearchControls(
    filter: SearchFilter,
    sort: SearchSort,
    onSelectFilter: (SearchFilter) -> Unit,
    onSelectSort: (SearchSort) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            SearchFilter.entries.forEach { item ->
                Box(
                    modifier = Modifier.height(48.dp).clickable { onSelectFilter(item) },
                    contentAlignment = Alignment.Center,
                ) {
                    Surface(
                        color = if (item == filter) MaterialTheme.colorScheme.primary.copy(alpha = .1f) else Color.Transparent,
                        contentColor = if (item == filter) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.height(32.dp),
                    ) {
                        Box(Modifier.padding(horizontal = 12.dp), Alignment.Center) {
                            Text(if (item == SearchFilter.All) "全部" else "主题", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }
        }
        Spacer(Modifier.weight(1f))
        var expanded by remember { mutableStateOf(false) }
        Box {
            Row(
                modifier = Modifier.height(48.dp).clickable { expanded = true }.padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(sort.title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Icon(Icons.Outlined.ArrowDropDown, null)
            }
            DropdownMenu(expanded, { expanded = false }) {
                SearchSort.entries.reversed().forEach { item ->
                    DropdownMenuItem(
                        text = { Text(item.title) },
                        onClick = { expanded = false; onSelectSort(item) },
                    )
                }
            }
        }
    }
}

private val SearchSort.title: String
    get() = when (this) {
        SearchSort.Latest -> "最新"
        SearchSort.Relevance -> "相关"
        SearchSort.Oldest -> "最旧"
    }

private val SearchScope.prompt: String
    get() = when (this) {
        SearchScope.Global -> "搜索帖子或回复"
        is SearchScope.ForumOnly -> "搜索本吧帖子或回复"
    }

@Composable
private fun SearchThreadRow(
    result: SearchItem.ThreadResult,
    callbacks: SearchCallbacks,
    mediaLoadingPolicy: ReaderMediaLoadingPolicy,
    modifier: Modifier = Modifier,
) {
    val thread = result.thread
    val forum = thread.forumRoute()
    ReaderCard(modifier = modifier) {
        Column(
            Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SearchThreadIdentityHeader(thread, forum, callbacks)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .clickable { callbacks.onOpenThread(result) },
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (thread.title.isNotBlank()) {
                    Text(
                        thread.title,
                        style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                thread.textPreview.takeIf(String::isNotBlank)?.let { preview ->
                    Text(
                        preview,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = if (thread.title.isBlank()) 3 else 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            SearchThreadBadges(thread)
            ThreadMediaPreview(
                blocks = thread.blocks,
                mediaLoadingPolicy = mediaLoadingPolicy,
                onItemClick = { callbacks.onOpenMedia(thread, it.blockIndex) },
            ) { url, description, modifier ->
                RemoteImage(url = url, contentDescription = description, modifier = modifier)
            }
            ReaderInteractionStats(
                comments = thread.replyCount,
                likes = thread.likeCount,
                isLiked = thread.isLiked,
                commentsIcon = Icons.Outlined.ChatBubbleOutline,
                likesIcon = Icons.Outlined.ThumbUpOffAlt,
                likedIcon = Icons.Outlined.ThumbUp,
            )
        }
    }
}

@Composable
private fun SearchThreadIdentityHeader(
    thread: ThreadSummary,
    forum: Forum?,
    callbacks: SearchCallbacks,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clickable(enabled = forum != null || thread.author.id > 0L) {
                    if (forum != null) callbacks.onOpenForum(forum) else callbacks.onOpenUser(thread.author)
                },
            contentAlignment = Alignment.Center,
        ) {
            AvatarImage(
                url = forum?.avatarUrl ?: thread.author.portrait,
                name = forum?.displayName ?: thread.author.resolvedDisplayName,
                modifier = Modifier.size(32.dp),
            )
        }
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(
                forum?.displayName ?: thread.author.resolvedDisplayName,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.clickable(enabled = forum != null || thread.author.id > 0L) {
                    if (forum != null) callbacks.onOpenForum(forum) else callbacks.onOpenUser(thread.author)
                },
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (forum != null) {
                    Text(
                        thread.author.resolvedDisplayName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.clickable(enabled = thread.author.id > 0L) { callbacks.onOpenUser(thread.author) },
                    )
                }
                compactSearchThreadTime(thread.lastReplyAtEpochSeconds)?.let { time ->
                    Text(
                        if (forum == null) time else " · $time",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

@Composable
private fun SearchThreadBadges(thread: ThreadSummary) {
    if (!thread.isTop && !thread.isGood) return
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        if (thread.isTop) SearchThreadBadge("置顶")
        if (thread.isGood) SearchThreadBadge("精品")
    }
}

@Composable
private fun SearchThreadBadge(label: String) {
    Surface(
        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f),
        contentColor = MaterialTheme.colorScheme.primary,
        shape = RoundedCornerShape(4.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
    }
}

@Composable
private fun SearchUserRow(
    user: UserSummary,
    onOpen: (UserSummary) -> Unit,
    modifier: Modifier = Modifier,
) {
    ReaderCard(modifier = modifier) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable(enabled = user.id > 0L) { onOpen(user) },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            AvatarImage(
                url = user.portrait,
                name = user.resolvedDisplayName,
                modifier = Modifier.size(40.dp),
            )
            Column {
                Text(user.resolvedDisplayName, style = MaterialTheme.typography.titleSmall)
                Text("用户", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

internal fun compactSearchThreadTime(
    epochSeconds: Long?,
    nowEpochSeconds: Long = System.currentTimeMillis() / 1_000,
): String? {
    val timestamp = epochSeconds?.takeIf { it > 0L } ?: return null
    val elapsed = (nowEpochSeconds - timestamp).coerceAtLeast(0L)
    return when {
        elapsed < 60 -> "刚刚"
        elapsed < 3_600 -> "${elapsed / 60}分钟前"
        elapsed < 86_400 -> "${elapsed / 3_600}小时前"
        elapsed < 7 * 86_400 -> "${elapsed / 86_400}天前"
        else -> SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(Date(timestamp * 1_000))
    }
}

private val previewSearchItems = listOf(
    SearchItem.ThreadResult(
        ThreadSummary(
            id = 1,
            title = "搜索框固定在滚动区外",
            author = UserSummary(1, "u", "示例作者", ""),
            forumName = "Android",
            replyCount = 18,
            viewCount = 90,
            blocks = listOf(ContentBlock.Text("筛选栏保持紧凑，并把范围和排序分列左右。")),
        ),
    ),
    SearchItem.UserResult(UserSummary(2, "sample", "示例用户", "")),
)

@Preview(showBackground = true, widthDp = 390, heightDp = 844)
@Composable
private fun SearchScreenPreview() {
    TiebaPureTheme {
        SearchScreen(
            uiState = SearchUiState(input = "搜索", submittedKeyword = "搜索", items = previewSearchItems, hasMore = false),
            callbacks = SearchCallbacks(),
            onInputChanged = {}, onSubmit = {}, onClearQuery = {}, onSelectHistory = {},
            onRemoveHistory = {}, onClearHistory = {}, onSelectFilter = {}, onSelectSort = {},
            onRefresh = {}, onLoadMore = {},
        )
    }
}
