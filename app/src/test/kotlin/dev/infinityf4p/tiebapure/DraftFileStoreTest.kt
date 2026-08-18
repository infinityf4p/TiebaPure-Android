package dev.infinityf4p.tiebapure

import dev.infinityf4p.tiebapure.core.data.ContentDraftEntity
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DraftFileStoreTest {
    @Test
    fun stagedAttachmentRoundTripsAndRejectsTampering() = runTest {
        val directory = Files.createTempDirectory("draft-files").toFile()
        try {
            val store = AppDraftFileStore.forTests(directory)
            val bytes = "TPDR-test-payload".toByteArray()
            val stored = store.stage { it.write(bytes) }
            val entity = draftEntity(stored)

            assertArrayEquals(bytes, store.read(entity) { it.readBytes() })
            directory.resolve(stored.fileName).appendText("tampered")
            assertTrue(runCatching { store.read(entity) { it.readBytes() } }.isFailure)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun cleanupKeepsOnlyReferencedCommittedAttachments() = runTest {
        val directory = Files.createTempDirectory("draft-cleanup").toFile()
        try {
            val store = AppDraftFileStore.forTests(directory)
            val kept = store.stage { it.write("TPDR-kept".toByteArray()) }
            val removed = store.stage { it.write("TPDR-removed".toByteArray()) }

            store.cleanup(setOf(kept.fileName))

            assertTrue(directory.resolve(kept.fileName).isFile)
            assertFalse(directory.resolve(removed.fileName).exists())
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun draftEntity(stored: StoredDraftAttachment) = ContentDraftEntity(
        accountId = "account",
        targetKey = "target",
        title = "title",
        body = "body",
        targetMetadata = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8),
        attachmentFileName = stored.fileName,
        attachmentByteCount = stored.byteCount,
        attachmentSHA256 = stored.sha256,
        imageCount = 0,
        updatedAtMilliseconds = 1,
    )
}
