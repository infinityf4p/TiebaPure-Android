package dev.infinityf4p.tiebapure.core.designsystem

import dev.infinityf4p.tiebapure.core.model.ContentBlock
import dev.infinityf4p.tiebapure.core.model.ImageContent
import dev.infinityf4p.tiebapure.core.model.ReaderMediaLoadingPolicy
import dev.infinityf4p.tiebapure.core.model.VideoContent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ThreadMediaPreviewTest {
    @Test
    fun interactionCountsMatchIosCompactFormatting() {
        assertEquals("0", compactInteractionCount(-1))
        assertEquals("999", compactInteractionCount(999))
        assertEquals("1k", compactInteractionCount(1_000))
        assertEquals("1.2k", compactInteractionCount(1_249))
        assertEquals("1w", compactInteractionCount(10_000))
        assertEquals("1.2w", compactInteractionCount(12_499))
    }

    @Test
    fun interactionStatsUseEvenInteriorCenters() {
        assertEquals(emptyList<Float>(), interactionStatCenterFractions(0))
        assertEquals(listOf(0.5f), interactionStatCenterFractions(1))
        assertEquals(listOf(1f / 3f, 2f / 3f), interactionStatCenterFractions(2))
    }

    @Test
    fun selectsAtMostThreeAvailableMediaInContentOrder() {
        val selection = selectThreadMediaPreview(
            listOf(
                ContentBlock.Text("正文"),
                image("https://tiebapic.baidu.com/a.jpg"),
                video("https://tiebapic.baidu.com/v.jpg"),
                image("https://tiebapic.baidu.com/b.jpg"),
                image("https://tiebapic.baidu.com/c.jpg"),
            ),
        )

        assertEquals(4, selection.totalCount)
        assertEquals(
            listOf(ThreadMediaPreviewKind.Image, ThreadMediaPreviewKind.Video, ThreadMediaPreviewKind.Image),
            selection.items.map(ThreadMediaPreviewItem::kind),
        )
        assertEquals(listOf(1, 2, 3), selection.items.map(ThreadMediaPreviewItem::blockIndex))
    }

    @Test
    fun omitsMediaWithoutAUsablePreviewButKeepsLaterMedia() {
        val selection = selectThreadMediaPreview(
            listOf(
                image("  "),
                ContentBlock.Video(VideoContent(null, null, null, 0, 0, 0)),
                ContentBlock.Text("文字仍由帖子行显示"),
                image(null, original = "https://tiebapic.baidu.com/original.jpg"),
            ),
        )

        assertEquals(1, selection.totalCount)
        assertEquals("https://tiebapic.baidu.com/original.jpg", selection.items.single().previewUrl)
        assertEquals(3, selection.items.single().blockIndex)
    }

    @Test
    fun keepsPlayableVideoWithoutCoverAsPlaceholderPreview() {
        val selectedVideo = video(cover = null, videoUrl = "https://tb-video.bdstatic.com/video.mp4")
        val blocks = listOf(
            ContentBlock.Text("正文"),
            selectedVideo,
        )
        val selection = selectThreadMediaPreview(blocks)

        assertEquals(1, selection.totalCount)
        assertEquals(ThreadMediaPreviewKind.Video, selection.items.single().kind)
        assertEquals(1, selection.items.single().blockIndex)
        assertEquals(null, selection.items.single().previewUrl)
        val action = assertIs<ThreadMediaPreviewAction.Video>(
            resolveThreadMediaPreviewAction(blocks, selection.items.single().blockIndex),
        )
        assertEquals(selectedVideo.value, action.video)
    }

    @Test
    fun omitsCoverlessVideoWhenPlaybackDestinationIsInvalid() {
        val selection = selectThreadMediaPreview(
            listOf(
                video(cover = null, videoUrl = "https://example.com/video.mp4"),
                video(cover = null, videoUrl = "https://tb-video.bdstatic.com/video.m3u8"),
            ),
        )

        assertEquals(0, selection.totalCount)
        assertEquals(emptyList(), selection.items)
    }

    @Test
    fun nonPositiveLimitProducesNoVisibleItemsWithoutLosingTotal() {
        val selection = selectThreadMediaPreview(listOf(image("https://tiebapic.baidu.com/a.jpg")), limit = 0)

        assertEquals(1, selection.totalCount)
        assertEquals(emptyList(), selection.items)
    }

    @Test
    fun imageActionKeepsAvailableImageOrderAndSelectedPage() {
        val blocks = listOf(
            image("https://tiebapic.baidu.com/a.jpg"),
            ContentBlock.Text("正文"),
            image(null),
            image("https://tiebapic.baidu.com/b.jpg"),
        )

        val action = assertIs<ThreadMediaPreviewAction.Images>(resolveThreadMediaPreviewAction(blocks, 3))

        assertEquals(2, action.images.size)
        assertEquals(1, action.initialPage)
        assertEquals("https://tiebapic.baidu.com/b.jpg", action.images[1].thumbnailUrl)
    }

    @Test
    fun videoActionKeepsPlaybackMetadata() {
        val selected = video("https://tiebapic.baidu.com/v.jpg")
        val action = assertIs<ThreadMediaPreviewAction.Video>(
            resolveThreadMediaPreviewAction(listOf(ContentBlock.Text("正文"), selected), 1),
        )

        assertEquals(selected.value, action.video)
    }

    @Test
    fun manualPolicyRequiresExplicitAuthorization() {
        assertEquals(true, shouldLoadThreadMediaPreview(ReaderMediaLoadingPolicy.Automatic, false))
        assertEquals(true, shouldLoadThreadMediaPreview(ReaderMediaLoadingPolicy.DataSaving, false))
        assertEquals(false, shouldLoadThreadMediaPreview(ReaderMediaLoadingPolicy.Manual, false))
        assertEquals(true, shouldLoadThreadMediaPreview(ReaderMediaLoadingPolicy.Manual, true))
    }

    private fun image(thumbnail: String?, original: String? = null): ContentBlock.Image = ContentBlock.Image(
        ImageContent(thumbnail, original, width = 1, height = 1, showOriginalButton = false),
    )

    private fun video(
        cover: String?,
        videoUrl: String? = null,
        webUrl: String? = null,
    ): ContentBlock.Video = ContentBlock.Video(
        VideoContent(videoUrl, cover, webUrl, width = 16, height = 9, durationSeconds = 3),
    )
}
