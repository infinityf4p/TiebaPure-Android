package dev.infinityf4p.tiebapure.core.network

import com.google.protobuf.MessageLite
import dev.infinityf4p.tiebapure.core.model.Account
import java.security.MessageDigest
import java.util.Locale
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

enum class TiebaClientVersion(val value: String) {
    V12("12.52.1.0"),
    V22("22.5.1.0"),
    Mini("7.2.0.0"),
}

data class TiebaDeviceProfile(
    val clientId: String,
    val osVersion: String,
    val model: String,
    val brand: String,
    val screenWidthPixels: Int,
    val screenHeightPixels: Int,
    val screenDensity: Double,
    val androidId: String = "",
) {
    init {
        require(clientId.isNotBlank())
        require(screenWidthPixels > 0 && screenHeightPixels > 0 && screenDensity > 0)
    }

    val miniCuid: String
        get() = "${clientId.uppercase(Locale.ROOT)}|000000000000000"
}

fun interface EpochMillisecondsClock {
    fun now(): Long

    companion object {
        val System = EpochMillisecondsClock(java.lang.System::currentTimeMillis)
    }
}

class TiebaRequestBuilder(
    val device: TiebaDeviceProfile,
    private val clock: EpochMillisecondsClock = EpochMillisecondsClock.System,
) {
    fun miniCommonFields(timestamp: Long = clock.now()): Map<String, String> {
        val cuid = device.miniCuid
        return mapOf(
            "_client_id" to device.clientId,
            "_client_type" to "2",
            "_client_version" to TiebaClientVersion.Mini.value,
            "_os_version" to device.osVersion,
            "_phone_imei" to "000000000000000",
            "cuid" to cuid,
            "cuid_galaxy2" to cuid,
            "from" to "1021636m",
            "model" to device.model,
            "net_type" to "1",
            "subapp_type" to "mini",
            "timestamp" to timestamp.toString(),
        )
    }

    fun officialCommonFields(
        account: Account? = null,
        clientVersion: String = "11.10.8.6",
        timestamp: Long = clock.now(),
    ): Map<String, String> = buildMap {
        val cuid = device.miniCuid
        put("_client_id", device.clientId)
        put("_client_type", "2")
        put("_client_version", clientVersion)
        put("_os_version", device.osVersion)
        put("_phone_imei", "000000000000000")
        put("active_timestamp", timestamp.toString())
        put("brand", device.brand)
        put("cmode", "1")
        put("cuid", cuid)
        put("cuid_galaxy2", cuid)
        put("cuid_gid", "")
        put("from", "tieba")
        put("is_teenager", "0")
        put("mac", "02:00:00:00:00:00")
        put("model", device.model)
        put("net_type", "1")
        put("start_scheme", "")
        put("start_type", "1")
        put("timestamp", timestamp.toString())
        account?.bduss?.takeIf(String::isNotBlank)?.let {
            put("BDUSS", TiebaHeaderPolicy.requireSafeCookieValue("BDUSS", it))
        }
        account?.baiduId?.takeIf(String::isNotBlank)?.let {
            put("baiduid", TiebaHeaderPolicy.requireSafeCookieValue("BAIDUID", it))
        }
    }

    fun officialHeaders(
        baiduId: String? = null,
        clientVersion: String = "11.10.8.6",
        timestamp: Long = clock.now(),
    ): Map<String, String> {
        val cuid = device.miniCuid
        val cookie = buildList {
            add("CUID=${TiebaHeaderPolicy.requireSafeCookieValue("CUID", cuid)}")
            add("ka=open")
            add("TBBRAND=${TiebaHeaderPolicy.requireSafeCookieValue("TBBRAND", device.brand)}")
            baiduId?.takeIf(String::isNotBlank)?.let {
                add("BAIDUID=${TiebaHeaderPolicy.requireSafeCookieValue("BAIDUID", it)}")
            }
        }.joinToString("; ")
        return mapOf(
            "Charset" to "UTF-8",
            "Cookie" to cookie,
            "Pragma" to "no-cache",
            "User-Agent" to "bdtb for Android $clientVersion",
            "client_logid" to timestamp.toString(),
            "client_type" to "2",
            "cuid" to cuid,
            "cuid_galaxy2" to cuid,
            "cuid_gid" to "",
        )
    }

    fun formRequest(
        endpoint: TiebaEndpoint,
        fields: Map<String, String>,
        headers: Map<String, String> = emptyMap(),
        signingSecret: String? = null,
    ): Request {
        val signedFields = if (signingSecret != null && "sign" !in fields) {
            fields + ("sign" to TiebaFormSigner.sign(fields, signingSecret))
        } else {
            fields
        }
        val body = TiebaFormCodec.encode(signedFields, sortedKeys = signingSecret != null)
            .toRequestBody(FORM_MEDIA_TYPE)
        return Request.Builder()
            .url(endpoint.url)
            .post(body)
            .headers(safeHeaders(headers, "bdtb for Android 12.0.8.0"))
            .build()
    }

    fun getRequest(
        endpoint: TiebaEndpoint,
        query: Map<String, String>,
        headers: Map<String, String> = emptyMap(),
    ): Request {
        val url = endpoint.url.newBuilder().apply {
            query.forEach { (name, value) -> addQueryParameter(name, value) }
        }.build()
        return Request.Builder()
            .url(url)
            .get()
            .headers(safeHeaders(headers, "tieba/${TiebaClientVersion.V12.value} skin/default"))
            .build()
    }

    fun protobufRequest(
        endpoint: TiebaEndpoint,
        message: MessageLite,
        account: Account?,
        includeStoken: Boolean,
        headers: Map<String, String> = emptyMap(),
        includePartContentType: Boolean = true,
    ): Request {
        val multipart = ProtoMultipartBody.create(
            message = message,
            stoken = account?.stoken?.takeIf { includeStoken },
            includePartContentType = includePartContentType,
        )
        return Request.Builder()
            .url(endpoint.url)
            .post(multipart)
            .headers(safeHeaders(headers, "tieba/${TiebaClientVersion.V12.value}"))
            .build()
    }

    private fun safeHeaders(headers: Map<String, String>, defaultUserAgent: String): Headers {
        val validated = TiebaHeaderPolicy.validate(headers)
        return Headers.Builder().apply {
            if (validated.keys.none { it.equals("User-Agent", ignoreCase = true) }) {
                add("User-Agent", defaultUserAgent)
            }
            validated.forEach { (name, value) -> add(name, value) }
        }.build()
    }

    private companion object {
        val FORM_MEDIA_TYPE = "application/x-www-form-urlencoded".toMediaType()
    }
}

object TiebaFormSigner {
    const val DEFAULT_SECRET = "tiebaclient!!!"

    fun sign(fields: Map<String, String>, secret: String = DEFAULT_SECRET): String {
        val raw = fields.entries
            .map { (key, value) -> "$key=$value" }
            .sorted()
            .joinToString(separator = "") + secret
        return MessageDigest.getInstance("MD5")
            .digest(raw.toByteArray(Charsets.UTF_8))
            .joinToString(separator = "") {
                java.lang.String.format(Locale.ROOT, "%02X", it.toInt() and 0xff)
            }
    }
}

object TiebaFormCodec {
    private val hex = "0123456789ABCDEF".toCharArray()

    fun encode(fields: Map<String, String>, sortedKeys: Boolean = false): String {
        val entries = if (sortedKeys) fields.entries.sortedBy { it.key } else fields.entries
        return entries.joinToString("&") { (key, value) -> "${escape(key)}=${escape(value)}" }
    }

    fun escape(value: String): String = buildString {
        value.toByteArray(Charsets.UTF_8).forEach { signedByte ->
            val byte = signedByte.toInt() and 0xff
            when {
                byte in 'a'.code..'z'.code || byte in 'A'.code..'Z'.code ||
                    byte in '0'.code..'9'.code || byte == '-'.code || byte == '.'.code ||
                    byte == '_'.code || byte == '~'.code -> append(byte.toChar())
                byte == ' '.code -> append('+')
                else -> {
                    append('%')
                    append(hex[byte ushr 4])
                    append(hex[byte and 0x0f])
                }
            }
        }
    }
}

object ProtoMultipartBody {
    const val BOUNDARY = "--------7da3d81520810*"

    fun create(
        message: MessageLite,
        stoken: String? = null,
        includePartContentType: Boolean = true,
    ): MultipartBody = MultipartBody.Builder(BOUNDARY)
        .setType(MultipartBody.FORM)
        .apply {
            stoken?.let {
                addFormDataPart("stoken", TiebaHeaderPolicy.requireSafeCookieValue("STOKEN", it))
            }
            val mediaType = if (includePartContentType) OCTET_STREAM else null
            addFormDataPart("data", "file", message.toByteArray().toRequestBody(mediaType))
        }
        .build()

    private val OCTET_STREAM = "application/octet-stream".toMediaType()
}
