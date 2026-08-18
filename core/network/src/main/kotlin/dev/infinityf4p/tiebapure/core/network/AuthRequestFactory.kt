package dev.infinityf4p.tiebapure.core.network

import dev.infinityf4p.tiebapure.core.model.Account
import okhttp3.Request

internal const val TIEBA_WEB_POSTING_USER_AGENT =
    "Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X) " +
        "AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.0 Mobile/15E148 Safari/604.1"

object TiebaAuthRequestFactory {
    private const val AUTH_CLIENT_VERSION = "11.10.8.6"
    private const val WEB_USER_AGENT = "Mozilla/5.0 (Linux; Android 17) " +
        "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Mobile Safari/537.36"

    fun login(
        bduss: String,
        stoken: String,
        baiduId: String?,
        builder: TiebaRequestBuilder,
        timestamp: Long,
    ): Request {
        TiebaHeaderPolicy.requireSafeCookieValue("BDUSS", bduss)
        TiebaHeaderPolicy.requireSafeCookieValue("STOKEN", stoken)
        val account = Account("", "", "", "", "", "", baiduId, "")
        val fields = builder.officialCommonFields(account, AUTH_CLIENT_VERSION, timestamp).toMutableMap().apply {
            put("bdusstoken", "$bduss|")
            put("stoken", stoken)
            put("channel_id", "")
            put("channel_uid", "")
            put("authsid", "null")
        }
        val headers = builder.officialHeaders(baiduId, AUTH_CLIENT_VERSION, timestamp).toMutableMap().apply {
            remove("Charset")
            remove("client_type")
        }
        return builder.formRequest(
            TiebaEndpoint.Login,
            fields,
            headers,
            TiebaFormSigner.DEFAULT_SECRET,
        )
    }

    fun initNickname(
        bduss: String,
        stoken: String,
        baiduId: String?,
        builder: TiebaRequestBuilder,
        timestamp: Long,
    ): Request {
        TiebaHeaderPolicy.requireSafeCookieValue("BDUSS", bduss)
        TiebaHeaderPolicy.requireSafeCookieValue("STOKEN", stoken)
        val account = Account("", "", "", "", "", "", baiduId, "")
        val fields = builder.officialCommonFields(account, AUTH_CLIENT_VERSION, timestamp).toMutableMap().apply {
            put("BDUSS", bduss)
            put("stoken", stoken)
        }
        val headers = builder.officialHeaders(baiduId, AUTH_CLIENT_VERSION, timestamp).toMutableMap().apply {
            remove("Charset")
            remove("client_type")
        }
        return builder.formRequest(
            TiebaEndpoint.InitNickname,
            fields,
            headers,
            TiebaFormSigner.DEFAULT_SECRET,
        )
    }

    fun webMyInfo(account: Account, builder: TiebaRequestBuilder): Request = builder.getRequest(
        TiebaEndpoint.WebMyInfo,
        query = mapOf("need_user" to "1"),
        headers = mapOf("Cookie" to TiebaHeaderPolicy.minimalCookieHeader(account), "User-Agent" to WEB_USER_AGENT),
    )

    fun webTbs(account: Account, builder: TiebaRequestBuilder, timestamp: Long): Request = builder.getRequest(
        TiebaEndpoint.WebTbs,
        query = mapOf("t" to timestamp.toString()),
        headers = mapOf(
            "Accept" to "application/json, text/plain, */*",
            "Accept-Language" to "zh-CN,zh;q=0.9",
            "Cache-Control" to "no-cache",
            "Cookie" to TiebaHeaderPolicy.minimalCookieHeader(account, includeBaiduId = false),
            "Pragma" to "no-cache",
            "User-Agent" to TIEBA_WEB_POSTING_USER_AGENT,
        ),
    )
}
