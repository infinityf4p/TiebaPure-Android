package dev.infinityf4p.tiebapure.feature.thread

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.ThumbUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.LinkInteractionListener
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.infinityf4p.tiebapure.core.designsystem.ReaderCard
import dev.infinityf4p.tiebapure.core.designsystem.compactInteractionCount
import dev.infinityf4p.tiebapure.core.media.AvatarImage
import dev.infinityf4p.tiebapure.core.model.ContentBlock
import dev.infinityf4p.tiebapure.core.model.ImageContent
import dev.infinityf4p.tiebapure.core.model.Post
import dev.infinityf4p.tiebapure.core.model.Subpost
import dev.infinityf4p.tiebapure.core.model.TiebaEmoticon
import dev.infinityf4p.tiebapure.core.model.UserSummary
import dev.infinityf4p.tiebapure.core.model.VideoContent
import dev.infinityf4p.tiebapure.core.model.plainText
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

@Composable
internal fun AuthorLine(
    author: UserSummary,
    floor: Int?,
    likeCount: Int,
    isLiked: Boolean,
    isLikeUpdating: Boolean,
    onToggleLike: (() -> Unit)?,
    onUserClick: (Long) -> Unit,
    modifier: Modifier = Modifier,
    isThreadAuthor: Boolean = false,
    isMainPost: Boolean = false,
) {
    Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clickable(enabled = author.id > 0L) { onUserClick(author.id) },
            contentAlignment = Alignment.CenterStart,
        ) {
            AvatarImage(
                url = author.portrait,
                name = author.resolvedDisplayName,
                modifier = Modifier.size(if (isMainPost) 40.dp else 36.dp),
            )
        }
        if (isMainPost) Spacer(Modifier.width(4.dp))
        Row(
            modifier = Modifier
                .weight(1f)
                .heightIn(min = 48.dp)
                .clickable(enabled = author.id > 0L) { onUserClick(author.id) },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = author.resolvedDisplayName,
                modifier = Modifier.weight(1f, fill = false),
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            author.level?.takeIf { it > 0 }?.let { level ->
                IdentityBadge(
                    text = userLevelBadgeText(level, author.levelName),
                    background = Color(0xFFFF9500).copy(alpha = 0.18f),
                    contentColor = MaterialTheme.colorScheme.onSurface,
                )
            }
            floor?.takeIf { it > 0 && !isMainPost }?.let {
                IdentityBadge(
                    text = "${it}楼",
                    background = MaterialTheme.colorScheme.surfaceContainerHigh,
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (isThreadAuthor) {
                IdentityBadge(
                    text = "楼主",
                    background = MaterialTheme.colorScheme.primary.copy(alpha = 0.16f),
                    contentColor = MaterialTheme.colorScheme.primary,
                )
            }
        }
        if (likeCount > 0 || onToggleLike != null) {
            val description = if (isLiked) "取消点赞" else "点赞"
            Row(
                modifier = Modifier
                    .testTag("thread-like-action")
                    .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                    .semantics {
                        contentDescription = description
                        stateDescription = "当前${likeCount}个赞"
                        if (onToggleLike != null) role = Role.Button
                    }
                    .then(
                        if (onToggleLike != null) {
                            Modifier.clickable(enabled = !isLikeUpdating, onClick = onToggleLike)
                        } else {
                            Modifier
                        },
                    )
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.End,
            ) {
                if (isLikeUpdating) {
                    CircularProgressIndicator(Modifier.size(17.dp), strokeWidth = 2.dp)
                } else {
                    Icon(
                        Icons.Outlined.ThumbUp,
                        contentDescription = null,
                        modifier = Modifier.size(17.dp),
                        tint = if (isLiked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (likeCount > 0) {
                    Spacer(Modifier.width(4.dp))
                    Text(
                        compactInteractionCount(likeCount),
                        style = MaterialTheme.typography.labelMedium,
                        color = if (isLiked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

@Composable
private fun IdentityBadge(text: String, background: Color, contentColor: Color) {
    Surface(shape = RoundedCornerShape(4.dp), color = background, contentColor = contentColor) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
    }
}

@Composable
internal fun PostBody(
    post: Post,
    onReply: ((Post) -> Unit)?,
    onUserClick: (Long) -> Unit,
    onLinkClick: (String) -> Unit,
    onImagesClick: (List<ImageContent>, Int) -> Unit,
    onVideoClick: (VideoContent) -> Unit,
    onOpenSubposts: (Post) -> Unit,
    modifier: Modifier = Modifier,
    isMainPost: Boolean = false,
    threadAuthorId: Long? = null,
    showSubpostPreview: Boolean = true,
) {
    val hasPreview = showSubpostPreview && post.previewSubposts.isNotEmpty()
    val placement = threadPostMetadataPlacement(isMainPost, hasPreview)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .testTag("thread-post-body-${post.id}")
            .then(if (onReply == null) Modifier else Modifier.clickable { onReply(post) }),
        verticalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        RichContent(
            blocks = post.blocks,
            onLinkClick = onLinkClick,
            onUserClick = onUserClick,
            onImagesClick = onImagesClick,
            onVideoClick = onVideoClick,
            modifier = Modifier.fillMaxWidth(),
            textStyle = if (isMainPost) RichContentTextStyle.Body else RichContentTextStyle.Reply,
        )
        MetadataLine(
            createdAtEpochSeconds = post.createdAtEpochSeconds,
            ipAddress = post.ipAddress ?: post.author.ipAddress,
            placement = placement,
            onReply = onReply?.let { reply -> { reply(post) } },
        )
        if (hasPreview) {
            SubpostPreview(
                post = post,
                threadAuthorId = threadAuthorId,
                onUserClick = onUserClick,
                onOpen = { onOpenSubposts(post) },
            )
        }
    }
}

@Composable
private fun MetadataLine(
    createdAtEpochSeconds: Long?,
    ipAddress: String?,
    placement: ThreadPostMetadataPlacement,
    onReply: (() -> Unit)?,
) {
    val readingPreferences = LocalReadingPreferences.current
    val textMetrics = readerScaledTextMetrics(
        baseFontSize = MaterialTheme.typography.bodySmall.fontSize.value,
        baseLineHeight = MaterialTheme.typography.bodySmall.lineHeight.value,
        fontScale = readingPreferences.fontSize.scale,
        lineSpacingMultiplier = readingPreferences.lineSpacing.multiplier,
    )
    val visualHeight = maxOf(placement.visualHeight.toFloat(), textMetrics.lineHeight + 2f)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = placement.topSpacing.dp, bottom = placement.bottomSpacing.dp)
            .height(visualHeight.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = listOfNotNull(
                formatThreadTimestamp(createdAtEpochSeconds),
                normalizeThreadLocation(ipAddress),
            ).joinToString("  "),
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodySmall.copy(
                fontSize = textMetrics.fontSize.sp,
                lineHeight = textMetrics.lineHeight.sp,
            ),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (onReply != null) {
            Row(
                modifier = Modifier
                    .testTag("thread-metadata-reply")
                    .sizeIn(minWidth = 56.dp, minHeight = visualHeight.dp)
                    .clickable(onClick = onReply),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.End,
            ) {
                Icon(
                    Icons.Outlined.ChatBubbleOutline,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    "回复",
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontSize = textMetrics.fontSize.sp,
                        lineHeight = textMetrics.lineHeight.sp,
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun SubpostPreview(
    post: Post,
    threadAuthorId: Long?,
    onUserClick: (Long) -> Unit,
    onOpen: () -> Unit,
) {
    val visibleSubposts = post.previewSubposts.take(3)
    val readingPreferences = LocalReadingPreferences.current
    val textMetrics = readerScaledTextMetrics(
        baseFontSize = MaterialTheme.typography.bodyMedium.fontSize.value,
        baseLineHeight = MaterialTheme.typography.bodyMedium.lineHeight.value,
        fontScale = readingPreferences.fontSize.scale,
        lineSpacingMultiplier = readingPreferences.lineSpacing.multiplier,
    )
    val previewStyle = MaterialTheme.typography.bodyMedium.copy(
        fontSize = textMetrics.fontSize.sp,
        lineHeight = textMetrics.lineHeight.sp,
    )
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(
            Modifier.padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 4.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            visibleSubposts.forEach { subpost ->
                val authorColor = MaterialTheme.colorScheme.onSurfaceVariant
                val primaryColor = MaterialTheme.colorScheme.primary
                val richPreview = buildAnnotatedString {
                    val validAuthorId = subpost.author.id.takeIf { it > 0L }
                    if (validAuthorId != null) {
                        pushLink(
                            LinkAnnotation.Clickable(
                                tag = "user-$validAuthorId",
                                styles = TextLinkStyles(style = SpanStyle(color = authorColor, fontWeight = FontWeight.Medium)),
                                linkInteractionListener = LinkInteractionListener { onUserClick(validAuthorId) },
                            ),
                        )
                    } else {
                        pushStyle(SpanStyle(color = authorColor, fontWeight = FontWeight.Medium))
                    }
                    append(subpost.author.resolvedDisplayName)
                    pop()
                    if (threadAuthorId != null && threadAuthorId > 0L && subpost.author.id == threadAuthorId) {
                        append(" ")
                        pushStyle(
                            SpanStyle(
                                color = primaryColor,
                                background = primaryColor.copy(alpha = 0.16f),
                                fontWeight = FontWeight.Bold,
                            ),
                        )
                        append("楼主")
                        pop()
                    }
                    append(": ${subpostPreviewText(subpost.blocks)}")
                }
                Text(
                    text = richPreview,
                    modifier = Modifier.fillMaxWidth(),
                    style = previewStyle,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (post.subpostCount > visibleSubposts.size) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(SUBPOST_OPEN_ALL_TOUCH_HEIGHT_DP.dp)
                        .testTag("thread-subpost-open-all")
                        .clickable(onClick = onOpen),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(
                        modifier = Modifier
                            .height(SUBPOST_OPEN_ALL_VISUAL_HEIGHT_DP.dp)
                            .testTag("thread-subpost-open-all-visual"),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "查看全部${post.subpostCount}条回复",
                            style = MaterialTheme.typography.labelMedium.copy(
                                fontSize = textMetrics.fontSize.sp,
                                lineHeight = textMetrics.lineHeight.sp,
                            ),
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Icon(
                            Icons.Outlined.ChevronRight,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun SubpostRow(
    subpost: Subpost,
    onReply: ((Subpost) -> Unit)?,
    onUserClick: (Long) -> Unit,
    onLinkClick: (String) -> Unit,
    onImagesClick: (List<ImageContent>, Int) -> Unit,
    onVideoClick: (VideoContent) -> Unit,
    isLikeUpdating: Boolean,
    onToggleLike: (() -> Unit)?,
    threadAuthorId: Long? = null,
) {
    ReaderCard(
        cornerRadius = 0.dp,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            start = 16.dp,
            top = 12.dp,
            end = 16.dp,
            bottom = ThreadPostMetadataPlacement.StandaloneReply.cardBottomPadding.dp,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("thread-subpost-body-${subpost.id}")
                .then(if (onReply == null) Modifier else Modifier.clickable { onReply(subpost) }),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            AuthorLine(
                author = subpost.author,
                floor = subpost.floor,
                likeCount = subpost.likeCount,
                isLiked = subpost.isLiked,
                isLikeUpdating = isLikeUpdating,
                onToggleLike = onToggleLike,
                onUserClick = onUserClick,
                isThreadAuthor = threadAuthorId != null && threadAuthorId > 0L && subpost.author.id == threadAuthorId,
            )
            Column(Modifier.padding(start = 48.dp)) {
                RichContent(
                    blocks = subpost.blocks,
                    onLinkClick = onLinkClick,
                    onUserClick = onUserClick,
                    onImagesClick = onImagesClick,
                    onVideoClick = onVideoClick,
                    textStyle = RichContentTextStyle.Subpost,
                )
                MetadataLine(
                    subpost.createdAtEpochSeconds,
                    subpost.ipAddress ?: subpost.author.ipAddress,
                    ThreadPostMetadataPlacement.StandaloneReply,
                    onReply?.let { reply -> { reply(subpost) } },
                )
            }
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

internal enum class ThreadPostMetadataPlacement(
    val visualHeight: Int,
    val topSpacing: Int,
    val bottomSpacing: Int,
    val cardBottomPadding: Int,
) {
    MainPost(visualHeight = 28, topSpacing = 4, bottomSpacing = 0, cardBottomPadding = 12),
    StandaloneReply(visualHeight = 20, topSpacing = 6, bottomSpacing = 0, cardBottomPadding = 6),
    BeforeSubpostPreview(visualHeight = 20, topSpacing = 6, bottomSpacing = 6, cardBottomPadding = 12),
}

internal data class ReaderScaledTextMetrics(val fontSize: Float, val lineHeight: Float)

internal fun readerScaledTextMetrics(
    baseFontSize: Float,
    baseLineHeight: Float,
    fontScale: Float,
    lineSpacingMultiplier: Float,
): ReaderScaledTextMetrics {
    val resolvedFontSize = baseFontSize * fontScale.coerceAtLeast(0.5f)
    val resolvedLineHeight = (baseLineHeight * fontScale.coerceAtLeast(0.5f) * lineSpacingMultiplier.coerceAtLeast(0.5f))
        .coerceAtLeast(resolvedFontSize * 1.1f)
    return ReaderScaledTextMetrics(resolvedFontSize, resolvedLineHeight)
}

internal fun replyFilterControlHeight(metrics: ReaderScaledTextMetrics): Float =
    maxOf(48f, metrics.lineHeight + 12f)

internal fun threadPostMetadataPlacement(
    isMainPost: Boolean,
    hasPreviewSubposts: Boolean,
): ThreadPostMetadataPlacement = when {
    isMainPost -> ThreadPostMetadataPlacement.MainPost
    hasPreviewSubposts -> ThreadPostMetadataPlacement.BeforeSubpostPreview
    else -> ThreadPostMetadataPlacement.StandaloneReply
}

internal fun userLevelBadgeText(level: Int, levelName: String?): String {
    val normalizedName = levelName.orEmpty().replace("\n", "").trim()
    return if (normalizedName.isEmpty()) "Lv.$level" else "$level $normalizedName"
}

internal fun normalizeThreadLocation(value: String?): String? {
    var normalized = value?.trim()?.takeIf(String::isNotEmpty) ?: return null
    listOf("IP属地：", "IP属地:", "来自").firstOrNull(normalized::startsWith)?.let { prefix ->
        normalized = normalized.removePrefix(prefix).trim()
    }
    return normalized.takeIf(String::isNotEmpty)
}

internal fun subpostPreviewText(blocks: List<ContentBlock>): String = blocks.joinToString("") { block ->
    when (block) {
        is ContentBlock.Emoticon -> TiebaEmoticon.displayText(block.code)
        else -> block.plainText().orEmpty()
    }
}

internal fun formatCount(value: Int): String = when {
    value < 1_000 -> value.toString()
    value < 10_000 -> compactCount(value / 1_000.0, "k")
    else -> compactCount(value / 10_000.0, "w")
}

private fun compactCount(value: Double, suffix: String): String =
    "%.1f%s".format(Locale.US, value, suffix)

internal fun formatThreadTimestamp(
    epochSeconds: Long?,
    nowMillis: Long = System.currentTimeMillis(),
    locale: Locale = Locale.getDefault(),
    timeZone: TimeZone = TimeZone.getDefault(),
): String? {
    val seconds = epochSeconds?.takeIf { it > 0 } ?: return null
    val timestampMillis = seconds * 1_000L
    val elapsedSeconds = ((nowMillis - timestampMillis) / 1_000L).coerceAtLeast(0L)
    if (elapsedSeconds < 60L) return "刚刚"
    if (elapsedSeconds < 3_600L) return "${elapsedSeconds / 60L}分钟前"

    val now = Calendar.getInstance(timeZone, locale).apply { timeInMillis = nowMillis }
    val value = Calendar.getInstance(timeZone, locale).apply { timeInMillis = timestampMillis }
    val sameDay = now.get(Calendar.ERA) == value.get(Calendar.ERA) &&
        now.get(Calendar.YEAR) == value.get(Calendar.YEAR) &&
        now.get(Calendar.DAY_OF_YEAR) == value.get(Calendar.DAY_OF_YEAR)
    if (sameDay) return SimpleDateFormat("HH:mm", locale).apply { this.timeZone = timeZone }.format(Date(timestampMillis))

    val yesterday = (now.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, -1) }
    val wasYesterday = yesterday.get(Calendar.ERA) == value.get(Calendar.ERA) &&
        yesterday.get(Calendar.YEAR) == value.get(Calendar.YEAR) &&
        yesterday.get(Calendar.DAY_OF_YEAR) == value.get(Calendar.DAY_OF_YEAR)
    if (wasYesterday) {
        return "昨天 ${SimpleDateFormat("HH:mm", locale).apply { this.timeZone = timeZone }.format(Date(timestampMillis))}"
    }
    val pattern = if (now.get(Calendar.YEAR) == value.get(Calendar.YEAR)) "MM-dd" else "yyyy-MM-dd"
    return SimpleDateFormat(pattern, locale).apply { this.timeZone = timeZone }.format(Date(timestampMillis))
}
