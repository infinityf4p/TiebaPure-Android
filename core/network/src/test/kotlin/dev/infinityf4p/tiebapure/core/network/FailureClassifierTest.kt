package dev.infinityf4p.tiebapure.core.network

import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertSame

class FailureClassifierTest {
    @Test
    fun dispatchedWriteFailureBecomesUnknownOutcome() {
        val error = IOException("connection reset")

        assertIs<TiebaWriteException.OutcomeUnknown>(
            TiebaFailureClassifier.classify(error, TiebaOperation.Write, requestDispatched = true),
        )
    }

    @Test
    fun readFailureRemainsRetryableTransportFailure() {
        val error = IOException("connection reset")

        assertSame(error, TiebaFailureClassifier.classify(error, TiebaOperation.Read, requestDispatched = true))
    }
}
