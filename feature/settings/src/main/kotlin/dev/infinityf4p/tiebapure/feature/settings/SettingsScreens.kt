package dev.infinityf4p.tiebapure.feature.settings

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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.automirrored.outlined.Reply
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.CheckCircleOutline
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.DownloadDone
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.FontDownload
import androidx.compose.material.icons.outlined.FormatSize
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.SettingsBrightness
import androidx.compose.material.icons.outlined.ThumbUpOffAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.infinityf4p.tiebapure.core.designsystem.ReaderNavigationRow
import dev.infinityf4p.tiebapure.core.designsystem.ReaderSectionHeader
import dev.infinityf4p.tiebapure.core.designsystem.readerFontFamily
import dev.infinityf4p.tiebapure.core.media.AvatarImage
import dev.infinityf4p.tiebapure.core.model.Account
import dev.infinityf4p.tiebapure.core.model.BlocklistEntry
import dev.infinityf4p.tiebapure.core.model.BlocklistEntryKind
import dev.infinityf4p.tiebapure.core.model.BlocklistPolicy
import dev.infinityf4p.tiebapure.core.model.ImportedReaderFont
import dev.infinityf4p.tiebapure.core.model.ReaderFontFamily
import dev.infinityf4p.tiebapure.core.model.ReaderFontSize
import dev.infinityf4p.tiebapure.core.model.ReaderLineSpacing
import dev.infinityf4p.tiebapure.core.model.ReaderMediaLoadingPolicy
import dev.infinityf4p.tiebapure.core.model.ReadingPreferences
import dev.infinityf4p.tiebapure.core.model.ThreadReplySort
import java.net.URI

enum class SettingsDestination { Reading, SavedThreads, Blocklist, About }

@Immutable
data class SettingsUiState(
    val settings: SettingsValues = SettingsValues(),
    val account: Account? = null,
    val isSigning: Boolean = false,
    val signStatus: String? = null,
    val operationError: String? = null,
    val isLoggingOut: Boolean = false,
)

@Composable
fun SettingsRoute(
    viewModel: SettingsViewModel,
    host: SettingsHostState,
    modifier: Modifier = Modifier,
    onOpen: (SettingsDestination) -> Unit,
) {
    val featureState by viewModel.state.collectAsStateWithLifecycle()
    SettingsScreen(
        state = SettingsUiState(
            settings = featureState.values,
            account = host.account,
            isSigning = featureState.isSigning,
            signStatus = featureState.signStatus,
            operationError = featureState.errorMessage,
            isLoggingOut = featureState.isLoggingOut,
        ),
        modifier = modifier,
        onAppearanceChange = viewModel::setAppearance,
        onPostingEnabledChange = viewModel::setPostingEnabled,
        onReplyingEnabledChange = viewModel::setReplyingEnabled,
        onLikingEnabledChange = viewModel::setLikingEnabled,
        onAutoSignEnabledChange = viewModel::setAutomaticSignEnabled,
        onSignNow = viewModel::signNow,
        onOpen = onOpen,
        onLogout = viewModel::logOut,
    )
}

@Composable
fun ReadingSettingsRoute(
    viewModel: SettingsViewModel,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val fontPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { viewModel.importReaderFont(it.toString()) }
    }
    ReadingSettingsScreen(
        preferences = state.values.reading,
        readerFonts = state.readerFonts,
        modifier = modifier,
        statusMessage = state.message,
        errorMessage = state.errorMessage,
        onChange = viewModel::setReadingPreferences,
        onImportFont = { fontPicker.launch(arrayOf("font/ttf", "font/otf", "application/x-font-ttf", "application/x-font-opentype")) },
        onRemoveFont = viewModel::removeReaderFont,
    )
}

@Composable
fun BlocklistSettingsRoute(
    viewModel: SettingsViewModel,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    BlocklistSettingsScreen(
        entries = state.blocklist,
        modifier = modifier,
        statusMessage = state.message,
        errorMessage = state.errorMessage,
        onAdd = viewModel::addBlocklistEntry,
        onRemove = viewModel::removeBlocklistEntry,
        onClear = viewModel::clearBlocklist,
    )
}

@Composable
fun SettingsScreen(
    state: SettingsUiState,
    modifier: Modifier = Modifier,
    onAppearanceChange: (SettingsAppearance) -> Unit,
    onPostingEnabledChange: (Boolean) -> Unit,
    onReplyingEnabledChange: (Boolean) -> Unit,
    onLikingEnabledChange: (Boolean) -> Unit,
    onAutoSignEnabledChange: (Boolean) -> Unit,
    onSignNow: () -> Unit,
    onOpen: (SettingsDestination) -> Unit,
    onLogout: () -> Unit,
) {
    var confirmsLogout by remember { mutableStateOf(false) }
    LazyColumn(
        modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
        contentPadding = PaddingValues(bottom = 24.dp),
    ) {
        item { ReaderSectionHeader("显示模式", trailing = appearanceLabel(state.settings.appearance)) }
        item {
            Row(
                Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SettingsAppearance.entries.forEach { appearance ->
                    FilterChip(
                        selected = state.settings.appearance == appearance,
                        onClick = { onAppearanceChange(appearance) },
                        label = { Text(appearanceLabel(appearance)) },
                        leadingIcon = { Icon(appearanceIcon(appearance), contentDescription = null) },
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                    )
                }
            }
        }
        item {
            SettingsFooter("选择后会立即应用；跟随系统会随设备的外观设置自动切换。")
        }

        item { ReaderSectionHeader("内容") }
        item {
            SettingsSectionSurface {
                Column {
                    SettingsSwitchRow("允许发帖", Icons.Outlined.EditNote, state.settings.postingEnabled, onPostingEnabledChange)
                    SettingsSwitchRow("允许回帖", Icons.AutoMirrored.Outlined.Reply, state.settings.replyingEnabled, onReplyingEnabledChange)
                    SettingsSwitchRow("允许点赞", Icons.Outlined.ThumbUpOffAlt, state.settings.likingEnabled, onLikingEnabledChange)
                    ReaderNavigationRow("阅读设置", icon = Icons.Outlined.FormatSize) { onOpen(SettingsDestination.Reading) }
                    ReaderNavigationRow("本地保存的帖子", icon = Icons.Outlined.DownloadDone) {
                        onOpen(SettingsDestination.SavedThreads)
                    }
                    ReaderNavigationRow("屏蔽设置", icon = Icons.Outlined.Block) { onOpen(SettingsDestination.Blocklist) }
                }
            }
            SettingsFooter(
                "发帖和回帖使用非官方实验接口。开启并使用后，可能触发贴吧风控，造成内容被隐藏或删除、账号功能受限；极端情况下账号可能被冻结。请确认能够承担风险后再使用。关闭点赞后仍会显示点赞数量；设置和屏蔽规则仅保存在本机。",
            )
        }

        state.account?.let { account ->
            item { ReaderSectionHeader("签到") }
            item {
                SettingsSectionSurface {
                    Column {
                        SettingsSwitchRow("自动签到", Icons.Outlined.CheckCircleOutline, state.settings.automaticSignEnabled, onAutoSignEnabledChange)
                        ReaderNavigationRow(
                            title = if (state.isSigning) "正在签到" else "立即签到",
                            icon = if (state.isSigning) Icons.Outlined.Refresh else Icons.Outlined.AutoAwesome,
                            enabled = !state.isSigning,
                            onClick = onSignNow,
                        )
                    }
                }
                SettingsFooter(
                    state.signStatus ?: "签到会按关注列表逐个请求，需要几秒到几十秒；同一天只会自动执行一次。",
                )
            }
            item { ReaderSectionHeader("账号") }
            item {
                AccountSettingsRow(account)
            }
            item {
                Spacer(Modifier.heightIn(min = 16.dp))
                SettingsSectionSurface {
                    ReaderNavigationRow(
                        title = if (state.isLoggingOut) "正在退出" else "退出登录",
                        icon = Icons.AutoMirrored.Outlined.Logout,
                        enabled = !state.isLoggingOut,
                        onClick = { confirmsLogout = true },
                    )
                }
            }
        }
        state.operationError?.let { error ->
            item {
                Text(
                    error,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }

    if (confirmsLogout) {
        AlertDialog(
            onDismissRequest = { confirmsLogout = false },
            title = { Text("退出登录？") },
            text = { Text("这会清除本机保存的百度登录状态。") },
            confirmButton = {
                TextButton(onClick = { confirmsLogout = false; onLogout() }) { Text("退出登录") }
            },
            dismissButton = { TextButton(onClick = { confirmsLogout = false }) { Text("取消") } },
        )
    }
}

@Composable
private fun SettingsSectionSurface(content: @Composable () -> Unit) {
    androidx.compose.material3.Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        content = content,
    )
}

@Composable
private fun SettingsFooter(text: String) {
    Text(
        text,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun AccountSettingsRow(account: Account) {
    Row(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AvatarImage(
            url = settingsPortraitUrl(account.portrait),
            name = account.resolvedDisplayName,
            modifier = Modifier.size(48.dp),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(account.resolvedDisplayName, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
            account.name.takeIf { it.isNotBlank() && it != account.resolvedDisplayName }?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text("UID ${account.uid}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SettingsSwitchRow(
    title: String,
    icon: ImageVector,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface)
            .heightIn(min = 52.dp).clickable { onCheckedChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(12.dp))
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Switch(checked = checked, onCheckedChange = null)
    }
    HorizontalDivider(modifier = Modifier.padding(start = 50.dp))
}

@Composable
fun ReadingSettingsScreen(
    preferences: ReadingPreferences,
    readerFonts: List<ImportedReaderFont> = emptyList(),
    modifier: Modifier = Modifier,
    statusMessage: String? = null,
    errorMessage: String? = null,
    onChange: (ReadingPreferences) -> Unit,
    onImportFont: () -> Unit = {},
    onRemoveFont: (String) -> Unit = {},
) {
    LazyColumn(
        modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
        contentPadding = PaddingValues(bottom = 24.dp),
    ) {
        item { ReaderSectionHeader("正文") }
        item {
            ChoiceChipRow(
                label = "字号",
                values = ReaderFontSize.entries,
                selected = preferences.fontSize,
                title = ::fontSizeLabel,
                onSelect = { onChange(preferences.copy(fontSize = it)) },
            )
            ChoiceChipRow(
                label = "正文间距",
                values = ReaderLineSpacing.entries,
                selected = preferences.lineSpacing,
                title = ::lineSpacingLabel,
                onSelect = { onChange(preferences.copy(lineSpacing = it)) },
            )
            Text(
                "阅读设置会应用到主贴、楼层回复和楼中楼正文。",
                modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(16.dp),
                style = MaterialTheme.typography.bodyLarge.copy(
                    fontSize = MaterialTheme.typography.bodyLarge.fontSize * preferences.fontSize.scale,
                    lineHeight = MaterialTheme.typography.bodyLarge.lineHeight * preferences.lineSpacing.multiplier,
                    fontFamily = readerFontFamily(preferences.fontFamily),
                ),
            )
        }
        item { SettingsFooter("系统大字体仍会继续生效；首页和吧页的帖子摘要保持紧凑显示。") }
        item { ReaderSectionHeader("字体") }
        items(ReaderFontFamily.builtIn, key = ReaderFontFamily::rawValue) { family ->
            RadioChoiceRow(
                title = readerFontFamilyLabel(family),
                subtitle = if (family == ReaderFontFamily.System) "跟随 Android 系统字体" else "应用到帖子标题和正文",
                selected = preferences.fontFamily == family,
                onClick = { onChange(preferences.copy(fontFamily = family)) },
            )
        }
        items(readerFonts, key = ImportedReaderFont::id) { font ->
            ReaderFontRow(
                font = font,
                selected = preferences.fontFamily == font.family,
                onSelect = { font.family?.let { onChange(preferences.copy(fontFamily = it)) } },
                onRemove = { onRemoveFont(font.id) },
            )
        }
        item {
            TextButton(
                onClick = onImportFont,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) {
                Icon(Icons.Outlined.FontDownload, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("导入 TTF / OTF 字体")
            }
        }
        item {
            SettingsFooter(
                errorMessage ?: statusMessage
                    ?: "字体文件只保存在本机应用私有目录；单个文件最多 20 MB，最多导入 20 个。",
            )
        }
        item { ReaderSectionHeader("回复") }
        item {
            ChoiceChipRow(
                label = "默认排序",
                values = ThreadReplySort.entries,
                selected = preferences.defaultReplySort,
                title = ::replySortLabel,
                onSelect = { onChange(preferences.copy(defaultReplySort = it)) },
            )
        }
        item { SettingsFooter("只影响新打开的帖子；恢复上次阅读位置时会使用正序定位。") }
        item { ReaderSectionHeader("图片与视频") }
        item {
            ReaderMediaLoadingPolicy.entries.forEach { policy ->
                RadioChoiceRow(
                    title = mediaLoadingLabel(policy),
                    subtitle = mediaLoadingDescription(policy),
                    selected = preferences.mediaLoading == policy,
                    onClick = { onChange(preferences.copy(mediaLoading = policy)) },
                )
            }
        }
        item { SettingsFooter("此设置只影响帖子媒体和视频封面，不影响头像、贴吧图标或表情。") }
        item {
            TextButton(
                onClick = { onChange(ReadingPreferences()) },
                enabled = preferences != ReadingPreferences(),
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) {
                Icon(Icons.Outlined.DeleteOutline, null)
                Spacer(Modifier.width(8.dp))
                Text("恢复默认设置")
            }
        }
    }
}

@Composable
private fun ReaderFontRow(
    font: ImportedReaderFont,
    selected: Boolean,
    onSelect: () -> Unit,
    onRemove: () -> Unit,
) {
    val importedFamily = font.family?.let { readerFontFamily(it) }
    Row(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface)
            .heightIn(min = 56.dp).clickable(onClick = onSelect).padding(start = 8.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
            Text(font.displayName, style = MaterialTheme.typography.bodyLarge.copy(fontFamily = importedFamily))
            Text(
                "${font.fileExtension.uppercase()} · ${formatFontBytes(font.byteCount)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onRemove) {
            Icon(Icons.Outlined.DeleteOutline, contentDescription = "删除${font.displayName}")
        }
    }
    HorizontalDivider(modifier = Modifier.padding(start = 52.dp))
}

@Composable
private fun <T> ChoiceChipRow(
    label: String,
    values: List<T>,
    selected: T,
    title: (T) -> String,
    onSelect: (T) -> Unit,
) {
    Column(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            values.forEach { value ->
                FilterChip(
                    selected = value == selected,
                    onClick = { onSelect(value) },
                    label = { Text(title(value)) },
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                )
            }
        }
    }
    HorizontalDivider()
}

@Composable
private fun RadioChoiceRow(title: String, subtitle: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface)
            .heightIn(min = 56.dp).clickable(onClick = onClick).padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Column(Modifier.weight(1f).padding(end = 8.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    HorizontalDivider(modifier = Modifier.padding(start = 52.dp))
}

@Composable
fun BlocklistSettingsScreen(
    entries: List<BlocklistEntry>,
    modifier: Modifier = Modifier,
    statusMessage: String? = null,
    errorMessage: String? = null,
    onAdd: (BlocklistEntry) -> Unit,
    onRemove: (BlocklistEntry) -> Unit,
    onClear: (BlocklistEntryKind) -> Unit = {},
) {
    var keywordInput by remember { mutableStateOf("") }
    var userInput by remember { mutableStateOf("") }
    var forumInput by remember { mutableStateOf("") }
    var clearTarget by remember { mutableStateOf<BlocklistEntryKind?>(null) }
    LazyColumn(
        modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
        contentPadding = PaddingValues(bottom = 24.dp),
    ) {
        errorMessage?.let { error ->
            item(key = "blocklist-error") {
                Text(
                    error,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        } ?: statusMessage?.let { status ->
            item(key = "blocklist-status") { SettingsFooter(status) }
        }
        BlocklistEntryKind.entries.forEach { sectionKind ->
            val values = entries.filter { it.kind == sectionKind }
            item { ReaderSectionHeader(blocklistKindLabel(sectionKind), trailing = values.size.toString()) }
            item(key = "input-${sectionKind.name}") {
                val input = when (sectionKind) {
                    BlocklistEntryKind.Keyword -> keywordInput
                    BlocklistEntryKind.User -> userInput
                    BlocklistEntryKind.Forum -> forumInput
                }
                BlocklistInputRow(
                    kind = sectionKind,
                    input = input,
                    onInputChange = { value ->
                        when (sectionKind) {
                            BlocklistEntryKind.Keyword -> keywordInput = value
                            BlocklistEntryKind.User -> userInput = value
                            BlocklistEntryKind.Forum -> forumInput = value
                        }
                    },
                    onAdd = {
                        BlocklistPolicy.normalize(BlocklistEntry(sectionKind, input))?.let(onAdd)
                        when (sectionKind) {
                            BlocklistEntryKind.Keyword -> keywordInput = ""
                            BlocklistEntryKind.User -> userInput = ""
                            BlocklistEntryKind.Forum -> forumInput = ""
                        }
                    },
                )
            }
            if (values.isEmpty()) {
                item(key = "empty-${sectionKind.name}") {
                    SettingsSectionSurface {
                        Text(
                            blocklistEmptyText(sectionKind),
                            Modifier.fillMaxWidth().padding(16.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            } else {
                items(values, key = { it.identity }) { entry -> BlocklistEntryRow(entry, onRemove) }
                item(key = "clear-${sectionKind.name}") {
                    SettingsSectionSurface {
                        TextButton(
                            onClick = { clearTarget = sectionKind },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        ) { Text("清空", color = MaterialTheme.colorScheme.error) }
                    }
                }
            }
            item(key = "footer-${sectionKind.name}") {
                SettingsFooter(blocklistFooter(sectionKind))
            }
        }
    }
    clearTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { clearTarget = null },
            title = { Text("清空${blocklistKindLabel(target)}屏蔽？") },
            text = { Text("此操作只会删除本机保存的屏蔽规则。") },
            confirmButton = {
                TextButton(onClick = { onClear(target); clearTarget = null }) {
                    Text("清空", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { clearTarget = null }) { Text("取消") } },
        )
    }
}

@Composable
private fun BlocklistInputRow(
    kind: BlocklistEntryKind,
    input: String,
    onInputChange: (String) -> Unit,
    onAdd: () -> Unit,
) {
    val normalized = BlocklistPolicy.normalize(BlocklistEntry(kind, input))
    SettingsSectionSurface {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = onInputChange,
                label = { Text(blocklistPrompt(kind)) },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            Button(onClick = onAdd, enabled = normalized != null, modifier = Modifier.heightIn(min = 48.dp)) {
                Text("添加")
            }
        }
    }
}

@Composable
private fun BlocklistEntryRow(entry: BlocklistEntry, onRemove: (BlocklistEntry) -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface)
            .heightIn(min = 52.dp).padding(start = 16.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(entry.value, style = MaterialTheme.typography.bodyLarge)
            entry.numericId?.let {
                Text("UID $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        IconButton(onClick = { onRemove(entry) }) {
            Icon(Icons.Outlined.DeleteOutline, contentDescription = "移除${entry.value}", tint = MaterialTheme.colorScheme.error)
        }
    }
    HorizontalDivider(modifier = Modifier.padding(start = 16.dp))
}

@Composable
fun AboutSettingsScreen(
    info: SettingsAboutInfo,
    modifier: Modifier = Modifier,
    onOpenUrl: (String) -> Unit,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
        contentPadding = PaddingValues(bottom = 24.dp),
    ) {
        item { ReaderSectionHeader("TiebaPure") }
        item {
            Column(
                Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("TiebaPure", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Text("版本 ${info.versionName}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("以浏览为主的非官方百度贴吧客户端；登录后支持关注、点赞，以及实验性的发帖与回复。与百度公司及贴吧官方无隶属、授权或认可关系。")
            }
        }
        item { ReaderSectionHeader("项目") }
        item {
            SettingsSectionSurface {
                Column {
                    ReaderNavigationRow("查看源代码", icon = Icons.Outlined.Link) { onOpenUrl(info.projectUrl) }
                    ReaderNavigationRow("项目作者", subtitle = "infinityf4p") { onOpenUrl(info.authorUrl) }
                }
            }
        }
        item { ReaderSectionHeader("开源许可") }
        item {
            SettingsSectionSurface {
                Column {
                    ReaderNavigationRow("TiebaPure", trailing = "GPL-3.0-only") { onOpenUrl(info.licenseUrl) }
                    ReaderNavigationRow("Protocol Buffers", trailing = "BSD-3-Clause") { onOpenUrl(info.protobufLicenseUrl) }
                }
            }
        }
        item { ReaderSectionHeader("本机数据") }
        item {
            Text(
                "阅读偏好、功能开关、屏蔽规则和草稿保存在本机。卸载应用或清除应用数据后，这些内容会被删除。",
                modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(16.dp),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

internal fun appearanceLabel(value: SettingsAppearance): String = when (value) {
    SettingsAppearance.System -> "跟随系统"
    SettingsAppearance.Light -> "浅色"
    SettingsAppearance.Dark -> "深色"
}

private fun appearanceIcon(value: SettingsAppearance): ImageVector = when (value) {
    SettingsAppearance.System -> Icons.Outlined.SettingsBrightness
    SettingsAppearance.Light -> Icons.Outlined.LightMode
    SettingsAppearance.Dark -> Icons.Outlined.DarkMode
}

private fun fontSizeLabel(value: ReaderFontSize): String = when (value) {
    ReaderFontSize.Small -> "小"
    ReaderFontSize.Standard -> "标准"
    ReaderFontSize.Large -> "大"
    ReaderFontSize.ExtraLarge -> "特大"
}

private fun readerFontFamilyLabel(value: ReaderFontFamily): String = when (value) {
    ReaderFontFamily.System -> "系统"
    ReaderFontFamily.Serif -> "衬线"
    ReaderFontFamily.Rounded -> "圆体"
    ReaderFontFamily.Monospace -> "等宽"
    else -> "自定义"
}

private fun formatFontBytes(value: Long): String = when {
    value >= 1_024 * 1_024 -> "${value / (1_024 * 1_024)} MB"
    else -> "${maxOf(1, value / 1_024)} KB"
}

private fun lineSpacingLabel(value: ReaderLineSpacing): String = when (value) {
    ReaderLineSpacing.Compact -> "紧凑"
    ReaderLineSpacing.Standard -> "标准"
    ReaderLineSpacing.Relaxed -> "宽松"
}

private fun replySortLabel(value: ThreadReplySort): String = when (value) {
    ThreadReplySort.Hot -> "热门"
    ThreadReplySort.Ascending -> "正序"
    ThreadReplySort.Descending -> "倒序"
}

private fun mediaLoadingLabel(value: ReaderMediaLoadingPolicy): String = when (value) {
    ReaderMediaLoadingPolicy.Automatic -> "自动加载"
    ReaderMediaLoadingPolicy.DataSaving -> "节省流量"
    ReaderMediaLoadingPolicy.Manual -> "手动加载"
}

private fun mediaLoadingDescription(value: ReaderMediaLoadingPolicy): String = when (value) {
    ReaderMediaLoadingPolicy.Automatic -> "自动加载媒体，失败时尝试备用地址"
    ReaderMediaLoadingPolicy.DataSaving -> "自动加载预览，不额外请求备用原图"
    ReaderMediaLoadingPolicy.Manual -> "仅在点击后加载媒体"
}

private fun blocklistKindLabel(value: BlocklistEntryKind): String = when (value) {
    BlocklistEntryKind.Keyword -> "关键词"
    BlocklistEntryKind.User -> "用户"
    BlocklistEntryKind.Forum -> "吧"
}

private fun blocklistPrompt(value: BlocklistEntryKind): String = when (value) {
    BlocklistEntryKind.Keyword -> "输入关键词"
    BlocklistEntryKind.User -> "输入用户名"
    BlocklistEntryKind.Forum -> "输入吧名"
}

private fun blocklistEmptyText(value: BlocklistEntryKind): String = when (value) {
    BlocklistEntryKind.Keyword -> "暂无关键词屏蔽"
    BlocklistEntryKind.User -> "暂无用户屏蔽"
    BlocklistEntryKind.Forum -> "暂无吧屏蔽"
}

internal fun blocklistFooter(value: BlocklistEntryKind): String = when (value) {
    BlocklistEntryKind.Keyword -> "标题或内容包含关键词的帖子和楼层会被隐藏，不区分大小写。"
    BlocklistEntryKind.User -> "可直接输入用户名；在用户主页中屏蔽可精确匹配账号。"
    BlocklistEntryKind.Forum -> "填写吧名，无需带“吧”字后缀。"
}

internal fun settingsPortraitUrl(value: String): String? {
    val portrait = value.trim()
    if (portrait.isEmpty() || portrait.any(Char::isISOControl)) return null
    if (!portrait.contains("://") && !portrait.startsWith("//")) {
        val token = portrait.substringBefore('?')
        if (token.isBlank() || '/' in token || '\\' in token || '#' in token || ".." in token) return null
        return "https://himg.bdimg.com/sys/portrait/item/$token"
    }
    val uri = runCatching { URI(if (portrait.startsWith("//")) "https:$portrait" else portrait) }.getOrNull()
        ?: return null
    val host = uri.host?.trimEnd('.')?.lowercase() ?: return null
    if (uri.rawUserInfo != null || uri.port != -1 || uri.rawQuery != null || uri.rawFragment != null) return null
    if (!uri.scheme.equals("https", true) && !uri.scheme.equals("http", true)) return null
    if (host != "tb.himg.baidu.com" && host != "himg.bdimg.com") return null
    val prefix = "/sys/portrait/item/"
    val token = uri.rawPath?.takeIf { it.startsWith(prefix) }?.removePrefix(prefix) ?: return null
    if (token.isBlank() || '/' in token || token.contains("..")) return null
    return "https://himg.bdimg.com/sys/portrait/item/$token"
}
