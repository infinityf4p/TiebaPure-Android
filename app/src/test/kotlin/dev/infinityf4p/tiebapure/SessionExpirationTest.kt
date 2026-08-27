package dev.infinityf4p.tiebapure

import dev.infinityf4p.tiebapure.core.data.AccountRepository
import dev.infinityf4p.tiebapure.core.model.Account
import dev.infinityf4p.tiebapure.core.model.MessageKind
import dev.infinityf4p.tiebapure.core.model.UserRelationshipKind
import dev.infinityf4p.tiebapure.core.network.TiebaApiException
import java.io.IOException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class SessionExpirationTest {
    @Test
    fun coordinatorLogsOutCurrentSessionOnceAndPublishesNotice() = runTest {
        val account = account("current", "bduss-a")
        var current: Account? = account
        var loggedOutAccount: Account? = null
        var logoutCount = 0
        val coordinator = SessionExpirationCoordinator(
            currentAccount = { current },
            logOut = {
                loggedOutAccount = it
                logoutCount += 1
                current = null
            },
        )

        coordinator.report(account)
        coordinator.report(account)

        assertEquals(1, logoutCount)
        assertEquals(account, loggedOutAccount)
        assertNull(current)
        assertNotNull(coordinator.notice.value)
    }

    @Test
    fun staleSessionReportDoesNotLogOutNewAccount() = runTest {
        val old = account("same-user", "old-bduss")
        val current = account("same-user", "new-bduss")
        var logoutCount = 0
        val coordinator = SessionExpirationCoordinator(
            currentAccount = { current },
            logOut = { logoutCount += 1 },
        )

        coordinator.report(old)

        assertEquals(0, logoutCount)
        assertNull(coordinator.notice.value)
    }

    @Test
    fun repositoryReportsOnlyExplicitSessionExpiration() = runTest {
        val account = account("current", "bduss-a")
        var reports = 0
        val expired = throwingAccountRepository(
            TiebaApiException.SessionExpired(4, "expired"),
        ).monitorSessions { reports += 1 }

        runCatching { expired.followedForums(account) }
        assertEquals(1, reports)

        val offline = throwingAccountRepository(IOException("offline"))
            .monitorSessions { reports += 1 }
        runCatching { offline.followedForums(account) }
        assertEquals(1, reports)
    }

    private fun throwingAccountRepository(failure: Throwable) = object : AccountRepository {
        override suspend fun followedForums(account: Account) = throw failure
        override suspend fun relationships(
            account: Account?,
            userId: Long,
            kind: UserRelationshipKind,
            page: Int,
        ) = error("unused")
        override suspend fun messages(account: Account, kind: MessageKind, page: Int) = error("unused")
        override suspend fun threadFavorites(account: Account, page: Int) = error("unused")
        override suspend fun resolveForumId(forumName: String) = error("unused")
        override suspend fun forumMembership(account: Account, forumId: Long) = error("unused")
    }

    private fun account(uid: String, bduss: String) = Account(
        uid = uid,
        name = "name",
        displayName = "display",
        portrait = "",
        bduss = bduss,
        stoken = "stoken-$bduss",
        tbs = "tbs",
    )
}
