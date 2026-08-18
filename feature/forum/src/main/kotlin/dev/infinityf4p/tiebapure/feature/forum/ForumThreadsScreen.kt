package dev.infinityf4p.tiebapure.feature.forum

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material.icons.outlined.ThumbUp
import androidx.compose.material.icons.outlined.ThumbUpOffAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
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
import dev.infinityf4p.tiebapure.core.model.ForumThreadCategory
import dev.infinityf4p.tiebapure.core.model.ThreadSummary
import dev.infinityf4p.tiebapure.core.model.UserSummary
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class ForumThreadsCapabilities(
    val canCreateThread: Boolean = false,
)

data class ForumThreadsCallbacks(
    val onBack: () -> Unit = {},
    val onSearch: (Forum) -> Unit = {},
    val onOpenThread: (ThreadSummary) -> Unit = {},
    val onOpenUser: (UserSummary) -> Unit = {},
    val onBlockForum: (Forum) -> Unit = {},
    val onCreateThread: (Forum) -> Unit = {},
    val onOpenMedia: (ThreadSummary, Int) -> Unit = { _, _ -> },
    val capabilities: ForumThreadsCapabilities = ForumThreadsCapabilities(),
)

@Composable
fun ForumThreadsRoute(
    viewModel: ForumThreadsViewModel,
    callbacks: ForumThreadsCallbacks = ForumThreadsCallbacks(),
    mediaLoadingPolicy: ReaderMediaLoadingPolicy = ReaderMediaLoadingPolicy.Automatic,
    modifier: Modifier = Modifier,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    ForumThreadsScreen(
        uiState = uiState,
        callbacks = callbacks,
        onSelectCategory = viewModel::selectCategory,
        onTogglePinned = viewModel::togglePinned,
        onRefresh = {
            viewModel.refresh()
            viewModel.refreshForumInteraction()
        },
        onLoadMore = viewModel::loadMore,
        onRetry = viewModel::retry,
        onToggleForumFollow = viewModel::toggleForumFollow,
        onDismissForumActionError = viewModel::dismissForumActionError,
        mediaLoadingPolicy = mediaLoadingPolicy,
        modifier = modifier,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ForumThreadsScreen(
    uiState: ForumThreadsUiState,
    callbacks: ForumThreadsCallbacks,
    onSelectCategory: (ForumThreadCategory) -> Unit,
    onTogglePinned: () -> Unit,
    onRefresh: () -> Unit,
    onLoadMore: () -> Unit,
    onToggleForumFollow: () -> Unit,
    onDismissForumActionError: () -> Unit,
    onRetry: () -> Unit = onRefresh,
    mediaLoadingPolicy: ReaderMediaLoadingPolicy = ReaderMediaLoadingPolicy.Automatic,
    modifier: Modifier = Modifier,
) {
    if (uiState.forumActionError != null) {
        AlertDialog(
            onDismissRequest = onDismissForumActionError,
            confirmButton = { TextButton(onClick = onDismissForumActionError) { Text("好") } },
            title = { Text("提示") },
            text = { Text(uiState.forumActionError) },
        )
    }
    Column(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Row(
            Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(callbacks.onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回") }
            Text(uiState.forum.displayName, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            IconButton(
                onClick = onToggleForumFollow,
                enabled = uiState.canRequestForumFollow && !uiState.isForumFollowOutcomeUnknown,
                modifier = Modifier.testTag("forum-follow-button"),
            ) {
                when {
                    uiState.isResolvingForumId || uiState.isLoadingMembership || uiState.isUpdatingForumFollow ->
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    uiState.forumMembership?.isFollowed == true ->
                        Icon(Icons.Filled.Star, forumFollowDescription(uiState))
                    else -> Icon(Icons.Outlined.StarBorder, forumFollowDescription(uiState))
                }
            }
            if (callbacks.capabilities.canCreateThread) {
                IconButton(
                    onClick = { callbacks.onCreateThread(uiState.forum) },
                    enabled = canCreateThread(uiState.forum, callbacks.capabilities),
                    modifier = Modifier.testTag("forum-create-thread-button"),
                ) {
                    Icon(Icons.Outlined.Edit, "发新主题")
                }
            }
            IconButton({ callbacks.onSearch(uiState.forum) }) { Icon(Icons.Outlined.Search, "搜索本吧") }
            var menu by remember { mutableStateOf(false) }
            Box {
                IconButton({ menu = true }) { Icon(Icons.Outlined.MoreHoriz, "更多") }
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem(
                        text = { Text("屏蔽${uiState.forum.displayName}") },
                        onClick = { menu = false; callbacks.onBlockForum(uiState.forum) },
                    )
                }
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        PullToRefreshBox(
            isRefreshing = uiState.isRefreshing,
            onRefresh = onRefresh,
            modifier = Modifier.fillMaxSize().testTag("forum-threads"),
        ) {
            when {
                uiState.isInitialLoading && uiState.threads.isEmpty() -> Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
                uiState.errorMessage != null && uiState.threads.isEmpty() && !uiState.hasMore -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                    TextButton(onClick = onRetry) { Text("${uiState.errorMessage} 点击重试") }
                }
                else -> LazyColumn(Modifier.fillMaxSize()) {
                    item(key = "category") {
                        ReaderSectionBand { ForumCategoryBar(uiState.category, onSelectCategory) }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                    if (uiState.pinnedThreads.isNotEmpty()) {
                        item(key = "pinned") {
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .height(48.dp)
                                    .background(MaterialTheme.colorScheme.surfaceContainerLow)
                                    .clickable(onClick = onTogglePinned)
                                    .padding(horizontal = 16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text("置顶内容", style = MaterialTheme.typography.labelLarge)
                                Text(" ${uiState.pinnedThreads.size}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Spacer(Modifier.weight(1f))
                                Icon(if (uiState.showsPinned) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null)
                            }
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        }
                    }
                    itemsIndexed(uiState.visibleThreads, key = { _, item -> item.id }) { index, thread ->
                        ForumThreadItem(thread, uiState.category, callbacks, mediaLoadingPolicy)
                        if (index >= uiState.visibleThreads.lastIndex - 4 && uiState.hasMore) {
                            LaunchedEffect(thread.id) { onLoadMore() }
                        }
                    }
                    if (uiState.threads.isEmpty()) {
                        item(key = "empty-page-message") {
                            Box(Modifier.fillMaxWidth().height(132.dp), Alignment.Center) {
                                Text(
                                    uiState.errorMessage ?: if (uiState.hasMore) {
                                        "当前页暂无可显示帖子"
                                    } else {
                                        "暂无帖子\n下拉即可刷新本吧帖子"
                                    },
                                    color = if (uiState.errorMessage == null) {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    } else {
                                        MaterialTheme.colorScheme.error
                                    },
                                )
                            }
                        }
                    }
                    if (uiState.showsEmptyPageContinuation) {
                        item(key = "empty-page-continuation") {
                            EmptyPageContinuation(
                                isLoading = uiState.isRefreshing || uiState.isLoadingMore,
                                error = uiState.errorMessage,
                                onRetry = onRetry,
                                onLoadMore = onLoadMore,
                            )
                        }
                    }
                    if (uiState.threads.isNotEmpty()) {
                        item(key = "footer") {
                            Box(Modifier.fillMaxWidth().height(64.dp), Alignment.Center) {
                                when {
                                    uiState.isLoadingMore -> CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                                    uiState.errorMessage != null -> TextButton(onClick = onRetry) { Text("加载失败，点击重试") }
                                    uiState.hasMore -> TextButton(onClick = onLoadMore) { Text("加载更多帖子") }
                                    else -> Text("没有更多了", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
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
        .testTag("forum-empty-page-load-more")
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

private fun forumFollowDescription(uiState: ForumThreadsUiState): String = when {
    uiState.isResolvingForumId || uiState.isLoadingMembership -> "正在加载关注状态"
    uiState.isUpdatingForumFollow -> "正在更新关注状态"
    uiState.followAvailability == ForumFollowAvailability.LoginRequired -> "登录后才能关注本吧"
    uiState.followAvailability == ForumFollowAvailability.Unsupported -> "当前不可关注本吧"
    uiState.forum.id <= 0 -> "尚未确认贴吧，无法关注"
    uiState.forumMembership == null -> "重新加载关注状态"
    uiState.forumMembership.isFollowed -> "取消关注本吧"
    else -> "关注本吧"
}

@Composable
private fun ForumCategoryBar(
    category: ForumThreadCategory,
    onSelect: (ForumThreadCategory) -> Unit,
) {
    var latestMenu by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box {
            CategoryChip(
                title = "最新",
                selected = category != ForumThreadCategory.Featured,
                trailingDropDown = true,
                onClick = { latestMenu = true },
            )
            DropdownMenu(latestMenu, { latestMenu = false }) {
                DropdownMenuItem(
                    text = { Text("回复时间排序") },
                    onClick = { latestMenu = false; onSelect(ForumThreadCategory.ReplyTime) },
                )
                DropdownMenuItem(
                    text = { Text("发帖时间排序") },
                    onClick = { latestMenu = false; onSelect(ForumThreadCategory.PublishTime) },
                )
            }
        }
        CategoryChip(
            title = "精华",
            selected = category == ForumThreadCategory.Featured,
            trailingDropDown = false,
            onClick = { onSelect(ForumThreadCategory.Featured) },
        )
        Spacer(Modifier.weight(1f))
    }
}

@Composable
private fun CategoryChip(title: String, selected: Boolean, trailingDropDown: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier.height(48.dp).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            color = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = .1f) else MaterialTheme.colorScheme.surface,
            contentColor = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            shape = RoundedCornerShape(20.dp),
            modifier = Modifier.height(36.dp),
        ) {
            Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.labelMedium)
                if (trailingDropDown) Icon(Icons.Outlined.ArrowDropDown, null, Modifier.size(18.dp))
            }
        }
    }
}

@Composable
private fun ForumThreadItem(
    thread: ThreadSummary,
    category: ForumThreadCategory,
    callbacks: ForumThreadsCallbacks,
    mediaLoadingPolicy: ReaderMediaLoadingPolicy,
) {
    ReaderCard(cornerRadius = 0.dp) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .testTag("forum-thread-author-${thread.id}")
                    .then(
                        if (canOpenForumThreadAuthor(thread.author)) {
                            Modifier.clickable { callbacks.onOpenUser(thread.author) }
                        } else {
                            Modifier
                        },
                    ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AvatarImage(
                    url = thread.author.portrait,
                    name = thread.author.resolvedDisplayName,
                    modifier = Modifier.size(32.dp),
                )
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            thread.author.resolvedDisplayName,
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (thread.author.level != null) {
                            Text(
                                " ${thread.author.level} ${thread.author.levelName.orEmpty()}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                            )
                        }
                    }
                    forumThreadMetadata(thread, category)?.let { metadata ->
                        Text(
                            metadata,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                }
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .clickable { callbacks.onOpenThread(thread) },
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    thread.title.ifBlank { thread.textPreview },
                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                thread.textPreview.takeIf { it.isNotBlank() && it != thread.title }?.let { preview ->
                    Text(
                        preview,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            ForumThreadBadges(thread)
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
    HorizontalDivider(Modifier.padding(start = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
}

@Composable
private fun ForumThreadBadges(thread: ThreadSummary) {
    if (!thread.isTop && !thread.isGood) return
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        if (thread.isTop) ForumThreadBadge("置顶")
        if (thread.isGood) ForumThreadBadge("精品")
    }
}

@Composable
private fun ForumThreadBadge(label: String) {
    Surface(
        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f),
        contentColor = MaterialTheme.colorScheme.primary,
        shape = RoundedCornerShape(4.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
    }
}

internal fun forumThreadMetadata(
    thread: ThreadSummary,
    category: ForumThreadCategory,
    nowEpochSeconds: Long = System.currentTimeMillis() / 1_000,
): String? {
    val timestamp = when (category) {
        ForumThreadCategory.PublishTime -> thread.createdAtEpochSeconds
        ForumThreadCategory.ReplyTime -> thread.lastReplyAtEpochSeconds ?: thread.createdAtEpochSeconds
        ForumThreadCategory.Featured -> thread.lastReplyAtEpochSeconds ?: thread.createdAtEpochSeconds
    }?.takeIf { it > 0L } ?: return null
    val elapsed = (nowEpochSeconds - timestamp).coerceAtLeast(0L)
    val formatted = when {
        elapsed < 60 -> "刚刚"
        elapsed < 3_600 -> "${elapsed / 60}分钟前"
        elapsed < 86_400 -> "${elapsed / 3_600}小时前"
        elapsed < 7 * 86_400 -> "${elapsed / 86_400}天前"
        else -> SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(Date(timestamp * 1_000))
    }
    val action = if (category == ForumThreadCategory.PublishTime) "发布" else "回复"
    return "$formatted · $action"
}

private val previewForum = Forum(1, "安卓", "安卓吧")
private val previewForumThreads = listOf(
    ThreadSummary(1, title = "置顶内容默认折叠", author = UserSummary(1, "a", "吧务", "", 12, "熟悉"), replyCount = 24, viewCount = 80, blocks = emptyList(), isTop = true),
    ThreadSummary(2, title = "吧页支持回复时间和发帖时间排序", author = UserSummary(2, "b", "示例用户", "", 8, "常驻"), replyCount = 9, viewCount = 40, blocks = listOf(ContentBlock.Text("紧凑筛选随列表滚动。"))),
)

@Preview(showBackground = true, widthDp = 390, heightDp = 844)
@Composable
private fun ForumThreadsPreview() {
    TiebaPureTheme {
        ForumThreadsScreen(
            ForumThreadsUiState(forum = previewForum, threads = previewForumThreads),
            ForumThreadsCallbacks(), {}, {}, {}, {}, {}, {},
        )
    }
}
