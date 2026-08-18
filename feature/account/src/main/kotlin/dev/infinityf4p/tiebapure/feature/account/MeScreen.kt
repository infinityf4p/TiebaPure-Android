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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Book
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Login
import androidx.compose.material.icons.outlined.NotificationsNone
import androidx.compose.material.icons.outlined.PeopleOutline
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
    MeScreen(state, modifier, onLogin, onOpen)
}

@Composable
fun MeScreen(
    state: MeUiState,
    modifier: Modifier = Modifier,
    onLogin: () -> Unit,
    onOpen: (AccountDestination) -> Unit,
) {
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
private fun AccountHeader(account: Account, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        UserAvatar(account.portrait, account.resolvedDisplayName, 48)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(account.resolvedDisplayName, style = MaterialTheme.typography.bodyLarge, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
            Text("UID ${account.uid}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(Icons.Outlined.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    HorizontalDivider(modifier = Modifier.padding(start = 76.dp))
}
