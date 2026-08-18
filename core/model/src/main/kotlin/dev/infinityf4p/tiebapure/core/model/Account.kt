package dev.infinityf4p.tiebapure.core.model

/** Credentials collected by the isolated Baidu login WebView. */
data class BaiduWebCredentials(
    val bduss: String,
    val stoken: String,
    val baiduId: String? = null,
)

data class Account(
    val uid: String,
    val name: String,
    val displayName: String,
    val portrait: String,
    val bduss: String,
    val stoken: String,
    val baiduId: String? = null,
    val tbs: String,
) {
    val id: String
        get() = uid

    val resolvedDisplayName: String
        get() = displayName.ifBlank { name }

    /** Only cookies required by the read-only web API. */
    fun minimalCookieHeader(): String = buildList {
        add("BDUSS=$bduss")
        add("STOKEN=$stoken")
        baiduId?.takeIf(String::isNotBlank)?.let { add("BAIDUID=$it") }
    }.joinToString("; ")

    fun sessionIdentity(): AccountSessionIdentity = AccountSessionIdentity(
        accountId = uid,
        bduss = bduss,
        stoken = stoken,
        baiduId = baiduId,
    )
}

/** Display metadata and a refreshed TBS do not define a new login session. */
data class AccountSessionIdentity internal constructor(
    val accountId: String,
    internal val bduss: String,
    internal val stoken: String,
    internal val baiduId: String?,
)
