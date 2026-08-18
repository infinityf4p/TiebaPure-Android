package dev.infinityf4p.tiebapure.feature.account

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.infinityf4p.tiebapure.core.designsystem.ReaderState
import dev.infinityf4p.tiebapure.core.designsystem.ReaderStatePane
import dev.infinityf4p.tiebapure.core.media.AvatarImage
import dev.infinityf4p.tiebapure.core.model.Forum
import dev.infinityf4p.tiebapure.core.model.ThreadSummary
import dev.infinityf4p.tiebapure.core.model.UserContentVisibility
import dev.infinityf4p.tiebapure.core.model.UserProfile
import dev.infinityf4p.tiebapure.core.model.UserProfileSex

@Composable
fun UserProfileRoute(
    viewModel: UserProfileViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onEditProfile: (UserProfile) -> Unit = {},
    onOpenRelationship: (following: Boolean) -> Unit = {},
    onOpenForum: (Forum) -> Unit = {},
    onOpenThread: (ThreadSummary) -> Unit = {},
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    UserProfileScreen(
        state = state,
        onBack = onBack,
        modifier = modifier,
        onRetry = viewModel::refresh,
        onToggleFollow = viewModel::toggleFollow,
        onSelectTab = viewModel::selectTab,
        onLoadMore = viewModel::loadMoreThreads,
        onDeleteThread = viewModel::deleteThread,
        onEditProfile = onEditProfile,
        onOpenRelationship = onOpenRelationship,
        onOpenForum = onOpenForum,
        onOpenThread = onOpenThread,
    )
}

@Composable
fun UserProfileScreen(
    state: UserProfileUiState,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onRetry: () -> Unit = {},
    onToggleFollow: () -> Unit = {},
    onSelectTab: (UserProfileTab) -> Unit = {},
    onLoadMore: () -> Unit = {},
    onDeleteThread: (Long) -> Unit = {},
    onEditProfile: (UserProfile) -> Unit = {},
    onOpenRelationship: (following: Boolean) -> Unit = {},
    onOpenForum: (Forum) -> Unit = {},
    onOpenThread: (ThreadSummary) -> Unit = {},
) {
    Column(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        AccountScreenHeader("用户主页", onBack)
        val profile = state.profile
        when {
            state.isInitialLoading && profile == null -> ReaderStatePane(ReaderState.Loading("正在加载个人主页"))
            profile == null && state.errorMessage != null -> ReaderStatePane(
                ReaderState.Error("无法加载个人主页", state.errorMessage),
                onRetry = onRetry,
            )
            profile != null -> ProfileContent(
                state,
                onToggleFollow,
                onSelectTab,
                onLoadMore,
                onDeleteThread,
                onEditProfile,
                onOpenRelationship,
                onOpenForum,
                onOpenThread,
            )
            else -> ReaderStatePane(ReaderState.Empty("用户资料不可用"))
        }
    }
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun ProfileContent(
    state: UserProfileUiState,
    onToggleFollow: () -> Unit,
    onSelectTab: (UserProfileTab) -> Unit,
    onLoadMore: () -> Unit,
    onDeleteThread: (Long) -> Unit,
    onEditProfile: (UserProfile) -> Unit,
    onOpenRelationship: (Boolean) -> Unit,
    onOpenForum: (Forum) -> Unit,
    onOpenThread: (ThreadSummary) -> Unit,
) {
    val profile = requireNotNull(state.profile)
    var pendingDeleteThreadId by remember { mutableStateOf<Long?>(null) }
    LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
        item(key = "profile-header") {
            ProfileHeader(
                profile,
                state.isMutatingFollow || state.isFollowOutcomeUnknown,
                onToggleFollow,
                onEditProfile,
                onOpenRelationship,
            )
        }
        state.actionError?.let {
            item(key = "profile-action-error") {
                Text(
                    it,
                    modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface)
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        stickyHeader(key = "profile-tabs") {
            ProfileTabs(profile, state.selectedTab, onSelectTab)
        }
        when (state.selectedTab) {
            UserProfileTab.Threads -> {
                when {
                    state.threadVisibility == UserContentVisibility.Private -> item {
                        ProfileEmpty("该用户已隐藏帖子动态", "对方没有公开个人帖子，当前无法查看。")
                    }
                    state.threads.isEmpty() && state.errorMessage != null -> item {
                        ProfileEmpty("帖子加载失败", state.errorMessage)
                    }
                    state.threads.isEmpty() -> item {
                        ProfileEmpty(
                            title = "暂未发布帖子",
                            message = "这里还没有可公开查看的帖子。",
                            actionLabel = "继续加载".takeIf { state.hasMoreThreads },
                            onAction = onLoadMore.takeIf { state.hasMoreThreads },
                        )
                    }
                    else -> items(state.threads, key = ThreadSummary::id) { thread ->
                        AccountThreadRow(
                            thread = thread,
                            onClick = { onOpenThread(thread) },
                            trailing = state.deletionTargets[thread.id]?.let {
                                {
                                    IconButton(
                                        onClick = { pendingDeleteThreadId = thread.id },
                                        enabled = state.isDeletingThreadId == null &&
                                            thread.id !in state.unknownDeletionThreadIds,
                                    ) {
                                        Icon(Icons.Outlined.DeleteOutline, contentDescription = "删除帖子")
                                    }
                                }
                            },
                        )
                    }
                }
                if (state.threads.isNotEmpty()) item {
                    PaginationFooter(state.isLoadingMore, state.hasMoreThreads, state.errorMessage, onLoadMore)
                }
            }
            UserProfileTab.Forums -> {
                if (profile.followedForumsVisibility == UserContentVisibility.Private) {
                    item { ProfileEmpty("该用户已隐藏关注的吧", "对方没有公开关注列表，当前无法查看。") }
                } else if (profile.followedForums.isEmpty()) {
                    item { ProfileEmpty("暂未关注贴吧", "这里还没有可公开查看的关注吧。") }
                } else {
                    items(profile.followedForums, key = { it.id.toString() + it.name }) { forum ->
                        ProfileForumRow(forum) { onOpenForum(forum) }
                    }
                }
            }
        }
    }
    pendingDeleteThreadId?.let { threadId ->
        AlertDialog(
            onDismissRequest = { pendingDeleteThreadId = null },
            title = { Text("删除帖子") },
            text = { Text("删除后无法恢复，确定删除这条帖子吗？") },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    pendingDeleteThreadId = null
                    onDeleteThread(threadId)
                }) { Text("删除") }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { pendingDeleteThreadId = null }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun ProfileHeader(
    profile: UserProfile,
    isMutatingFollow: Boolean,
    onToggleFollow: () -> Unit,
    onEditProfile: (UserProfile) -> Unit,
    onOpenRelationship: (Boolean) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            UserAvatar(profile.user.portrait, profile.user.resolvedDisplayName, 56)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        profile.user.resolvedDisplayName,
                        modifier = Modifier.weight(1f, fill = false),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    profile.user.level?.takeIf { it > 0 }?.let {
                        Text(
                            "Lv.$it",
                            modifier = Modifier.clip(RoundedCornerShape(5.dp))
                                .background(MaterialTheme.colorScheme.tertiaryContainer)
                                .padding(horizontal = 6.dp, vertical = 3.dp),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                            maxLines = 1,
                        )
                    }
                }
                profileIdentityMetadata(profile).takeIf(String::isNotEmpty)?.let {
                    Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (profile.isCurrentUser) {
                OutlinedButton(onClick = { onEditProfile(profile) }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text("编辑资料")
                }
            } else {
                Button(
                    onClick = onToggleFollow,
                    enabled = !isMutatingFollow,
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { Text(if (profile.isFollowed) "已关注" else "关注") }
            }
        }
        profile.intro.takeIf(String::isNotBlank)?.let {
            Text(it, modifier = Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodyMedium)
        }
        profileDetailMetadata(profile).takeIf(String::isNotEmpty)?.let {
            Text(
                it,
                modifier = Modifier.padding(top = if (profile.intro.isBlank()) 8.dp else 3.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        HorizontalDivider(modifier = Modifier.padding(top = 8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ProfileMetric("获赞", profile.agreeCount, modifier = Modifier.weight(1f))
            ProfileMetric("关注", profile.followingCount, modifier = Modifier.weight(1f)) { onOpenRelationship(true) }
            ProfileMetric("粉丝", profile.followerCount, modifier = Modifier.weight(1f)) { onOpenRelationship(false) }
        }
    }
}

@Composable
private fun ProfileMetric(
    label: String,
    value: Int,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .then(modifier)
            .then(if (onClick == null) Modifier else Modifier.clickable(onClick = onClick))
            .heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(compactProfileCount(value), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ProfileTabs(profile: UserProfile, selected: UserProfileTab, onSelect: (UserProfileTab) -> Unit) {
    TabRow(
        selectedTabIndex = selected.ordinal,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Tab(
            selected = selected == UserProfileTab.Threads,
            onClick = { onSelect(UserProfileTab.Threads) },
            text = { Text("帖子 ${profile.threadCount}") },
        )
        Tab(
            selected = selected == UserProfileTab.Forums,
            onClick = { onSelect(UserProfileTab.Forums) },
            text = { Text("关注的吧 ${profile.followedForumCount}") },
        )
    }
}

@Composable
private fun ProfileForumRow(forum: Forum, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface)
            .clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp)
            .heightIn(min = 56.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AvatarImage(forum.avatarUrl, forum.displayName, Modifier.size(40.dp))
        Spacer(Modifier.width(12.dp))
        Text(
            forum.displayName,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
    HorizontalDivider(modifier = Modifier.padding(start = 68.dp))
}

@Composable
private fun ProfileEmpty(
    title: String,
    message: String? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Box(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).heightIn(min = 220.dp)) {
        if (actionLabel != null && onAction != null) {
            AccountEmptyPane(title, message.orEmpty(), actionLabel = actionLabel, onAction = onAction)
        } else {
            ReaderStatePane(ReaderState.Empty(title, message))
        }
    }
}

internal fun profileIdentityMetadata(profile: UserProfile): String = listOfNotNull(
    when (profile.sex) {
        UserProfileSex.Male -> "男"
        UserProfileSex.Female -> "女"
        UserProfileSex.Unspecified -> null
    },
    profile.tiebaId.trim().takeIf(String::isNotEmpty)?.let { "ID $it" },
).joinToString(" · ")

internal fun profileDetailMetadata(profile: UserProfile): String = listOfNotNull(
    profile.tiebaAge.trim().takeIf(String::isNotEmpty)?.let { "吧龄 $it" },
    profile.location?.trim()?.removePrefix("IP属地")?.trim()?.takeIf(String::isNotEmpty)?.let { "IP属地 $it" },
).joinToString(" · ")
