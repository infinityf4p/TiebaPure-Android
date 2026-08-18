package dev.infinityf4p.tiebapure.feature.composer

import dev.infinityf4p.tiebapure.core.model.ContentSubmissionKind
import dev.infinityf4p.tiebapure.core.model.ContentSubmissionImage
import dev.infinityf4p.tiebapure.core.model.ContentSubmissionTarget
import dev.infinityf4p.tiebapure.core.model.ContentSubmissionReceipt
import dev.infinityf4p.tiebapure.core.model.SubmissionVerificationChallenge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ComposerPolicyTest {
    @Test fun sendingStateBlocksLeavingEditor() {
        val target = ContentSubmissionTarget(ContentSubmissionKind.NewThread, 1, "测试")
        assertTrue(!ComposerUiState(target, submission = ComposerSubmissionState.Sending).canExit)
        assertTrue(ComposerUiState(target, submission = ComposerSubmissionState.Idle).canExit)
        assertTrue(
            ComposerUiState(
                target,
                submission = ComposerSubmissionState.OutcomeUnknown("请刷新核对"),
            ).canExit,
        )
    }

    @Test fun createsTrimmedValidRequest() {
        val target = ContentSubmissionTarget(ContentSubmissionKind.NewThread, 1, "测试")
        val request = validatedRequest(target, " 标题 ", " 正文 ", emptyList())
        assertEquals("标题", request?.title)
        assertEquals("正文", request?.body)
    }

    @Test fun rejectsEmptyBody() {
        val target = ContentSubmissionTarget(ContentSubmissionKind.NewThread, 1, "测试")
        assertNull(validatedRequest(target, "标题", " ", emptyList()))
    }

    @Test fun emoticonTokenAppendIsCanonicalFriendlyAndNeverSplitsAtTheLimit() {
        assertEquals("#(滑稽)", appendEmoticonToken("", "#(滑稽)", 20))
        assertEquals("正文 #(滑稽)", appendEmoticonToken("正文", "#(滑稽)", 20))
        assertEquals("正文\n#(滑稽)", appendEmoticonToken("正文\n", "#(滑稽)", 20))
        assertEquals("正文", appendEmoticonToken("正文", "#(滑稽)", 4))
    }

    @Test fun disabledSubmissionCapabilityUsesTheCorrectSettingsReason() {
        val newThread = ComposerSubmissionCapability.fromSettings(
            kind = ContentSubmissionKind.NewThread,
            enabled = false,
        )
        val reply = ComposerSubmissionCapability.fromSettings(
            kind = ContentSubmissionKind.ThreadReply,
            enabled = false,
        )

        assertEquals("请先在设置中开启允许发帖。", newThread.resolvedUnavailableReason(ContentSubmissionKind.NewThread))
        assertEquals("请先在设置中开启允许回帖。", reply.resolvedUnavailableReason(ContentSubmissionKind.ThreadReply))
        assertNull(
            ComposerSubmissionCapability.Enabled.resolvedUnavailableReason(ContentSubmissionKind.NewThread),
        )
    }

    @Test fun targetKeySeparatesReplyLocations() {
        val first = ContentSubmissionTarget(ContentSubmissionKind.ThreadReply, 1, "测试", threadId = 11)
        val second = ContentSubmissionTarget(ContentSubmissionKind.ThreadReply, 1, "测试", threadId = 12)
        assertTrue(composerTargetKey(first) != composerTargetKey(second))
    }

    @Test fun repositoryContractPreservesUnknownOutcomeWithoutRetry() = runTest {
        val repository = FixtureComposerRepository(
            ComposerSubmissionResult.OutcomeUnknown("网络连接已中断"),
        )
        val target = ContentSubmissionTarget(ContentSubmissionKind.NewThread, 1, "测试")
        val result = repository.submit(
            dev.infinityf4p.tiebapure.core.model.ContentSubmissionRequest(target, "标题", "正文"),
        )
        assertTrue(result is ComposerSubmissionResult.OutcomeUnknown)
        assertEquals(1, repository.submitCount)
    }

    @Test fun draftCanBeSavedListedAndDeleted() = runTest {
        val repository = FixtureComposerRepository(ComposerSubmissionResult.OutcomeUnknown("unused"))
        val target = ContentSubmissionTarget(ContentSubmissionKind.NewThread, 1, "测试")
        val draft = buildDraft("account", target, "标题", "正文", emptyList(), 123)
        repository.saveDraft(draft)
        assertEquals(draft, repository.drafts.value.single())
        repository.deleteDraft("account", draft.targetKey)
        assertTrue(repository.drafts.value.isEmpty())
    }

    @Test fun viewModelDoesNotRetryUnknownOutcome() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val repository = FixtureComposerRepository(ComposerSubmissionResult.OutcomeUnknown("网络连接已中断"))
            val target = ContentSubmissionTarget(ContentSubmissionKind.NewThread, 1, "测试")
            val viewModel = ComposerViewModel(
                accountId = "account",
                target = target,
                repository = repository,
                riskAcknowledged = true,
                initialTitle = "标题",
                initialBody = "正文",
            )
            viewModel.requestSend()
            advanceUntilIdle()
            assertTrue(viewModel.state.value.submission is ComposerSubmissionState.OutcomeUnknown)
            viewModel.requestSend()
            advanceUntilIdle()
            assertEquals(1, repository.submitCount)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test fun disablingSubmissionClearsPendingRiskAndNeverWritesButKeepsDraftActions() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val repository = FixtureComposerRepository(
                ComposerSubmissionResult.Success(ContentSubmissionReceipt(7, 9u)),
            )
            val target = ContentSubmissionTarget(ContentSubmissionKind.ThreadReply, 1, "测试", threadId = 7)
            val viewModel = ComposerViewModel(
                accountId = "account",
                target = target,
                repository = repository,
                riskAcknowledged = false,
                initialBody = "可继续编辑的正文",
            )

            viewModel.requestSend(ComposerSubmissionCapability.Enabled)
            assertTrue(viewModel.state.value.showRiskConfirmation)

            val disabled = ComposerSubmissionCapability.fromSettings(target.kind, enabled = false)
            viewModel.updateSubmissionCapability(disabled)
            viewModel.confirmRiskAndSend(disabled)
            advanceUntilIdle()

            assertTrue(!viewModel.state.value.showRiskConfirmation)
            assertEquals(0, repository.submitCount)
            assertEquals("可继续编辑的正文", viewModel.state.value.body)
            assertTrue(viewModel.state.value.canExit)

            viewModel.updateBody("关闭回复后仍可编辑")
            viewModel.saveDraft()
            advanceUntilIdle()
            assertEquals("关闭回复后仍可编辑", repository.drafts.value.single().body)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test fun verificationChallengeStopsWithoutSecondWrite() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val challenge = SubmissionVerificationChallenge("captcha", "md5", "https://example.test/code", "请输入验证码")
            val repository = FixtureComposerRepository(
                ComposerSubmissionResult.VerificationRequired(challenge),
            )
            val target = ContentSubmissionTarget(ContentSubmissionKind.NewThread, 1, "测试")
            val viewModel = ComposerViewModel(
                accountId = "account",
                target = target,
                repository = repository,
                riskAcknowledged = true,
                initialTitle = "标题",
                initialBody = "正文",
            )
            viewModel.requestSend()
            advanceUntilIdle()
            assertTrue(viewModel.state.value.submission is ComposerSubmissionState.VerificationRequired)
            viewModel.requestSend()
            advanceUntilIdle()
            assertTrue(viewModel.state.value.submission is ComposerSubmissionState.VerificationRequired)
            assertEquals(1, repository.submitCount)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test fun restoringSummaryLoadsAttachmentOnlyOnDemand() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val repository = FixtureComposerRepository(ComposerSubmissionResult.OutcomeUnknown("unused"))
            val target = ContentSubmissionTarget(ContentSubmissionKind.ThreadReply, 1, "测试", threadId = 9)
            val stored = buildDraft(
                "account",
                target,
                "",
                "正文",
                listOf(ContentSubmissionImage(byteArrayOf(1, 2, 3), "image/png")),
                123,
            )
            repository.saveDraft(stored)
            val summary = stored.copy(images = emptyList(), storedImageCount = 1)
            val viewModel = ComposerViewModel("account", target, repository, riskAcknowledged = true)

            viewModel.restoreDraft(summary)
            advanceUntilIdle()

            assertEquals(1, repository.loadCount)
            assertEquals(1, viewModel.state.value.images.size)
            assertEquals("已恢复草稿", viewModel.state.value.draftMessage)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test fun saveAndCloseRequestsCloseOnlyAfterSuccessfulPersistence() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val repository = FixtureComposerRepository(ComposerSubmissionResult.OutcomeUnknown("unused"))
            val target = ContentSubmissionTarget(ContentSubmissionKind.NewThread, 1, "测试")
            val viewModel = ComposerViewModel(
                "account", target, repository, riskAcknowledged = true,
                initialTitle = "标题", initialBody = "正文",
            )

            viewModel.saveDraft(closeAfterSave = true)
            assertTrue(!viewModel.state.value.closeAfterDraftSave)
            advanceUntilIdle()

            assertTrue(viewModel.state.value.closeAfterDraftSave)
            viewModel.consumeDraftCloseRequest()
            assertTrue(!viewModel.state.value.closeAfterDraftSave)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test fun failedSaveAndCloseKeepsEditorOpen() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val repository = FixtureComposerRepository(ComposerSubmissionResult.OutcomeUnknown("unused")).apply {
                saveFailure = IllegalStateException("disk full")
            }
            val target = ContentSubmissionTarget(ContentSubmissionKind.NewThread, 1, "测试")
            val viewModel = ComposerViewModel(
                "account", target, repository, riskAcknowledged = true,
                initialTitle = "标题", initialBody = "正文",
            )

            viewModel.saveDraft(closeAfterSave = true)
            advanceUntilIdle()

            assertTrue(!viewModel.state.value.closeAfterDraftSave)
            assertTrue(viewModel.state.value.errorMessage?.contains("disk full") == true)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test fun successfulSendWithDraftCleanupFailureWarnsAndSchedulesRetry() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val repository = FixtureComposerRepository(
                ComposerSubmissionResult.Success(ContentSubmissionReceipt(7, 9u)),
            ).apply { deleteFailure = IllegalStateException("database busy") }
            val target = ContentSubmissionTarget(ContentSubmissionKind.NewThread, 1, "测试")
            val viewModel = ComposerViewModel(
                "account", target, repository, riskAcknowledged = true,
                initialTitle = "标题", initialBody = "正文",
            )

            viewModel.requestSend()
            advanceUntilIdle()

            assertTrue(viewModel.state.value.submission is ComposerSubmissionState.Sent)
            assertTrue(viewModel.state.value.draftCleanupWarning?.contains("请勿重复发送") == true)
            assertEquals(1, repository.cleanupScheduleCount)
        } finally {
            Dispatchers.resetMain()
        }
    }
}

private class FixtureComposerRepository(
    vararg results: ComposerSubmissionResult,
) : ComposerRepository {
    override val drafts = MutableStateFlow<List<ComposerDraft>>(emptyList())
    private val results = ArrayDeque(results.toList())
    var submitCount = 0
    var loadCount = 0
    var cleanupScheduleCount = 0
    var saveFailure: Throwable? = null
    var deleteFailure: Throwable? = null

    override suspend fun saveDraft(value: ComposerDraft) {
        saveFailure?.let { throw it }
        drafts.value = drafts.value.filterNot {
            it.accountId == value.accountId && it.targetKey == value.targetKey
        } + value
    }

    override suspend fun loadDraft(accountId: String, targetKey: String): ComposerDraft? =
        drafts.value.firstOrNull { it.accountId == accountId && it.targetKey == targetKey }.also { loadCount += 1 }

    override suspend fun deleteDraft(accountId: String, targetKey: String) {
        deleteFailure?.let { throw it }
        drafts.value = drafts.value.filterNot { it.accountId == accountId && it.targetKey == targetKey }
    }

    override fun scheduleDraftCleanup(accountId: String, targetKey: String, storageRevision: String?) {
        cleanupScheduleCount += 1
    }

    override suspend fun submit(
        request: dev.infinityf4p.tiebapure.core.model.ContentSubmissionRequest,
    ): ComposerSubmissionResult {
        submitCount += 1
        return results.removeFirst()
    }
}
