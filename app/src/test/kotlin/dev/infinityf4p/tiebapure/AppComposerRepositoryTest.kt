package dev.infinityf4p.tiebapure

import dev.infinityf4p.tiebapure.core.data.ContentDraftDao
import dev.infinityf4p.tiebapure.core.data.ContentDraftEntity
import dev.infinityf4p.tiebapure.core.model.Account
import dev.infinityf4p.tiebapure.core.model.ContentSubmissionKind
import dev.infinityf4p.tiebapure.core.model.ContentSubmissionRequest
import dev.infinityf4p.tiebapure.core.model.ContentSubmissionTarget
import dev.infinityf4p.tiebapure.feature.composer.ComposerDraft
import java.nio.file.Files
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.cancel
import kotlinx.coroutines.yield
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppComposerRepositoryTest {
    @Test
    fun disabledReplySettingIsRecheckedBeforeNetworkSubmission() = runTest {
        val fixture = fixture(FakeDraftDao.UpsertBehavior.Success)
        var submitCount = 0
        val repository = AppComposerRepository.forTests(
            MutableStateFlow(fixture.account),
            fixture.dao,
            AppDraftFileStore.forTests(fixture.directory),
            submitContent = { _, _ ->
                submitCount += 1
                error("must not reach network")
            },
            submissionAllowed = { false },
        )
        try {
            val request = ContentSubmissionRequest(fixture.draft.target, "", "body")

            val result = runCatching { repository.submit(request) }

            assertTrue(result.exceptionOrNull()?.message?.contains("开启允许回帖") == true)
            assertEquals(0, submitCount)
        } finally {
            fixture.directory.deleteRecursively()
        }
    }

    @Test
    fun cancellationBeforeDatabaseCommitDeletesStagedAttachment() = runTest {
        val fixture = fixture(FakeDraftDao.UpsertBehavior.CancelBeforeCommit)
        try {
            val task = async { fixture.repository.saveDraft(fixture.draft) }
            val result = runCatching { task.await() }

            assertTrue(result.exceptionOrNull() is CancellationException)
            assertTrue(fixture.dao.loadAll().isEmpty())
            assertTrue(fixture.directory.listFiles().orEmpty().isEmpty())
        } finally {
            fixture.directory.deleteRecursively()
        }
    }

    @Test
    fun cancellationAfterDatabaseCommitKeepsReferencedAttachment() = runTest {
        val fixture = fixture(FakeDraftDao.UpsertBehavior.CancelAfterCommit)
        try {
            val task = async { fixture.repository.saveDraft(fixture.draft) }
            val result = runCatching { task.await() }

            assertTrue(result.exceptionOrNull() is CancellationException)
            val stored = fixture.dao.load(fixture.draft.accountId, fixture.draft.targetKey)
            assertNotNull(stored)
            assertTrue(fixture.directory.resolve(checkNotNull(stored).attachmentFileName).isFile)
            assertDraftContentEquals(
                fixture.draft,
                checkNotNull(fixture.repository.loadDraft(fixture.draft.accountId, fixture.draft.targetKey)),
            )
        } finally {
            fixture.directory.deleteRecursively()
        }
    }

    @Test
    fun repairDeletesInvalidTargetMetadataAndItsAttachment() = runTest {
        val fixture = fixture(FakeDraftDao.UpsertBehavior.Success)
        try {
            fixture.repository.saveDraft(fixture.draft)
            val original = checkNotNull(fixture.dao.load(fixture.draft.accountId, fixture.draft.targetKey))
            fixture.dao.replaceForTest(original.copy(targetMetadata = byteArrayOf(1, 2, 3)))

            fixture.repository.repairStorage()

            assertTrue(fixture.dao.loadAll().isEmpty())
            assertFalse(fixture.directory.resolve(original.attachmentFileName).exists())
        } finally {
            fixture.directory.deleteRecursively()
        }
    }

    @Test
    fun missingAttachmentRemainsListedAndCanBeDeleted() = runTest {
        val fixture = fixture(FakeDraftDao.UpsertBehavior.Success)
        try {
            fixture.repository.saveDraft(fixture.draft)
            val entity = checkNotNull(fixture.dao.load(fixture.draft.accountId, fixture.draft.targetKey))
            assertTrue(fixture.directory.resolve(entity.attachmentFileName).delete())

            fixture.repository.repairStorage()

            assertEquals(1, fixture.repository.draftsValue().size)
            assertTrue(runCatching {
                fixture.repository.loadDraft(fixture.draft.accountId, fixture.draft.targetKey)
            }.isFailure)
            fixture.repository.deleteDraft(fixture.draft.accountId, fixture.draft.targetKey)
            assertTrue(fixture.dao.loadAll().isEmpty())
        } finally {
            fixture.directory.deleteRecursively()
        }
    }

    @Test
    fun hashDamagedAttachmentRemainsListedAndCanBeDeleted() = runTest {
        val fixture = fixture(FakeDraftDao.UpsertBehavior.Success)
        try {
            fixture.repository.saveDraft(fixture.draft)
            val entity = checkNotNull(fixture.dao.load(fixture.draft.accountId, fixture.draft.targetKey))
            fixture.directory.resolve(entity.attachmentFileName).appendText("tampered")

            fixture.repository.repairStorage()

            assertEquals(1, fixture.repository.draftsValue().size)
            assertTrue(runCatching {
                fixture.repository.loadDraft(fixture.draft.accountId, fixture.draft.targetKey)
            }.isFailure)
            fixture.repository.deleteDraft(fixture.draft.accountId, fixture.draft.targetKey)
            assertTrue(fixture.dao.loadAll().isEmpty())
            assertFalse(fixture.directory.resolve(entity.attachmentFileName).exists())
        } finally {
            fixture.directory.deleteRecursively()
        }
    }

    @Test
    fun delayedCleanupDoesNotDeleteNewerDraftWithSameTarget() = runTest {
        val fixture = fixture(FakeDraftDao.UpsertBehavior.Success)
        try {
            fixture.repository.saveDraft(fixture.draft)
            val oldRevision = checkNotNull(fixture.repository.draftsValue().single().storageRevision)
            val newer = fixture.draft.copy(body = "new body", updatedAtEpochMillis = 2)
            fixture.repository.saveDraft(newer)

            assertTrue(
                fixture.repository.cleanupDraftIfRevisionMatches(
                    fixture.draft.accountId,
                    fixture.draft.targetKey,
                    oldRevision,
                ),
            )

            assertDraftContentEquals(newer, checkNotNull(fixture.repository.loadDraft(newer.accountId, newer.targetKey)))
        } finally {
            fixture.directory.deleteRecursively()
        }
    }

    @Test
    fun delayedCleanupDeletesOnlyMatchingDraftRevision() = runTest {
        val fixture = fixture(FakeDraftDao.UpsertBehavior.Success)
        try {
            fixture.repository.saveDraft(fixture.draft)
            val revision = checkNotNull(fixture.repository.draftsValue().single().storageRevision)

            assertTrue(
                fixture.repository.cleanupDraftIfRevisionMatches(
                    fixture.draft.accountId,
                    fixture.draft.targetKey,
                    revision,
                ),
            )

            assertTrue(fixture.dao.loadAll().isEmpty())
            assertTrue(fixture.directory.listFiles().orEmpty().isEmpty())
        } finally {
            fixture.directory.deleteRecursively()
        }
    }

    private fun fixture(
        behavior: FakeDraftDao.UpsertBehavior,
        submissionAllowed: (ContentSubmissionKind) -> Boolean = { true },
    ): Fixture {
        val directory = Files.createTempDirectory("composer-repository").toFile()
        val dao = FakeDraftDao(behavior)
        val account = Account(
            uid = "account", name = "name", displayName = "display", portrait = "portrait",
            bduss = "bduss", stoken = "stoken", tbs = "tbs",
        )
        val target = ContentSubmissionTarget(ContentSubmissionKind.ThreadReply, 1, "forum", threadId = 7)
        val draft = ComposerDraft(account.id, target, "", "body", emptyList(), 1)
        val repository = AppComposerRepository.forTests(
            MutableStateFlow(account),
            dao,
            AppDraftFileStore.forTests(directory),
            submissionAllowed = submissionAllowed,
        )
        return Fixture(directory, dao, repository, draft, account)
    }

    private suspend fun AppComposerRepository.draftsValue() = drafts.first()

    private fun assertDraftContentEquals(expected: ComposerDraft, actual: ComposerDraft) {
        assertEquals(expected.accountId, actual.accountId)
        assertEquals(expected.target, actual.target)
        assertEquals(expected.title, actual.title)
        assertEquals(expected.body, actual.body)
        assertEquals(expected.images, actual.images)
        assertEquals(expected.updatedAtEpochMillis, actual.updatedAtEpochMillis)
        assertNotNull(actual.storageRevision)
    }

    private data class Fixture(
        val directory: java.io.File,
        val dao: FakeDraftDao,
        val repository: AppComposerRepository,
        val draft: ComposerDraft,
        val account: Account,
    )
}

private class FakeDraftDao(
    private var behavior: UpsertBehavior,
) : ContentDraftDao() {
    private val values = MutableStateFlow<List<ContentDraftEntity>>(emptyList())

    override fun observeAll(): Flow<List<ContentDraftEntity>> = values
    override suspend fun loadAll(): List<ContentDraftEntity> = values.value
    override suspend fun load(accountId: String, targetKey: String): ContentDraftEntity? =
        values.value.firstOrNull { it.accountId == accountId && it.targetKey == targetKey }

    override suspend fun remove(accountId: String, targetKey: String) {
        values.value = values.value.filterNot { it.accountId == accountId && it.targetKey == targetKey }
    }

    override suspend fun clearAccount(accountId: String) {
        values.value = values.value.filterNot { it.accountId == accountId }
    }

    override suspend fun upsert(entity: ContentDraftEntity): ContentDraftEntity? {
        val previous = load(entity.accountId, entity.targetKey)
        when (behavior) {
            UpsertBehavior.CancelBeforeCommit -> throw CancellationException("before commit")
            UpsertBehavior.CancelAfterCommit -> {
                replaceForTest(entity)
                behavior = UpsertBehavior.Success
                currentCoroutineContext().cancel(CancellationException("after commit"))
                yield()
            }
            UpsertBehavior.Success -> replaceForTest(entity)
        }
        return previous
    }


    override suspend fun insert(entity: ContentDraftEntity) {
        replaceForTest(entity)
    }

    fun replaceForTest(entity: ContentDraftEntity) {
        values.value = values.value.filterNot {
            it.accountId == entity.accountId && it.targetKey == entity.targetKey
        } + entity
    }

    enum class UpsertBehavior { Success, CancelBeforeCommit, CancelAfterCommit }
}
