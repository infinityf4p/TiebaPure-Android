package dev.infinityf4p.tiebapure.feature.thread

import dev.infinityf4p.tiebapure.core.model.ContentBlock
import dev.infinityf4p.tiebapure.core.model.ImageContent
import dev.infinityf4p.tiebapure.core.model.ReaderMediaLoadingPolicy
import dev.infinityf4p.tiebapure.core.model.VideoContent
import dev.infinityf4p.tiebapure.core.model.VoiceContent
import androidx.compose.ui.graphics.Color
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class RichContentPolicyTest {
    @Test
    fun mixedBlocksKeepSourceOrderAndOnlyMergeAdjacentCompatibleBlocks() {
        val firstImage = image("thumb-1", "original-1")
        val secondImage = image("thumb-2", "original-2")
        val voice = requireNotNull(VoiceContent.create("0123456789abcdef0123456789abcdef", 1_200))
        val video = video()
        val blocks = listOf(
            ContentBlock.Text("before "),
            ContentBlock.Link("link", "https://tieba.baidu.com"),
            ContentBlock.Image(firstImage),
            ContentBlock.Image(secondImage),
            ContentBlock.Text("between "),
            ContentBlock.Mention(42L, "@user"),
            ContentBlock.Voice(voice),
            ContentBlock.Video(video),
            ContentBlock.Text("after"),
        )

        val groups = groupRichContent(blocks)

        assertEquals(
            listOf(
                RichContentGroup.Inline(blocks.subList(0, 2)),
                RichContentGroup.Media(blocks.subList(2, 4)),
                RichContentGroup.Inline(blocks.subList(4, 6)),
                RichContentGroup.Voice(voice),
                RichContentGroup.Media(listOf(ContentBlock.Video(video))),
                RichContentGroup.Inline(listOf(blocks.last())),
            ),
            groups,
        )
    }

    @Test
    fun imagesSeparatedByOtherContentAreNeverMergedIntoOneGrid() {
        val firstImage = image("thumb-1", "original-1")
        val secondImage = image("thumb-2", "original-2")
        val groups = groupRichContent(
            listOf(
                ContentBlock.Image(firstImage),
                ContentBlock.Text("caption"),
                ContentBlock.Image(secondImage),
                ContentBlock.Video(video()),
            ),
        )

        assertEquals(3, groups.size)
        assertEquals(RichContentGroup.Media(listOf(ContentBlock.Image(firstImage))), groups[0])
        assertTrue(groups[1] is RichContentGroup.Inline)
        assertEquals(
            RichContentGroup.Media(listOf(ContentBlock.Image(secondImage), ContentBlock.Video(video()))),
            groups[2],
        )
    }

    @Test
    fun imagesAndVideosInOneMediaRunShareGalleryImagesAndPreserveImageIndex() {
        val firstImage = image("thumb-1", "original-1")
        val secondImage = image("thumb-2", "original-2")
        val mediaBlocks = listOf(
            ContentBlock.Image(firstImage),
            ContentBlock.Video(video()),
            ContentBlock.Image(secondImage),
        )

        assertEquals(listOf(RichContentGroup.Media(mediaBlocks)), groupRichContent(mediaBlocks))
        assertEquals(
            RichMediaGallerySelection(listOf(firstImage, secondImage), initialPage = 0),
            resolveRichMediaGallery(mediaBlocks, selectedBlockIndex = 0),
        )
        assertEquals(
            RichMediaGallerySelection(listOf(firstImage, secondImage), initialPage = 1),
            resolveRichMediaGallery(mediaBlocks, selectedBlockIndex = 2),
        )
        assertNull(resolveRichMediaGallery(mediaBlocks, selectedBlockIndex = 1))
    }

    @Test
    fun automaticLoadsNormalImageAndVideoPreviews() {
        val image = image("thumbnail", "original")
        val plan = RichMediaLoadingPlan.resolve(ReaderMediaLoadingPolicy.Automatic)

        val previews = plan.previewImages(listOf(image), explicitlyAuthorized = false)

        assertSame(image, previews?.single())
        assertTrue(plan.showsVideoPreview(explicitlyAuthorized = false))
        assertTrue(plan.loadsEmoticonsAutomatically)
    }

    @Test
    fun dataSavingLoadsThumbnailOnlyAndShowsVideoCoverWithoutPlayingIt() {
        val image = image("thumbnail", "original")
        val plan = RichMediaLoadingPlan.resolve(ReaderMediaLoadingPolicy.DataSaving)

        val preview = plan.previewImages(listOf(image), explicitlyAuthorized = false)?.single()

        assertEquals("thumbnail", preview?.thumbnailUrl)
        assertNull(preview?.originalUrl)
        assertTrue(plan.showsVideoPreview(explicitlyAuthorized = false))
        assertTrue(plan.showsVideoPreview(explicitlyAuthorized = true))
        assertTrue(plan.loadsEmoticonsAutomatically)
    }

    @Test
    fun manualRequiresExplicitAuthorizationForEveryMediaKind() {
        val image = image("thumbnail", "original")
        val plan = RichMediaLoadingPlan.resolve(ReaderMediaLoadingPolicy.Manual)

        assertNull(plan.previewImages(listOf(image), explicitlyAuthorized = false))
        assertSame(image, plan.previewImages(listOf(image), explicitlyAuthorized = true)?.single())
        assertFalse(plan.showsVideoPreview(explicitlyAuthorized = false))
        assertTrue(plan.showsVideoPreview(explicitlyAuthorized = true))
        assertFalse(plan.loadsEmoticonsAutomatically)
    }

    @Test
    fun inlineTextUsesArtworkOnlyForKnownEmoticonsAndKeepsUnknownFallback() {
        val result = buildRichText(
            blocks = listOf(
                ContentBlock.Text("before"),
                ContentBlock.Emoticon("#(滑稽)"),
                ContentBlock.Emoticon("#(unknown)"),
                ContentBlock.Text("after"),
            ),
            linkColor = Color.Blue,
            onLinkClick = {},
            onUserClick = {},
        )

        assertEquals(listOf("#(滑稽)"), result.emoticons.map(RichInlineEmoticon::code))
        assertTrue(result.annotated.text.contains("[unknown]"))
        assertTrue(result.annotated.text.startsWith("before"))
        assertTrue(result.annotated.text.endsWith("after"))
    }

    private fun image(thumbnail: String, original: String) = ImageContent(
        thumbnailUrl = thumbnail,
        originalUrl = original,
        width = 100,
        height = 100,
        showOriginalButton = true,
    )

    private fun video() = VideoContent(
        videoUrl = "https://example.com/video.mp4",
        coverUrl = "https://example.com/cover.jpg",
        webUrl = null,
        width = 16,
        height = 9,
        durationSeconds = 10,
    )
}
