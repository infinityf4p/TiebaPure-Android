package dev.infinityf4p.tiebapure.core.network

import java.io.IOException
import java.util.concurrent.TimeUnit
import okhttp3.CookieJar
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.buffer

object TiebaHttpClientFactory {
    const val MAXIMUM_API_RESPONSE_BYTES: Long = 16L * 1_024 * 1_024

    fun create(
        maximumResponseBytes: Long = MAXIMUM_API_RESPONSE_BYTES,
        configure: OkHttpClient.Builder.() -> Unit = {},
    ): OkHttpClient {
        require(maximumResponseBytes > 0)
        return OkHttpClient.Builder()
            .cookieJar(CookieJar.NO_COOKIES)
            // A mutation may have reached Tieba before a connection failure.
            // Retrying below the repository would risk duplicate writes.
            .retryOnConnectionFailure(false)
            .followRedirects(false)
            .followSslRedirects(false)
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .callTimeout(45, TimeUnit.SECONDS)
            .addInterceptor(StrictRedirectInterceptor())
            // This must be an application interceptor so the cap applies to the
            // decoded stream, not only to a potentially tiny gzip payload.
            .addInterceptor(ResponseSizeLimitInterceptor(maximumResponseBytes))
            .apply(configure)
            // These invariants cannot be relaxed by the optional configuration block.
            .cookieJar(CookieJar.NO_COOKIES)
            .retryOnConnectionFailure(false)
            .followRedirects(false)
            .followSslRedirects(false)
            .eventListenerFactory { WriteDispatchEventListener }
            .build()
    }
}

internal class WriteDispatchTracker {
    @Volatile
    var requestStarted: Boolean = false
        private set

    fun markRequestStarted() {
        requestStarted = true
    }
}

private object WriteDispatchEventListener : EventListener() {
    override fun requestHeadersStart(call: Call) {
        call.request().tag(WriteDispatchTracker::class.java)?.markRequestStarted()
    }
}

object TiebaRedirectPolicy {
    fun allows(method: String, destination: HttpUrl): Boolean {
        if (method != "GET" && method != "HEAD") return false
        if (!destination.isHttps || destination.port != 443) return false
        if (destination.username.isNotEmpty() || destination.password.isNotEmpty()) return false
        val host = destination.host.trimEnd('.').lowercase()
        return host == "baidu.com" || host.endsWith(".baidu.com")
    }
}

class StrictRedirectInterceptor(
    private val maximumRedirects: Int = 5,
) : Interceptor {
    init {
        require(maximumRedirects >= 0)
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        var request = chain.request()
        var redirectCount = 0
        while (true) {
            val response = chain.proceed(request)
            if (response.code !in REDIRECT_CODES) return response
            val location = response.header("Location") ?: return response
            val destination = request.url.resolve(location)
            if (destination == null || !TiebaRedirectPolicy.allows(request.method, destination)) {
                response.close()
                throw TiebaNetworkException.UnsafeRedirect(destination?.toString())
            }
            if (redirectCount >= maximumRedirects) {
                response.close()
                throw TiebaNetworkException.TooManyRedirects(maximumRedirects)
            }

            val next = request.newBuilder().url(destination)
            if (destination.host != request.url.host || destination.port != request.url.port) {
                next.removeHeader("Authorization")
                next.removeHeader("Cookie")
            }
            response.close()
            request = next.build()
            redirectCount += 1
        }
    }

    private companion object {
        val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
    }
}

class ResponseSizeLimitInterceptor(
    private val maximumBytes: Long,
) : Interceptor {
    init {
        require(maximumBytes > 0)
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val response = chain.proceed(chain.request())
        val body = response.body
        if (body.contentLength() > maximumBytes) {
            response.close()
            throw TiebaNetworkException.ResponseTooLarge(maximumBytes)
        }
        return response.newBuilder()
            .body(LimitedResponseBody(body, maximumBytes))
            .build()
    }
}

internal class LimitedResponseBody(
    private val delegate: ResponseBody,
    private val maximumBytes: Long,
) : ResponseBody() {
    private val limitedSource: BufferedSource by lazy {
        object : ForwardingSource(delegate.source()) {
            private var consumed = 0L

            override fun read(sink: Buffer, byteCount: Long): Long {
                val remainingWithProbe = maximumBytes - consumed + 1
                if (remainingWithProbe <= 0) throw TiebaNetworkException.ResponseTooLarge(maximumBytes)
                val read = super.read(sink, minOf(byteCount, remainingWithProbe))
                if (read > 0) consumed += read
                if (consumed > maximumBytes) throw TiebaNetworkException.ResponseTooLarge(maximumBytes)
                return read
            }
        }.buffer()
    }

    override fun contentType() = delegate.contentType()
    override fun contentLength() = delegate.contentLength()
    override fun source(): BufferedSource = limitedSource
}

fun Response.requireSuccess(maximumErrorPreviewBytes: Long = 4_096): Response {
    if (isSuccessful) return this
    val preview = try {
        body.source().peek().readUtf8(maximumErrorPreviewBytes)
    } catch (_: IOException) {
        ""
    }
    close()
    throw TiebaNetworkException.HttpStatus(code, preview)
}
