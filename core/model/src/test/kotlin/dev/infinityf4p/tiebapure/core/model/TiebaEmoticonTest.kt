package dev.infinityf4p.tiebapure.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TiebaEmoticonTest {
    @Test
    fun catalogMatchesIosBuiltInSetAndCanonicalTokens() {
        assertEquals(59, TiebaEmoticon.catalog.size)
        assertEquals("呵呵", TiebaEmoticon.catalog.first().name)
        assertEquals("噗", TiebaEmoticon.catalog.last().name)
        assertEquals("#(滑稽)", TiebaEmoticon.canonicalToken("image_emoticon25"))
    }

    @Test
    fun namesWrappersAndAliasesResolveToCanonicalArtwork() {
        assertEquals("image_emoticon2", TiebaEmoticon.imageNameFor("#(哈哈)"))
        assertEquals("image_emoticon2", TiebaEmoticon.imageNameFor("(#大笑)"))
        assertEquals("image_emoticon7", TiebaEmoticon.imageNameFor("[黑头开心]"))
        assertEquals("image_emoticon25", TiebaEmoticon.imageNameFor("小滑稽"))
    }

    @Test
    fun numericImageNamesAreStrictlyBounded() {
        assertTrue(TiebaEmoticon.isValidImageName("image_emoticon1"))
        assertTrue(TiebaEmoticon.isValidImageName("image_emoticon999"))
        assertFalse(TiebaEmoticon.isValidImageName("image_emoticon0"))
        assertFalse(TiebaEmoticon.isValidImageName("image_emoticon010"))
        assertFalse(TiebaEmoticon.isValidImageName("image_emoticon1000"))
        assertFalse(TiebaEmoticon.isValidImageName("image_emoticon1.png"))
        assertFalse(TiebaEmoticon.isValidImageName("image_emoticon١"))
    }

    @Test
    fun imageUrlsAreConstructedInsideTheFixedCdnBoundary() {
        assertEquals(
            "https://tb2.bdstatic.com/tb/editor/images/client/image_emoticon50.png",
            TiebaEmoticon.imageUrlFor("#(OK)"),
        )
        assertNull(TiebaEmoticon.imageUrlFor("https://attacker.invalid/a.png"))
    }

    @Test
    fun unknownCodesHaveReadableFallbackWithoutNetworkUrl() {
        assertEquals("[不存在的表情]", TiebaEmoticon.displayText("#(不存在的表情)"))
        assertNull(TiebaEmoticon.imageNameFor("#(不存在的表情)"))
    }
}
