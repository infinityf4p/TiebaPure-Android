package dev.infinityf4p.tiebapure

import dev.infinityf4p.tiebapure.core.model.ContentBlock
import dev.infinityf4p.tiebapure.core.model.Forum
import dev.infinityf4p.tiebapure.core.model.ImageContent
import dev.infinityf4p.tiebapure.core.model.Post
import dev.infinityf4p.tiebapure.core.model.Subpost
import dev.infinityf4p.tiebapure.core.model.ThreadSummary
import dev.infinityf4p.tiebapure.core.model.UserSummary
import dev.infinityf4p.tiebapure.core.model.VideoContent
import dev.infinityf4p.tiebapure.core.model.VoiceContent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
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
}
