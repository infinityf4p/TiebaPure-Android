package dev.infinityf4p.tiebapure.core.media

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import dev.infinityf4p.tiebapure.core.model.ImageContent
import dev.infinityf4p.tiebapure.core.model.VideoContent
import java.io.File

@Composable
fun RemoteImage(
    url: String?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
) {
    val safeUrl = url?.takeIf(MediaUrlPolicy::isAllowed)
    val context = LocalContext.current.applicationContext
    val loader = remember { OriginalImageLoader(context) }
    var model by remember(safeUrl) { mutableStateOf<File?>(null) }
    LaunchedEffect(safeUrl) {
        model = safeUrl?.let { runCatching { loader.load(it) }.getOrNull() }
    }
    Box(
        modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        if (model == null) {
            Text(
                text = contentDescription?.firstOrNull()?.uppercase() ?: "?",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelLarge,
            )
        } else {
            AsyncImage(
                model = model,
                contentDescription = contentDescription,
                modifier = Modifier.fillMaxSize(),
                contentScale = contentScale,
            )
        }
    }
}

@Composable
fun AvatarImage(
    url: String?,
    name: String,
    modifier: Modifier = Modifier,
) {
    RemoteImage(
        url = url,
        contentDescription = "$name 的头像",
        modifier = modifier.clip(CircleShape),
    )
}

@Composable
fun MediaGrid(
    images: List<ImageContent>,
    onImageClick: (index: Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (images.isEmpty()) return

    if (images.size == 1) {
        val image = images.first()
        RemoteImage(
            url = image.thumbnailUrl ?: image.originalUrl,
            contentDescription = "帖子图片 1",
            modifier = modifier
                .widthIn(max = 320.dp)
                .fillMaxWidth(if (image.aspectRatio >= 1.25) 1f else 0.72f)
                .aspectRatio(image.aspectRatio.toFloat().coerceIn(0.65f, 1.8f))
                .clip(RoundedCornerShape(8.dp))
                .clickable { onImageClick(0) },
        )
        return
    }

    val columns = mediaGridColumnCount(images.size)
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        images.chunked(columns).forEachIndexed { rowIndex, rowImages ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                rowImages.forEachIndexed { columnIndex, image ->
                    val index = rowIndex * columns + columnIndex
                    RemoteImage(
                        url = image.thumbnailUrl ?: image.originalUrl,
                        contentDescription = "帖子图片 ${index + 1}",
                        modifier = Modifier
                            .weight(1f)
                            .aspectRatio(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { onImageClick(index) },
                    )
                }
                repeat(columns - rowImages.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

fun mediaGridColumnCount(imageCount: Int): Int = when (imageCount) {
    2, 4 -> 2
    else -> 3
}

@Composable
fun VideoPreview(
    video: VideoContent,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(video.aspectRatio.toFloat().coerceIn(0.7f, 1.8f))
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .semantics { contentDescription = "播放视频" },
        contentAlignment = Alignment.Center,
    ) {
        RemoteImage(
            url = video.coverUrl,
            contentDescription = "视频封面",
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
        )
        Surface(
            modifier = Modifier.size(52.dp),
            shape = CircleShape,
            color = Color.Black.copy(alpha = 0.62f),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = Icons.Rounded.PlayArrow,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(30.dp),
                )
            }
        }
        if (video.durationSeconds > 0) {
            Text(
                text = formatDuration(video.durationSeconds),
                color = Color.White,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(8.dp)
                    .background(Color.Black.copy(alpha = 0.64f), RoundedCornerShape(4.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
    }
}

@Composable
internal fun OriginalImageBar(
    image: ImageContent,
    state: OriginalImageState,
    onRequestOriginal: () -> Unit,
    onDownload: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        when (state) {
            is OriginalImageState.Loading -> {
                val percent = (state.progress * 100).toInt().coerceIn(0, 100)
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(30.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color.White.copy(alpha = 0.14f)),
                ) {
                    Spacer(
                        Modifier
                            .fillMaxWidth(state.progress.coerceIn(0f, 1f))
                            .height(30.dp)
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)),
                    )
                    Text(
                        text = "$percent%",
                        color = Color.White,
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.align(Alignment.Center),
                    )
                }
            }
            OriginalImageState.Ready -> Text(
                text = "已查看原图",
                color = Color.White,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.weight(1f).padding(horizontal = 10.dp),
            )
            is OriginalImageState.Failed,
            OriginalImageState.Preview,
            -> Surface(
                onClick = onRequestOriginal,
                modifier = Modifier.weight(1f).height(36.dp),
                shape = RoundedCornerShape(6.dp),
                color = Color.White.copy(alpha = 0.14f),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = buildOriginalLabel(image, state is OriginalImageState.Failed),
                        color = Color.White,
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        IconButton(onClick = onDownload, modifier = Modifier.size(48.dp)) {
            Icon(Icons.Outlined.Download, contentDescription = "下载图片", tint = Color.White)
        }
    }
}

internal fun formatDuration(seconds: Int): String = "%d:%02d".format(seconds / 60, seconds % 60)

internal fun formatByteCount(bytes: Long?): String? = bytes?.takeIf { it > 0 }?.let {
    when {
        it >= 1024L * 1024L -> "%.1fMB".format(it.toDouble() / (1024 * 1024))
        it >= 1024L -> "%.1fKB".format(it.toDouble() / 1024)
        else -> "${it}B"
    }
}

private fun buildOriginalLabel(image: ImageContent, retry: Boolean): String {
    val prefix = if (retry) "重试原图" else "查看原图"
    return formatByteCount(image.originalSizeBytes)?.let { "$prefix $it" } ?: prefix
}
