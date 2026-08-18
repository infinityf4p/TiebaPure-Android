package dev.infinityf4p.tiebapure.core.network

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EndpointTest {
    @Test
    fun everyStaticEndpointUsesHttpsAndBaiduDomain() {
        assertTrue(TiebaEndpoint.All.isNotEmpty())
        TiebaEndpoint.All.forEach { endpoint ->
            assertTrue(endpoint.url.isHttps, endpoint.url.toString())
            assertTrue(
                endpoint.url.host == "baidu.com" || endpoint.url.host.endsWith(".baidu.com"),
                endpoint.url.toString(),
            )
        }
    }

    @Test
    fun protobufEndpointsCarryExpectedCommands() {
        assertEquals("302001", TiebaEndpoint.ThreadPage.url.queryParameter("cmd"))
        assertEquals("302002", TiebaEndpoint.Subposts.url.queryParameter("cmd"))
        assertEquals("protobuf", TiebaEndpoint.UserProfile.url.queryParameter("format"))
    }
}
