package dev.infinityf4p.tiebapure.feature.account

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.infinityf4p.tiebapure.core.designsystem.ReaderState
import dev.infinityf4p.tiebapure.core.model.ThreadSummary

@Composable
fun FollowingUpdatesRoute(
    viewModel: FollowingUpdatesViewModel,
    onBack: () -> Unit,
    onLogin: () -> Unit,
    onOpenThread: (ThreadSummary) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    FollowingUpdatesScreen(
        state = state,
        onBack = onBack,
        onLogin = onLogin,
        onOpenThread = onOpenThread,
        onRefresh = viewModel::refresh,
        onLoadMore = viewModel::loadMore,
        onRetry = viewModel::retry,
        modifier = modifier,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FollowingUpdatesScreen(
    state: FollowingUpdatesUiState,
    onBack: () -> Unit,
    onLogin: () -> Unit,
    onOpenThread: (ThreadSummary) -> Unit,
    onRefresh: () -> Unit,
    onLoadMore: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        AccountScreenHeader("关注更新", onBack)
        if (!state.isLoggedIn) {
            AccountEmptyPane(
                title = "登录后查看关注更新",
                message = "登录后可以查看关注用户发布的新帖子。",
                actionLabel = "去登录",
                onAction = onLogin,
            )
            return@Column
        }
        PullToRefreshBox(
            isRefreshing = state.isRefreshing,
            onRefresh = onRefresh,
            modifier = Modifier.fillMaxSize(),
        ) {
            when {
                state.isInitialLoading -> AccountStatePane(ReaderState.Loading("正在加载关注更新"))
                state.threads.isEmpty() && state.errorMessage != null -> AccountStatePane(
                    ReaderState.Error("关注更新加载失败", state.errorMessage),
                    onRetry = onRetry,
                )
                state.threads.isEmpty() -> AccountEmptyPane(
                    title = if (state.followedUserCount == 0 && !state.hasMore) "暂无关注用户" else "暂无新帖子",
                    message = if (state.followedUserCount == 0 && !state.hasMore) {
                        "关注用户后，他们发布的新帖子会显示在这里。"
                    } else {
                        "当前已读取的关注用户暂未发布公开帖子。"
                    },
                    actionLabel = if (state.hasMore) "继续加载" else "刷新",
                    onAction = if (state.hasMore) onLoadMore else onRefresh,
                )
                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 24.dp),
                ) {
                    if (
                        state.errorMessage != null &&
                        state.failedOperation == FollowingUpdatesFailedOperation.Refresh
                    ) {
                        item(key = "refresh-error") {
                            FollowingUpdatesNotice(state.errorMessage, isError = true, action = onRetry)
                        }
                    } else if (state.unavailableUserCount > 0) {
                        item(key = "partial-warning") {
                            FollowingUpdatesNotice(
                                "${state.unavailableUserCount} 位关注用户的帖子暂时无法加载",
                                isError = false,
                            )
                        }
                    }
                    items(state.threads, key = ThreadSummary::id) { thread ->
                        FollowingUpdateRow(thread) { onOpenThread(thread) }
                    }
                    item(key = "footer") {
                        FollowingUpdatesFooter(state, onLoadMore, onRetry)
                    }
                }
            }
        }
    }
}

@Composable
private fun FollowingUpdateRow(thread: ThreadSummary, onClick: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            UserAvatar(thread.author.portrait, thread.author.resolvedDisplayName, 32)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    thread.author.resolvedDisplayName,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                compactDate(thread.createdAtEpochSeconds)?.let { time ->
                    Text(
                        time,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        Text(
            thread.title.ifBlank { "无标题" },
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.SemiBold,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
        thread.textPreview.takeIf(String::isNotBlank)?.let { preview ->
            Text(
                preview,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            buildList {
                thread.forumName?.trim()?.removeSuffix("吧")?.takeIf(String::isNotEmpty)?.let { add("${it}吧") }
                add("${thread.replyCount} 回复")
            }.joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    HorizontalDivider(modifier = Modifier.padding(start = 58.dp))
}

@Composable
private fun FollowingUpdatesNotice(
    message: String,
    isError: Boolean,
    action: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            message,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodySmall,
            color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        action?.let {
            TextButton(onClick = it, modifier = Modifier.heightIn(min = 48.dp)) { Text("重试") }
        }
    }
}

@Composable
private fun FollowingUpdatesFooter(
    state: FollowingUpdatesUiState,
    onLoadMore: () -> Unit,
    onRetry: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when {
            state.isLoadingMore -> CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
            state.errorMessage != null && state.failedOperation == FollowingUpdatesFailedOperation.LoadMore -> {
                TextButton(onClick = onRetry, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text("加载失败，点此重试")
                }
            }
            state.hasMore -> TextButton(onClick = onLoadMore, modifier = Modifier.heightIn(min = 48.dp)) {
                Text("加载更多")
            }
            else -> Text(
                "已显示 ${state.threads.size} 条更新",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
