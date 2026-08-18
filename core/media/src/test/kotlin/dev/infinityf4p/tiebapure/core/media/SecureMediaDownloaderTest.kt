package dev.infinityf4p.tiebapure.core.media

import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okio.Buffer

class SecureMediaDownloaderTest {
    @Test
    fun directMp4DownloadsCompletelyAndLeaseDeletesFile() = withFixture { fixture ->
        fixture.server.enqueue(
            MockResponse.Builder()
                .addHeader("Content-Type", "video/mp4")
                .body(Buffer().write(mp4Payload(128 * 1_024)))
                .build(),
        )
        val directory = fixture.newDirectory("video")
        val downloader = SecureMediaDownloader(
            directory = directory,
            suppliedClient = fixture.client,
            maximumBytes = 256L * 1_024,
            kind = RemoteMediaKind.Video,
        )

        val downloaded = runBlocking {
            downloader.download("https://tb-video.bdstatic.com/tieba-smallvideo/demo.mp4")
        }

        assertEquals("video/mp4", downloaded.mimeType)
        assertEquals(128L * 1_024, downloaded.byteCount)
        assertTrue(downloaded.lease.file.isFile)
        downloaded.lease.release()
        assertFalse(downloaded.lease.file.exists())
    }

    @Test
    fun signedOrExtensionlessTrustedVideoIsValidatedByMimeAndSignature() = withFixture { fixture ->
        fixture.server.enqueue(
            MockResponse.Builder()
                .addHeader("Content-Type", "video/mp4")
                .body(Buffer().write(mp4Payload(4_096)))
                .build(),
        )

        val downloaded = runBlocking {
            fixture.videoDownloader().download("https://tb-video.bdstatic.com/play?id=trusted-signature")
        }

        assertEquals("video/mp4", downloaded.mimeType)
        downloaded.lease.release()
        assertNoFiles(fixture.videoDirectory)
    }

    @Test
    fun videoRejectsHlsBeforeNetwork() = withFixture { fixture ->
        val downloader = fixture.videoDownloader()

        assertFailsWith<MediaDownloadException> {
            runBlocking { downloader.download("https://tb-video.bdstatic.com/demo.m3u8") }
        }
        assertEquals(0, fixture.server.requestCount)
    }

    @Test
    fun redirectMustRemainTrustedAndDirectMp4() = withFixture { fixture ->
        val downloader = fixture.videoDownloader()
        fixture.server.enqueue(
            MockResponse.Builder()
                .code(302)
                .addHeader("Location", "https://attacker.invalid/stolen.mp4")
                .build(),
        )

        assertFailsWith<MediaDownloadException> {
            runBlocking { downloader.download("https://tb-video.bdstatic.com/start.mp4") }
        }
        assertEquals(1, fixture.server.requestCount)
        assertNoFiles(fixture.videoDirectory)

        fixture.server.enqueue(
            MockResponse.Builder()
                .code(302)
                .addHeader("Location", "https://tb-video.bdstatic.com/playlist.m3u8")
                .build(),
        )
        assertFailsWith<MediaDownloadException> {
            runBlocking { downloader.download("https://tb-video.bdstatic.com/start.mp4") }
        }
        assertEquals(2, fixture.server.requestCount)
        assertNoFiles(fixture.videoDirectory)
    }

    @Test
    fun declaredAndCumulativeSizeLimitsCleanTemporaryFiles() = withFixture { fixture ->
        val downloader = fixture.videoDownloader(maximumBytes = 64)
        fixture.server.enqueue(
            MockResponse.Builder()
                .addHeader("Content-Type", "video/mp4")
                .body(Buffer().write(mp4Payload(65)))
                .build(),
        )

        assertFailsWith<MediaDownloadException> {
            runBlocking { downloader.download("https://tb-video.bdstatic.com/declared.mp4") }
        }
        assertNoFiles(fixture.videoDirectory)

        fixture.server.enqueue(
            MockResponse.Builder()
                .addHeader("Content-Type", "video/mp4")
                .chunkedBody(Buffer().write(mp4Payload(65)), 8)
                .build(),
        )
        assertFailsWith<MediaDownloadException> {
            runBlocking { downloader.download("https://tb-video.bdstatic.com/chunked.mp4") }
        }
        assertNoFiles(fixture.videoDirectory)
    }

    @Test
    fun mimeAndFileSignatureMustBothBeMp4() = withFixture { fixture ->
        val downloader = fixture.videoDownloader()
        fixture.server.enqueue(
            MockResponse.Builder()
                .addHeader("Content-Type", "text/html")
                .body("not video")
                .build(),
        )
        assertFailsWith<MediaDownloadException> {
            runBlocking { downloader.download("https://tb-video.bdstatic.com/wrong-mime.mp4") }
        }
        assertNoFiles(fixture.videoDirectory)

        fixture.server.enqueue(
            MockResponse.Builder()
                .addHeader("Content-Type", "video/mp4")
                .body("not video")
                .build(),
        )
        assertFailsWith<MediaDownloadException> {
            runBlocking { downloader.download("https://tb-video.bdstatic.com/wrong-bytes.mp4") }
        }
        assertNoFiles(fixture.videoDirectory)
    }

    @Test
    fun cancellationStopsStreamingAndCleansTemporaryFile() = withFixture { fixture ->
        fixture.server.enqueue(
            MockResponse.Builder()
                .addHeader("Content-Type", "video/mp4")
                .body(Buffer().write(mp4Payload(256 * 1_024)))
                .throttleBody(1_024, 100, TimeUnit.MILLISECONDS)
                .build(),
        )
        val downloader = fixture.videoDownloader(maximumBytes = 512L * 1_024)

        runBlocking {
            val job = launch {
                downloader.download("https://tb-video.bdstatic.com/slow.mp4")
            }
            fixture.server.takeRequest(2, TimeUnit.SECONDS)
            job.cancelAndJoin()
        }

        assertNoFiles(fixture.videoDirectory)
    }

    @Test
    fun imageRequiresTrustedRedirectMimeAndSignature() = withFixture { fixture ->
        val directory = fixture.newDirectory("image")
        val downloader = SecureMediaDownloader(
            directory = directory,
            suppliedClient = fixture.client,
            maximumBytes = 1_024,
            kind = RemoteMediaKind.Image,
        )
        fixture.server.enqueue(
            MockResponse.Builder()
                .code(302)
                .addHeader("Location", "https://imgsa.bdimg.com/final.png")
                .build(),
        )
        fixture.server.enqueue(
            MockResponse.Builder()
                .addHeader("Content-Type", "image/png")
                .body(Buffer().write(pngPayload()))
                .build(),
        )

        val downloaded = runBlocking {
            downloader.download("https://tiebapic.baidu.com/start.png")
        }
        assertEquals(2, fixture.server.requestCount)
        downloaded.lease.release()
        assertNoFiles(directory)

        fixture.server.enqueue(
            MockResponse.Builder()
                .addHeader("Content-Type", "image/png")
                .body("not an image")
                .build(),
        )
        assertFailsWith<MediaDownloadException> {
            runBlocking { downloader.download("https://tiebapic.baidu.com/fake.png") }
        }
        assertNoFiles(directory)
    }

    @Test
    fun voiceDownloadsOnlyFromCanonicalEndpointAndLeaseDeletesFile() = withFixture { fixture ->
        fixture.server.enqueue(
            MockResponse.Builder()
                .addHeader("Content-Type", "audio/mpeg")
                .body(Buffer().write(ByteArray(128) { 1 }))
                .build(),
        )

        val downloaded = runBlocking {
            fixture.audioDownloader().download(checkNotNull(VoiceAudioUrlPolicy.urlForMd5("a".repeat(32))))
        }

        assertEquals("audio/mpeg", downloaded.mimeType)
        assertEquals(128, downloaded.byteCount)
        assertTrue(downloaded.lease.file.isFile)
        downloaded.lease.release()
        assertNoFiles(fixture.audioDirectory)

        assertFailsWith<MediaDownloadException> {
            runBlocking {
                fixture.audioDownloader().download(
                    "https://tiebac.baidu.com/c/p/other" +
                        "?voice_md5=${"a".repeat(32)}&play_from=pb_voice_play",
                )
            }
        }
        assertEquals(1, fixture.server.requestCount)
    }

    @Test
    fun voiceRejectsUntrustedRedirectMimeAndOversizeResponse() = withFixture { fixture ->
        val downloader = fixture.audioDownloader(maximumBytes = 64)
        val source = checkNotNull(VoiceAudioUrlPolicy.urlForMd5("b".repeat(32)))
        fixture.server.enqueue(
            MockResponse.Builder()
                .code(302)
                .addHeader("Location", "https://attacker.invalid/voice.mp3")
                .build(),
        )
        assertFailsWith<MediaDownloadException> { runBlocking { downloader.download(source) } }
        assertNoFiles(fixture.audioDirectory)

        fixture.server.enqueue(
            MockResponse.Builder()
                .addHeader("Content-Type", "text/html")
                .body("not audio")
                .build(),
        )
        assertFailsWith<MediaDownloadException> { runBlocking { downloader.download(source) } }
        assertNoFiles(fixture.audioDirectory)

        fixture.server.enqueue(
            MockResponse.Builder()
                .addHeader("Content-Type", "audio/mpeg")
                .body(Buffer().write(ByteArray(65) { 1 }))
                .build(),
        )
        assertFailsWith<MediaDownloadException> { runBlocking { downloader.download(source) } }
        assertNoFiles(fixture.audioDirectory)
    }

    private fun withFixture(block: (Fixture) -> Unit) {
        val root = Files.createTempDirectory("TiebaPureMediaTest-").toFile()
        MockWebServer().use { server ->
            server.start()
            val fixture = Fixture(root, server)
            try {
                block(fixture)
            } finally {
                root.deleteRecursively()
            }
        }
    }

    private class Fixture(
        private val root: File,
        val server: MockWebServer,
    ) {
        val videoDirectory = newDirectory("video-default")
        val audioDirectory = newDirectory("audio-default")
        val client: OkHttpClient = OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                val original = chain.request()
                val local = server.url(original.url.encodedPath).newBuilder().apply {
                    original.url.encodedQuery?.let(::encodedQuery)
                }.build()
                chain.proceed(original.newBuilder().url(local).build())
            })
            .build()

        fun newDirectory(name: String): File = File(root, name).apply { check(mkdirs()) }

        fun videoDownloader(maximumBytes: Long = 512L * 1_024): SecureMediaDownloader =
            SecureMediaDownloader(
                directory = videoDirectory,
                suppliedClient = client,
                maximumBytes = maximumBytes,
                kind = RemoteMediaKind.Video,
            )

        fun audioDownloader(maximumBytes: Long = 8L * 1_024 * 1_024): SecureMediaDownloader =
            SecureMediaDownloader(
                directory = audioDirectory,
                suppliedClient = client,
                maximumBytes = maximumBytes,
                kind = RemoteMediaKind.Audio,
            )
    }

    private companion object {
        fun mp4Payload(size: Int): ByteArray {
            require(size >= 12)
            return ByteArray(size).also { bytes ->
                bytes[3] = 24
                "ftyp".toByteArray(Charsets.US_ASCII).copyInto(bytes, 4)
                "isom".toByteArray(Charsets.US_ASCII).copyInto(bytes, 8)
            }
        }

        fun pngPayload(): ByteArray = byteArrayOf(
            0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a,
            0x00, 0x00, 0x00, 0x00,
        )

        fun assertNoFiles(directory: File) {
            assertTrue(directory.listFiles().orEmpty().isEmpty())
        }
    }
}
