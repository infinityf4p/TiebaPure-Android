package dev.infinityf4p.tiebapure

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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

private const val SAVED_THREAD_BACKUP_MIME_TYPE = "application/vnd.infinityf4p.tiebapure.backup"

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
    var statusMessage by remember { mutableStateOf<String?>(null) }
    val storageBytes = remember(entries) {
        entries.sumOf { it.mediaBytes + it.snapshotBytes }
    }
    var isWorking by remember { mutableStateOf(false) }
    var menuExpanded by remember { mutableStateOf(false) }
    var pendingImportUri by remember { mutableStateOf<String?>(null) }
    var confirmsClear by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<SavedThreadListItem?>(null) }
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(SAVED_THREAD_BACKUP_MIME_TYPE),
    ) { uri ->
        uri?.let {
            scope.launch {
                isWorking = true
                errorMessage = null
                runCatching { repository.exportBackup(it.toString()) }
                    .onSuccess { count -> statusMessage = "已导出 $count 个本地帖子。" }
                    .onFailure { errorMessage = it.message ?: "导出失败。" }
                isWorking = false
            }
        }
    }
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> pendingImportUri = uri?.toString() }
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
                actions = {
                    if (isWorking) {
                        CircularProgressIndicator(Modifier.padding(12.dp).size(24.dp), strokeWidth = 2.dp)
                    } else {
                        IconButton(onClick = { menuExpanded = true }) {
                            Icon(Icons.Outlined.MoreVert, contentDescription = "本地保存操作")
                        }
                        DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                            DropdownMenuItem(
                                text = { Text("检查新回复") },
                                leadingIcon = { Icon(Icons.Outlined.Refresh, null) },
                                enabled = entries.isNotEmpty(),
                                onClick = {
                                    menuExpanded = false
                                    scope.launch {
                                        isWorking = true
                                        errorMessage = null
                                        runCatching { repository.checkForUpdates() }
                                            .onSuccess { result ->
                                                statusMessage = if (result.newReplies == 0) {
                                                    "已检查 ${result.checkedThreads} 个帖子，没有新回复。"
                                                } else {
                                                    "${result.changedThreads} 个帖子共有 ${result.newReplies} 条新回复。"
                                                }
                                            }
                                            .onFailure { errorMessage = it.message ?: "更新检查失败。" }
                                        isWorking = false
                                    }
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("导出备份") },
                                leadingIcon = { Icon(Icons.Outlined.FileUpload, null) },
                                enabled = entries.isNotEmpty(),
                                onClick = {
                                    menuExpanded = false
                                    exportLauncher.launch("TiebaPure-${System.currentTimeMillis()}.tiebapurebackup")
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("导入备份") },
                                leadingIcon = { Icon(Icons.Outlined.FileDownload, null) },
                                onClick = {
                                    menuExpanded = false
                                    importLauncher.launch(
                                        arrayOf(
                                            SAVED_THREAD_BACKUP_MIME_TYPE,
                                            "application/zip",
                                            "application/octet-stream",
                                        ),
                                    )
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("清空本地保存") },
                                leadingIcon = { Icon(Icons.Outlined.DeleteSweep, null) },
                                enabled = entries.isNotEmpty(),
                                onClick = { menuExpanded = false; confirmsClear = true },
                            )
                        }
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
                                    buildString {
                                        append(savedThreadMediaModeLabel(entry.mediaMode))
                                        if (entry.mediaBytes > 0) append(" · ${formatSavedBytes(entry.mediaBytes)}")
                                        append(" · ")
                                        append(DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                                            .format(Date(entry.savedAtMilliseconds)))
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                entry.lastCheckedAtMilliseconds?.let { checkedAt ->
                                    Text(
                                        "上次检查 " + DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                                            .format(Date(checkedAt)),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                if (entry.newReplyCount > 0) {
                                    Text(
                                        "新增 ${entry.newReplyCount} 条回复",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                }
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
                            "共 ${entries.size} 个帖子，占用 ${formatSavedBytes(storageBytes)}。完全离线模式不会在文件缺失时回退联网；最多保存 100 个帖子。",
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
            statusMessage?.let {
                Text(
                    it,
                    modifier = Modifier.padding(16.dp),
                    color = MaterialTheme.colorScheme.primary,
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
    pendingImportUri?.let { uri ->
        AlertDialog(
            onDismissRequest = { pendingImportUri = null },
            title = { Text("导入本地帖子备份") },
            text = { Text("合并会保留本机较新的同名帖子；替换会先清空现有本地帖子。登录状态和设置不会导入。") },
            confirmButton = {
                TextButton(onClick = {
                    pendingImportUri = null
                    scope.launch {
                        isWorking = true
                        errorMessage = null
                        runCatching { repository.importBackup(uri, SavedThreadImportMode.Merge) }
                            .onSuccess { statusMessage = "已导入 ${it.importedThreads} 个，跳过 ${it.skippedThreads} 个。" }
                            .onFailure { errorMessage = it.message ?: "导入失败。" }
                        isWorking = false
                    }
                }) { Text("合并") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { pendingImportUri = null }) { Text("取消") }
                    TextButton(onClick = {
                        pendingImportUri = null
                        scope.launch {
                            isWorking = true
                            errorMessage = null
                            runCatching { repository.importBackup(uri, SavedThreadImportMode.Replace) }
                                .onSuccess { statusMessage = "已用备份替换为 ${it.importedThreads} 个帖子。" }
                                .onFailure { errorMessage = it.message ?: "导入失败。" }
                            isWorking = false
                        }
                    }) { Text("替换", color = MaterialTheme.colorScheme.error) }
                }
            },
        )
    }
    if (confirmsClear) {
        AlertDialog(
            onDismissRequest = { confirmsClear = false },
            title = { Text("清空全部本地帖子？") },
            text = { Text("帖子快照和离线媒体都会删除，无法撤销。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmsClear = false
                    scope.launch {
                        runCatching { repository.clearAll() }
                            .onSuccess { statusMessage = "已清空本地保存。" }
                            .onFailure { errorMessage = it.message ?: "清空失败。" }
                    }
                }) { Text("清空", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmsClear = false }) { Text("取消") } },
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
fun SavedThreadSaveModeDialog(
    onDismiss: () -> Unit,
    onSelect: (SavedThreadMediaMode) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("保存到本机") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                SavedThreadSaveModeRow(
                    title = "仅文字",
                    subtitle = "保存全部楼层和楼中楼，媒体联网加载",
                    onClick = { onSelect(SavedThreadMediaMode.TextOnly) },
                )
                SavedThreadSaveModeRow(
                    title = "文字与图片",
                    subtitle = "额外保存正文图片、头像和视频封面",
                    onClick = { onSelect(SavedThreadMediaMode.Images) },
                )
                SavedThreadSaveModeRow(
                    title = "完全离线",
                    subtitle = "同时保存视频和语音；任一媒体失败则不覆盖旧保存",
                    onClick = { onSelect(SavedThreadMediaMode.Complete) },
                )
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun SavedThreadSaveModeRow(title: String, subtitle: String, onClick: () -> Unit) {
    Surface(onClick = onClick, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
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
                    buildString {
                        append("保存于 ")
                        append(DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(snapshot.savedAtMilliseconds)))
                        append(" · ${savedThreadMediaModeLabel(snapshot.mediaMode)}")
                        if (snapshot.newReplyCount > 0) append(" · 新增 ${snapshot.newReplyCount} 条回复")
                    },
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

internal fun savedThreadMediaModeLabel(mode: SavedThreadMediaMode): String = when (mode) {
    SavedThreadMediaMode.TextOnly -> "仅文字"
    SavedThreadMediaMode.Images -> "文字与图片"
    SavedThreadMediaMode.Complete -> "完全离线"
}

internal fun formatSavedBytes(value: Long): String = when {
    value >= 1_024L * 1_024 * 1_024 -> "${value / (1_024L * 1_024 * 1_024)} GB"
    value >= 1_024L * 1_024 -> "${value / (1_024L * 1_024)} MB"
    value >= 1_024L -> "${value / 1_024L} KB"
    else -> "$value B"
}
