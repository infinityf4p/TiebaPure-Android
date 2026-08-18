package dev.infinityf4p.tiebapure.core.network

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AuthRequestFactoryTest {
    @Test
    fun webIdentityUsesMinimalValidatedCookieHeader() {
        val request = TiebaAuthRequestFactory.webMyInfo(testAccount(), testRequestBuilder())

        assertEquals("BDUSS=bduss; STOKEN=stoken; BAIDUID=baiduid", request.header("Cookie"))
        assertFalse(request.header("Cookie")!!.contains("CUID"))
    }

    @Test
    fun loginIsSignedAndDoesNotSendRejectedHeaders() {
        val request = TiebaAuthRequestFactory.login(
            "bduss",
            "stoken",
            "baiduid",
            testRequestBuilder(),
            1234,
        )
        val buffer = okio.Buffer()
        request.body!!.writeTo(buffer)
        val body = buffer.readUtf8()

        assertEquals("POST", request.method)
        assertEquals(null, request.header("Charset"))
        assertEquals(null, request.header("client_type"))
        assertTrue(body.contains("bdusstoken=bduss%7C"))
        assertTrue(body.contains("sign="))
    }
}
