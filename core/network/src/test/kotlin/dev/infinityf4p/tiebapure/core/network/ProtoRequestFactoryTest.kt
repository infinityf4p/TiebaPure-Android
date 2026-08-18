package dev.infinityf4p.tiebapure.core.network

import dev.infinityf4p.tiebapure.core.model.ForumThreadCategory
import dev.infinityf4p.tiebapure.core.model.ThreadReplySort
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProtoRequestFactoryTest {
    private val requestBuilder = testRequestBuilder()
    private val factory = TiebaProtoRequestFactory(requestBuilder, EpochMillisecondsClock { 1234 })

    @Test
    fun commonCopiesAccountAndDeviceFields() {
        val common = factory.common(testAccount())

        assertEquals("bduss", common.bduss)
        assertEquals("stoken", common.stoken)
        assertEquals("client", common.clientId)
        assertEquals(TiebaClientVersion.V12.value, common.clientVersion)
        assertEquals(1080, common.scrW)
        assertEquals(2400, common.scrH)
        assertEquals(3.0, common.scrDip)
        assertEquals(1234, common.timestamp)
    }

    @Test
    fun postTargetOnFirstPageUsesServerPageLookup() {
        val message = factory.threadPage(
            account = null,
            threadId = 99,
            page = 1,
            postId = 88u,
            onlyThreadAuthor = true,
            sort = ThreadReplySort.Hot,
        )

        assertEquals(0, message.data.pn)
        assertEquals(88L, message.data.pid)
        assertEquals(1, message.data.lz)
        assertEquals(2, message.data.r)
    }

    @Test
    fun featuredForumUsesCompleteGoodFilterTuple() {
        val message = factory.forumThreads(testAccount(), "测试", 1, ForumThreadCategory.Featured)

        assertTrue(message.data.hasSortType())
        assertEquals(-1, message.data.sortType)
        assertTrue(message.data.hasIsGood())
        assertEquals(1, message.data.isGood)
        assertTrue(message.data.hasCid())
        assertEquals(0, message.data.cid)
    }
}
