package dev.infinityf4p.tiebapure.feature.account

import java.net.URI

data class BaiduLoginCookies(
    val bduss: String,
    val stoken: String,
    val baiduId: String?,
)

object LoginBoundary {
    const val completionUrl = "https://tieba.baidu.com/index/tbwise/mine"
    const val loginUrl = "https://wappass.baidu.com/passport?login&u=https://tieba.baidu.com/index/tbwise/mine"

    private val blockedSchemes = setOf(
        "tbclient", "bdtb", "baidutieba", "tieba", "baiduboxapp", "market", "intent",
    )

    fun isAllowedUrl(rawUrl: String?): Boolean {
        val uri = rawUrl?.let(::parseUri) ?: return false
        val scheme = uri.scheme?.lowercase() ?: return false
        if (scheme in blockedSchemes) return false
        if (scheme == "about") return uri.toString() == "about:blank"
        if (scheme != "https") return false
        if (uri.rawUserInfo != null || uri.port !in setOf(-1, 443)) return false
        val host = uri.host?.lowercase() ?: return false
        return host == "baidu.com" || host.endsWith(".baidu.com")
    }

    fun isExternalAppRedirect(rawUrl: String?): Boolean {
        val scheme = rawUrl?.let(::parseUri)?.scheme?.lowercase() ?: return false
        return scheme in blockedSchemes
    }

    fun isCompletionUrl(rawUrl: String?): Boolean {
        val uri = rawUrl?.let(::parseUri) ?: return false
        if (uri.scheme?.lowercase() != "https") return false
        if (uri.rawUserInfo != null || uri.port !in setOf(-1, 443)) return false
        val host = uri.host?.lowercase() ?: return false
        if (host != "tieba.baidu.com" && host != "tiebac.baidu.com") return false
        return uri.path?.trimEnd('/') == "/index/tbwise/mine"
    }

    fun extractCookies(cookieHeaders: Iterable<String?>): BaiduLoginCookies? {
        val values = linkedMapOf<String, String>()
        cookieHeaders.filterNotNull().forEach { header ->
            header.split(';').forEach { token ->
                val index = token.indexOf('=')
                if (index <= 0) return@forEach
                val name = token.substring(0, index).trim().uppercase()
                val value = token.substring(index + 1).trim()
                if (name in setOf("BDUSS", "BDUSS_BFESS", "STOKEN", "BAIDUID") && isSafeCookie(value)) {
                    values[name] = value
                }
            }
        }
        val bduss = values["BDUSS"] ?: values["BDUSS_BFESS"] ?: return null
        val stoken = values["STOKEN"] ?: return null
        return BaiduLoginCookies(bduss = bduss, stoken = stoken, baiduId = values["BAIDUID"])
    }

    fun hasPrimaryLoginCookie(cookieHeaders: Iterable<String?>): Boolean = cookieHeaders
        .filterNotNull()
        .flatMap { it.split(';') }
        .any { token ->
            val index = token.indexOf('=')
            if (index <= 0) return@any false
            val name = token.substring(0, index).trim().uppercase()
            val value = token.substring(index + 1).trim()
            name in setOf("BDUSS", "BDUSS_BFESS") && isSafeCookie(value)
        }

    private fun isSafeCookie(value: String): Boolean = value.isNotEmpty() &&
        value.length <= 4_096 && value.none { it == ';' || it.code < 0x20 || it.code > 0x7e }

    private fun parseUri(value: String): URI? = runCatching { URI(value) }.getOrNull()
}
