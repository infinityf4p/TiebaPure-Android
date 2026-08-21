package dev.infinityf4p.tiebapure.feature.thread

import dev.infinityf4p.tiebapure.core.model.Forum
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import java.util.Locale
import java.util.TimeZone
import java.text.SimpleDateFormat

class ThreadFormattingTest {
    @Test
    fun shareUrlUsesOfficialTiebaThreadAddress() {
        assertEquals("https://tieba.baidu.com/p/7", buildThreadShareUrl(7))
        assertFailsWith<IllegalArgumentException> { buildThreadShareUrl(0) }
    }

    @Test
    fun countsStayOnOneLineWithCompactUnits() {
        assertEquals("999", formatCount(999))
        assertEquals("1.0k", formatCount(1_000))
        assertEquals("9.9k", formatCount(9_900))
        assertEquals("1.0w", formatCount(10_000))
        assertEquals("12.4w", formatCount(123_999))
    }

    @Test
    fun metadataPlacementMatchesIosVerticalRhythm() {
        assertEquals(
            ThreadPostMetadataPlacement.StandaloneReply,
            threadPostMetadataPlacement(isMainPost = false, hasPreviewSubposts = false),
        )
        assertEquals(
            ThreadPostMetadataPlacement.BeforeSubpostPreview,
            threadPostMetadataPlacement(isMainPost = false, hasPreviewSubposts = true),
        )
        assertEquals(6, ThreadPostMetadataPlacement.StandaloneReply.topSpacing)
        assertEquals(6, ThreadPostMetadataPlacement.BeforeSubpostPreview.topSpacing)
        assertEquals(6, ThreadPostMetadataPlacement.StandaloneReply.cardBottomPadding)
        assertEquals(6, ThreadPostMetadataPlacement.BeforeSubpostPreview.bottomSpacing)
    }

    @Test
    fun readerPreferencesScaleFilterAndMetadataMetricsWithoutClipping() {
        val normal = readerScaledTextMetrics(14f, 18f, 1f, 1f)
        val enlarged = readerScaledTextMetrics(14f, 18f, 1.25f, 1.2f)

        assertEquals(ReaderScaledTextMetrics(14f, 18f), normal)
        assertEquals(17.5f, enlarged.fontSize)
        assertEquals(27f, enlarged.lineHeight, 0.001f)
        assertEquals(48f, replyFilterControlHeight(normal))
        assertEquals(48f, replyFilterControlHeight(enlarged))
        assertEquals(48f, replyFilterControlHeight(ReaderScaledTextMetrics(28f, 36f)))
        assertEquals(false, replyFiltersUseStackedLayout(360f, 1f))
        assertEquals(true, replyFiltersUseStackedLayout(320f, 1.2f))
    }

    @Test
    fun metadataNormalizesLocationAndUsesRelativeDateText() {
        assertEquals("北京", normalizeThreadLocation(" IP属地：北京 "))
        assertEquals("上海", normalizeThreadLocation("来自 上海"))
        assertEquals(null, normalizeThreadLocation("IP属地：  "))

        val utc = TimeZone.getTimeZone("UTC")
        val now = checkNotNull(
            SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).apply { timeZone = utc }
                .parse("2025-08-09 00:00"),
        ).time
        assertEquals("刚刚", formatThreadTimestamp(now / 1_000L - 20, now, Locale.US, utc))
        assertEquals("2分钟前", formatThreadTimestamp(now / 1_000L - 120, now, Locale.US, utc))
        assertEquals("昨天 23:00", formatThreadTimestamp(now / 1_000L - 3_600, now, Locale.US, utc))
    }

    @Test
    fun levelBadgeRemovesLineBreaks() {
        assertEquals("Lv.4", userLevelBadgeText(4, null))
        assertEquals("4 F4", userLevelBadgeText(4, " F\n4 "))
    }

    @Test
    fun capabilitiesFailClosedWithoutRemovingReadOnlyContent() {
        assertEquals(
            ThreadActionVisibility(
                showReplyActions = false,
                showLikeActions = false,
                showCollectAction = false,
            ),
            ThreadCapabilities(
                canReply = false,
                canLike = false,
                canCollect = false,
            ).actionVisibility(hasPage = true),
        )
        assertEquals(
            ThreadActionVisibility(
                showReplyActions = false,
                showLikeActions = false,
                showCollectAction = true,
            ),
            ThreadCapabilities().actionVisibility(hasPage = false),
        )
    }

    @Test
    fun footerOnlyDeclaresEndAfterAResolvedPage() {
        assertNull(threadFooterContent(hasPage = false, isLoadingMore = false, hasMore = false))
        assertEquals(
            ThreadFooterContent.Loading,
            threadFooterContent(hasPage = true, isLoadingMore = true, hasMore = true),
        )
        assertEquals(
            ThreadFooterContent.Error,
            threadFooterContent(hasPage = true, isLoadingMore = false, hasMore = false, hasError = true),
        )
        assertEquals(
            ThreadFooterContent.LoadMore,
            threadFooterContent(hasPage = true, isLoadingMore = false, hasMore = true),
        )
        assertEquals(
            ThreadFooterContent.End,
            threadFooterContent(hasPage = true, isLoadingMore = false, hasMore = false),
        )
    }

    @Test
    fun openAllKeepsCompactVisualInsideMinimumTouchTarget() {
        assertEquals(48, SUBPOST_OPEN_ALL_TOUCH_HEIGHT_DP)
        assertEquals(30, SUBPOST_OPEN_ALL_VISUAL_HEIGHT_DP)
    }

    @Test
    fun forumNavigationRequiresAndNormalizesARealRouteName() {
        assertEquals(
            "测试",
            normalizedThreadForumRoute(Forum(1, "", "测试吧"))?.name,
        )
        assertEquals(
            "原始名",
            normalizedThreadForumRoute(Forum(1, " 原始名 ", "展示名"))?.name,
        )
        assertNull(normalizedThreadForumRoute(Forum(1, " ", "吧")))
    }
}
