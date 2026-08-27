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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Book
import androidx.compose.material.icons.outlined.CheckCircleOutline
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.DynamicFeed
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Login
import androidx.compose.material.icons.outlined.ManageAccounts
import androidx.compose.material.icons.outlined.NotificationsNone
import androidx.compose.material.icons.outlined.PeopleOutline
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.infinityf4p.tiebapure.core.designsystem.ReaderNavigationRow
import dev.infinityf4p.tiebapure.core.designsystem.ReaderSectionHeader
import dev.infinityf4p.tiebapure.core.model.Account

enum class AccountDestination {
    OwnProfile,
    Messages,
    FollowedUsers,
    FollowedForums,
    FollowingUpdates,
    ThreadFavorites,
    BrowsingHistory,
    Settings,
    About,
}

@Composable
fun MeRoute(
    viewModel: MeViewModel,
    modifier: Modifier = Modifier,
    onLogin: () -> Unit,
    onOpen: (AccountDestination) -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    MeScreen(
        state = state,
        modifier = modifier,
        onLogin = onLogin,
        onOpen = onOpen,
        onSwitchAccount = viewModel::switchAccount,
        onRemoveAccount = viewModel::removeAccount,
        onConsumeAccountActionError = viewModel::consumeAccountActionError,
    )
}

@Composable
fun MeScreen(
    state: MeUiState,
    modifier: Modifier = Modifier,
    onLogin: () -> Unit,
    onOpen: (AccountDestination) -> Unit,
    onSwitchAccount: (String) -> Unit,
    onRemoveAccount: (String) -> Unit,
    onConsumeAccountActionError: () -> Unit,
) {
    var showsAccountSwitcher by rememberSaveable { mutableStateOf(false) }
    var removalTargetId by rememberSaveable { mutableStateOf<String?>(null) }
    var requestedSwitchAccountId by rememberSaveable { mutableStateOf<String?>(null) }

    LaunchedEffect(state.savedAccounts.map(SavedAccountSummary::id)) {
        if (state.savedAccounts.isEmpty()) {
            showsAccountSwitcher = false
            removalTargetId = null
        } else if (state.savedAccounts.none { it.id == removalTargetId }) {
            removalTargetId = null
        }
    }
    LaunchedEffect(
        state.savedAccounts,
        state.switchingAccountId,
        requestedSwitchAccountId,
    ) {
        val requestedId = requestedSwitchAccountId ?: return@LaunchedEffect
        val switched = state.savedAccounts.any { it.id == requestedId && it.isActive }
        if (switched && state.switchingAccountId == null) {
            showsAccountSwitcher = false
            requestedSwitchAccountId = null
        }
    }

    Column(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        AccountScreenHeader("我的", onBack = null)
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 24.dp),
        ) {
        item { ReaderSectionHeader("账号") }
        item {
            when (val status = state.loginStatus) {
                AccountLoginStatus.Loading -> AccountStatusRow("正在读取登录状态")
                AccountLoginStatus.LoggedOut -> LoggedOutPanel(onLogin)
                is AccountLoginStatus.LoggedIn -> AccountHeader(
                    account = status.account,
                    onClick = { onOpen(AccountDestination.OwnProfile) },
                    onManageAccounts = {
                        onConsumeAccountActionError()
                        showsAccountSwitcher = true
                    },
                )
                is AccountLoginStatus.Failed -> Column(
                    Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text("登录状态读取失败", style = MaterialTheme.typography.titleSmall)
                    Text(status.message, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Button(
                        onClick = onLogin,
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) { Text("重新登录") }
                }
            }
        }
        if (state.account != null) {
            item {
                AccountSectionSurface {
                    Column {
                        ReaderNavigationRow("消息", icon = Icons.Outlined.NotificationsNone) {
                            onOpen(AccountDestination.Messages)
                        }
                        ReaderNavigationRow("关注的用户", icon = Icons.Outlined.PeopleOutline) {
                            onOpen(AccountDestination.FollowedUsers)
                        }
                        ReaderNavigationRow("关注的吧", icon = Icons.Outlined.StarBorder) {
                            onOpen(AccountDestination.FollowedForums)
                        }
                        ReaderNavigationRow("关注更新", icon = Icons.Outlined.DynamicFeed) {
                            onOpen(AccountDestination.FollowingUpdates)
                        }
                    }
                }
            }
        }
        item { ReaderSectionHeader("浏览") }
        item {
            AccountSectionSurface {
                Column {
                    ReaderNavigationRow("帖子收藏", icon = Icons.Outlined.FavoriteBorder) {
                        onOpen(AccountDestination.ThreadFavorites)
                    }
                    ReaderNavigationRow(
                        title = "浏览历史",
                        icon = Icons.Outlined.History,
                        trailing = state.visibleHistoryCount.takeIf { it > 0 }?.toString(),
                        onClick = { onOpen(AccountDestination.BrowsingHistory) },
                    )
                }
            }
        }
        item { ReaderSectionHeader("应用") }
        item {
            AccountSectionSurface {
                Column {
                    ReaderNavigationRow("设置", icon = Icons.Outlined.Settings) { onOpen(AccountDestination.Settings) }
                    ReaderNavigationRow("关于 TiebaPure", icon = Icons.Outlined.Info) { onOpen(AccountDestination.About) }
                }
            }
        }
        }
    }

    val removalTarget = state.savedAccounts.firstOrNull { it.id == removalTargetId }
    if (removalTarget != null) {
        RemoveSavedAccountDialog(
            account = removalTarget,
            enabled = !state.isAccountActionInProgress,
            onConfirm = {
                removalTargetId = null
                onRemoveAccount(removalTarget.id)
            },
            onDismiss = { removalTargetId = null },
        )
    } else if (showsAccountSwitcher) {
        AccountSwitcherDialog(
            accounts = state.savedAccounts,
            maximumSavedAccountCount = state.maximumSavedAccountCount,
            switchingAccountId = state.switchingAccountId,
            removingAccountId = state.removingAccountId,
            errorMessage = state.accountActionErrorMessage,
            onSwitchAccount = { accountId ->
                requestedSwitchAccountId = accountId
                onSwitchAccount(accountId)
            },
            onRemoveAccount = { removalTargetId = it },
            onAddAccount = {
                showsAccountSwitcher = false
                requestedSwitchAccountId = null
                onConsumeAccountActionError()
                onLogin()
            },
            onDismiss = {
                showsAccountSwitcher = false
                requestedSwitchAccountId = null
                onConsumeAccountActionError()
            },
        )
    }
}

@Composable
private fun LoggedOutPanel(onLogin: () -> Unit) {
    AccountSectionSurface {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Book, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(12.dp))
                Text("未登录也可以浏览公开帖子", style = MaterialTheme.typography.bodyLarge)
            }
            Button(onClick = onLogin, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Icon(Icons.Outlined.Login, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("手机号验证码登录")
            }
        }
    }
}

@Composable
private fun AccountStatusRow(text: String) {
    Text(
        text,
        modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(16.dp),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun AccountHeader(
    account: Account,
    onClick: () -> Unit,
    onManageAccounts: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .clickable(onClick = onClick)
                .padding(start = 16.dp, end = 4.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            UserAvatar(account.portrait, account.resolvedDisplayName, 48)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    account.resolvedDisplayName,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "UID ${account.uid}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Icon(
                Icons.Outlined.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onManageAccounts, modifier = Modifier.padding(end = 4.dp)) {
            Icon(Icons.Outlined.ManageAccounts, contentDescription = "切换或管理账号")
        }
    }
    HorizontalDivider(modifier = Modifier.padding(start = 76.dp))
}

@Composable
private fun AccountSwitcherDialog(
    accounts: List<SavedAccountSummary>,
    maximumSavedAccountCount: Int,
    switchingAccountId: String?,
    removingAccountId: String?,
    errorMessage: String?,
    onSwitchAccount: (String) -> Unit,
    onRemoveAccount: (String) -> Unit,
    onAddAccount: () -> Unit,
    onDismiss: () -> Unit,
) {
    val isBusy = switchingAccountId != null || removingAccountId != null
    val canAddAccount = accounts.size < maximumSavedAccountCount
    AlertDialog(
        onDismissRequest = { if (!isBusy) onDismiss() },
        title = { Text("切换账号") },
        text = {
            Column(Modifier.fillMaxWidth()) {
                accounts.forEachIndexed { index, account ->
                    SavedAccountRow(
                        account = account,
                        isSwitching = switchingAccountId == account.id,
                        isRemoving = removingAccountId == account.id,
                        enabled = !isBusy,
                        onSwitch = { onSwitchAccount(account.id) },
                        onRemove = { onRemoveAccount(account.id) },
                    )
                    if (index < accounts.lastIndex) HorizontalDivider()
                }
                HorizontalDivider(modifier = Modifier.padding(top = 4.dp))
                TextButton(
                    onClick = onAddAccount,
                    enabled = canAddAccount && !isBusy,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                ) {
                    Icon(Icons.Outlined.PersonAdd, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(if (canAddAccount) "添加账号" else "已达到账号上限")
                }
                Text(
                    "已保存 ${accounts.size}/$maximumSavedAccountCount 个账号",
                    modifier = Modifier.fillMaxWidth(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                errorMessage?.let {
                    Text(
                        it,
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss, enabled = !isBusy) { Text("完成") }
        },
    )
}

@Composable
private fun SavedAccountRow(
    account: SavedAccountSummary,
    isSwitching: Boolean,
    isRemoving: Boolean,
    enabled: Boolean,
    onSwitch: () -> Unit,
    onRemove: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .clickable(enabled = enabled && !account.isActive, onClick = onSwitch)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        UserAvatar(account.portrait, account.displayName, 40)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                account.displayName,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "UID ${account.id}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        when {
            isSwitching || isRemoving -> CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                strokeWidth = 2.dp,
            )
            account.isActive -> Icon(
                Icons.Outlined.CheckCircleOutline,
                contentDescription = "当前账号",
                tint = MaterialTheme.colorScheme.primary,
            )
            else -> Spacer(Modifier.size(24.dp))
        }
        IconButton(onClick = onRemove, enabled = enabled) {
            Icon(
                Icons.Outlined.DeleteOutline,
                contentDescription = "移除${account.displayName}",
                tint = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun RemoveSavedAccountDialog(
    account: SavedAccountSummary,
    enabled: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = { if (enabled) onDismiss() },
        title = { Text("移除${account.displayName}？") },
        text = {
            Text(
                if (account.isActive) {
                    "移除当前账号后将自动切换到另一个已保存账号；如果没有其他账号，则退出登录。"
                } else {
                    "本机将不再保存该账号的登录信息。"
                },
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = enabled) {
                Text("移除", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = enabled) { Text("取消") }
        },
    )
}
