package dev.infinityf4p.tiebapure.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ContentSubmissionPolicyTest {
    private val forum = ContentSubmissionTarget(ContentSubmissionKind.NewThread, 1, "测试")

    @Test fun trimsAndAcceptsValidThread() {
        val result = ContentSubmissionPolicy.validate(ContentSubmissionRequest(forum, " 标题 ", " 正文 "))
        assertEquals("标题", result.title)
        assertEquals("正文", result.body)
    }

    @Test fun rejectsImagesForNewThread() {
        assertThrows(ContentSubmissionValidationException.ImagesUnsupportedForNewThread::class.java) {
            ContentSubmissionPolicy.validate(
                ContentSubmissionRequest(forum, "标题", "正文", listOf(ContentSubmissionImage(byteArrayOf(1), "image/png"))),
            )
        }
    }
}
