package dev.infinityf4p.tiebapure.core.network

import com.google.protobuf.MessageLite
import com.google.protobuf.Parser
import java.io.IOException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/** Executes one request without retaining cookies or leaking OkHttp response types. */
class TiebaTransport(
    private val client: OkHttpClient,
) {
    suspend fun bytes(request: Request): ByteArray = client.newCall(request).awaitBytes()

    suspend fun text(request: Request): String = bytes(request).toString(Charsets.UTF_8)

    /**
     * Executes a non-idempotent request exactly once. Cancellation is honored
     * before the call starts; after that point the response is awaited because
     * the server may already have committed the mutation.
     */
    suspend fun writeBytes(request: Request): ByteArray {
        currentCoroutineContext().ensureActive()
        val tracker = WriteDispatchTracker()
        val tagged = request.newBuilder().tag(WriteDispatchTracker::class.java, tracker).build()
        return try {
            withContext(NonCancellable) { client.newCall(tagged).awaitBytes() }
        } catch (error: Throwable) {
            throw TiebaFailureClassifier.classify(error, TiebaOperation.Write, tracker.requestStarted)
        }
    }

    suspend fun writeText(request: Request): String = writeBytes(request).toString(Charsets.UTF_8)

    suspend fun <T : MessageLite> protobuf(request: Request, parser: Parser<T>): T {
        val payload = bytes(request)
        return try {
            parser.parseFrom(payload)
        } catch (error: Exception) {
            throw TiebaNetworkException.Decode(error)
        }
    }
}

private suspend fun Call.awaitBytes(): ByteArray = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, error: IOException) {
            if (continuation.isActive) continuation.resumeWith(Result.failure(error))
        }

        override fun onResponse(call: Call, response: Response) {
            try {
                response.use {
                    it.requireSuccess()
                    val payload = it.body.bytes()
                    if (continuation.isActive) continuation.resumeWith(Result.success(payload))
                }
            } catch (error: Throwable) {
                if (continuation.isActive) continuation.resumeWith(Result.failure(error))
            }
        }
    })
}
