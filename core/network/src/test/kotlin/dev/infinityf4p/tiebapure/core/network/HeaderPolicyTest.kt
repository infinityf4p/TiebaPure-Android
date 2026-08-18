package dev.infinityf4p.tiebapure.core.network

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class HeaderPolicyTest {
    @Test
    fun accountCookieContainsOnlyMinimalCookieNames() {
        assertEquals(
            "BDUSS=bduss; STOKEN=stoken; BAIDUID=baiduid",
            TiebaHeaderPolicy.minimalCookieHeader(testAccount()),
        )
    }

    @Test
    fun cookieConstructionRejectsDelimiterInjection() {
        val account = testAccount().copy(stoken = "valid; ADMIN=true")

        assertFailsWith<TiebaNetworkException.InvalidRequest> {
            TiebaHeaderPolicy.minimalCookieHeader(account)
        }
    }

    @Test
    fun headerConstructionRejectsCrLfInjection() {
        assertFailsWith<TiebaNetworkException.InvalidRequest> {
            TiebaHeaderPolicy.validate(mapOf("Referer" to "https://example.com\r\nX: injected"))
        }
    }
}
