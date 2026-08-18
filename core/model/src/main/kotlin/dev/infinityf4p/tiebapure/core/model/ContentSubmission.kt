package dev.infinityf4p.tiebapure.core.model

enum class ContentSubmissionKind { NewThread, ThreadReply, PostReply, SubpostReply }

data class ContentSubmissionTarget(
    val kind: ContentSubmissionKind,
    val forumId: Long,
    val forumName: String,
    val threadId: Long? = null,
    val parentPostId: ULong? = null,
    val parentFloor: Int? = null,
    val subpostId: ULong? = null,
    val replyUser: UserSummary? = null,
)

data class ContentSubmissionImage(
    val bytes: ByteArray,
    val mimeType: String,
) {
    override fun equals(other: Any?): Boolean = other is ContentSubmissionImage &&
        mimeType == other.mimeType && bytes.contentEquals(other.bytes)

    override fun hashCode(): Int = 31 * bytes.contentHashCode() + mimeType.hashCode()
}

data class ContentSubmissionRequest(
    val target: ContentSubmissionTarget,
    val title: String = "",
    val body: String,
    val images: List<ContentSubmissionImage> = emptyList(),
)

data class ContentSubmissionReceipt(
    val threadId: Long,
    val postId: ULong?,
)

data class SubmissionVerificationChallenge(
    val verificationType: String,
    val md5: String,
    val imageUrl: String?,
    val message: String,
)

sealed class ContentSubmissionValidationException(message: String) : IllegalArgumentException(message) {
    data object MissingForum : ContentSubmissionValidationException("缺少贴吧信息。")
    data object MissingThread : ContentSubmissionValidationException("缺少帖子信息。")
    data object MissingTitle : ContentSubmissionValidationException("请输入标题。")
    data object MissingBody : ContentSubmissionValidationException("请输入正文。")
    data object ImagesUnsupportedForNewThread : ContentSubmissionValidationException("发布新主题暂不支持图片。")
    data class TitleTooLong(val maximum: Int) : ContentSubmissionValidationException("标题不能超过 $maximum 个字符。")
    data class BodyTooLong(val maximum: Int) : ContentSubmissionValidationException("正文不能超过 $maximum 个字符。")
    data class TooManyImages(val maximum: Int) : ContentSubmissionValidationException("最多选择 $maximum 张图片。")
    data class ImageTooLarge(val maximumBytes: Int) : ContentSubmissionValidationException("单张图片不能超过 ${maximumBytes / 1_024 / 1_024} MiB。")
    data class UnsupportedImage(val mimeType: String) : ContentSubmissionValidationException("不支持的图片格式：$mimeType")
}

object ContentSubmissionPolicy {
    const val maximumTitleCharacters = 64
    const val maximumBodyCharacters = 10_000
    const val maximumImages = 9
    const val maximumImageBytes = 10 * 1_024 * 1_024
    const val maximumPixelDimension = 20_000
    const val maximumPixelCount = 80_000_000L

    private val allowedImageTypes = setOf(
        "image/gif", "image/heic", "image/heif", "image/jpeg", "image/png", "image/tiff", "image/webp",
    )

    fun validate(request: ContentSubmissionRequest): ContentSubmissionRequest {
        if (request.target.forumId <= 0 || request.target.forumName.isBlank()) {
            throw ContentSubmissionValidationException.MissingForum
        }
        val title = request.title.trim()
        val body = request.body.trim()
        if (request.target.kind == ContentSubmissionKind.NewThread) {
            if (title.isEmpty()) throw ContentSubmissionValidationException.MissingTitle
            if (request.images.isNotEmpty()) throw ContentSubmissionValidationException.ImagesUnsupportedForNewThread
        } else if ((request.target.threadId ?: 0) <= 0) {
            throw ContentSubmissionValidationException.MissingThread
        }
        if (body.isEmpty()) throw ContentSubmissionValidationException.MissingBody
        if (title.length > maximumTitleCharacters) {
            throw ContentSubmissionValidationException.TitleTooLong(maximumTitleCharacters)
        }
        if (body.length > maximumBodyCharacters) {
            throw ContentSubmissionValidationException.BodyTooLong(maximumBodyCharacters)
        }
        if (request.images.size > maximumImages) {
            throw ContentSubmissionValidationException.TooManyImages(maximumImages)
        }
        request.images.forEach { image ->
            val mime = image.mimeType.lowercase()
            if (mime !in allowedImageTypes) throw ContentSubmissionValidationException.UnsupportedImage(mime)
            if (image.bytes.size > maximumImageBytes) {
                throw ContentSubmissionValidationException.ImageTooLarge(maximumImageBytes)
            }
        }
        return request.copy(title = title, body = body)
    }
}
