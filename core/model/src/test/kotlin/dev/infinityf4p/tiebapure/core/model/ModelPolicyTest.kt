package dev.infinityf4p.tiebapure.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ModelPolicyTest {
    @Test
    fun accountCookieContainsOnlyValidatedCookieNames() {
        val account = Account("1", "raw", "Display", "p", "bd", "st", "ba", "tbs")

        assertEquals("BDUSS=bd; STOKEN=st; BAIDUID=ba", account.minimalCookieHeader())
    }

    @Test
    fun forumRoutePreservesProtocolNameAndAddsDisplaySuffixOnly() {
        val thread = ThreadSummary(
            id = 1,
            title = "title",
            author = UserSummary(1, "u", "", ""),
            forumName = "网吧",
            replyCount = 0,
            viewCount = 0,
            blocks = emptyList(),
        )

        assertEquals("网吧", thread.forumRoute()?.name)
        assertEquals("网吧", thread.forumRoute()?.displayName)
    }

    @Test
    fun invalidVoiceMd5IsRejected() {
        assertNull(VoiceContent.create("not-an-md5", 1))
        assertEquals(0, VoiceContent.create("A".repeat(32), -1)?.durationMilliseconds)
    }

    @Test
    fun contentFilterKeepsOpenedMainPostButFiltersRepliesAndNestedReplies() {
        val blocked = UserSummary(9, "blocked", "屏蔽用户", "")
        val allowed = UserSummary(10, "allowed", "正常用户", "")
        val blocklist = TiebaBlocklistSnapshot.from(
            listOf(
                BlocklistEntry(BlocklistEntryKind.User, "blocked", numericId = 9),
                BlocklistEntry(BlocklistEntryKind.Keyword, "广告词"),
            ),
        )
        val blockedSubpost = Subpost(21u, 1, blocked, null, listOf(ContentBlock.Text("正常回复")), null, 0)
        val main = Post(1u, 42, 1, blocked, null, null, listOf(ContentBlock.Text("广告词")), 1, 0, previewSubposts = listOf(blockedSubpost))
        val blockedReply = main.copy(id = 2u, floor = 2)
        val allowedReply = main.copy(
            id = 3u,
            floor = 3,
            author = allowed,
            blocks = listOf(ContentBlock.Text("正常内容")),
            previewSubposts = listOf(blockedSubpost),
        )
        val thread = ThreadSummary(42, title = "主题", author = blocked, replyCount = 2, viewCount = 1, blocks = main.blocks)
        val page = ThreadPage(thread, Forum(1, "测试", "测试吧"), main, listOf(main, blockedReply, allowedReply), 1, 1, false)

        val filtered = TiebaContentFilterPolicy.filter(page, blocklist)

        assertEquals(1uL, filtered.mainPost?.id)
        assertEquals(listOf(1uL, 3uL), filtered.posts.map(Post::id))
        assertTrue(filtered.posts.last().previewSubposts.isEmpty())
    }

    @Test
    fun contentFilterUsesForumUserAndKeywordRulesAcrossModels() {
        val blocklist = TiebaBlocklistSnapshot.from(
            listOf(
                BlocklistEntry(BlocklistEntryKind.Forum, "测试吧", numericId = 7),
                BlocklistEntry(BlocklistEntryKind.User, "Muted"),
                BlocklistEntry(BlocklistEntryKind.Keyword, "spoiler"),
            ),
        )
        val muted = UserSummary(0, "muted", "", "")
        val clean = UserSummary(2, "clean", "Clean", "")
        val thread = ThreadSummary(1, 7, "普通主题", clean, "测试", replyCount = 0, viewCount = 0, blocks = emptyList())
        val message = TiebaMessage("m", MessageKind.Reply, clean, 1, 2u, "正文", null, false, threadTitle = "contains SPOILER")
        val forumMessage = message.copy(threadTitle = "普通标题", forumName = "测试")
        val favorite = AccountThreadFavorite(1, 7, "测试吧", "普通主题", "Clean", 0, null, null)

        assertFalse(TiebaContentFilterPolicy.shouldKeep(thread, blocklist))
        assertFalse(TiebaContentFilterPolicy.shouldKeep(message, blocklist))
        assertFalse(TiebaContentFilterPolicy.shouldKeep(forumMessage, blocklist))
        assertFalse(TiebaContentFilterPolicy.shouldKeep(muted, blocklist))
        assertFalse(TiebaContentFilterPolicy.shouldKeep(favorite, blocklist))
        assertTrue(TiebaContentFilterPolicy.shouldKeep(clean, blocklist))
    }
}
