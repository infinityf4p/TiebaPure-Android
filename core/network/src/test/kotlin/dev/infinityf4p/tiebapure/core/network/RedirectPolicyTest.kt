package dev.infinityf4p.tiebapure.core.network

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import okhttp3.HttpUrl.Companion.toHttpUrl

class RedirectPolicyTest {
    @Test
    fun allowsReadRedirectToBaiduHttps() {
        assertTrue(TiebaRedirectPolicy.allows("GET", "https://c.tieba.baidu.com/path".toHttpUrl()))
        assertTrue(TiebaRedirectPolicy.allows("HEAD", "https://baidu.com/path".toHttpUrl()))
    }

    @Test
    fun refusesWriteRedirectAndNonHttpsDestination() {
        assertFalse(TiebaRedirectPolicy.allows("POST", "https://tieba.baidu.com/path".toHttpUrl()))
        assertFalse(TiebaRedirectPolicy.allows("GET", "http://tieba.baidu.com/path".toHttpUrl()))
    }

    @Test
    fun refusesLookalikeAndCredentialedHosts() {
        assertFalse(TiebaRedirectPolicy.allows("GET", "https://tieba.baidu.com.evil.test/path".toHttpUrl()))
        assertFalse(TiebaRedirectPolicy.allows("GET", "https://user@tieba.baidu.com/path".toHttpUrl()))
        assertFalse(TiebaRedirectPolicy.allows("GET", "https://tieba.baidu.com:8443/path".toHttpUrl()))
    }
}
