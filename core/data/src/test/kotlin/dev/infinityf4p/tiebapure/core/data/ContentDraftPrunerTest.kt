package dev.infinityf4p.tiebapure.core.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContentDraftPrunerTest {
    @Test
    fun newestDraftsSurvivePerAccountCountLimit() {
        val candidates = (0..ContentDraftLimits.maximumDraftsPerAccount).map { index ->
            candidate("account", "target-$index", bytes = 8, updatedAt = index.toLong())
        }

        val retained = retainedContentDraftKeys(candidates)

        assertEquals(ContentDraftLimits.maximumDraftsPerAccount, retained.size)
        assertFalse("account" to "target-0" in retained)
        assertTrue("account" to "target-100" in retained)
    }

    @Test
    fun globalByteBudgetPrunesOldestAcrossAccounts() {
        val bytes = 90L * 1_024 * 1_024
        val candidates = (0..5).map { index ->
            candidate("account-${index % 3}", "target-$index", bytes, index.toLong())
        }

        val retained = retainedContentDraftKeys(candidates)

        assertEquals(5, retained.size)
        assertFalse("account-0" to "target-0" in retained)
    }

    @Test
    fun replacingDraftIsCountedOnlyOnceAndPreferred() {
        val preferred = candidate("account", "target", 96L * 1_024 * 1_024, 1)
        val candidates = listOf(
            candidate("account", "older-a", 90L * 1_024 * 1_024, 100),
            candidate("account", "older-b", 90L * 1_024 * 1_024, 99),
            preferred,
        )

        val retained = retainedContentDraftKeys(candidates, preferred.accountId to preferred.targetKey)

        assertTrue(preferred.accountId to preferred.targetKey in retained)
        assertEquals(2, retained.size)
    }

    @Test
    fun equalTimestampsUseStableAccountAndTargetOrdering() {
        val candidates = listOf("f", "b", "e", "a", "d", "c").map { account ->
            candidate(account, "target", 90L * 1_024 * 1_024, 1)
        }

        val retained = retainedContentDraftKeys(candidates)

        assertEquals(setOf("a", "b", "c", "d", "e").mapTo(mutableSetOf()) { it to "target" }, retained)
    }

    @Test
    fun globalCountLimitKeepsNewestAcrossAccounts() {
        val candidates = (0..ContentDraftLimits.maximumDraftsGlobally).map { index ->
            candidate("account-${index % 3}", "target-$index", 8, index.toLong())
        }

        val retained = retainedContentDraftKeys(candidates)

        assertEquals(ContentDraftLimits.maximumDraftsGlobally, retained.size)
        assertFalse("account-0" to "target-0" in retained)
    }

    private fun candidate(account: String, target: String, bytes: Long, updatedAt: Long) =
        ContentDraftPruneCandidate(account, target, bytes, updatedAt)
}
