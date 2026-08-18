package dev.infinityf4p.tiebapure.feature.account

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.infinityf4p.tiebapure.core.media.AvatarImage
import dev.infinityf4p.tiebapure.core.model.ThreadSummary
import java.text.SimpleDateFormat
import java.net.URI
import java.util.Date
import java.util.Locale

@Composable
internal fun AccountScreenHeader(
    title: String,
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    actionEnabled: Boolean = true,
    onAction: (() -> Unit)? = null,
) {
    Surface(modifier = modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surface) {
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (onBack != null) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回")
                }
            } else {
                Spacer(Modifier.width(12.dp))
            }
            Text(
                title,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (actionLabel != null && onAction != null) {
                TextButton(
                    onClick = onAction,
                    enabled = actionEnabled,
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { Text(actionLabel) }
            } else {
                Spacer(Modifier.width(12.dp))
            }
        }
    }
    HorizontalDivider()
}

@Composable
internal fun UserAvatar(
    portrait: String,
    displayName: String,
    sizeDp: Int,
    modifier: Modifier = Modifier,
) {
    val url = portraitUrl(portrait)
    if (url == null) {
        Box(
            modifier.size(sizeDp.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                displayName.take(1).ifBlank { "?" },
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                fontWeight = FontWeight.SemiBold,
            )
        }
    } else {
        AvatarImage(
            url = url,
            name = displayName,
            modifier = modifier.size(sizeDp.dp),
        )
    }
}

@Composable
internal fun AccountSectionSurface(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        content = content,
    )
}

@Composable
internal fun AccountStatePane(
    state: dev.infinityf4p.tiebapure.core.designsystem.ReaderState,
    modifier: Modifier = Modifier,
    onRetry: (() -> Unit)? = null,
) {
    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        dev.infinityf4p.tiebapure.core.designsystem.ReaderStatePane(state, onRetry = onRetry)
    }
}

@Composable
internal fun AccountThreadRow(
    thread: ThreadSummary,
    onClick: () -> Unit,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .heightIn(min = 52.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(
                thread.title.ifBlank { "无标题" },
                style = MaterialTheme.typography.titleSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            thread.textPreview.takeIf(String::isNotBlank)?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                listOfNotNull(thread.forumName, "${thread.replyCount} 回复").joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        trailing?.invoke() ?: Icon(
            Icons.Outlined.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    HorizontalDivider(modifier = Modifier.padding(start = 16.dp))
}

@Composable
internal fun PaginationFooter(
    isLoading: Boolean,
    hasMore: Boolean,
    errorMessage: String?,
    onLoadMore: () -> Unit,
    completedLabel: String = "已显示全部",
) {
    Row(
        modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background)
            .heightIn(min = 52.dp).padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when {
            isLoading -> CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
            errorMessage != null -> TextButton(
                onClick = onLoadMore,
                modifier = Modifier.heightIn(min = 48.dp),
            ) { Text("加载失败，点此重试") }
            hasMore -> TextButton(
                onClick = onLoadMore,
                modifier = Modifier.heightIn(min = 48.dp),
            ) { Text("加载更多") }
            else -> Text(
                completedLabel,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
internal fun AccountEmptyPane(
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Column(
        modifier = modifier.fillMaxSize().padding(24.dp),
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
        if (actionLabel != null && onAction != null) {
            TextButton(onClick = onAction, modifier = Modifier.padding(top = 8.dp).heightIn(min = 48.dp)) {
                Text(actionLabel)
            }
        }
    }
}

internal fun compactProfileCount(value: Int): String {
    val safeValue = value.coerceAtLeast(0)
    if (safeValue < 10_000) return safeValue.toString()
    val whole = safeValue / 10_000
    val decimal = safeValue % 10_000 / 1_000
    return if (decimal == 0) "${whole}万" else "$whole.${decimal}万"
}

internal fun portraitUrl(value: String): String? {
    val portrait = value.trim()
    if (portrait.isEmpty() || portrait.any { it.isISOControl() }) return null
    if (!portrait.contains("://") && !portrait.startsWith("//")) {
        val token = portrait.substringBefore('?')
        if (token.isBlank() || '/' in token || '\\' in token || '#' in token || ".." in token) return null
        return "https://himg.bdimg.com/sys/portrait/item/$token"
    }
    val normalized = if (portrait.startsWith("//")) "https:$portrait" else portrait
    val uri = runCatching { URI(normalized) }.getOrNull() ?: return null
    val host = uri.host?.trimEnd('.')?.lowercase() ?: return null
    if (uri.rawUserInfo != null || uri.port != -1 || uri.rawQuery != null || uri.rawFragment != null) return null
    if (!uri.scheme.equals("https", ignoreCase = true) && !uri.scheme.equals("http", ignoreCase = true)) return null
    if (host != "tb.himg.baidu.com" && host != "himg.bdimg.com") return null
    val prefix = "/sys/portrait/item/"
    val token = uri.rawPath?.takeIf { it.startsWith(prefix) }?.removePrefix(prefix) ?: return null
    if (token.isBlank() || '/' in token || token.contains("..")) return null
    return "https://himg.bdimg.com/sys/portrait/item/$token"
}

internal fun compactDate(epochSeconds: Long?): String? {
    if (epochSeconds == null || epochSeconds <= 0) return null
    return SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(Date(epochSeconds * 1_000))
}
