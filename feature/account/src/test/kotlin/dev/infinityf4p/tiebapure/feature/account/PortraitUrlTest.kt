package dev.infinityf4p.tiebapure.feature.account

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PortraitUrlTest {
    @Test fun buildsTrustedPortraitUrl() {
        assertEquals("https://himg.bdimg.com/sys/portrait/item/abc", portraitUrl("abc"))
        assertEquals("https://himg.bdimg.com/sys/portrait/item/abc", portraitUrl("abc?timestamp=1"))
        assertEquals(
            "https://himg.bdimg.com/sys/portrait/item/legacy-token",
            portraitUrl("http://tb.himg.baidu.com/sys/portrait/item/legacy-token"),
        )
        assertEquals(
            "https://himg.bdimg.com/sys/portrait/item/current-token",
            portraitUrl("https://himg.bdimg.com/sys/portrait/item/current-token"),
        )
    }

    @Test fun rejectsEmptyAndHeaderInjection() {
        assertNull(portraitUrl(""))
        assertNull(portraitUrl("abc\r\nX: y"))
        assertNull(portraitUrl("https://evil.example/sys/portrait/item/abc"))
        assertNull(portraitUrl("https://user@himg.bdimg.com/sys/portrait/item/abc"))
        assertNull(portraitUrl("https://himg.bdimg.com/sys/portrait/item/abc?x=1"))
        assertNull(portraitUrl("https://tb.himg.baidu.com/a"))
        assertNull(portraitUrl("../abc"))
    }

    @Test fun loginBoundaryAllowsOnlyBaiduHttps() {
        assertEquals(true, LoginBoundary.isAllowedUrl("https://wappass.baidu.com/passport"))
        assertEquals(false, LoginBoundary.isAllowedUrl("http://tieba.baidu.com/"))
        assertEquals(false, LoginBoundary.isAllowedUrl("https://baidu.com.evil.invalid/"))
        assertEquals(false, LoginBoundary.isAllowedUrl("https://user@tieba.baidu.com/"))
        assertEquals(false, LoginBoundary.isAllowedUrl("https://tieba.baidu.com:444/"))
        assertEquals(false, LoginBoundary.isAllowedUrl("tbclient://open"))
        assertEquals(true, LoginBoundary.isExternalAppRedirect("tbclient://open"))
        assertEquals(false, LoginBoundary.isExternalAppRedirect("https://tieba.baidu.com/"))
        assertEquals(true, LoginBoundary.isCompletionUrl(LoginBoundary.completionUrl))
        assertEquals(false, LoginBoundary.isCompletionUrl("https://user@tieba.baidu.com/index/tbwise/mine"))
    }

    @Test fun loginCookieExtractionRequiresBothCredentials() {
        val result = LoginBoundary.extractCookies(listOf("BAIDUID=id; BDUSS=bd", "STOKEN=st"))
        assertEquals(BaiduLoginCookies("bd", "st", "id"), result)
        assertNull(LoginBoundary.extractCookies(listOf("BDUSS=bd")))
        assertNull(LoginBoundary.extractCookies(listOf("BDUSS=bad\r\nX=x; STOKEN=st")))
        assertEquals(true, LoginBoundary.hasPrimaryLoginCookie(listOf("BDUSS=bd")))
        assertEquals(true, LoginBoundary.hasPrimaryLoginCookie(listOf("BDUSS_BFESS=bd")))
        assertEquals(false, LoginBoundary.hasPrimaryLoginCookie(listOf("STOKEN=st")))
        assertEquals(false, LoginBoundary.hasPrimaryLoginCookie(listOf("BDUSS=bad\r\nX=x")))
    }
}
