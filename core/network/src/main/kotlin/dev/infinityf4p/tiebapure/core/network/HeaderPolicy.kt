package dev.infinityf4p.tiebapure.core.network

import dev.infinityf4p.tiebapure.core.model.Account

object TiebaHeaderPolicy {
    fun requireSafeHeaderValue(name: String, value: String): String {
        if (value.any { it.code < 0x20 || it.code == 0x7f }) {
            throw TiebaNetworkException.InvalidRequest("$name contains a forbidden header character")
        }
        return value
    }

    fun requireSafeCookieValue(name: String, value: String): String {
        requireSafeHeaderValue(name, value)
        if (';' in value) {
            throw TiebaNetworkException.InvalidRequest("$name contains a cookie delimiter")
        }
        return value
    }

    fun minimalCookieHeader(account: Account, includeBaiduId: Boolean = true): String = buildList {
        add("BDUSS=${requireSafeCookieValue("BDUSS", account.bduss)}")
        add("STOKEN=${requireSafeCookieValue("STOKEN", account.stoken)}")
        account.baiduId?.takeIf { includeBaiduId && it.isNotBlank() }?.let {
            add("BAIDUID=${requireSafeCookieValue("BAIDUID", it)}")
        }
    }.joinToString("; ")

    fun validate(headers: Map<String, String>): Map<String, String> = headers.mapValues { (name, value) ->
        requireSafeHeaderValue(name, value)
    }
}
