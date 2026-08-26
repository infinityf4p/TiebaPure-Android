package dev.infinityf4p.tiebapure.core.network

import dev.infinityf4p.tiebapure.core.model.ContentBlock
import dev.infinityf4p.tiebapure.core.model.ForumThreadCategory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okio.Buffer
import tieba.ErrorOuterClass.Error
import tieba.PbContentOuterClass.PbContent
import tieba.Personalized
import tieba.ThreadInfoOuterClass.ThreadInfo
import tieba.UserOuterClass.User
import tieba.frsPage.FrsPage
import tiebapure.profile.UserProfile as ProfileProtocol

class TiebaReadServiceTest {
    @Test
    fun personalizedRequestExecutesAndDecodesProtobuf() = withServer { server, client ->
        val response = Personalized.PersonalizedResponse.newBuilder()
            .setError(Error.getDefaultInstance())
            .setData(Personalized.PersonalizedResponseData.newBuilder().addThreadList(
                ThreadInfo.newBuilder()
                    .setId(42)
                    .setTitle("fixture title")
                    .setAuthor(User.newBuilder().setId(7).setName("author").setNameShow("作者"))
                    .addFirstPostContent(PbContent.newBuilder().setType(0).setText("fixture body"))
                    .build(),
            ))
            .build()
        server.enqueue(MockResponse.Builder().body(Buffer().write(response.toByteArray())).build())

        val service = DefaultTiebaReadService(TiebaTransport(client), testRequestBuilder())
        val threads = runBlocking { service.home(account = null, page = 1, loadType = 1) }

        assertEquals(42, threads.single().id)
        assertEquals("作者", threads.single().author.displayName)
        assertEquals("fixture body", (threads.single().blocks.single() as ContentBlock.Text).value)
        assertEquals("/c/f/excellent/personalized", server.takeRequest().url.encodedPath)
    }

    @Test
    fun anonymousForumRequestExecutesAndDecodesFlexibleJson() = withServer { server, client ->
        server.enqueue(MockResponse.Builder().body(
            """{
              "error_code":"0",
              "user_list":[{"id":"9","name":"raw","name_show":"显示名","portrait":"p"}],
              "thread_list":[{
                "id":"88","title":"吧页主题","author_id":"9","reply_num":"12","view_num":34,
                "agreeNum":"5","abstract":[{"text":"摘要"}],"media":[{
                  "src_pic":"http://imgsrc.baidu.com/thumb.jpg","origin_pic":"https://imgsrc.baidu.com/original.jpg",
                  "show_original_btn":"1"
                }]
              }]
            }""".trimIndent(),
        ).build())

        val service = DefaultTiebaReadService(TiebaTransport(client), testRequestBuilder())
        val page = runBlocking { service.forum(null, "测试", 1, ForumThreadCategory.ReplyTime) }

        assertEquals(88, page.threads.single().id)
        assertEquals(5, page.threads.single().likeCount)
        assertEquals("显示名", page.threads.single().author.displayName)
        assertIs<ContentBlock.Image>(page.threads.single().blocks.last())
        assertTrue((page.threads.single().blocks.last() as ContentBlock.Image).value.thumbnailUrl!!.startsWith("https://"))
    }

    @Test
    fun forumInfoRequestDecodesPublicForumMetadata() = withServer { server, client ->
        server.enqueue(MockResponse.Builder().body(
            """{
              "error_code":"0",
              "forum":{
                "id":"7160",
                "member_num":"308524",
                "post_num":"4664391",
                "thread_num":"155882",
                "slogan":"同济学子和谐有爱的网络大家庭~",
                "first_class":"教育",
                "second_class":"华东地区高等院校"
              }
            }""".trimIndent(),
        ).build())

        val service = DefaultTiebaReadService(TiebaTransport(client), testRequestBuilder())
        val info = runBlocking { service.forumInfo("同济大学吧") }
        val request = server.takeRequest()

        assertEquals("/c/f/frs/page", request.url.encodedPath)
        assertEquals(7160, info.forumId)
        assertEquals(308_524, info.memberCount)
        assertEquals(4_664_391, info.postCount)
        assertEquals(155_882, info.threadCount)
        assertEquals("同济学子和谐有爱的网络大家庭~", info.introduction)
        assertEquals("教育", info.primaryCategory)
        assertEquals("华东地区高等院校", info.secondaryCategory)
    }

    @Test
    fun authenticatedForumRequestDeclaresAndDecodesProtobuf() = withServer { server, client ->
        val response = FrsPage.FrsPageResponse.newBuilder()
            .setError(Error.getDefaultInstance())
            .setData(
                FrsPage.FrsPageResponseData.newBuilder()
                    .addUserList(User.newBuilder().setId(9).setName("raw").setNameShow("显示名"))
                    .addThreadList(
                        ThreadInfo.newBuilder()
                            .setId(88)
                            .setTitle("吧页主题")
                            .setAuthorId(9)
                            .setForumId(7)
                            .setForumName("测试")
                            .build(),
                    ),
            )
            .build()
        server.enqueue(MockResponse.Builder().body(Buffer().write(response.toByteArray())).build())

        val service = DefaultTiebaReadService(TiebaTransport(client), testRequestBuilder())
        val page = runBlocking {
            service.forum(testAccount(), "测试", 1, ForumThreadCategory.ReplyTime)
        }
        val request = server.takeRequest()

        assertEquals("protobuf", request.headers["X-BD-DATA-TYPE"])
        assertEquals("301001", request.url.queryParameter("cmd"))
        assertEquals(88, page.threads.single().id)
        assertEquals("显示名", page.threads.single().author.displayName)
    }

    @Test
    fun userThreadsOnlyExposeDeletionTargetsForCurrentAccount() = withServer { server, client ->
        val response = ProfileProtocol.UserThreadsResponse.newBuilder()
            .setError(Error.getDefaultInstance())
            .setData(
                ProfileProtocol.UserThreadsResponseData.newBuilder()
                    .addPostList(
                        ProfileProtocol.UserThreadItem.newBuilder()
                            .setForumId(11)
                            .setForumName("测试")
                            .setThreadId(22)
                            .setPostId(33)
                            .setTitle("fixture title"),
                    ),
            )
            .build()
        repeat(2) {
            server.enqueue(MockResponse.Builder().body(Buffer().write(response.toByteArray())).build())
        }

        val service = DefaultTiebaReadService(TiebaTransport(client), testRequestBuilder())
        val otherUserPage = runBlocking { service.userThreads(testAccount(), userId = 7, page = 1) }
        val currentUserPage = runBlocking { service.userThreads(testAccount(), userId = 42, page = 1) }

        assertTrue(otherUserPage.deletionTargetsByThreadId.isEmpty())
        val target = currentUserPage.deletionTargetsByThreadId.getValue(22)
        assertEquals(11L, target.forumId)
        assertEquals("测试", target.forumName)
        assertEquals(22L, target.threadId)
        assertEquals(33uL, target.firstPostId)
    }

    @Test
    fun searchFixtureAcceptsObjectAndDictionaryUserShapes() {
        val page = TiebaJsonMapper.searchUsers(
            """{"no":"0","data":{"exactMatch":{"user_id":"1","user_name":"alpha","show_nickname":"Alpha"},"fuzzyMatch":{"x":{"uid":2,"name":"beta"}}}}""",
        )

        assertEquals(listOf(true, false), page.results.map { it.isExactMatch })
        assertEquals(listOf(1L, 2L), page.results.map { it.user.id })
    }

    @Test
    fun searchReplyIdDoesNotMasqueradeAsThreadFirstPostId() {
        val page = TiebaJsonMapper.searchThreads(
            """{"no":0,"data":{"current_page":1,"has_more":0,"post_list":[{"tid":"42","pid":"99","title":"主题","content":"命中的回复"}]}}""",
        )

        assertEquals(99uL, page.results.single().matchedPostId)
        assertNull(page.results.single().thread.firstPostId)
    }

    @Test
    fun searchReplyKeepsExplicitFirstPostIdSeparateFromMatchedPostId() {
        val page = TiebaJsonMapper.searchThreads(
            """{"no":0,"data":{"post_list":[{"tid":"42","pid":"99","content":"命中的回复","main_post":{"pid":"11","content":"主楼"},"post_info":{"pid":"99","content":"命中的回复"}}]}}""",
        )

        assertEquals(99uL, page.results.single().matchedPostId)
        assertEquals(11uL, page.results.single().thread.firstPostId)
    }

    @Test
    fun searchMainPostMatchUsesMatchedPostAsConfirmedFirstPost() {
        val page = TiebaJsonMapper.searchThreads(
            """{"no":0,"data":{"post_list":[{"tid":"42","pid":"11","content":"主楼","main_post":{"content":"主楼"}}]}}""",
        )

        assertEquals(11uL, page.results.single().matchedPostId)
        assertEquals(11uL, page.results.single().thread.firstPostId)
    }

    private fun withServer(block: (MockWebServer, OkHttpClient) -> Unit) {
        MockWebServer().use { server ->
            server.start()
            val client = TiebaHttpClientFactory.create {
                addInterceptor { chain ->
                    val original = chain.request()
                    val local = server.url(original.url.encodedPath).newBuilder().apply {
                        original.url.query?.let(::encodedQuery)
                    }.build()
                    chain.proceed(original.newBuilder().url(local).build())
                }
            }
            block(server, client)
        }
    }
}
