package dev.infinityf4p.tiebapure.core.model

sealed interface ContentBlock {
    data class Text(val value: String) : ContentBlock
    data class Link(val title: String, val url: String?) : ContentBlock
    data class Mention(val userId: Long?, val text: String) : ContentBlock
    data class Emoticon(val code: String) : ContentBlock
    data class Image(val value: ImageContent) : ContentBlock
    data class Video(val value: VideoContent) : ContentBlock
    data class Voice(val value: VoiceContent) : ContentBlock
}

fun ContentBlock.plainText(): String? = when (this) {
    is ContentBlock.Text -> value
    is ContentBlock.Link -> title.ifBlank { url.orEmpty() }
    is ContentBlock.Mention -> text
    is ContentBlock.Emoticon -> code
    is ContentBlock.Voice -> "[语音]"
    is ContentBlock.Image,
    is ContentBlock.Video,
    -> null
}

data class ImageContent(
    val thumbnailUrl: String?,
    val originalUrl: String?,
    val width: Int,
    val height: Int,
    val showOriginalButton: Boolean,
    val originalSizeBytes: Long? = null,
) {
    val aspectRatio: Double
        get() = if (width > 0 && height > 0) width.toDouble() / height else 1.0
}

data class VideoContent(
    val videoUrl: String?,
    val coverUrl: String?,
    val webUrl: String?,
    val width: Int,
    val height: Int,
    val durationSeconds: Int,
) {
    val aspectRatio: Double
        get() = if (width > 0 && height > 0) width.toDouble() / height else 16.0 / 9.0
}

data class VoiceContent private constructor(
    val md5: String,
    val durationMilliseconds: Int,
) {
    companion object {
        private val md5Pattern = Regex("^[0-9a-f]{32}$")

        fun create(md5: String, durationMilliseconds: Int): VoiceContent? {
            val normalized = md5.trim().lowercase()
            if (!md5Pattern.matches(normalized)) return null
            return VoiceContent(normalized, durationMilliseconds.coerceAtLeast(0))
        }
    }
}
