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
}
