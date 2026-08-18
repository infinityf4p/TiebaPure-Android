package dev.infinityf4p.tiebapure.feature.forum

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.infinityf4p.tiebapure.core.designsystem.TiebaPureTheme
import dev.infinityf4p.tiebapure.core.media.AvatarImage
import dev.infinityf4p.tiebapure.core.model.Forum

@Composable
fun ForumHubRoute(
    viewModel: ForumHubViewModel,
    onOpenForum: (Forum) -> Unit,
    modifier: Modifier = Modifier,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    ForumHubScreen(
        uiState = uiState,
        onRefresh = { viewModel.refresh() },
        onOpenForum = onOpenForum,
        onRemoveRecent = viewModel::removeRecent,
        onClearRecent = viewModel::clearRecent,
        modifier = modifier,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ForumHubScreen(
    uiState: ForumHubUiState,
    onRefresh: () -> Unit,
    onOpenForum: (Forum) -> Unit,
    onRemoveRecent: (Forum) -> Unit,
    onClearRecent: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var forumInput by remember { mutableStateOf("") }
    var managesRecent by remember { mutableStateOf(false) }
    var confirmsClearRecent by remember { mutableStateOf(false) }
    val keyboardController = LocalSoftwareKeyboardController.current
    val submitForum: () -> Unit = {
        normalizedForum(forumInput)?.let(onOpenForum)
        forumInput = ""
        keyboardController?.hide()
        Unit
    }
    if (confirmsClearRecent) {
        AlertDialog(
            onDismissRequest = { confirmsClearRecent = false },
            title = { Text("清空最近浏览？") },
            text = { Text("最近浏览记录将全部移除。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmsClearRecent = false
                        managesRecent = false
                        onClearRecent()
                    },
                ) { Text("清空") }
            },
            dismissButton = { TextButton(onClick = { confirmsClearRecent = false }) { Text("取消") } },
        )
    }
    val sections = buildList {
        if (uiState.recentForums.isNotEmpty()) add(ForumHubSection.Recent)
        add(ForumHubSection.Followed)
    }
    Column(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Row(
            modifier = Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) { Text("进吧", style = MaterialTheme.typography.titleLarge) }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        PullToRefreshBox(
            isRefreshing = uiState.isRefreshing,
            onRefresh = onRefresh,
            modifier = Modifier.fillMaxSize().testTag("forum-hub"),
        ) {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 84.dp),
                modifier = Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        OutlinedTextField(
                            value = forumInput,
                            onValueChange = { forumInput = it },
                            label = { Text("输入吧名") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                            keyboardActions = KeyboardActions(onGo = { if (forumInput.isNotBlank()) submitForum() }),
                            modifier = Modifier.weight(1f).testTag("forum-input"),
                        )
                        IconButton(
                            onClick = submitForum,
                            enabled = forumInput.isNotBlank(),
                        ) { Icon(Icons.AutoMirrored.Outlined.ArrowForward, contentDescription = "进入贴吧") }
                    }
                }
                sections.forEach { section ->
                    item(
                        key = "header-${section.name}",
                        span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) },
                    ) {
                        ForumSectionHeader(
                            title = if (section == ForumHubSection.Recent) "最近浏览" else "关注贴吧",
                            trailing = if (section == ForumHubSection.Recent) {
                                {
                                    if (managesRecent) {
                                        TextButton(onClick = { confirmsClearRecent = true }) { Text("清空") }
                                        TextButton(onClick = { managesRecent = false }) { Text("完成") }
                                    } else TextButton(onClick = { managesRecent = true }) { Text("管理") }
                                }
                            } else null,
                        )
                    }
                    val forums = if (section == ForumHubSection.Recent) uiState.recentForums else uiState.followedForums
                    if (forums.isEmpty() && section == ForumHubSection.Followed) {
                        item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                            Text(
                                text = if (uiState.isLoading) "正在加载关注的吧" else uiState.errorMessage ?: "暂无关注的吧",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp),
                            )
                            if (uiState.errorMessage != null) {
                                TextButton(
                                    onClick = onRefresh,
                                    modifier = Modifier.fillMaxWidth().height(48.dp),
                                ) { Text("重试") }
                            }
                        }
                    } else {
                        items(forums, key = { "${section.name}-${it.name}" }) { forum ->
                            ForumTile(
                                forum = forum,
                                isManaging = section == ForumHubSection.Recent && managesRecent,
                                onOpen = { onOpenForum(forum) },
                                onDelete = { onRemoveRecent(forum) },
                            )
                        }
                    }
                }
                item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                    Box(Modifier.height(32.dp))
                }
            }
        }
    }
}

private enum class ForumHubSection { Recent, Followed }

@Composable
private fun ForumSectionHeader(title: String, trailing: (@Composable () -> Unit)?) {
    Row(
        modifier = Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.End) { trailing?.invoke() }
    }
}

@Composable
private fun ForumTile(
    forum: Forum,
    isManaging: Boolean,
    onOpen: () -> Unit,
    onDelete: () -> Unit,
) {
    Box(Modifier.padding(horizontal = 4.dp)) {
        Column(
            modifier = Modifier.fillMaxWidth().clickable(enabled = !isManaging, onClick = onOpen).padding(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            AvatarImage(
                url = forum.avatarUrl,
                name = forum.displayName,
                modifier = Modifier.size(52.dp),
            )
            Text(forum.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
        }
        if (isManaging) {
            IconButton(onClick = onDelete, modifier = Modifier.align(Alignment.TopEnd).size(48.dp)) {
                Icon(Icons.Outlined.Close, contentDescription = "删除${forum.displayName}")
            }
        }
    }
}

internal fun normalizedForum(value: String): Forum? {
    val display = value.trim().removeSuffix("吧").trim()
    if (display.isEmpty()) return null
    return Forum(id = 0, name = display, displayName = "${display}吧")
}

@Preview(showBackground = true, widthDp = 390, heightDp = 844)
@Composable
private fun ForumHubPreview() {
    val forums = listOf(
        Forum(1, "安卓", "安卓吧"),
        Forum(2, "数码", "数码吧"),
        Forum(3, "摄影", "摄影吧"),
    )
    TiebaPureTheme {
        ForumHubScreen(
            ForumHubUiState(followedForums = forums, recentForums = forums.take(2)),
            {}, {}, {}, {},
        )
    }
}
