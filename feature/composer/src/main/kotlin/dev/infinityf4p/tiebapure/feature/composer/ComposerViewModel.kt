package dev.infinityf4p.tiebapure.feature.composer

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dev.infinityf4p.tiebapure.core.model.ContentSubmissionImage
import dev.infinityf4p.tiebapure.core.model.ContentSubmissionPolicy
import dev.infinityf4p.tiebapure.core.model.ContentSubmissionReceipt
import dev.infinityf4p.tiebapure.core.model.ContentSubmissionRequest
import dev.infinityf4p.tiebapure.core.model.ContentSubmissionTarget
import dev.infinityf4p.tiebapure.core.model.SubmissionVerificationChallenge
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface ComposerSubmissionState {
    data object Idle : ComposerSubmissionState
    data object Sending : ComposerSubmissionState
    data class Sent(val receipt: ContentSubmissionReceipt) : ComposerSubmissionState
    data class VerificationRequired(val challenge: SubmissionVerificationChallenge) : ComposerSubmissionState
    data class OutcomeUnknown(val message: String) : ComposerSubmissionState
    data class Failed(val message: String) : ComposerSubmissionState
}

data class ComposerUiState(
    val target: ContentSubmissionTarget,
    val title: String = "",
    val body: String = "",
    val images: List<ContentSubmissionImage> = emptyList(),
    val drafts: List<ComposerDraft> = emptyList(),
    val submission: ComposerSubmissionState = ComposerSubmissionState.Idle,
    val riskAcknowledged: Boolean = false,
    val showRiskConfirmation: Boolean = false,
    val isSavingDraft: Boolean = false,
    val isLoadingDraft: Boolean = false,
    val draftMessage: String? = null,
    val errorMessage: String? = null,
    val restoredDraftKey: String? = null,
    val closeAfterDraftSave: Boolean = false,
    val draftCleanupWarning: String? = null,
) {
    val allowsImages: Boolean
        get() = target.kind != dev.infinityf4p.tiebapure.core.model.ContentSubmissionKind.NewThread
    val isBusy: Boolean get() = submission is ComposerSubmissionState.Sending || isSavingDraft || isLoadingDraft
    val canExit: Boolean get() = submission !is ComposerSubmissionState.Sending
    val hasContent: Boolean get() = title.isNotBlank() || body.isNotBlank() || images.isNotEmpty()
}

class ComposerViewModel(
    private val accountId: String,
    target: ContentSubmissionTarget,
    private val repository: ComposerRepository,
    riskAcknowledged: Boolean,
    initialTitle: String = "",
    initialBody: String = "",
    initialImages: List<ContentSubmissionImage> = emptyList(),
    private val nowEpochMillis: () -> Long = System::currentTimeMillis,
) : ViewModel() {
    private val mutableState = MutableStateFlow(
        ComposerUiState(target, initialTitle, initialBody, initialImages, riskAcknowledged = riskAcknowledged),
    )
    val state: StateFlow<ComposerUiState> = mutableState.asStateFlow()
    private var pendingRequest: ContentSubmissionRequest? = null

    init {
        repository.drafts
            .onEach { drafts ->
                mutableState.update {
                    it.copy(drafts = drafts.filter { draft -> draft.accountId == accountId }
                        .sortedByDescending(ComposerDraft::updatedAtEpochMillis))
                }
            }
            .launchIn(viewModelScope)
    }

    fun updateTitle(value: String) = mutableState.update {
        it.copy(title = value.take(ContentSubmissionPolicy.maximumTitleCharacters + 1), draftMessage = null)
    }

    fun updateBody(value: String) = mutableState.update {
        it.copy(body = value.take(ContentSubmissionPolicy.maximumBodyCharacters + 1), draftMessage = null)
    }

    fun addImages(values: List<ContentSubmissionImage>) {
        if (!state.value.allowsImages) return
        val remaining = (ContentSubmissionPolicy.maximumImages - state.value.images.size).coerceAtLeast(0)
        mutableState.update { current ->
            current.copy(images = current.images + values.take(remaining), errorMessage = null, draftMessage = null)
        }
    }

    fun removeImage(index: Int) = mutableState.update { current ->
        if (index !in current.images.indices) current
        else current.copy(images = current.images.toMutableList().also { it.removeAt(index) }, draftMessage = null)
    }

    fun saveDraft(closeAfterSave: Boolean = false) {
        if (state.value.isBusy) return
        val draft = runCatching {
            buildDraft(accountId, state.value.target, state.value.title, state.value.body, state.value.images, nowEpochMillis())
        }.getOrElse { error ->
            mutableState.update { it.copy(errorMessage = readableComposerError(error)) }
            return
        }
        viewModelScope.launch {
            mutableState.update { it.copy(isSavingDraft = true, errorMessage = null, draftMessage = null) }
            runCatching { repository.saveDraft(draft) }
                .onSuccess {
                    mutableState.update {
                        it.copy(
                            isSavingDraft = false,
                            draftMessage = "草稿已保存",
                            restoredDraftKey = draft.targetKey,
                            closeAfterDraftSave = closeAfterSave,
                        )
                    }
                }
                .onFailure { error ->
                    mutableState.update { it.copy(isSavingDraft = false, errorMessage = "草稿保存失败：${readableComposerError(error)}") }
                }
        }
    }

    fun consumeDraftCloseRequest() = mutableState.update { it.copy(closeAfterDraftSave = false) }

    fun acknowledgeDraftCleanupWarning() = mutableState.update { it.copy(draftCleanupWarning = null) }

    fun restoreDraft(value: ComposerDraft) {
        if (value.accountId != accountId || value.targetKey != composerTargetKey(state.value.target)) {
            mutableState.update { it.copy(errorMessage = "该草稿属于其他帖子或回复位置，请从对应页面打开。") }
            return
        }
        if (state.value.isBusy) return
        viewModelScope.launch {
            mutableState.update { it.copy(isLoadingDraft = true, errorMessage = null, draftMessage = null) }
            runCatching { repository.loadDraft(value.accountId, value.targetKey) }
                .onSuccess { loaded ->
                    if (loaded == null) {
                        mutableState.update { it.copy(isLoadingDraft = false, errorMessage = "草稿已不存在或附件已损坏。") }
                    } else {
                        mutableState.update {
                            it.copy(
                                title = loaded.title,
                                body = loaded.body,
                                images = loaded.images,
                                isLoadingDraft = false,
                                restoredDraftKey = loaded.targetKey,
                                draftMessage = "已恢复草稿",
                                errorMessage = null,
                                submission = ComposerSubmissionState.Idle,
                            )
                        }
                    }
                }
                .onFailure { error ->
                    mutableState.update {
                        it.copy(isLoadingDraft = false, errorMessage = "草稿恢复失败：${readableComposerError(error)}")
                    }
                }
        }
    }

    fun deleteDraft(value: ComposerDraft) {
        viewModelScope.launch {
            runCatching { repository.deleteDraft(value.accountId, value.targetKey) }
                .onFailure { error -> mutableState.update { it.copy(errorMessage = readableComposerError(error)) } }
        }
    }

    fun updateSubmissionCapability(capability: ComposerSubmissionCapability) {
        if (capability.canSubmit) return
        pendingRequest = null
        mutableState.update { it.copy(showRiskConfirmation = false) }
    }

    fun requestSend(capability: ComposerSubmissionCapability = ComposerSubmissionCapability.Enabled) {
        if (!acceptsSubmission(capability)) return
        if (state.value.isBusy || state.value.submission !is ComposerSubmissionState.Idle) return
        val request = currentValidatedRequest() ?: return
        pendingRequest = request
        if (!state.value.riskAcknowledged) {
            mutableState.update { it.copy(showRiskConfirmation = true) }
            return
        }
        submit(request)
    }

    fun confirmRiskAndSend(capability: ComposerSubmissionCapability = ComposerSubmissionCapability.Enabled) {
        if (!acceptsSubmission(capability)) return
        val request = pendingRequest ?: currentValidatedRequest() ?: return
        mutableState.update { it.copy(riskAcknowledged = true, showRiskConfirmation = false) }
        submit(request)
    }

    fun dismissRiskConfirmation() {
        pendingRequest = null
        mutableState.update { it.copy(showRiskConfirmation = false) }
    }

    fun confirmOutcomeChecked() {
        if (state.value.submission !is ComposerSubmissionState.OutcomeUnknown) return
        pendingRequest = null
        mutableState.update { it.copy(submission = ComposerSubmissionState.Idle) }
    }

    fun dismissError() = mutableState.update {
        val submission = when (it.submission) {
            is ComposerSubmissionState.Failed,
            is ComposerSubmissionState.VerificationRequired,
            -> ComposerSubmissionState.Idle
            else -> it.submission
        }
        it.copy(errorMessage = null, submission = submission)
    }

    private fun currentValidatedRequest(): ContentSubmissionRequest? {
        val request = runCatching {
            ContentSubmissionPolicy.validate(
                ContentSubmissionRequest(state.value.target, state.value.title, state.value.body, state.value.images),
            )
        }.getOrElse { error ->
            mutableState.update { it.copy(errorMessage = readableComposerError(error)) }
            return null
        }
        return request
    }

    private fun acceptsSubmission(capability: ComposerSubmissionCapability): Boolean {
        if (capability.canSubmit) return true
        updateSubmissionCapability(capability)
        return false
    }

    private fun submit(request: ContentSubmissionRequest) {
        if (state.value.submission is ComposerSubmissionState.Sending) return
        viewModelScope.launch {
            mutableState.update { it.copy(submission = ComposerSubmissionState.Sending, errorMessage = null) }
            runCatching { repository.submit(request) }
                .onSuccess { result -> handleResult(result) }
                .onFailure { error ->
                    mutableState.update {
                        it.copy(submission = ComposerSubmissionState.Failed(readableComposerError(error)))
                    }
                }
        }
    }

    private suspend fun handleResult(result: ComposerSubmissionResult) {
        when (result) {
            is ComposerSubmissionResult.Success -> {
                val targetKey = composerTargetKey(state.value.target)
                val storageRevision = state.value.drafts.firstOrNull {
                    it.accountId == accountId && it.targetKey == targetKey
                }?.storageRevision
                val cleanupFailed = runCatching { repository.deleteDraft(accountId, targetKey) }.isFailure
                if (cleanupFailed) repository.scheduleDraftCleanup(accountId, targetKey, storageRevision)
                pendingRequest = null
                mutableState.update {
                    it.copy(
                        submission = ComposerSubmissionState.Sent(result.receipt),
                        draftCleanupWarning = if (cleanupFailed) {
                            "内容已发送成功，但本地草稿暂未清理。应用会自动重试清理，请勿重复发送。"
                        } else {
                            null
                        },
                    )
                }
            }
            is ComposerSubmissionResult.VerificationRequired -> mutableState.update {
                it.copy(submission = ComposerSubmissionState.VerificationRequired(result.challenge))
            }
            is ComposerSubmissionResult.OutcomeUnknown -> mutableState.update {
                it.copy(submission = ComposerSubmissionState.OutcomeUnknown(result.message))
            }
        }
    }

    companion object {
        fun factory(
            accountId: String,
            target: ContentSubmissionTarget,
            repository: ComposerRepository,
            riskAcknowledged: Boolean,
            initialTitle: String = "",
            initialBody: String = "",
            initialImages: List<ContentSubmissionImage> = emptyList(),
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = ComposerViewModel(
                accountId, target, repository, riskAcknowledged, initialTitle, initialBody, initialImages,
            ) as T
        }
    }
}

internal fun readableComposerError(error: Throwable): String =
    error.message?.takeIf(String::isNotBlank) ?: "操作失败，请稍后重试。"
