package dev.infinityf4p.tiebapure.core.network

import dev.infinityf4p.tiebapure.core.model.ContentSubmissionKind
import dev.infinityf4p.tiebapure.core.model.ContentSubmissionRequest
import dev.infinityf4p.tiebapure.core.model.ContentSubmissionTarget
import dev.infinityf4p.tiebapure.core.model.TiebaLikeObjectType
import java.net.URLDecoder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RequestFactoryTest {
    @Test
    fun searchEncodesLiteralPlusInQuery() {
        val request = TiebaReadRequestFactory.searchThreads("C++", 1, testRequestBuilder())

        assertTrue(request.url.encodedQuery!!.contains("word=C%2B%2B"))
        assertFalse(request.url.encodedQuery!!.contains("word=C++"))
    }

    @Test
    fun replyMessagesUseSignedLegacyClientProfile() {
        val request = TiebaReadRequestFactory.messages(
            account = testAccount(),
            page = 2,
            mention = false,
            builder = testRequestBuilder(),
        )
        val fields = request.formFields()
        val signature = assertNotNull(fields["sign"])

        assertEquals(TiebaEndpoint.ReplyMessages.url, request.url)
        assertEquals("POST", request.method)
        assertEquals("bdtb for Android 8.2.2", request.header("User-Agent"))
        assertEquals("ka=open", request.header("Cookie"))
        assertEquals("no-cache", request.header("Pragma"))
        assertEquals("CLIENT|000000000000000", request.header("cuid"))
        assertEquals("bduss", fields["BDUSS"])
        assertEquals("2", fields["pn"])
        assertEquals("client", fields["_client_id"])
        assertEquals("2", fields["_client_type"])
        assertEquals("8.2.2", fields["_client_version"])
        assertEquals("baidu_appstore", fields["from"])
        assertEquals("1", fields["net_type"])
        assertEquals("0", fields["stErrorNums"])
        assertEquals("1725000000000", fields["timestamp"])
        assertNull(fields["subapp_type"])
        assertNull(fields["cuid_galaxy2"])
        assertEquals(TiebaFormSigner.sign(fields - "sign"), signature)
    }

    @Test
    fun mentionMessagesUseAtMeEndpointAndSignature() {
        val request = TiebaReadRequestFactory.messages(
            account = testAccount(),
            page = 1,
            mention = true,
            builder = testRequestBuilder(),
        )
        val fields = request.formFields()

        assertEquals(TiebaEndpoint.MentionMessages.url, request.url)
        assertEquals(TiebaFormSigner.sign(fields - "sign"), fields["sign"])
    }

    @Test
    fun likeUsesThreadSpecialPostIdAndToggleOperation() {
        val fields = TiebaMutationRequestFactory.like(
            account = testAccount(),
            tbs = "tbs",
            threadId = 100,
            postId = 200u,
            objectType = TiebaLikeObjectType.Thread,
            targetLiked = false,
            builder = testRequestBuilder(),
        )

        assertEquals("0", fields["post_id"])
        assertEquals("1", fields["op_type"])
        assertEquals("3", fields["obj_type"])
    }

    @Test
    fun subpostWebReplyIncludesBothParentIdentifiers() {
        val request = ContentSubmissionRequest(
            target = ContentSubmissionTarget(
                kind = ContentSubmissionKind.SubpostReply,
                forumId = 1,
                forumName = "测试",
                threadId = 2,
                parentPostId = 3u,
                parentFloor = 4,
                subpostId = 5u,
            ),
            body = "body",
        )
        val fields = ContentSubmissionRequestFactory.webReplyFields(
            testAccount(), "tbs", request, "", 1234,
        )

        assertEquals("3", fields["pid"])
        assertEquals("4", fields["floor"])
        assertEquals("5", fields["lzl_id"])
    }

    @Test
    fun followToggleSelectsMatchingMutationEndpoint() {
        val request = TiebaMutationHttpRequestFactory.userFollow(
            testAccount(),
            dev.infinityf4p.tiebapure.core.model.UserSummary(7, "u", "U", "portrait"),
            currentlyFollowed = true,
            tbs = "tbs",
            builder = testRequestBuilder(),
        )

        assertEquals(TiebaEndpoint.UnfollowUser.url, request.url)
        assertEquals("POST", request.method)
        assertEquals("tieba/${TiebaClientVersion.V22.value}", request.header("User-Agent"))
        val body = okio.Buffer().also { request.body!!.writeTo(it) }.readUtf8()
        assertTrue(body.contains("sign="))
    }

    @Test
    fun followRequestConvertsDisplayPortraitUrlBackToProtocolToken() {
        val fields = TiebaMutationRequestFactory.followUser(
            testAccount(),
            dev.infinityf4p.tiebapure.core.model.UserSummary(
                7, "u", "U", "https://tb.himg.baidu.com/sys/portrait/item/portrait-token",
            ),
            "tbs",
        )

        assertEquals("portrait-token", fields["portrait"])
    }

    @Test
    fun webMutationCookieExcludesBaiduId() {
        val request = TiebaMutationHttpRequestFactory.webNewThread(
            testAccount(),
            "tbs",
            ContentSubmissionRequest(
                ContentSubmissionTarget(ContentSubmissionKind.NewThread, 1, "测试"),
                title = "title",
                body = "body",
            ),
            testRequestBuilder(),
            1234,
        )

        assertEquals("BDUSS=bduss; STOKEN=stoken", request.header("Cookie"))
        assertEquals("https://tieba.baidu.com/f?kw=%E6%B5%8B%E8%AF%95", request.header("Referer"))
        assertEquals("zh-CN,zh;q=0.9", request.header("Accept-Language"))
        assertTrue(request.header("User-Agent").orEmpty().contains("iPhone OS 18_0"))
    }

    @Test
    fun strictWebTbsUsesTheSameIdentityAsTheFinalMutation() {
        val account = testAccount()
        val tbs = TiebaAuthRequestFactory.webTbs(account, testRequestBuilder(), 1234)
        val mutation = TiebaMutationHttpRequestFactory.webNewThread(
            account,
            "tbs",
            ContentSubmissionRequest(
                ContentSubmissionTarget(ContentSubmissionKind.NewThread, 1, "测试"),
                title = "title",
                body = "body",
            ),
            testRequestBuilder(),
            1234,
        )

        assertEquals(mutation.header("User-Agent"), tbs.header("User-Agent"))
        assertEquals(mutation.header("Cookie"), tbs.header("Cookie"))
        assertEquals("zh-CN,zh;q=0.9", tbs.header("Accept-Language"))
    }

    @Test
    fun webReplyAndUploadUseTheValidatedThreadReferer() {
        val account = testAccount()
        val submission = ContentSubmissionRequest(
            ContentSubmissionTarget(ContentSubmissionKind.ThreadReply, 1, "测试", threadId = 99),
            body = "body",
        )
        val reply = TiebaMutationHttpRequestFactory.webReply(
            account, "tbs", submission, "", testRequestBuilder(), 1234,
        )
        val upload = TiebaMutationHttpRequestFactory.webUploadPicture(
            account, 99, byteArrayOf(1), testRequestBuilder(), "1234_0",
        )
        val expected = "https://tieba.baidu.com/p/99?lp=5028&mo_device=1&is_jingpost=0&pn=1&"

        assertEquals(expected, reply.header("Referer"))
        assertEquals(expected, upload.header("Referer"))
        assertEquals(reply.header("User-Agent"), upload.header("User-Agent"))
        assertEquals("BDUSS=bduss; STOKEN=stoken", upload.header("Cookie"))
        val body = okio.Buffer().also { upload.body!!.writeTo(it) }.readUtf8()
        assertTrue(body.contains("pic=AQ%3D%3D"))
    }
}

private fun okhttp3.Request.formFields(): Map<String, String> {
    val body = okio.Buffer().also { this.body!!.writeTo(it) }.readUtf8()
    return body.split('&').associate { pair ->
        val separator = pair.indexOf('=')
        require(separator >= 0) { "Malformed form field" }
        URLDecoder.decode(pair.substring(0, separator), Charsets.UTF_8) to
            URLDecoder.decode(pair.substring(separator + 1), Charsets.UTF_8)
    }
}
