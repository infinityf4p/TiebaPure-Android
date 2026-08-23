package dev.infinityf4p.tiebapure.core.media

import java.nio.file.Files
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflineMediaPolicyTest {
    @Test
    fun validatesImageAndVideoSignatures() {
        val root = Files.createTempDirectory("offline-media-signatures").toFile()
        try {
            val png = root.resolve("image.bin").apply {
                writeBytes(byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a))
            }
            val video = root.resolve("video.bin").apply {
                writeBytes(byteArrayOf(0, 0, 0, 16, 0x66, 0x74, 0x79, 0x70, 0x69, 0x73, 0x6f, 0x6d))
            }
            val invalid = root.resolve("invalid.bin").apply { writeText("not media") }

            assertTrue(OfflineMediaPolicy.isSupported(png, OfflineMediaKind.Image))
            assertTrue(OfflineMediaPolicy.isSupported(video, OfflineMediaKind.Video))
            assertFalse(OfflineMediaPolicy.isSupported(invalid, OfflineMediaKind.Image))
            assertFalse(OfflineMediaPolicy.isSupported(invalid, OfflineMediaKind.Video))
        } finally {
            root.deleteRecursively()
        }
    }
}
