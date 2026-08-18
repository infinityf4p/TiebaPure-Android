package dev.infinityf4p.tiebapure.core.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

sealed interface ReaderState {
    data class Loading(val message: String = "正在加载") : ReaderState
    data class Empty(val title: String, val message: String? = null) : ReaderState
    data class Error(val title: String, val message: String, val retryLabel: String = "重试") : ReaderState
}

/** A white/content surface on the grouped reader background, matching iOS ReaderCard. */
@Composable
fun ReaderCard(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 8.dp,
    contentPadding: PaddingValues = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val cardModifier = modifier
        .fillMaxWidth()
        .clip(RoundedCornerShape(cornerRadius))
    Surface(
        modifier = if (onClick == null) cardModifier else cardModifier.clickable(
            role = Role.Button,
            onClick = onClick,
        ),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
    ) {
        Box(Modifier.padding(contentPadding)) { content() }
    }
}

/** A non-sticky section band used between reader content groups. */
@Composable
fun ReaderSectionBand(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        content()
    }
}

@Composable
fun ReaderInteractionStats(
    comments: Int?,
    likes: Int?,
    modifier: Modifier = Modifier,
    isLiked: Boolean = false,
    isLikeUpdating: Boolean = false,
    onCommentsClick: (() -> Unit)? = null,
    onLikesClick: (() -> Unit)? = null,
    commentsIcon: ImageVector,
    likesIcon: ImageVector,
    likedIcon: ImageVector = likesIcon,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val hasTwoStats = comments != null && likes != null
        if (hasTwoStats) Spacer(Modifier.weight(0.5f))
        comments?.let {
            ReaderInteractionStat(
                value = it,
                label = "评论",
                icon = commentsIcon,
                selected = false,
                enabled = true,
                onClick = onCommentsClick,
                modifier = Modifier.weight(1f),
            )
        }
        likes?.let {
            ReaderInteractionStat(
                value = it,
                label = if (isLiked) "取消点赞" else "点赞",
                icon = if (isLiked) likedIcon else likesIcon,
                selected = isLiked,
                enabled = !isLikeUpdating,
                onClick = onLikesClick,
                modifier = Modifier.weight(1f),
            )
        }
        if (hasTwoStats) Spacer(Modifier.weight(0.5f))
    }
}

@Composable
private fun ReaderInteractionStat(
    value: Int,
    label: String,
    icon: ImageVector,
    selected: Boolean,
    enabled: Boolean,
    onClick: (() -> Unit)?,
    modifier: Modifier,
) {
    val tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    val semantics = Modifier.semantics {
        contentDescription = if (label == "评论") "评论，当前${value}条评论" else "$label，当前${value}个赞"
    }
    val content: @Composable () -> Unit = {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp), tint = tint)
            Text(
                compactInteractionCount(value),
                style = MaterialTheme.typography.bodyMedium,
                color = tint,
                maxLines = 1,
            )
        }
    }
    if (onClick == null) {
        Box(modifier.heightIn(min = 48.dp).then(semantics), contentAlignment = Alignment.Center) { content() }
    } else {
        TextButton(
            onClick = onClick,
            modifier = modifier.heightIn(min = 48.dp).then(semantics),
            enabled = enabled,
            colors = ButtonDefaults.textButtonColors(contentColor = tint, disabledContentColor = tint.copy(alpha = 0.5f)),
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
        ) { content() }
    }
}

fun compactInteractionCount(value: Int): String {
    val count = value.coerceAtLeast(0)
    return when {
        count < 1_000 -> count.toString()
        count < 10_000 -> compactDecimal(count / 1_000.0) + "k"
        else -> compactDecimal(count / 10_000.0) + "w"
    }
}

internal fun interactionStatCenterFractions(count: Int): List<Float> = when {
    count <= 0 -> emptyList()
    count == 1 -> listOf(0.5f)
    else -> (1..count).map { index -> index.toFloat() / (count + 1).toFloat() }
}

private fun compactDecimal(value: Double): String {
    val rounded = kotlin.math.round(value * 10.0) / 10.0
    return if (rounded % 1.0 == 0.0) rounded.toInt().toString() else "%.1f".format(java.util.Locale.US, rounded)
}

@Composable
fun ReaderStatePane(
    state: ReaderState,
    modifier: Modifier = Modifier,
    onRetry: (() -> Unit)? = null,
) {
    Box(
        modifier = modifier.fillMaxSize().padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when (state) {
                is ReaderState.Loading -> {
                    CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 2.5.dp)
                    Text(state.message, style = MaterialTheme.typography.bodyMedium)
                }
                is ReaderState.Empty -> {
                    Text(state.title, style = MaterialTheme.typography.titleMedium)
                    state.message?.let {
                        Text(
                            it,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
                is ReaderState.Error -> {
                    Text(state.title, style = MaterialTheme.typography.titleMedium)
                    Text(
                        state.message,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    onRetry?.let { retry -> TextButton(onClick = retry) { Text(state.retryLabel) } }
                }
            }
        }
    }
}

@Composable
fun ReaderSectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    trailing: String? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.weight(1f))
        trailing?.let {
            Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun ReaderNavigationRow(
    title: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    subtitle: String? = null,
    trailing: String? = null,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        icon?.let {
            Icon(it, contentDescription = null, modifier = Modifier.size(22.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            subtitle?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        trailing?.let {
            Spacer(Modifier.width(12.dp))
            Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    HorizontalDivider(modifier = Modifier.padding(start = if (icon == null) 16.dp else 50.dp))
}
