package dev.infinityf4p.tiebapure

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SavedThreadMediaPolicyTest {
    @Test
    fun failedReplyRequestBecomesMissingContent() = runTest {
        assertNull(attemptSavedThreadRequest<String> { throw IOException("offline") })
    }

    @Test
    fun successfulReplyRequestIsRetained() = runTest {
        assertEquals("reply", attemptSavedThreadRequest { "reply" })
    }

    @Test
    fun individualMediaFailureIsSkipped() = runTest {
        assertFalse(attemptSavedThreadMedia { throw IOException("offline") })
    }

    @Test
    fun successfulMediaAttemptIsRetained() = runTest {
        assertTrue(attemptSavedThreadMedia {})
    }

    @Test
    fun cancellationStillStopsSaving() {
        assertThrows(CancellationException::class.java) {
            runTest {
                attemptSavedThreadMedia { throw CancellationException("cancelled") }
            }
        }
        assertThrows(CancellationException::class.java) {
            runTest {
                attemptSavedThreadRequest<Unit> { throw CancellationException("cancelled") }
            }
        }
    }
}
