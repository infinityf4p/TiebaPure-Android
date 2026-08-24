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
}
