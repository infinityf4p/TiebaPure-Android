package dev.infinityf4p.tiebapure

import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Test

class AutomaticSignPolicyTest {
    @Test
    fun dayStampUsesTheUsersLocalCalendarDay() {
        val previous = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"))
            assertEquals("2026-08-13", automaticSignDayStamp(1_786_550_400_000L))
            assertEquals("v2:2026-08-13", automaticSignCompletionMarker(1_786_550_400_000L))
        } finally {
            TimeZone.setDefault(previous)
        }
    }

    @Test
    fun signRunMessageListsFailedAndUnknownForums() {
        assertEquals(
            """
                签到完成：成功 73 个，已签到 4 个，失败 2 个，待确认 1 个，请先刷新后再决定是否重试。
                失败贴吧：同济大学吧、f1吧。
                待确认贴吧：Android吧。
            """.trimIndent(),
            forumSignRunMessage(
                succeeded = 73,
                alreadySigned = 4,
                failedForumNames = listOf("同济大学吧", "f1吧"),
                outcomeUnknownForumNames = listOf("Android吧"),
            ),
        )
    }
}
