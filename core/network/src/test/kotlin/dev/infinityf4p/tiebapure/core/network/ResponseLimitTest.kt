package dev.infinityf4p.tiebapure.core.network

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSource

class ResponseLimitTest {
    @Test
    fun streamedBodyAtLimitIsAccepted() {
        val body = LimitedResponseBody(UnknownLengthBody("1234"), maximumBytes = 4)

        assertEquals("1234", body.string())
    }

    @Test
    fun streamedBodyBeyondLimitIsRejectedWithoutContentLength() {
        val body = LimitedResponseBody(UnknownLengthBody("12345"), maximumBytes = 4)

        val error = assertFailsWith<TiebaNetworkException.ResponseTooLarge> { body.bytes() }
        assertEquals(4, error.limitBytes)
    }

    private class UnknownLengthBody(content: String) : ResponseBody() {
        private val source = Buffer().writeUtf8(content)

        override fun contentType() = "text/plain".toMediaType()
        override fun contentLength(): Long = -1
        override fun source(): BufferedSource = source
    }
}
