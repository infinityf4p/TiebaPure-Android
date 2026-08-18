package dev.infinityf4p.tiebapure.feature.account

import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.infinityf4p.tiebapure.core.designsystem.ReaderState
import dev.infinityf4p.tiebapure.core.designsystem.ReaderStatePane
import dev.infinityf4p.tiebapure.core.model.AccountThreadFavorite
import dev.infinityf4p.tiebapure.core.model.BrowsingHistoryEntry

@Composable
fun ThreadFavoritesRoute(
    viewModel: ThreadFavoritesViewModel,
    onBack: () -> Unit,
    onLogin: () -> Unit,
    onOpenFavorite: (AccountThreadFavorite) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    ThreadFavoritesScreen(
        state,
        onBack,
        onLogin,
        onOpenFavorite,
        viewModel::setSearchText,
        viewModel::setProgressFilter,
        viewModel::setSelecting,
        viewModel::toggleSelection,
        viewModel::refresh,
        viewModel::loadMore,
        viewModel::removeSelected,
        modifier,
    )
}

@Composable
fun ThreadFavoritesScreen(
    state: ThreadFavoritesUiState,
    onBack: () -> Unit,
    onLogin: () -> Unit,
    onOpenFavorite: (AccountThreadFavorite) -> Unit,
    onSearchTextChange: (String) -> Unit,
    onFilterChange: (FavoriteProgressFilter) -> Unit,
    onSetSelecting: (Boolean) -> Unit,
    onToggleSelection: (Long) -> Unit,
    onRetry: () -> Unit,
    onLoadMore: () -> Unit,
    onRemoveSelected: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var confirmsDeletion by remember { mutableStateOf(false) }
    Column(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        AccountScreenHeader(
            title = "帖子收藏",
            onBack = onBack,
            actionLabel = if (state.isSelecting) "完成" else "管理",
            actionEnabled = state.favorites.isNotEmpty() && !state.isRemoving,
            onAction = { onSetSelecting(!state.isSelecting) },
        )
        if (!state.isLoggedIn) {
            LoginRequiredPane("登录后查看收藏", "账号收藏由百度同步，登录后可以在这里管理。", onLogin)
            return@Column
        }
        if (state.favorites.isNotEmpty()) {
            LibrarySearchBar(
                searchText = state.searchText,
                onSearchTextChange = onSearchTextChange,
            )
        }
        when {
            state.isInitialLoading -> ReaderStatePane(ReaderState.Loading("正在加载收藏"))
            state.favorites.isEmpty() && state.errorMessage != null -> ReaderStatePane(
                ReaderState.Error("收藏加载失败", state.errorMessage),
                onRetry = onRetry,
            )
            state.favorites.isEmpty() -> AccountStatePane(
                ReaderState.Empty(favoriteEmptyState(state).first, favoriteEmptyState(state).second),
            )
            else -> LazyColumn(Modifier.weight(1f).background(MaterialTheme.colorScheme.background)) {
                item(key = "favorite-filter") {
                    LibraryFilterRow(
                        filters = FavoriteProgressFilter.entries.map { it.title },
                        selectedIndex = state.progressFilter.ordinal,
                        onSelectedIndex = { onFilterChange(FavoriteProgressFilter.entries[it]) },
                    )
                }
                if (state.visibleFavorites.isEmpty()) {
                    item(key = "favorite-empty") {
                        LibraryEmptyItem(
                            title = favoriteEmptyState(state).first,
                            message = favoriteEmptyState(state).second,
                            actionLabel = "加载更多收藏".takeIf { state.hasMore },
                            onAction = onLoadMore.takeIf { state.hasMore },
                        )
                    }
                } else {
                    items(state.visibleFavorites, key = { it.favorite.threadId }) { item ->
                        SelectableLibraryRow(
                            title = item.favorite.title.ifBlank { "无标题" },
                            metadata = listOfNotNull(
                                item.favorite.forumName.takeIf(String::isNotBlank),
                                item.favorite.authorDisplayName.takeIf(String::isNotBlank),
                                "${item.favorite.replyCount} 回复",
                                if (item.hasReadingPosition) "有阅读进度" else null,
                                if (item.favorite.threadId in state.unknownRemovalThreadIds) "移除结果待确认" else null,
                            ).joinToString(" · "),
                            selecting = state.isSelecting,
                            selected = item.favorite.threadId in state.selectedThreadIds,
                            onClick = {
                                if (state.isSelecting) onToggleSelection(item.favorite.threadId) else onOpenFavorite(item.favorite)
                            },
                        )
                    }
                    item { PaginationFooter(state.isLoadingMore, state.hasMore, state.errorMessage, onLoadMore) }
                }
            }
        }
        if (state.isSelecting) {
            SelectionBar(
                selectedCount = state.selectedThreadIds.size,
                enabled = !state.isRemoving && state.selectedThreadIds.none {
                    it in state.unknownRemovalThreadIds
                },
                onDelete = { confirmsDeletion = true },
            )
        }
    }
    if (confirmsDeletion) {
        AlertDialog(
            onDismissRequest = { confirmsDeletion = false },
            title = { Text("移除收藏") },
            text = { Text("将从账号收藏中移除选中的 ${state.selectedThreadIds.size} 条帖子。") },
            confirmButton = {
                TextButton(onClick = { confirmsDeletion = false; onRemoveSelected() }) { Text("移除") }
            },
            dismissButton = { TextButton(onClick = { confirmsDeletion = false }) { Text("取消") } },
        )
    }
}

@Composable
fun BrowsingHistoryRoute(
    viewModel: BrowsingHistoryViewModel,
    onBack: () -> Unit,
    onOpenEntry: (BrowsingHistoryEntry) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    BrowsingHistoryScreen(
        state,
        onBack,
        onOpenEntry,
        viewModel::setSearchText,
        viewModel::setDateFilter,
        viewModel::setSelecting,
        viewModel::toggleSelection,
        viewModel::removeSelected,
        viewModel::clear,
        modifier,
    )
}

@Composable
fun BrowsingHistoryScreen(
    state: BrowsingHistoryUiState,
    onBack: () -> Unit,
    onOpenEntry: (BrowsingHistoryEntry) -> Unit,
    onSearchTextChange: (String) -> Unit,
    onFilterChange: (HistoryDateFilter) -> Unit,
    onSetSelecting: (Boolean) -> Unit,
    onToggleSelection: (Long) -> Unit,
    onRemoveSelected: () -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var confirmsDeletion by remember { mutableStateOf(false) }
    var confirmsClear by remember { mutableStateOf(false) }
    Column(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        AccountScreenHeader(
            title = "浏览历史",
            onBack = onBack,
            actionLabel = if (state.isSelecting) "完成" else "管理",
            actionEnabled = state.entries.isNotEmpty() && !state.isMutating,
            onAction = { onSetSelecting(!state.isSelecting) },
        )
        if (state.entries.isNotEmpty()) {
            LibrarySearchBar(
                searchText = state.searchText,
                onSearchTextChange = onSearchTextChange,
            )
        }
        when {
            state.isLoading -> ReaderStatePane(ReaderState.Loading("正在读取浏览历史"))
            state.entries.isEmpty() && state.errorMessage != null -> ReaderStatePane(
                ReaderState.Error("历史记录不可用", state.errorMessage),
            )
            state.entries.isEmpty() -> AccountStatePane(
                ReaderState.Empty(historyEmptyState(state).first, historyEmptyState(state).second),
            )
            else -> LazyColumn(Modifier.weight(1f).background(MaterialTheme.colorScheme.background)) {
                item(key = "history-filter") {
                    LibraryFilterRow(
                        filters = HistoryDateFilter.entries.map { it.title },
                        selectedIndex = state.dateFilter.ordinal,
                        onSelectedIndex = { onFilterChange(HistoryDateFilter.entries[it]) },
                    )
                }
                if (state.visibleEntries.isEmpty()) {
                    item(key = "history-empty") {
                        LibraryEmptyItem(historyEmptyState(state).first, historyEmptyState(state).second)
                    }
                } else {
                    items(state.visibleEntries, key = { it.thread.id }) { entry ->
                        SelectableLibraryRow(
                            title = entry.thread.title.ifBlank { "无标题" },
                            metadata = listOfNotNull(
                                entry.thread.forumName,
                                entry.thread.author.resolvedDisplayName,
                                compactDate(entry.visitedAtEpochMilliseconds / 1_000),
                            ).joinToString(" · "),
                            selecting = state.isSelecting,
                            selected = entry.thread.id in state.selectedThreadIds,
                            onClick = {
                                if (state.isSelecting) onToggleSelection(entry.thread.id) else onOpenEntry(entry)
                            },
                        )
                    }
                }
            }
        }
        if (state.isSelecting) {
            SelectionBar(
                selectedCount = state.selectedThreadIds.size,
                enabled = !state.isMutating,
                onDelete = { confirmsDeletion = true },
                secondaryLabel = "清空全部",
                onSecondary = { confirmsClear = true },
            )
        }
    }
    if (confirmsDeletion) {
        ConfirmDeleteDialog(
            title = "删除历史记录",
            message = "删除选中的 ${state.selectedThreadIds.size} 条浏览记录？",
            onDismiss = { confirmsDeletion = false },
            onConfirm = { confirmsDeletion = false; onRemoveSelected() },
        )
    }
    if (confirmsClear) {
        ConfirmDeleteDialog(
            title = "清空浏览历史",
            message = "这会删除本机保存的全部浏览记录。",
            onDismiss = { confirmsClear = false },
            onConfirm = { confirmsClear = false; onClear() },
        )
    }
}

@Composable
private fun LibrarySearchBar(
    searchText: String,
    onSearchTextChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = searchText,
        onValueChange = onSearchTextChange,
        modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        singleLine = true,
        leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
        placeholder = { Text("搜索标题、作者或贴吧") },
    )
}

@Composable
private fun LibraryFilterRow(
    filters: List<String>,
    selectedIndex: Int,
    onSelectedIndex: (Int) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        filters.forEachIndexed { index, title ->
            FilterChip(
                selected = selectedIndex == index,
                onClick = { onSelectedIndex(index) },
                label = { Text(title) },
                modifier = Modifier.weight(1f).heightIn(min = 48.dp),
            )
        }
    }
}

@Composable
private fun LibraryEmptyItem(
    title: String,
    message: String,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    AccountEmptyPane(
        title = title,
        message = message,
        modifier = Modifier.fillMaxWidth().heightIn(min = 220.dp),
        actionLabel = actionLabel,
        onAction = onAction,
    )
}

@Composable
private fun SelectableLibraryRow(
    title: String,
    metadata: String,
    selecting: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface)
            .heightIn(min = 56.dp).clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selecting) {
            Icon(
                if (selected) Icons.Outlined.CheckCircle else Icons.Outlined.RadioButtonUnchecked,
                contentDescription = if (selected) "已选择" else "未选择",
                tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(end = 12.dp),
            )
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(metadata, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
    HorizontalDivider(modifier = Modifier.padding(start = if (selecting) 52.dp else 16.dp))
}

@Composable
private fun SelectionBar(
    selectedCount: Int,
    enabled: Boolean,
    onDelete: () -> Unit,
    secondaryLabel: String? = null,
    onSecondary: (() -> Unit)? = null,
) {
    Row(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 12.dp, vertical = 6.dp).heightIn(min = 50.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("已选 $selectedCount 条", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        if (secondaryLabel != null && onSecondary != null) {
            TextButton(
                onClick = onSecondary,
                enabled = enabled,
                modifier = Modifier.heightIn(min = 48.dp),
            ) { Text(secondaryLabel) }
        }
        TextButton(
            onClick = onDelete,
            enabled = enabled && selectedCount > 0,
            modifier = Modifier.heightIn(min = 48.dp),
        ) { Text("删除") }
    }
}

@Composable
private fun LoginRequiredPane(title: String, message: String, onLogin: () -> Unit) {
    AccountEmptyPane(title, message, actionLabel = "去登录", onAction = onLogin)
}

internal fun favoriteEmptyState(state: ThreadFavoritesUiState): Pair<String, String> = when {
    state.favorites.isEmpty() -> "暂无帖子收藏" to "在帖子页点击右上角的收藏按钮后，会显示在这里。"
    state.searchText.isNotBlank() || state.progressFilter != FavoriteProgressFilter.All ->
        "没有匹配的帖子收藏" to "尝试调整搜索内容或阅读进度筛选。"
    else -> "没有可显示的帖子收藏" to "已按你的屏蔽设置隐藏相关收藏。"
}

internal fun historyEmptyState(state: BrowsingHistoryUiState): Pair<String, String> = when {
    state.entries.isEmpty() -> "暂无浏览历史" to "成功打开过的帖子会显示在这里。"
    state.searchText.isNotBlank() || state.dateFilter != HistoryDateFilter.All ->
        "没有匹配的浏览历史" to "尝试调整搜索内容或时间范围。"
    else -> "没有可显示的浏览历史" to "已按你的屏蔽设置隐藏相关记录。"
}

@Composable
private fun ConfirmDeleteDialog(title: String, message: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = { TextButton(onClick = onConfirm) { Text("删除") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
