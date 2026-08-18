package dev.infinityf4p.tiebapure.core.network

import dev.infinityf4p.tiebapure.core.model.Account
import dev.infinityf4p.tiebapure.core.model.ContentSubmissionKind
import dev.infinityf4p.tiebapure.core.model.ContentSubmissionRequest
import dev.infinityf4p.tiebapure.core.model.ContentSubmissionTarget
import dev.infinityf4p.tiebapure.core.model.TiebaLikeObjectType
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient

class TiebaWriteServiceTest {
    @Test
    fun clientDisablesAutomaticConnectionRetry() {
        assertFalse(TiebaHttpClientFactory.create().retryOnConnectionFailure)
    }

    @Test
    fun likeUsesTargetStateForEveryObjectTypeAndExecutesExactlyOnce() = withServer { server, client ->
        repeat(6) {
            server.enqueue(MockResponse.Builder().body("""{"error_code":"0","error_msg":""}""").build())
        }
        val service = service(client, FakeSessions(clientTbs = "fresh"))

        runBlocking {
            TiebaLikeObjectType.entries.forEach { type ->
                service.setPostLiked(testAccount(), 99, 7u, type, liked = true)
                service.setPostLiked(testAccount(), 99, 7u, type, liked = false)
            }
        }

        val requests = List(6) { server.takeRequest() }
        assertTrue(requests.all { it.url.encodedPath == "/c/c/agree/opAgree" })
        assertEquals(6, server.requestCount)
        requests.chunked(2).forEachIndexed { index, pair ->
            val expectedType = TiebaLikeObjectType.entries[index]
            val likedBody = pair[0].body?.utf8().orEmpty()
            val unlikedBody = pair[1].body?.utf8().orEmpty()
            assertTrue(likedBody.contains("tbs=fresh"))
            assertTrue(likedBody.contains("obj_type=${expectedType.protocolValue}"))
            assertTrue(likedBody.contains("op_type=0"))
            assertTrue(unlikedBody.contains("op_type=1"))
        }
    }

    @Test
    fun verificationResponseStopsWithoutRetry() = withServer { server, client ->
        server.enqueue(MockResponse.Builder().body(
            """{"error_code":"0","result":"0","need_vcode":"1","vcode_md5":"abc","error_msg":"需要验证码"}""",
        ).build())
        val service = service(client, FakeSessions(webTbs = "web-tbs"))
        val request = ContentSubmissionRequest(
            target = ContentSubmissionTarget(
                kind = ContentSubmissionKind.ThreadReply,
                forumId = 7,
                forumName = "测试",
                threadId = 99,
            ),
            body = "reply",
        )

        val error = assertFailsWith<ContentSubmissionException.VerificationRequired> {
            runBlocking { service.submitContent(testAccount(), request) }
        }

        assertEquals("abc", error.challenge.md5)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun malformedSuccessBecomesOutcomeUnknownWithoutRetry() = withServer { server, client ->
        server.enqueue(MockResponse.Builder().body("{}").build())
        val service = service(client, FakeSessions(webTbs = "web-tbs"))
        val request = ContentSubmissionRequest(
            target = ContentSubmissionTarget(ContentSubmissionKind.NewThread, 7, "测试"),
            title = "title",
            body = "body",
        )

        assertFailsWith<ContentSubmissionException.OutcomeUnknown> {
            runBlocking { service.submitContent(testAccount(), request) }
        }
        assertEquals(1, server.requestCount)
    }

    @Test
    fun accountGateSerializesSameAccountButNotDifferentAccounts() {
        runBlocking {
            val gate = TiebaAccountWriteGate()
            val active = AtomicInteger(0)
            val maximum = AtomicInteger(0)
            val release = CompletableDeferred<Unit>()
            suspend fun work(account: Account) = gate.withAccount(account) {
                maximum.updateAndGet { maxOf(it, active.incrementAndGet()) }
                release.await()
                active.decrementAndGet()
            }

            val first = async { work(testAccount()) }
            val second = async { work(testAccount()) }
            while (active.get() == 0) delay(1)
            assertEquals(1, maximum.get())
            release.complete(Unit)
            first.await()
            second.await()
        }
    }

    @Test
    fun invalidationRejectsNewWritesAndWaitsForAdmittedWrite() {
        runBlocking {
            val gate = TiebaAccountWriteGate()
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val running = async {
                gate.withAccount(testAccount()) {
                    entered.complete(Unit)
                    release.await()
                }
            }
            entered.await()
            val invalidation = async { gate.invalidateAndDrain(testAccount()) }
            delay(10)
            assertFalse(invalidation.isCompleted)
            assertFailsWith<ContentSubmissionException.NotLoggedIn> {
                gate.withAccount(testAccount()) { Unit }
            }
            release.complete(Unit)
            running.await()
            invalidation.await()
            assertTrue(invalidation.isCompleted)
        }
    }

    @Test
    fun validatedSessionCanBeReactivatedAfterDrain() = runBlocking {
        val gate = TiebaAccountWriteGate()
        gate.invalidateAndDrain(testAccount())
        assertFailsWith<ContentSubmissionException.NotLoggedIn> {
            gate.withAccount(testAccount()) { Unit }
        }
        gate.activate(testAccount())
        gate.withAccount(testAccount()) { Unit }
    }

    private fun service(client: OkHttpClient, sessions: TiebaSessionService) = DefaultTiebaWriteService(
        TiebaTransport(client), sessions, testRequestBuilder(), EpochMillisecondsClock { 1_725_000_000_000 },
    )

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

    private class FakeSessions(
        private val clientTbs: String = "client-tbs",
        private val webTbs: String = "web-tbs",
    ) : TiebaSessionService {
        override suspend fun refreshedClientTbs(account: Account, allowsStoredFallback: Boolean) = clientTbs
        override suspend fun strictlyRefreshedWebTbs(account: Account) = webTbs
    }
}
