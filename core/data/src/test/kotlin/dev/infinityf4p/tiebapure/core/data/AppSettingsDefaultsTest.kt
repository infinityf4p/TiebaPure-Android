package dev.infinityf4p.tiebapure.core.data

import org.junit.Assert.assertFalse
import org.junit.Test

class AppSettingsDefaultsTest {
    @Test
    fun writeActionsRequireExplicitOptIn() {
        val settings = AppSettings()

        assertFalse(settings.postingEnabled)
        assertFalse(settings.replyingEnabled)
        assertFalse(settings.likingEnabled)
    }
}
