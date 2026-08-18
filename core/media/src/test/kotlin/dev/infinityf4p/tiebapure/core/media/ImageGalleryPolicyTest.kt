package dev.infinityf4p.tiebapure.core.media

import dev.infinityf4p.tiebapure.core.model.ImageContent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ImageGalleryPolicyTest {
    @Test
    fun pageAndUrlPolicyClampInputsAndKeepTrustedBoundary() {
        assertEquals(0, ImageGalleryPolicy.clampedPage(-4, 2))
        assertEquals(1, ImageGalleryPolicy.clampedPage(9, 2))
        assertEquals(0, ImageGalleryPolicy.clampedPage(9, 0))

        val image = image(
            thumbnail = "https://tb2.bdstatic.com/preview.jpg",
            original = "https://imgsrc.baidu.com/original.jpg",
        )
        assertEquals(image.thumbnailUrl, ImageGalleryPolicy.previewUrl(image))
        assertEquals(image.originalUrl, ImageGalleryPolicy.originalUrl(image))
        assertEquals(image.originalUrl, ImageGalleryPolicy.downloadableImage(image)?.originalUrl)
        assertNull(ImageGalleryPolicy.downloadableImage(image("http://example.com/a.jpg", null)))
    }

    @Test
    fun sourceAndOriginalLabelsDescribeVisibleTierWithoutLeakingPath() {
        val image = image(
            thumbnail = "https://tb2.bdstatic.com/private/path/token.jpg?signature=secret",
            original = "https://imgsrc.baidu.com/private/original.jpg?signature=secret",
            width = 1080,
            height = 1920,
            size = 2_621_440,
        )

        assertEquals("预览 · 1080 × 1920 · tb2.bdstatic.com", ImageGalleryPolicy.sourceDescription(image, false))
        assertEquals("原图 · 1080 × 1920 · imgsrc.baidu.com", ImageGalleryPolicy.sourceDescription(image, true))
        assertEquals("查看原图 2.5MB", ImageGalleryPolicy.originalButtonLabel(image, true, OriginalImageState.Preview))
        assertEquals("重试原图", ImageGalleryPolicy.originalButtonLabel(image, true, OriginalImageState.Failed(null)))
        assertEquals("无原图", ImageGalleryPolicy.originalButtonLabel(image, false, OriginalImageState.Preview))
    }

    @Test
    fun zoomPolicyDisablesPagerAndUsesFittedImagePanBounds() {
        assertTrue(ImageGalleryGesturePolicy.pagerEnabled(1f))
        assertFalse(ImageGalleryGesturePolicy.pagerEnabled(1.5f))
        assertEquals(2f, ImageGalleryGesturePolicy.doubleTapScale(1f))
        assertEquals(1f, ImageGalleryGesturePolicy.doubleTapScale(2f))
        assertEquals(4f, ImageGalleryGesturePolicy.clampedScale(8f))

        val wide = ImageGalleryGesturePolicy.panBounds(
            viewportWidth = 1_000f,
            viewportHeight = 2_000f,
            imageAspectRatio = 2f,
            scale = 2f,
        )
        assertEquals(500f, wide.maximumX)
        assertEquals(0f, wide.maximumY)

        val tall = ImageGalleryGesturePolicy.panBounds(
            viewportWidth = 1_000f,
            viewportHeight = 2_000f,
            imageAspectRatio = 0.5f,
            scale = 2f,
        )
        assertEquals(500f, tall.maximumX)
        assertEquals(1_000f, tall.maximumY)
    }

    @Test
    fun doubleTapTranslationKeepsTappedPointNearCenter() {
        val translation = ImageGalleryGesturePolicy.doubleTapTranslation(
            tapX = 250f,
            tapY = 1_500f,
            viewportWidth = 1_000f,
            viewportHeight = 2_000f,
            targetScale = 2f,
        )
        assertEquals(250f, translation.x)
        assertEquals(-500f, translation.y)
    }

    private fun image(
        thumbnail: String?,
        original: String?,
        width: Int = 100,
        height: Int = 100,
        size: Long? = null,
    ) = ImageContent(
        thumbnailUrl = thumbnail,
        originalUrl = original,
        width = width,
        height = height,
        showOriginalButton = false,
        originalSizeBytes = size,
    )
}
