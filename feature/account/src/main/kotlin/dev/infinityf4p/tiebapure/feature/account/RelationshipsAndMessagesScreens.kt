package dev.infinityf4p.tiebapure.feature.account

import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.infinityf4p.tiebapure.core.designsystem.ReaderState
import dev.infinityf4p.tiebapure.core.designsystem.ReaderStatePane
import dev.infinityf4p.tiebapure.core.model.MessageKind
import dev.infinityf4p.tiebapure.core.model.TiebaMessage
import dev.infinityf4p.tiebapure.core.model.UserRelationshipKind
import dev.infinityf4p.tiebapure.core.model.UserSummary

@Composable
fun UserRelationshipsRoute(
    viewModel: UserRelationshipsViewModel,
    title: String,
    onBack: () -> Unit,
    onOpenUser: (UserSummary) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    UserRelationshipsScreen(state, viewModel.kind, title, onBack, onOpenUser, viewModel::refresh, viewModel::loadMore, modifier)
}

@Composable
fun UserRelationshipsScreen(
    state: UserRelationshipsUiState,
    kind: UserRelationshipKind,
    title: String,
    onBack: () -> Unit,
    onOpenUser: (UserSummary) -> Unit,
    onRetry: () -> Unit,
    onLoadMore: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        AccountScreenHeader(title, onBack)
        when {
            state.isInitialLoading -> AccountStatePane(ReaderState.Loading(if (kind == UserRelationshipKind.Following) "正在加载关注用户" else "正在加载粉丝"))
            state.users.isEmpty() && state.errorMessage != null -> AccountStatePane(
                ReaderState.Error("加载失败", state.errorMessage),
                onRetry = onRetry,
            )
            state.users.isEmpty() -> AccountEmptyPane(
                title = if (kind == UserRelationshipKind.Following) "暂无关注用户" else "暂无粉丝",
                message = if (kind == UserRelationshipKind.Following) "这里还没有可显示的关注用户。" else "这里还没有可显示的粉丝。",
                actionLabel = "继续加载".takeIf { state.hasMore },
                onAction = onLoadMore.takeIf { state.hasMore },
            )
            else -> LazyColumn(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                items(state.users, key = { it.id.toString() + it.portrait }) { user -> UserRelationshipRow(user, onOpenUser) }
                item {
                    PaginationFooter(
                        state.isLoadingMore,
                        state.hasMore,
                        state.errorMessage,
                        onLoadMore,
                        completedLabel = "已显示 ${state.users.size} 位用户",
                    )
                }
            }
        }
    }
}

@Composable
private fun UserRelationshipRow(user: UserSummary, onOpenUser: (UserSummary) -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface)
            .heightIn(min = 56.dp).clickable { onOpenUser(user) }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        UserAvatar(user.portrait, user.resolvedDisplayName, 40)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                user.resolvedDisplayName,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            userSecondaryName(user)?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Icon(Icons.Outlined.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    HorizontalDivider(modifier = Modifier.padding(start = 68.dp))
}

@Composable
fun MessagesRoute(
    viewModel: MessagesViewModel,
    onBack: () -> Unit,
    onLogin: () -> Unit,
    onOpenMessage: (TiebaMessage) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    MessagesScreen(state, onBack, onLogin, onOpenMessage, viewModel::selectKind, viewModel::refresh, viewModel::loadMore, modifier)
}

@Composable
fun MessagesScreen(
    state: MessagesUiState,
    onBack: () -> Unit,
    onLogin: () -> Unit,
    onOpenMessage: (TiebaMessage) -> Unit,
    onSelectKind: (MessageKind) -> Unit,
    onRetry: () -> Unit,
    onLoadMore: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        AccountScreenHeader("消息", onBack)
        if (!state.isLoggedIn) {
            Column(
                Modifier.fillMaxSize().padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text("登录后查看消息", style = MaterialTheme.typography.titleMedium)
                Text(
                    "登录后可以在这里查看回复我的和@我的消息。",
                    modifier = Modifier.padding(top = 8.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                androidx.compose.material3.TextButton(
                    onClick = onLogin,
                    modifier = Modifier.padding(top = 8.dp).heightIn(min = 48.dp),
                ) {
                    Text("去登录")
                }
            }
            return@Column
        }
        MessageKindPicker(state.kind, onSelectKind)
        when {
            state.isInitialLoading -> AccountStatePane(ReaderState.Loading("正在加载消息"))
            state.messages.isEmpty() && state.errorMessage != null -> AccountStatePane(
                ReaderState.Error("消息加载失败", state.errorMessage),
                onRetry = onRetry,
            )
            state.messages.isEmpty() -> AccountEmptyPane(
                title = if (state.kind == MessageKind.Reply) "还没有收到回复" else "还没有人@我",
                message = if (state.kind == MessageKind.Reply) "别人回复你的帖子或楼层后会显示在这里。" else "别人在帖子里@你后会显示在这里。",
                actionLabel = "继续加载".takeIf { state.hasMore },
                onAction = onLoadMore.takeIf { state.hasMore },
            )
            else -> LazyColumn(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                items(state.messages, key = TiebaMessage::id) { message -> MessageRow(message, onOpenMessage) }
                item { PaginationFooter(state.isLoadingMore, state.hasMore, state.errorMessage, onLoadMore) }
            }
        }
    }
}

@Composable
private fun MessageKindPicker(selected: MessageKind, onSelect: (MessageKind) -> Unit) {
    val values = listOf(MessageKind.Reply to "回复我的", MessageKind.Mention to "@我的")
    SingleChoiceSegmentedButtonRow(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background).padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        values.forEachIndexed { index, (kind, title) ->
            SegmentedButton(
                selected = selected == kind,
                onClick = { onSelect(kind) },
                modifier = Modifier.heightIn(min = 48.dp),
                shape = SegmentedButtonDefaults.itemShape(index, values.size),
            ) { Text(title) }
        }
    }
}

@Composable
private fun MessageRow(message: TiebaMessage, onOpenMessage: (TiebaMessage) -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface)
            .heightIn(min = 64.dp).clickable { onOpenMessage(message) }.padding(16.dp),
        verticalAlignment = Alignment.Top,
    ) {
        UserAvatar(message.sender.portrait, message.sender.resolvedDisplayName, 40)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    message.sender.resolvedDisplayName,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    " ${message.kindDescription}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
                Spacer(Modifier.weight(1f))
                compactDate(message.createdAtEpochSeconds)?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Text(message.text, style = MaterialTheme.typography.bodyMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
            message.sourceLine.takeIf(String::isNotBlank)?.let { source ->
                Text(
                    source,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
    HorizontalDivider(modifier = Modifier.padding(start = 68.dp))
}

private val TiebaMessage.kindDescription: String
    get() = when {
        kind == MessageKind.Mention -> "@了我"
        isFloorReply -> "在楼中楼回复了我"
        else -> "回复了我"
    }

internal fun userSecondaryName(user: UserSummary): String? = user.name.trim()
    .takeIf { it.isNotEmpty() && it != user.resolvedDisplayName }
    ?.let { "@$it" }

private val TiebaMessage.sourceLine: String
    get() = buildList {
        threadTitle.trim().takeIf(String::isNotEmpty)?.let { add("原帖：$it") }
        forumName?.trim()?.removeSuffix("吧")?.takeIf(String::isNotEmpty)?.let { add("${it}吧") }
    }.joinToString(" · ")
