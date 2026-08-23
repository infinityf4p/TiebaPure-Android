package dev.infinityf4p.tiebapure

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SavedThreadMediaTransactionTest {
    @Test
    fun rollbackRestoresPreviousDirectory() {
        val root = Files.createTempDirectory("saved-media-rollback").toFile()
        try {
            val destination = root.resolve("100").apply { mkdir() }
            destination.resolve("old.img").writeText("old")
            val staging = root.resolve(".staging-100").apply { mkdir() }
            staging.resolve("new.img").writeText("new")
            val transaction = SavedThreadMediaTransaction(staging, destination)

            transaction.commit()
            assertEquals("new", destination.resolve("new.img").readText())

            transaction.rollback()
            assertEquals("old", destination.resolve("old.img").readText())
            assertFalse(destination.resolve("new.img").exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun finishKeepsReplacementAndDeletesBackup() {
        val root = Files.createTempDirectory("saved-media-finish").toFile()
        try {
            val destination = root.resolve("100").apply { mkdir() }
            destination.resolve("old.img").writeText("old")
            val staging = root.resolve(".staging-100").apply { mkdir() }
            staging.resolve("new.img").writeText("new")
            val transaction = SavedThreadMediaTransaction(staging, destination)

            transaction.commit()
            transaction.finish()
            transaction.rollback()

            assertEquals("new", destination.resolve("new.img").readText())
            assertTrue(root.listFiles().orEmpty().none { it.name.startsWith(".backup-") })
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun firstCommitUsesMarkerAndRollbackLeavesNoDestination() {
        val root = Files.createTempDirectory("saved-media-first-rollback").toFile()
        try {
            val destination = root.resolve("100")
            val staging = root.resolve(".staging-100").apply { mkdir() }
            staging.resolve("new.img").writeText("new")
            val transaction = SavedThreadMediaTransaction(staging, destination)

            transaction.commit()
            assertEquals("new", destination.resolve("new.img").readText())
            assertTrue(root.listFiles().orEmpty().any { it.name.startsWith(".backup-100-") })

            transaction.rollback()
            assertFalse(destination.exists())
            assertTrue(root.listFiles().orEmpty().none { it.name.startsWith(".backup-") })
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun firstCommitFinishKeepsDestinationAndClosesStagingOnce() {
        val root = Files.createTempDirectory("saved-media-first-finish").toFile()
        try {
            val destination = root.resolve("100")
            val staging = root.resolve(".staging-100").apply { mkdir() }
            staging.resolve("new.img").writeText("new")
            var closedCount = 0
            val transaction = SavedThreadMediaTransaction(staging, destination) { closedCount += 1 }

            transaction.commit()
            transaction.finish()
            transaction.rollback()

            assertEquals("new", destination.resolve("new.img").readText())
            assertEquals(1, closedCount)
            assertTrue(root.listFiles().orEmpty().none { it.name.startsWith(".backup-") })
        } finally {
            root.deleteRecursively()
        }
    }
}
