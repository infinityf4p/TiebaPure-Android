package dev.infinityf4p.tiebapure.core.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.infinityf4p.tiebapure.core.model.ContentBlock
import dev.infinityf4p.tiebapure.core.model.ImageContent
import dev.infinityf4p.tiebapure.core.model.ReaderMediaLoadingPolicy
import dev.infinityf4p.tiebapure.core.model.VideoContent
import java.net.URI

enum class ThreadMediaPreviewKind {
    Image,
    Video,
}

data class ThreadMediaPreviewItem(
    val blockIndex: Int,
    val kind: ThreadMediaPreviewKind,
    val previewUrl: String?,
)

data class ThreadMediaPreviewSelection(
    val items: List<ThreadMediaPreviewItem>,
    val totalCount: Int,
)

sealed interface ThreadMediaPreviewAction {
    data class Images(val images: List<ImageContent>, val initialPage: Int) : ThreadMediaPreviewAction
    data class Video(val video: VideoContent) : ThreadMediaPreviewAction
}

fun resolveThreadMediaPreviewAction(
    blocks: List<ContentBlock>,
    selectedBlockIndex: Int,
): ThreadMediaPreviewAction? = when (val selected = blocks.getOrNull(selectedBlockIndex)) {
    is ContentBlock.Image -> {
        val available = blocks.mapIndexedNotNull { index, block ->
            (block as? ContentBlock.Image)?.value
                ?.takeIf { it.thumbnailUrl.nonBlankOrNull() != null || it.originalUrl.nonBlankOrNull() != null }
                ?.let { index to it }
        }
        val initialPage = available.indexOfFirst { it.first == selectedBlockIndex }
        if (initialPage < 0) null else ThreadMediaPreviewAction.Images(available.map { it.second }, initialPage)
    }
    is ContentBlock.Video -> ThreadMediaPreviewAction.Video(selected.value)
    else -> null
}

fun shouldLoadThreadMediaPreview(
    policy: ReaderMediaLoadingPolicy,
    explicitlyAuthorized: Boolean,
): Boolean = policy != ReaderMediaLoadingPolicy.Manual || explicitlyAuthorized

fun selectThreadMediaPreview(
    blocks: List<ContentBlock>,
    limit: Int = 3,
): ThreadMediaPreviewSelection {
    val available = blocks.mapIndexedNotNull { index, block ->
        when (block) {
            is ContentBlock.Image -> {
                val url = block.value.thumbnailUrl.nonBlankOrNull()
                    ?: block.value.originalUrl.nonBlankOrNull()
                    ?: return@mapIndexedNotNull null
                ThreadMediaPreviewItem(index, ThreadMediaPreviewKind.Image, url)
            }
            is ContentBlock.Video -> {
                val coverUrl = block.value.coverUrl.nonBlankOrNull()
                val hasPlaybackDestination = block.value.videoUrl.isAllowedMediaDestination(downloadableVideo = true) ||
                    block.value.webUrl.isAllowedMediaDestination(downloadableVideo = false)
                if (coverUrl == null && !hasPlaybackDestination) return@mapIndexedNotNull null
                ThreadMediaPreviewItem(index, ThreadMediaPreviewKind.Video, coverUrl)
            }
            else -> null
        }
    }
    return ThreadMediaPreviewSelection(
        items = available.take(limit.coerceIn(0, 3)),
        totalCount = available.size,
    )
}

@Composable
fun ThreadMediaPreview(
    blocks: List<ContentBlock>,
    modifier: Modifier = Modifier,
    mediaLoadingPolicy: ReaderMediaLoadingPolicy = ReaderMediaLoadingPolicy.Automatic,
    onItemClick: (ThreadMediaPreviewItem) -> Unit = {},
    renderRemoteImage: @Composable (url: String, contentDescription: String, modifier: Modifier) -> Unit,
) {
    val selection = remember(blocks) { selectThreadMediaPreview(blocks) }
    var manuallyLoadedItems by remember(blocks, mediaLoadingPolicy) { mutableStateOf(emptySet<Int>()) }
    if (selection.items.isEmpty()) return

    Box(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(if (selection.items.size == 1) 2f else 3f)
                .heightIn(min = 48.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            selection.items.forEachIndexed { visibleIndex, item ->
                val description = when (item.kind) {
                    ThreadMediaPreviewKind.Image -> "帖子图片 ${visibleIndex + 1}"
                    ThreadMediaPreviewKind.Video -> if (item.previewUrl == null) {
                        "帖子视频 ${visibleIndex + 1}"
                    } else {
                        "视频封面 ${visibleIndex + 1}"
                    }
                }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .testTag("thread-media-preview-$visibleIndex")
                        .semantics { contentDescription = description },
                    contentAlignment = Alignment.Center,
                ) {
                    val shouldLoad = item.previewUrl == null || shouldLoadThreadMediaPreview(
                        policy = mediaLoadingPolicy,
                        explicitlyAuthorized = item.blockIndex in manuallyLoadedItems,
                    )
                    if (shouldLoad) {
                        item.previewUrl?.let { previewUrl ->
                            renderRemoteImage(previewUrl, description, Modifier.fillMaxSize())
                        }
                        Box(
                            Modifier
                                .fillMaxSize()
                                .clickable { onItemClick(item) },
                        )
                    } else {
                        Text(
                            text = "点按加载",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier
                                .fillMaxSize()
                                .clickable {
                                    manuallyLoadedItems = manuallyLoadedItems + item.blockIndex
                                }
                                .semantics { contentDescription = "加载$description" }
                                .wrapContentSize(Alignment.Center),
                        )
                    }
                    if (item.kind == ThreadMediaPreviewKind.Video) {
                        Surface(
                            modifier = Modifier.size(40.dp),
                            shape = CircleShape,
                            color = Color.Black.copy(alpha = 0.62f),
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Rounded.PlayArrow,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(24.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
        val hiddenCount = selection.totalCount - selection.items.size
        if (hiddenCount > 0) {
            Text(
                text = "+$hiddenCount",
                color = Color.White,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(6.dp)
                    .background(Color.Black.copy(alpha = 0.62f), RoundedCornerShape(4.dp))
                    .padding(horizontal = 6.dp, vertical = 3.dp),
            )
        }
    }
}

private fun String?.nonBlankOrNull(): String? = this?.trim()?.takeIf(String::isNotEmpty)

private fun String?.isAllowedMediaDestination(downloadableVideo: Boolean): Boolean {
    val value = nonBlankOrNull() ?: return false
    val uri = runCatching { URI(value) }.getOrNull() ?: return false
    val host = uri.host?.trimEnd('.')?.lowercase() ?: return false
    if (uri.scheme?.lowercase() != "https" || uri.userInfo != null || uri.fragment != null) return false
    if (uri.port !in setOf(-1, 443) || uri.rawPath.isNullOrEmpty()) return false
    if (listOf("baidu.com", "bdimg.com", "bdstatic.com").none { host == it || host.endsWith(".$it") }) {
        return false
    }
    if (downloadableVideo) {
        val lastPathComponent = uri.rawPath.substringAfterLast('/').lowercase()
        if (lastPathComponent.endsWith(".m3u8") || lastPathComponent.endsWith(".m3u")) return false
    }
    return true
}
