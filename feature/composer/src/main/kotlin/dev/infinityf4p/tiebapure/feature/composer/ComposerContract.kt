package dev.infinityf4p.tiebapure.feature.composer

import dev.infinityf4p.tiebapure.core.model.ContentSubmissionImage
import dev.infinityf4p.tiebapure.core.model.ContentSubmissionReceipt
import dev.infinityf4p.tiebapure.core.model.ContentSubmissionRequest
import dev.infinityf4p.tiebapure.core.model.ContentSubmissionTarget
import dev.infinityf4p.tiebapure.core.model.SubmissionVerificationChallenge
import kotlinx.coroutines.flow.Flow

data class ComposerSubmissionCapability(
    val canSubmit: Boolean,
    val unavailableReason: String? = null,
) {
    fun resolvedUnavailableReason(kind: dev.infinityf4p.tiebapure.core.model.ContentSubmissionKind): String? {
        if (canSubmit) return null
        return unavailableReason?.trim()?.takeIf(String::isNotEmpty) ?: when (kind) {
            dev.infinityf4p.tiebapure.core.model.ContentSubmissionKind.NewThread ->
                "请先在设置中开启允许发帖。"
            else -> "请先在设置中开启允许回帖。"
        }
    }

    companion object {
        val Enabled = ComposerSubmissionCapability(canSubmit = true)

        fun fromSettings(
            kind: dev.infinityf4p.tiebapure.core.model.ContentSubmissionKind,
            enabled: Boolean,
        ): ComposerSubmissionCapability = if (enabled) {
            Enabled
        } else {
            ComposerSubmissionCapability(
                canSubmit = false,
                unavailableReason = when (kind) {
                    dev.infinityf4p.tiebapure.core.model.ContentSubmissionKind.NewThread ->
                        "请先在设置中开启允许发帖。"
                    else -> "请先在设置中开启允许回帖。"
                },
            )
        }
    }
}

data class ComposerDraft(
    val accountId: String,
    val target: ContentSubmissionTarget,
    val title: String,
    val body: String,
    val images: List<ContentSubmissionImage>,
    val updatedAtEpochMillis: Long,
    val storedImageCount: Int = images.size,
    val storageRevision: String? = null,
) {
    val targetKey: String get() = composerTargetKey(target)
    val request: ContentSubmissionRequest get() = ContentSubmissionRequest(target, title, body, images)
}

sealed interface ComposerSubmissionResult {
    data class Success(val receipt: ContentSubmissionReceipt) : ComposerSubmissionResult
    data class VerificationRequired(
        val challenge: SubmissionVerificationChallenge,
    ) : ComposerSubmissionResult

    /** The server may have accepted the content, so the client must not retry automatically. */
    data class OutcomeUnknown(val message: String) : ComposerSubmissionResult
}

/**
 * Persistence and write-request boundary. Production code may adapt Room and
 * the authenticated Tieba protocol client; this feature intentionally contains
 * no real account request implementation.
 */
interface ComposerRepository {
    val drafts: Flow<List<ComposerDraft>>

    suspend fun saveDraft(value: ComposerDraft)
    suspend fun loadDraft(accountId: String, targetKey: String): ComposerDraft?
    suspend fun deleteDraft(accountId: String, targetKey: String)
    fun scheduleDraftCleanup(accountId: String, targetKey: String, storageRevision: String?) = Unit
    suspend fun submit(request: ContentSubmissionRequest): ComposerSubmissionResult
}

internal fun composerTargetKey(target: ContentSubmissionTarget): String = listOf(
    target.kind.name,
    target.forumId,
    target.threadId ?: 0,
    target.parentPostId ?: 0u,
    target.subpostId ?: 0u,
    target.replyUser?.id ?: 0,
).joinToString(":")

internal fun buildDraft(
    accountId: String,
    target: ContentSubmissionTarget,
    title: String,
    body: String,
    images: List<ContentSubmissionImage>,
    nowEpochMillis: Long,
): ComposerDraft {
    require(accountId.isNotBlank()) { "缺少账号信息。" }
    require(title.length <= dev.infinityf4p.tiebapure.core.model.ContentSubmissionPolicy.maximumTitleCharacters) {
        "标题过长。"
    }
    require(body.length <= dev.infinityf4p.tiebapure.core.model.ContentSubmissionPolicy.maximumBodyCharacters) {
        "正文过长。"
    }
    require(images.size <= dev.infinityf4p.tiebapure.core.model.ContentSubmissionPolicy.maximumImages) {
        "图片数量过多。"
    }
    if (target.kind == dev.infinityf4p.tiebapure.core.model.ContentSubmissionKind.NewThread) {
        require(images.isEmpty()) { "发布新主题暂不支持图片。" }
    }
    return ComposerDraft(accountId, target, title, body, images.toList(), nowEpochMillis)
}
