package dev.infinityf4p.tiebapure

import dev.infinityf4p.tiebapure.core.model.ContentBlock
import dev.infinityf4p.tiebapure.core.model.Forum
import dev.infinityf4p.tiebapure.core.model.ImageContent
import dev.infinityf4p.tiebapure.core.model.Post
import dev.infinityf4p.tiebapure.core.model.Subpost
import dev.infinityf4p.tiebapure.core.model.ThreadPage
import dev.infinityf4p.tiebapure.core.model.ThreadSummary
import dev.infinityf4p.tiebapure.core.model.UserSummary
import dev.infinityf4p.tiebapure.core.model.VideoContent
import dev.infinityf4p.tiebapure.core.model.VoiceContent
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SavedThreadBlobCodecTest {
    @Test
    fun allContentTypesRoundTrip() {
        val snapshot = snapshot()

        val decoded = SavedThreadBlobCodec.decode(SavedThreadBlobCodec.encode(snapshot))

        assertEquals(snapshot, decoded)
        assertEquals(1, decoded.replyCount)
        assertEquals(1, decoded.subpostCount)
    }

    @Test
    fun modifiedBlobFailsChecksum() {
        val encoded = SavedThreadBlobCodec.encode(snapshot())
        encoded[encoded.size / 2] = (encoded[encoded.size / 2].toInt() xor 1).toByte()

        assertThrows(IllegalStateException::class.java) {
            SavedThreadBlobCodec.decode(encoded)
        }
    }

    @Test
    fun mediaAndUpdateMetadataRoundTrip() {
        val asset = SavedThreadMediaAsset(
            sourceKey = "https://tiebapic.baidu.com/example.jpg",
            kind = SavedThreadMediaKind.Image,
            fileName = "image-abc.img",
            byteCount = 42,
            sha256 = "a".repeat(64),
        )
        val snapshot = snapshot().copy(
            mediaMode = SavedThreadMediaMode.Images,
            mediaAssets = listOf(asset),
            lastCheckedAtMilliseconds = 1_900_000_000_000,
            latestReplyCount = 4,
        )

        val decoded = SavedThreadBlobCodec.decode(SavedThreadBlobCodec.encode(snapshot)).validated()

        assertEquals(snapshot, decoded)
        assertEquals(3, decoded.newReplyCount)
    }

    @Test
    fun partialCaptureStatusRoundTrips() {
        val snapshot = snapshot().copy(
            replyCaptureComplete = false,
            subpostCaptureComplete = false,
            mediaCaptureComplete = false,
        )

        val decoded = SavedThreadBlobCodec.decode(SavedThreadBlobCodec.encode(snapshot))

        assertEquals(snapshot, decoded)
        assertTrue(decoded.isPartial)
    }

    @Test
    fun versionTwoSnapshotRemainsReadableAsComplete() {
        val snapshot = snapshot()
        val current = SavedThreadBlobCodec.encode(snapshot)
        val legacyPayload = current.copyOfRange(0, current.size - CHECKSUM_BYTES - CAPTURE_STATUS_BYTES)
        legacyPayload[VERSION_LAST_BYTE_INDEX] = 2
        val legacy = legacyPayload + MessageDigest.getInstance("SHA-256").digest(legacyPayload)

        val decoded = SavedThreadBlobCodec.decode(legacy)

        assertEquals(snapshot, decoded)
        assertFalse(decoded.isPartial)
    }

    @Test
    fun baselineKeepsVisibleTextBeforeFetchingRemainingReplies() {
        val source = saveSource(replyCount = 3)

        val baseline = savedThreadBaseline(
            source = source,
            savedAtMilliseconds = 1_800_000_000_100,
            requestedMode = SavedThreadMediaMode.Images,
        ).validated()

        assertEquals(listOf(11uL, 12uL), baseline.posts.map { it.post.id })
        assertTrue(checkNotNull(baseline.mainPost).post.contentPreview.contains("正文"))
        assertEquals(1, baseline.subpostCount)
        assertFalse(baseline.replyCaptureComplete)
        assertTrue(baseline.subpostCaptureComplete)
        assertFalse(baseline.mediaCaptureComplete)
    }

    @Test
    fun visibleMainPostWithoutPostIdCanStillBeSaved() {
        val source = saveSource(replyCount = 0).let { original ->
            original.copy(
                page = original.page.copy(
                    thread = original.page.thread.copy(firstPostId = null, replyCount = 0),
                    mainPost = checkNotNull(original.page.mainPost).copy(id = 0uL),
                    posts = emptyList(),
                    totalPage = 1,
                    hasMore = false,
                ),
                loadedPosts = emptyList(),
            )
        }

        val baseline = savedThreadBaseline(
            source = source,
            savedAtMilliseconds = 1_800_000_000_200,
            requestedMode = SavedThreadMediaMode.TextOnly,
        ).validated()

        assertEquals(0uL, baseline.mainPost?.post?.id)
        assertTrue(checkNotNull(baseline.mainPost).post.contentPreview.contains("正文"))
        assertFalse(baseline.isPartial)
    }

    @Test
    fun partialSaveMessageNamesMissingContentKinds() {
        val result = SavedThreadSaveResult(
            snapshot().copy(
                replyCaptureComplete = false,
                mediaCaptureComplete = false,
            ),
        )

        assertEquals(
            "已保存主楼文本、1 层回复和 1 条楼中楼（仅文字）；部分回复未保存、部分媒体未下载。",
            savedThreadSaveMessage(result),
        )
    }

    @Test
    fun failedUpdateReportsThatPreviousSaveWasRetained() {
        assertEquals(
            "更新未完成，已保留原有本地保存。",
            savedThreadSaveMessage(SavedThreadSaveResult(snapshot(), retainedPrevious = true)),
        )
    }

    @Test
    fun duplicateFloorsAndCrossPostSubpostsAreRejected() {
        val original = snapshot()
        val reply = original.posts.last()
        val duplicateFloor = original.copy(
            posts = original.posts + reply.copy(post = reply.post.copy(id = 99uL)),
        )
        assertThrows(IllegalStateException::class.java) { duplicateFloor.validated() }

        val nested = reply.subposts.first()
        val duplicateSubpost = original.copy(
            posts = listOf(
                original.posts.first().copy(subposts = listOf(nested)),
                reply,
            ),
        )
        assertThrows(IllegalStateException::class.java) { duplicateSubpost.validated() }
    }

    @Test
    fun multipleMainPostsAreRejected() {
        val original = snapshot()
        val secondMain = original.posts.last().let { saved ->
            saved.copy(post = saved.post.copy(id = 99uL, floor = 1))
        }

        assertThrows(IllegalStateException::class.java) {
            original.copy(posts = original.posts + secondMain).validated()
        }
    }

    private fun snapshot(): SavedThreadSnapshot {
        val author = UserSummary(1, "author", "作者", "portrait", 12, "十二级", "北京")
        val voice = checkNotNull(VoiceContent.create("0123456789abcdef0123456789abcdef", 1_200))
        val blocks = listOf(
            ContentBlock.Text("正文"),
            ContentBlock.Link("链接", "https://example.com"),
            ContentBlock.Mention(2, "@用户"),
            ContentBlock.Emoticon("滑稽"),
            ContentBlock.Image(ImageContent("thumb", "original", 640, 480, true, 1234)),
            ContentBlock.Video(VideoContent("video", "cover", "web", 1920, 1080, 30)),
            ContentBlock.Voice(voice),
        )
        val main = Post(11u, 100, 1, author, "北京", 1_700_000_000, blocks, 0, 2, true, emptyList())
        val reply = Post(12u, 100, 2, author, "上海", 1_700_000_100, listOf(ContentBlock.Text("回复")), 1, 1, false, emptyList())
        val nested = Subpost(21u, 1, author, "广东", listOf(ContentBlock.Text("楼中楼")), 1_700_000_200, 0)
        return SavedThreadSnapshot(
            thread = ThreadSummary(
                id = 100,
                forumId = 9,
                title = "本地保存测试",
                author = author,
                forumName = "测试",
                forumAvatarUrl = "avatar",
                replyCount = 1,
                viewCount = 9,
                likeCount = 2,
                firstPostId = 11u,
                isLiked = true,
                createdAtEpochSeconds = 1_700_000_000,
                lastReplyAtEpochSeconds = 1_700_000_100,
                blocks = blocks,
                isGood = true,
                hasVideo = true,
            ),
            forum = Forum(9, "测试", "测试吧", "avatar", 10, 20),
            posts = listOf(
                SavedThreadPostSnapshot(main, emptyList()),
                SavedThreadPostSnapshot(reply, listOf(nested)),
            ),
            savedAtMilliseconds = 1_800_000_000_000,
        )
    }

    private fun saveSource(replyCount: Int): SavedThreadSaveSource {
        val snapshot = snapshot()
        val main = checkNotNull(snapshot.mainPost).post
        val savedReply = snapshot.posts.last()
        val reply = savedReply.post.copy(previewSubposts = savedReply.subposts)
        return SavedThreadSaveSource(
            page = ThreadPage(
                thread = snapshot.thread.copy(replyCount = replyCount),
                forum = snapshot.forum,
                mainPost = main,
                posts = listOf(reply),
                currentPage = 1,
                totalPage = if (replyCount > 1) 2 else 1,
                hasMore = replyCount > 1,
            ),
            loadedPosts = listOf(reply),
        )
    }

    private companion object {
        const val CHECKSUM_BYTES = 32
        const val CAPTURE_STATUS_BYTES = 3
        const val VERSION_LAST_BYTE_INDEX = 7
    }
}
