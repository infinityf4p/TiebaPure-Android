package dev.infinityf4p.tiebapure.core.media

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VoiceAudioPolicyTest {
    @Test
    fun buildsTheSameCanonicalVoiceUrlAsIos() {
        assertEquals(
            "https://tiebac.baidu.com/c/p/voice" +
                "?voice_md5=abcdef0123456789abcdef0123456789&play_from=pb_voice_play",
            VoiceAudioUrlPolicy.urlForMd5(" ABCDEF0123456789ABCDEF0123456789\n"),
        )
        assertNull(VoiceAudioUrlPolicy.urlForMd5("not-md5"))
    }

    @Test
    fun sourceUrlRequiresExactTrustedEndpointAndParameters() {
        val canonical = VoiceAudioUrlPolicy.urlForMd5("a".repeat(32))
        assertTrue(VoiceAudioUrlPolicy.isAllowedSourceUrl(canonical))
        assertFalse(VoiceAudioUrlPolicy.isAllowedSourceUrl(canonical + "&extra=1"))
        assertFalse(
            VoiceAudioUrlPolicy.isAllowedSourceUrl(
                "https://tiebac.baidu.com.evil.invalid/c/p/voice" +
                    "?voice_md5=${"a".repeat(32)}&play_from=pb_voice_play",
            ),
        )
        assertFalse(
            VoiceAudioUrlPolicy.isAllowedSourceUrl(
                canonical?.replace("https://", "http://"),
            ),
        )
    }

    @Test
    fun redirectsStayWithinBaiduHttpsButMayChangeEndpoint() {
        val source = checkNotNull(VoiceAudioUrlPolicy.urlForMd5("b".repeat(32)))
        assertEquals(
            "https://audio.bdstatic.com/cache/voice.mp3",
            VoiceAudioUrlPolicy.resolveRedirect(source, "https://audio.bdstatic.com/cache/voice.mp3"),
        )
        assertNull(VoiceAudioUrlPolicy.resolveRedirect(source, "http://audio.bdstatic.com/voice.mp3"))
        assertNull(VoiceAudioUrlPolicy.resolveRedirect(source, "https://attacker.invalid/voice.mp3"))
    }

    @Test
    fun allowsOnlyAudioOrGenericBinaryMimeTypes() {
        assertTrue(VoiceAudioUrlPolicy.isAllowedMimeType("audio/mpeg"))
        assertTrue(VoiceAudioUrlPolicy.isAllowedMimeType(" application/octet-stream "))
        assertFalse(VoiceAudioUrlPolicy.isAllowedMimeType("audio/"))
        assertFalse(VoiceAudioUrlPolicy.isAllowedMimeType("video/mp4"))
        assertFalse(VoiceAudioUrlPolicy.isAllowedMimeType(null))
    }

    @Test
    fun presentationUsesFallbackDurationAndClampsProgress() {
        assertEquals(
            VoicePlaybackPresentation(
                title = "语音",
                detail = "0:04",
                progress = null,
                action = VoicePlaybackAction.Toggle,
            ),
            VoicePlaybackControlPolicy.presentation(
                state = VoicePlaybackState(),
                fallbackDurationMilliseconds = 3_001,
            ),
        )
        assertEquals("1:02", VoicePlaybackControlPolicy.formatClock(61_001))
        assertEquals("50%", VoicePlaybackControlPolicy.formatPercent(0.504f))
        assertEquals("100%", VoicePlaybackControlPolicy.formatPercent(3f))

        val playing = VoicePlaybackControlPolicy.presentation(
            state = VoicePlaybackState(
                key = "a".repeat(32),
                phase = VoicePlaybackPhase.Playing,
                positionMilliseconds = 1_500,
                durationMilliseconds = 4_000,
            ),
            fallbackDurationMilliseconds = 1,
        )
        assertEquals("0:02 / 0:04", playing.detail)
        assertEquals(0.375f, playing.progress)
    }
}
