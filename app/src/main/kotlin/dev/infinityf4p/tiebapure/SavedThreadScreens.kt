package dev.infinityf4p.tiebapure

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.infinityf4p.tiebapure.core.media.ImageSaveAction
import dev.infinityf4p.tiebapure.core.model.Forum
import dev.infinityf4p.tiebapure.core.model.ImageContent
import dev.infinityf4p.tiebapure.core.model.ReadingPreferences
import dev.infinityf4p.tiebapure.core.model.Subpost
import dev.infinityf4p.tiebapure.core.model.ThreadPage
import dev.infinityf4p.tiebapure.core.model.ThreadReplySort
import dev.infinityf4p.tiebapure.feature.thread.LocalReadingPreferences
import dev.infinityf4p.tiebapure.feature.thread.SubpostUiState
import dev.infinityf4p.tiebapure.feature.thread.ThreadCapabilities
import dev.infinityf4p.tiebapure.feature.thread.ThreadReplyTarget
import dev.infinityf4p.tiebapure.feature.thread.ThreadScreen
import dev.infinityf4p.tiebapure.feature.thread.ThreadUiState
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SavedThreadsRoute(
    repository: AppSavedThreadRepository,
    onBack: () -> Unit,
    onOpen: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val entries by repository.entries.collectAsStateWithLifecycle(initialValue = emptyList())
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var pendingDelete by remember { mutableStateOf<SavedThreadListItem?>(null) }
    val visible = remember(entries, query) {
        val keyword = query.trim()
        if (keyword.isEmpty()) entries else entries.filter {
            it.title.contains(keyword, ignoreCase = true) ||
                it.authorName.contains(keyword, ignoreCase = true) ||
                it.forumName.contains(keyword, ignoreCase = true)
        }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("本地保存的帖子") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).background(MaterialTheme.colorScheme.background),
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                singleLine = true,
                leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                placeholder = { Text("搜索标题、作者或贴吧") },
            )
            when {
                entries.isEmpty() -> SavedThreadsEmpty("还没有本地保存", "请在帖子页右上角点击保存图标。")
                visible.isEmpty() -> SavedThreadsEmpty("没有匹配的帖子", "换个标题、作者或贴吧名称搜索。")
                else -> LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
                    items(visible, key = SavedThreadListItem::threadId) { entry ->
                        Row(
                            Modifier.fillMaxWidth().clickable { onOpen(entry.threadId) }
                                .padding(start = 16.dp, top = 12.dp, bottom = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(entry.title, style = MaterialTheme.typography.bodyLarge, maxLines = 2)
                                Text(
                                    "${entry.forumName} · ${entry.authorName}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                )
                                Text(
                                    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                                        .format(Date(entry.savedAtMilliseconds)),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Spacer(Modifier.width(8.dp))
                            IconButton(onClick = { pendingDelete = entry }) {
                                Icon(Icons.Outlined.DeleteOutline, contentDescription = "删除本地保存")
                            }
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                    item {
                        Text(
                            "正文和楼层结构保存在本机；图片、视频和语音仍需联网加载。最多保存100个帖子。",
                            modifier = Modifier.padding(16.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            errorMessage?.let {
                Text(
                    it,
                    modifier = Modifier.padding(16.dp),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
    pendingDelete?.let { entry ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除本地保存？") },
            text = { Text("删除后需要重新联网保存才能恢复。") },
            confirmButton = {
                TextButton(onClick = {
                    pendingDelete = null
                    scope.launch {
                        runCatching { repository.remove(entry.threadId) }
                            .onFailure { errorMessage = it.message ?: "删除失败。" }
                    }
                }) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun SavedThreadsEmpty(title: String, message: String) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(
            message,
            modifier = Modifier.padding(top = 8.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
fun SavedThreadDetailRoute(
    threadId: Long,
    repository: AppSavedThreadRepository,
    readingPreferences: ReadingPreferences,
    onBack: () -> Unit,
    onForumClick: (Forum) -> Unit,
    onUserClick: (Long) -> Unit,
    onLinkClick: (String) -> Unit,
    onShare: () -> Unit,
    onDownloadImage: (ImageContent) -> Unit,
    onSaveImage: ImageSaveAction?,
    modifier: Modifier = Modifier,
) {
    var snapshot by remember(threadId) { mutableStateOf<SavedThreadSnapshot?>(null) }
    var errorMessage by remember(threadId) { mutableStateOf<String?>(null) }
    LaunchedEffect(threadId) {
        runCatching { repository.load(threadId) }
            .onSuccess { snapshot = it ?: run { errorMessage = "本地保存不存在或已被删除。"; null } }
            .onFailure { errorMessage = it.message ?: "无法读取本地保存。" }
    }

    when {
        snapshot != null -> SavedThreadDetailScreen(
            snapshot = checkNotNull(snapshot),
            readingPreferences = readingPreferences,
            onBack = onBack,
            onForumClick = onForumClick,
            onUserClick = onUserClick,
            onLinkClick = onLinkClick,
            onShare = onShare,
            onDownloadImage = onDownloadImage,
            onSaveImage = onSaveImage,
            modifier = modifier,
        )
        errorMessage != null -> SavedThreadLoadError(checkNotNull(errorMessage), onBack, modifier)
        else -> Column(
            modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) { CircularProgressIndicator() }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SavedThreadLoadError(message: String, onBack: () -> Unit, modifier: Modifier) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("本地保存") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            SavedThreadsEmpty("无法打开本地保存", message)
        }
    }
}

@Composable
private fun SavedThreadDetailScreen(
    snapshot: SavedThreadSnapshot,
    readingPreferences: ReadingPreferences,
    onBack: () -> Unit,
    onForumClick: (Forum) -> Unit,
    onUserClick: (Long) -> Unit,
    onLinkClick: (String) -> Unit,
    onShare: () -> Unit,
    onDownloadImage: (ImageContent) -> Unit,
    onSaveImage: ImageSaveAction?,
    modifier: Modifier,
) {
    var selectedPostId by remember(snapshot.thread.id) { mutableStateOf<ULong?>(null) }
    val selectedPost = snapshot.posts.firstOrNull { it.post.id == selectedPostId }
    val main = checkNotNull(snapshot.mainPost).displayPost
    val replies = snapshot.posts.filter { it.post.id != main.id }.map(SavedThreadPostSnapshot::displayPost)
    val page = ThreadPage(
        thread = snapshot.thread,
        forum = snapshot.forum,
        mainPost = main,
        posts = replies,
        currentPage = 1,
        totalPage = 1,
        hasMore = false,
    )
    val state = ThreadUiState(
        page = page,
        posts = replies,
        sort = ThreadReplySort.Ascending,
        isInitialLoading = false,
        subposts = selectedPost?.let {
            SubpostUiState(
                parent = it.displayPost,
                items = it.subposts,
                currentPage = 1,
                hasMore = false,
                isLoading = false,
            )
        },
    )

    CompositionLocalProvider(LocalReadingPreferences provides readingPreferences) {
        Column(modifier) {
            Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
                Text(
                    "保存于 ${DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(snapshot.savedAtMilliseconds))}；媒体需联网加载",
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            ThreadScreen(
                state = state,
                capabilities = ThreadCapabilities(
                    canReply = false,
                    canLike = false,
                    canCollect = false,
                    canRefresh = false,
                    canFilterReplies = false,
                    alwaysShowSubpostOpenAction = true,
                ),
                onBack = onBack,
                onForumClick = onForumClick,
                onRefresh = {},
                onLoadMore = {},
                onRetry = {},
                onSort = {},
                onOnlyAuthor = {},
                onReply = {},
                onUserClick = onUserClick,
                onLinkClick = onLinkClick,
                onShare = onShare,
                onOpenSubposts = { selectedPostId = it.id },
                onCloseSubposts = { selectedPostId = null },
                onLoadMoreSubposts = {},
                onRetrySubposts = {},
                onToggleThreadLike = {},
                onTogglePostLike = {},
                onToggleSubpostLike = {},
                onToggleCollection = {},
                onReadingPositionChanged = {},
                onReadingPositionRestored = {},
                onActionErrorShown = {},
                onDownloadImage = onDownloadImage,
                onSaveImage = onSaveImage,
                modifier = Modifier.weight(1f),
            )
        }
    }
}
