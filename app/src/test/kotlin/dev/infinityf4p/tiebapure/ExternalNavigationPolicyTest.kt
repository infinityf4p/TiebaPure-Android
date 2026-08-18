package dev.infinityf4p.tiebapure

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ExternalNavigationPolicyTest {
    @Test
    fun acceptsCanonicalHttpsThreadLinks() {
        assertEquals(
            ExternalNavigationDestination.Thread(42),
            view("https://tieba.baidu.com/p/42?share=1#post-content"),
        )
        assertEquals(
            ExternalNavigationDestination.Thread(42),
            view("HTTPS://TIEBA.BAIDU.COM:443/p/42/"),
        )
    }

    @Test
    fun rejectsUnsafeOrNonCanonicalWebLinks() {
        listOf(
            "http://tieba.baidu.com/p/42",
            "https://user@tieba.baidu.com/p/42",
            "https://tieba.baidu.com.evil.example/p/42",
            "https://www.tieba.baidu.com/p/42",
            "https://tieba.baidu.com:444/p/42",
            "https://tieba.baidu.com/p/0",
            "https://tieba.baidu.com/p/-1",
            "https://tieba.baidu.com/p/not-a-number",
            "https://tieba.baidu.com/p/9223372036854775808",
            "https://tieba.baidu.com/p/%34%32",
            "https://tieba.baidu.com/p/42/extra",
        ).forEach { value -> assertNull(value, view(value)) }
    }

    @Test
    fun parsesReadOnlyCustomSchemeDestinations() {
        assertEquals(
            ExternalNavigationDestination.Thread(9001),
            view("tiebapure://thread/9001"),
        )
        assertEquals(
            ExternalNavigationDestination.Forum("摄影"),
            view("tiebapure://forum/%E6%91%84%E5%BD%B1%E5%90%A7"),
        )
        assertEquals(
            ExternalNavigationDestination.Search("相机 guide"),
            view("tiebapure://search?query=%E7%9B%B8%E6%9C%BA+guide"),
        )
        assertEquals(
            ExternalNavigationDestination.Search("Compose+Android"),
            view("tiebapure://search/Compose+Android"),
        )
        assertEquals(ExternalNavigationDestination.Search(null), view("tiebapure://search"))
    }

    @Test
    fun rejectsMalformedOrPrivilegedCustomDestinations() {
        listOf(
            "tiebapure://user/thread/1",
            "tiebapure://compose/thread/1",
            "tiebapure://user@thread/1",
            "tiebapure://thread/0",
            "tiebapure://thread/9223372036854775808",
            "tiebapure://thread/1?next=2",
            "tiebapure://forum/",
            "tiebapure://forum/%2Fhidden",
            "tiebapure://forum/%00hidden",
            "tiebapure://search?unknown=value",
            "tiebapure://search?q=one&query=two",
            "tiebapure://search?q=value#fragment",
        ).forEach { value -> assertNull(value, view(value)) }
    }

    @Test
    fun extractsOnlyValidatedTiebaLinksFromPlainTextShares() {
        val input = ExternalNavigationInput(
            action = ExternalNavigationAction.SendText,
            mimeType = "text/plain",
            sharedText = "看看这个：https://tieba.baidu.com/p/314159。",
        )
        assertEquals(ExternalNavigationDestination.Thread(314159), parseExternalNavigation(input))

        assertNull(parseExternalNavigation(input.copy(mimeType = "text/html")))
        assertNull(parseExternalNavigation(input.copy(sharedText = "http://tieba.baidu.com/p/314159")))
        assertNull(
            parseExternalNavigation(
                input.copy(sharedText = "https://evil.example/?next=https://tieba.baidu.com/p/314159"),
            ),
        )
    }

    @Test
    fun destinationsMapOnlyToExistingReadRoutes() {
        val encoder: (String) -> String = { value -> "encoded($value)" }

        assertEquals(
            "thread/7?postId=0&initialDestination=",
            buildExternalNavigationRoute(ExternalNavigationDestination.Thread(7), encoder),
        )
        assertEquals(
            "forum/encoded(摄影)",
            buildExternalNavigationRoute(ExternalNavigationDestination.Forum("摄影"), encoder),
        )
        assertEquals(
            "search?query=encoded(相机)",
            buildExternalNavigationRoute(ExternalNavigationDestination.Search("相机"), encoder),
        )
        assertEquals(
            "search",
            buildExternalNavigationRoute(ExternalNavigationDestination.Search(null), encoder),
        )
    }

    private fun view(value: String): ExternalNavigationDestination? = parseExternalNavigation(
        ExternalNavigationInput(ExternalNavigationAction.View, data = value),
    )
}
