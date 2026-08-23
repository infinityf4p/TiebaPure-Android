package dev.infinityf4p.tiebapure.feature.thread

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.LinkInteractionListener
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.infinityf4p.tiebapure.core.media.MediaGrid
import dev.infinityf4p.tiebapure.core.media.RemoteEmoticonImage
import dev.infinityf4p.tiebapure.core.media.VideoPreview
import dev.infinityf4p.tiebapure.core.media.VoicePlaybackControl
import dev.infinityf4p.tiebapure.core.model.ContentBlock
import dev.infinityf4p.tiebapure.core.model.ImageContent
import dev.infinityf4p.tiebapure.core.model.ReaderMediaLoadingPolicy
import dev.infinityf4p.tiebapure.core.model.ReadingPreferences
import dev.infinityf4p.tiebapure.core.designsystem.readerFontFamily
import dev.infinityf4p.tiebapure.core.model.TiebaEmoticon
import dev.infinityf4p.tiebapure.core.model.VideoContent
import dev.infinityf4p.tiebapure.core.model.VoiceContent

private const val EmoticonTagPrefix = "emoticon-"

val LocalReadingPreferences = staticCompositionLocalOf { ReadingPreferences() }

internal enum class RichContentTextStyle { Body, Reply, Subpost }

internal sealed interface RichContentGroup {
    data class Inline(val blocks: List<ContentBlock>) : RichContentGroup
    data class Media(val blocks: List<ContentBlock>) : RichContentGroup
    data class Voice(val voice: VoiceContent) : RichContentGroup
}

internal data class RichMediaGallerySelection(
    val images: List<ImageContent>,
    val initialPage: Int,
)

internal fun resolveRichMediaGallery(
    mediaBlocks: List<ContentBlock>,
    selectedBlockIndex: Int,
): RichMediaGallerySelection? {
    if (mediaBlocks.getOrNull(selectedBlockIndex) !is ContentBlock.Image) return null
    val indexedImages = mediaBlocks.mapIndexedNotNull { blockIndex, block ->
        (block as? ContentBlock.Image)?.value?.let { blockIndex to it }
    }
    val initialPage = indexedImages.indexOfFirst { it.first == selectedBlockIndex }
    return if (initialPage < 0) null else RichMediaGallerySelection(
        images = indexedImages.map { it.second },
        initialPage = initialPage,
    )
}

internal fun groupRichContent(blocks: List<ContentBlock>): List<RichContentGroup> {
    if (blocks.isEmpty()) return emptyList()

    val result = mutableListOf<RichContentGroup>()
    val inline = mutableListOf<ContentBlock>()
    val media = mutableListOf<ContentBlock>()

    fun flushInline() {
        if (inline.isNotEmpty()) {
            result += RichContentGroup.Inline(inline.toList())
            inline.clear()
        }
    }

    fun flushMedia() {
        if (media.isNotEmpty()) {
            result += RichContentGroup.Media(media.toList())
            media.clear()
        }
    }

    blocks.forEach { block ->
        when (block) {
            is ContentBlock.Text,
            is ContentBlock.Link,
            is ContentBlock.Mention,
            is ContentBlock.Emoticon,
            -> {
                flushMedia()
                inline += block
            }
            is ContentBlock.Image,
            is ContentBlock.Video,
            -> {
                flushInline()
                media += block
            }
            is ContentBlock.Voice -> {
                flushInline()
                flushMedia()
                result += RichContentGroup.Voice(block.value)
            }
        }
    }
    flushInline()
    flushMedia()
    return result
}

internal data class RichMediaLoadingPlan(
    val loadsImagePreviewsAutomatically: Boolean,
    val loadsVideoPreviewsAutomatically: Boolean,
    val loadsEmoticonsAutomatically: Boolean,
    val allowsOriginalImageFallback: Boolean,
) {
    fun previewImages(
        images: List<ImageContent>,
        explicitlyAuthorized: Boolean,
    ): List<ImageContent>? {
        if (!loadsImagePreviewsAutomatically && !explicitlyAuthorized) return null
        if (allowsOriginalImageFallback) return images
        return images.map { it.copy(originalUrl = null) }
    }

    fun showsVideoPreview(explicitlyAuthorized: Boolean): Boolean =
        loadsVideoPreviewsAutomatically || explicitlyAuthorized

    companion object {
        fun resolve(policy: ReaderMediaLoadingPolicy): RichMediaLoadingPlan = when (policy) {
            ReaderMediaLoadingPolicy.Automatic -> RichMediaLoadingPlan(
                loadsImagePreviewsAutomatically = true,
                loadsVideoPreviewsAutomatically = true,
                loadsEmoticonsAutomatically = true,
                allowsOriginalImageFallback = true,
            )
            ReaderMediaLoadingPolicy.DataSaving -> RichMediaLoadingPlan(
                loadsImagePreviewsAutomatically = true,
                loadsVideoPreviewsAutomatically = true,
                loadsEmoticonsAutomatically = true,
                allowsOriginalImageFallback = false,
            )
            ReaderMediaLoadingPolicy.Manual -> RichMediaLoadingPlan(
                loadsImagePreviewsAutomatically = false,
                loadsVideoPreviewsAutomatically = false,
                loadsEmoticonsAutomatically = false,
                allowsOriginalImageFallback = true,
            )
        }
    }
}

@Composable
internal fun RichContent(
    blocks: List<ContentBlock>,
    onLinkClick: (String) -> Unit,
    onUserClick: (Long) -> Unit,
    onImagesClick: (images: List<ImageContent>, index: Int) -> Unit,
    onVideoClick: (VideoContent) -> Unit,
    modifier: Modifier = Modifier,
    textColor: Color = MaterialTheme.colorScheme.onSurface,
    textStyle: RichContentTextStyle = RichContentTextStyle.Body,
) {
    val readingPreferences = LocalReadingPreferences.current
    val linkColor = MaterialTheme.colorScheme.primary
    val groups = remember(blocks) { groupRichContent(blocks) }
    val loadingPlan = remember(readingPreferences.mediaLoading) {
        RichMediaLoadingPlan.resolve(readingPreferences.mediaLoading)
    }
    var explicitlyLoadedGroups by remember(blocks, readingPreferences.mediaLoading) {
        mutableStateOf(emptySet<Pair<Int, Int>>())
    }
    var failedEmoticons by remember(blocks, readingPreferences.mediaLoading) {
        mutableStateOf(emptySet<String>())
    }
    val baseStyle = when (textStyle) {
        RichContentTextStyle.Body -> MaterialTheme.typography.bodyLarge
        RichContentTextStyle.Reply -> MaterialTheme.typography.bodyMedium.copy(fontSize = 16.sp, lineHeight = 22.sp)
        RichContentTextStyle.Subpost -> MaterialTheme.typography.bodyMedium
    }
    val fontSize = (baseStyle.fontSize.value * readingPreferences.fontSize.scale).sp
    val lineHeight = (
        baseStyle.lineHeight.value * readingPreferences.fontSize.scale * readingPreferences.lineSpacing.multiplier
    ).coerceAtLeast(fontSize.value * 1.1f).sp

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        groups.forEachIndexed { index, group ->
            when (group) {
                is RichContentGroup.Inline -> {
                    val richText = remember(group.blocks, linkColor, onLinkClick, onUserClick) {
                        buildRichText(group.blocks, linkColor, onLinkClick, onUserClick)
                    }
                    if (richText.annotated.isNotBlank()) {
                        BasicText(
                            text = richText.annotated,
                            style = baseStyle.copy(
                                color = textColor,
                                fontSize = fontSize,
                                lineHeight = lineHeight,
                                fontFamily = readerFontFamily(readingPreferences.fontFamily),
                            ),
                            inlineContent = richText.emoticons
                                .filterNot { "$index:${it.tag}" in failedEmoticons }
                                .associate { emoticon ->
                                emoticon.tag to InlineTextContent(
                                    Placeholder(
                                        width = 1.35.em,
                                        height = 1.35.em,
                                        placeholderVerticalAlign = PlaceholderVerticalAlign.TextCenter,
                                    ),
                                ) {
                                    RemoteEmoticonImage(
                                        code = emoticon.code,
                                        loadsAutomatically = loadingPlan.loadsEmoticonsAutomatically,
                                        modifier = Modifier.fillMaxSize(),
                                        onLoadFailed = {
                                            failedEmoticons += "$index:${emoticon.tag}"
                                        },
                                    )
                                }
                            },
                        )
                    }
                }
                is RichContentGroup.Media -> {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        group.blocks.forEachIndexed { mediaIndex, block ->
                            val loadKey = index to mediaIndex
                            when (block) {
                                is ContentBlock.Image -> {
                                    val previewImages = loadingPlan.previewImages(
                                        images = listOf(block.value),
                                        explicitlyAuthorized = loadKey in explicitlyLoadedGroups,
                                    )
                                    if (previewImages == null) {
                                        MediaLoadButton("加载图片") {
                                            explicitlyLoadedGroups += loadKey
                                        }
                                    } else {
                                        MediaGrid(
                                            images = previewImages,
                                            onImageClick = {
                                                resolveRichMediaGallery(group.blocks, mediaIndex)?.let { selection ->
                                                    onImagesClick(selection.images, selection.initialPage)
                                                }
                                            },
                                            modifier = Modifier.fillMaxWidth(),
                                        )
                                    }
                                }
                                is ContentBlock.Video -> {
                                    if (loadingPlan.showsVideoPreview(loadKey in explicitlyLoadedGroups)) {
                                        VideoPreview(
                                            video = block.value,
                                            onClick = { onVideoClick(block.value) },
                                            modifier = Modifier.fillMaxWidth(),
                                        )
                                    } else {
                                        MediaLoadButton("加载视频") {
                                            explicitlyLoadedGroups += loadKey
                                        }
                                    }
                                }
                                else -> Unit
                            }
                        }
                    }
                }
                is RichContentGroup.Voice -> VoicePlaybackControl(voice = group.voice)
            }
        }
    }
}

@Composable
private fun MediaLoadButton(
    label: String,
    onClick: () -> Unit,
) {
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
    ) {
        Text(label)
    }
}

internal data class RichInlineEmoticon(
    val tag: String,
    val code: String,
)

internal data class RichTextContent(
    val annotated: AnnotatedString,
    val emoticons: List<RichInlineEmoticon>,
)

internal fun buildRichText(
    blocks: List<ContentBlock>,
    linkColor: Color,
    onLinkClick: (String) -> Unit,
    onUserClick: (Long) -> Unit,
): RichTextContent {
    val emoticons = mutableListOf<RichInlineEmoticon>()
    val annotated = buildAnnotatedString {
        blocks.forEachIndexed { index, block ->
            when (block) {
                is ContentBlock.Text -> append(block.value)
                is ContentBlock.Link -> {
                    val value = block.url.orEmpty()
                    val text = block.title.ifBlank { value }
                    if (value.isBlank()) {
                        append(text)
                    } else {
                        pushLink(
                            LinkAnnotation.Clickable(
                                tag = value,
                                styles = TextLinkStyles(
                                    style = SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline),
                                ),
                                linkInteractionListener = LinkInteractionListener { onLinkClick(value) },
                            ),
                        )
                        append(text)
                        pop()
                    }
                }
                is ContentBlock.Mention -> {
                    val userId = block.userId?.takeIf { it > 0L }
                    if (userId == null) {
                        pushStyle(SpanStyle(color = linkColor))
                        append(block.text)
                        pop()
                    } else {
                        pushLink(
                            LinkAnnotation.Clickable(
                                tag = "user-$userId",
                                styles = TextLinkStyles(style = SpanStyle(color = linkColor)),
                                linkInteractionListener = LinkInteractionListener { onUserClick(userId) },
                            ),
                        )
                        append(block.text)
                        pop()
                    }
                }
                is ContentBlock.Emoticon -> {
                    if (TiebaEmoticon.imageNameFor(block.code) == null) {
                        append(TiebaEmoticon.displayText(block.code))
                    } else {
                        val tag = "$EmoticonTagPrefix$index"
                        emoticons += RichInlineEmoticon(tag, block.code)
                        appendInlineContent(tag, TiebaEmoticon.displayText(block.code))
                    }
                }
                is ContentBlock.Voice -> Unit
                is ContentBlock.Image,
                is ContentBlock.Video,
                -> Unit
            }
        }
    }
    return RichTextContent(annotated, emoticons)
}

private fun AnnotatedString.isNotBlank(): Boolean = text.isNotBlank()
