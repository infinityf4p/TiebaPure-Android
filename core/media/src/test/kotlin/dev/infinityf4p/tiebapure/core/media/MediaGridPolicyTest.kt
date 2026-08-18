package dev.infinityf4p.tiebapure.core.media

import kotlin.test.Test
import kotlin.test.assertEquals

class MediaGridPolicyTest {
    @Test
    fun fourImagesUseBalancedTwoByTwoGrid() {
        assertEquals(2, mediaGridColumnCount(2))
        assertEquals(3, mediaGridColumnCount(3))
        assertEquals(2, mediaGridColumnCount(4))
        assertEquals(3, mediaGridColumnCount(5))
        assertEquals(3, mediaGridColumnCount(9))
    }
}
