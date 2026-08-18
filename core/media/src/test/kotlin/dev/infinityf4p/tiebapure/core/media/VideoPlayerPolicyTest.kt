package dev.infinityf4p.tiebapure.core.media

import androidx.lifecycle.Lifecycle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VideoPlayerPolicyTest {
    @Test
    fun onlyCurrentGenerationMayUpdatePlaybackState() {
        val first = VideoPlaybackRequest(
            sourceUrl = "https://video.baidu.com/first.mp4",
            generation = 0,
        )
        val retry = first.copy(generation = 1)
        val otherSource = VideoPlaybackRequest(
            sourceUrl = "https://video.baidu.com/second.mp4",
            generation = 1,
        )

        assertTrue(acceptsVideoPlaybackUpdate(first, first))
        assertFalse(acceptsVideoPlaybackUpdate(retry, first))
        assertFalse(acceptsVideoPlaybackUpdate(otherSource, retry))
        assertFalse(acceptsVideoPlaybackUpdate(null, retry))
    }

    @Test
    fun downloadProgressIsMonotonicAndIgnoresUnknownValues() {
        assertNull(mergeVideoDownloadProgress(current = null, candidate = 0f))
        assertNull(mergeVideoDownloadProgress(current = null, candidate = Float.NaN))
        assertEquals(0.35f, mergeVideoDownloadProgress(current = null, candidate = 0.35f))
        assertEquals(0.35f, mergeVideoDownloadProgress(current = 0.35f, candidate = 0.2f))
        assertEquals(1f, mergeVideoDownloadProgress(current = 0.35f, candidate = 2f))
    }

    @Test
    fun backgroundStopPausesButForegroundEventsDoNotAutoResume() {
        assertTrue(shouldPauseVideoForLifecycleEvent(Lifecycle.Event.ON_STOP))
        Lifecycle.Event.entries
            .filterNot { it == Lifecycle.Event.ON_STOP }
            .forEach { assertFalse(shouldPauseVideoForLifecycleEvent(it), "$it must not resume or pause") }
    }

    @Test
    fun sessionAlwaysReleasesPlayerBeforeTemporaryFile() {
        val releases = mutableListOf<String>()
        val owner = OrderedVideoSessionOwner<PlaybackResource, FileResource>(
            disposePlayer = { releases += "player:${it.id}" },
            disposeLease = { releases += "lease:${it.id}" },
        )

        owner.replaceLease(FileResource("one"))
        owner.attachPlayer(PlaybackResource("one"))
        owner.release()
        owner.release()

        assertEquals(listOf("player:one", "lease:one"), releases)
    }

    @Test
    fun replacingSessionAndLateDisposeAreIdentitySafe() {
        val releases = mutableListOf<String>()
        val owner = OrderedVideoSessionOwner<PlaybackResource, FileResource>(
            disposePlayer = { releases += "player:${it.id}" },
            disposeLease = { releases += "lease:${it.id}" },
        )
        val oldPlayer = PlaybackResource("old")
        val newPlayer = PlaybackResource("new")

        owner.replaceLease(FileResource("old"))
        owner.attachPlayer(oldPlayer)
        owner.replaceLease(FileResource("new"))
        owner.attachPlayer(newPlayer)
        owner.releasePlayer(oldPlayer)
        owner.release()

        assertEquals(
            listOf("player:old", "lease:old", "player:new", "lease:new"),
            releases,
        )
    }

    private class PlaybackResource(val id: String)
    private class FileResource(val id: String)
}
