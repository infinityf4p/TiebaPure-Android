package dev.infinityf4p.tiebapure.core.network

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TiebaFormSignerTest {
    @Test
    fun signerMatchesCanonicalSortAndSignRule() {
        assertEquals(
            "42961B9881C2D7CB297E9498F9767789",
            TiebaFormSigner.sign(mapOf("b" to "2", "a" to "1")),
        )
    }

    @Test
    fun formEncodingPreservesLineBreaksAndEscapesDelimiters() {
        assertEquals(
            "content=a%26b%3Dc%2Bd%3F%0A%E4%B8%AD%E6%96%87",
            TiebaFormCodec.encode(mapOf("content" to "a&b=c+d?\n中文")),
        )
    }

    @Test
    fun signedFormBodyUsesStableKeyOrder() {
        val request = testRequestBuilder().formRequest(
            TiebaEndpoint.ForumPageForm,
            fields = linkedMapOf("z" to "last", "a" to "first"),
            signingSecret = TiebaFormSigner.DEFAULT_SECRET,
        )
        val buffer = okio.Buffer()
        request.body!!.writeTo(buffer)
        val body = buffer.readUtf8()

        assertEquals("a=first", body.substringBefore('&'))
        assertEquals(listOf("a", "sign", "z"), body.split('&').map { it.substringBefore('=') })
    }

    @Test
    fun multipartContainsStokenAndSerializedProtobuf() {
        val message = tieba.CommonRequestOuterClass.CommonRequest.newBuilder().setClientType(2).build()
        val body = ProtoMultipartBody.create(message, stoken = "stoken")
        val buffer = okio.Buffer()
        body.writeTo(buffer)
        val bytes = buffer.readByteArray()
        val text = bytes.toString(Charsets.ISO_8859_1)

        assertTrue(text.contains("name=\"stoken\""))
        assertTrue(text.contains("name=\"data\"; filename=\"file\""))
        assertTrue(text.endsWith("${ProtoMultipartBody.BOUNDARY}--\r\n"))
    }
}
