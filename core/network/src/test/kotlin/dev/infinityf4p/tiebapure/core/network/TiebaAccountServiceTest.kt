package dev.infinityf4p.tiebapure.core.network

import dev.infinityf4p.tiebapure.core.model.BaiduWebCredentials
import dev.infinityf4p.tiebapure.core.model.MessageKind
import dev.infinityf4p.tiebapure.core.model.UserRelationshipKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient

class TiebaAccountServiceTest {
    @Test
    fun loginCombinesClientIdentityAndInitializedNickname() = withServer { server, client ->
        server.enqueue(MockResponse.Builder().body(
            """{"error_code":"0","user":{"id":"42","name":"raw","portrait":"p"},"anti":{"tbs":"fresh"}}""",
        ).build())
        server.enqueue(MockResponse.Builder().body(
            """{"user_info":{"name_show":"显示名"}}""",
        ).build())

        val service = DefaultTiebaAccountService(TiebaTransport(client), testRequestBuilder())
        val account = runBlocking { service.validateLogin(BaiduWebCredentials("BDUSS", "STOKEN", "BAIDUID")) }

        assertEquals("42", account.uid)
        assertEquals("显示名", account.displayName)
        assertEquals("fresh", account.tbs)
        assertEquals(listOf("/c/s/login", "/c/s/initNickname"), listOf(
            server.takeRequest().url.encodedPath,
            server.takeRequest().url.encodedPath,
        ))
    }

    @Test
    fun loginFallsBackToWebIdentityWhenClientResponsesAreIncomplete() = withServer { server, client ->
        server.enqueue(MockResponse.Builder().body("{}").build())
        server.enqueue(MockResponse.Builder().body("{}").build())
        server.enqueue(MockResponse.Builder().body(
            """{"data":{"uid":"84","name":"web","name_show":"网页用户","portrait":"pw","tbs":"wt","is_login":"1"}}""",
        ).build())

        val service = DefaultTiebaAccountService(TiebaTransport(client), testRequestBuilder())
        val account = runBlocking { service.validateLogin(BaiduWebCredentials("BDUSS", "STOKEN")) }

        assertEquals("84", account.uid)
        assertEquals("网页用户", account.displayName)
        assertEquals("/mo/q/newmoindex", server.takeRequest().let { server.takeRequest(); server.takeRequest() }.url.encodedPath)
    }

    @Test
    fun unsafeCookieIsRejectedBeforeNetwork() = withServer { server, client ->
        val service = DefaultTiebaAccountService(TiebaTransport(client), testRequestBuilder())

        assertFailsWith<TiebaAuthenticationException.InvalidCredentials> {
            runBlocking { service.validateLogin(BaiduWebCredentials("bad;cookie", "token")) }
        }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun accountFeedsDecodeFlexibleShapes() = withServer { server, client ->
        server.enqueue(MockResponse.Builder().body(
            """{"error_code":"0","follow_list":[{"id":"7","name":"raw","name_show":"用户","portrait":"p?x=1"}],"pn":"2","total_follow_num":"9","has_more":"1"}""",
        ).build())
        server.enqueue(MockResponse.Builder().body(
            """{"error_code":"0","reply_list":[{"thread_id":"99","post_id":"12","is_floor":"0","time":"123","content":"回复","title":"帖子标题","fname":"测试","replyer":{"id":"7","name":"raw","name_show":"用户","portrait":"p"}}],"page":{"current_page":"1","has_more":"0"}}""",
        ).build())
        val service = DefaultTiebaAccountService(TiebaTransport(client), testRequestBuilder())

        val relationships = runBlocking { service.relationships(testAccount(), 42, UserRelationshipKind.Following, 2) }
        val messages = runBlocking { service.messages(testAccount(), MessageKind.Reply, 1) }

        assertEquals(7, relationships.users.single().id)
        assertEquals("https://himg.bdimg.com/sys/portrait/item/p", relationships.users.single().portrait)
        assertEquals(99, messages.messages.single().threadId)
        assertEquals(12u, messages.messages.single().postId)
        assertEquals("帖子标题", messages.messages.single().threadTitle)
        assertEquals("测试", messages.messages.single().forumName)
        assertFalse(messages.messages.single().isFloorReply)
    }

    @Test
    fun portraitNormalizationHandlesAbsoluteTokenAndControlCharacters() {
        assertEquals("https://example.com/a.jpg", TiebaRemoteUrl.portrait("https://example.com/a.jpg"))
        assertEquals("https://example.com/a.jpg", TiebaRemoteUrl.portrait("http://example.com/a.jpg"))
        assertEquals(
            "https://himg.bdimg.com/sys/portrait/item/portrait-token",
            TiebaRemoteUrl.portrait("portrait-token?timestamp=1"),
        )
        assertEquals(
            "https://himg.bdimg.com/sys/portrait/item/legacy-token",
            TiebaRemoteUrl.portrait("https://tb.himg.baidu.com/sys/portrait/item/legacy-token"),
        )
        assertEquals("", TiebaRemoteUrl.portrait("portrait\r\nX-Test: bad"))
        assertEquals("portrait-token", TiebaRemoteUrl.portraitToken(
            "https://tb.himg.baidu.com/sys/portrait/item/portrait-token",
        ))
        assertEquals("portrait-token", TiebaRemoteUrl.portraitToken(
            "https://himg.bdimg.com/sys/portrait/item/portrait-token",
        ))
    }

    private fun withServer(block: (MockWebServer, OkHttpClient) -> Unit) {
        MockWebServer().use { server ->
            server.start()
            val client = TiebaHttpClientFactory.create {
                addInterceptor { chain ->
                    val request = chain.request()
                    val local = server.url(request.url.encodedPath).newBuilder().apply {
                        request.url.query?.let(::encodedQuery)
                    }.build()
                    chain.proceed(request.newBuilder().url(local).build())
                }
            }
            block(server, client)
        }
    }
}
