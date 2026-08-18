package dev.infinityf4p.tiebapure.core.media

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MediaFormattingTest {
    @Test
    fun byteCountUsesOneDecimalPlace() {
        assertEquals("3.5MB", formatByteCount(3_670_016))
        assertEquals("1.5KB", formatByteCount(1_536))
    }

    @Test
    fun durationUsesClockFormat() {
        assertEquals("0:09", formatDuration(9))
        assertEquals("2:05", formatDuration(125))
    }

    @Test
    fun mediaPolicyAllowsOnlyTrustedHttpsHosts() {
        assertTrue(MediaUrlPolicy.isAllowed("https://tiebapic.baidu.com/forum/pic/item/a.jpg"))
        assertTrue(MediaUrlPolicy.isAllowed("https://imgsa.bdimg.com/forum/w%3D580/a.jpg"))
        assertFalse(MediaUrlPolicy.isAllowed("http://tiebapic.baidu.com/a.jpg"))
        assertFalse(MediaUrlPolicy.isAllowed("https://baidu.com.evil.invalid/a.jpg"))
        assertFalse(MediaUrlPolicy.isAllowed("https://user@baidu.com/a.jpg"))
        assertFalse(MediaUrlPolicy.isAllowed("https://baidu.com/a.jpg#fragment"))
        assertFalse(MediaUrlPolicy.isAllowed("https://baidu.com:8443/a.jpg"))
        assertFalse(MediaUrlPolicy.isAllowed("https://baidu.com:99999/a.jpg"))
        assertFalse(MediaUrlPolicy.isAllowed("https://baidu.com"))
    }

    @Test
    fun downloadableVideoPolicyAllowsSignedUrlsAndRejectsHls() {
        assertTrue(MediaUrlPolicy.isAllowedDirectMp4("https://tb-video.bdstatic.com/a.MP4?token=x"))
        assertTrue(MediaUrlPolicy.isAllowedDownloadableVideo("https://tb-video.bdstatic.com/play?id=signed"))
        assertFalse(MediaUrlPolicy.isAllowedDirectMp4("https://tb-video.bdstatic.com/a.m3u8"))
        assertFalse(MediaUrlPolicy.isAllowedDirectMp4("https://example.com/a.mp4"))
        assertTrue(MediaUrlPolicy.isAllowedDownloadableVideo("https://tb-video.bdstatic.com/a.mp4/segment"))
    }

    @Test
    fun emoticonPolicyAllowsOnlyTheExactFixedCdnPath() {
        assertTrue(
            MediaUrlPolicy.isAllowedTiebaEmoticon(
                "https://tb2.bdstatic.com/tb/editor/images/client/image_emoticon1.png",
            ),
        )
        assertFalse(MediaUrlPolicy.isAllowedTiebaEmoticon("http://tb2.bdstatic.com/tb/editor/images/client/image_emoticon1.png"))
        assertFalse(MediaUrlPolicy.isAllowedTiebaEmoticon("https://evil.bdstatic.com/tb/editor/images/client/image_emoticon1.png"))
        assertFalse(MediaUrlPolicy.isAllowedTiebaEmoticon("https://tb2.bdstatic.com/other/image_emoticon1.png"))
        assertFalse(MediaUrlPolicy.isAllowedTiebaEmoticon("https://tb2.bdstatic.com/tb/editor/images/client/image_emoticon0.png"))
        assertFalse(MediaUrlPolicy.isAllowedTiebaEmoticon("https://tb2.bdstatic.com/tb/editor/images/client/image_emoticon1.png?next=evil"))
        assertFalse(MediaUrlPolicy.isAllowedTiebaEmoticon("https://user@tb2.bdstatic.com/tb/editor/images/client/image_emoticon1.png"))
        assertEquals(
            "https://tb2.bdstatic.com/tb/editor/images/client/image_emoticon1.png",
            MediaUrlPolicy.resolveEmoticonRedirect(
                "https://tb2.bdstatic.com/tb/editor/images/client/image_emoticon1.png",
                "/tb/editor/images/client/image_emoticon1.png",
            ),
        )
        assertNull(
            MediaUrlPolicy.resolveEmoticonRedirect(
                "https://tb2.bdstatic.com/tb/editor/images/client/image_emoticon1.png",
                "https://imgsa.bdimg.com/forum/image_emoticon1.png",
            ),
        )
    }

    @Test
    fun emoticonDimensionsBoundDecodeMemory() {
        assertTrue(isSafeTiebaEmoticonDimensions(1, 1))
        assertTrue(isSafeTiebaEmoticonDimensions(4_096, 4_096))
        assertFalse(isSafeTiebaEmoticonDimensions(0, 1))
        assertFalse(isSafeTiebaEmoticonDimensions(4_097, 10))
        assertFalse(isSafeTiebaEmoticonDimensions(4_096, 4_097))
        assertFalse(isSafeTiebaEmoticonDimensions(Int.MAX_VALUE, Int.MAX_VALUE))
    }

    @Test
    fun redirectPolicyValidatesEveryDestination() {
        assertEquals(
            "https://tiebapic.baidu.com/original/a.jpg",
            MediaUrlPolicy.resolveRedirect(
                "https://tiebapic.baidu.com/forum/a.jpg",
                "/original/a.jpg",
            ),
        )
        assertNull(
            MediaUrlPolicy.resolveRedirect(
                "https://tiebapic.baidu.com/forum/a.jpg",
                "https://example.com/a.jpg",
            ),
        )
        assertNull(
            MediaUrlPolicy.resolveRedirect(
                "https://tiebapic.baidu.com/forum/a.jpg",
                "http://tiebapic.baidu.com/a.jpg",
            ),
        )
        assertEquals(
            "https://tb-video.bdstatic.com/final.mp4",
            MediaUrlPolicy.resolveVideoRedirect(
                "https://tb-video.bdstatic.com/start.mp4",
                "/final.mp4",
            ),
        )
        assertNull(
            MediaUrlPolicy.resolveVideoRedirect(
                "https://tb-video.bdstatic.com/start.mp4",
                "/playlist.m3u8",
            ),
        )
        assertNull(
            MediaUrlPolicy.resolveVideoRedirect(
                "https://tb-video.bdstatic.com/start.mp4",
                "https:\\\\attacker.invalid\\escaped.mp4",
            ),
        )
    }
}
