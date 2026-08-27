package dev.infinityf4p.tiebapure

import dev.infinityf4p.tiebapure.core.data.AccountCredentialState
import dev.infinityf4p.tiebapure.core.model.Account
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppAccountSessionControllerTest {
    @Test
    fun addingAndSwitchingAccountsDrainsPreviousSession() = runTest {
        val first = account("1")
        val second = account("2")
        val events = mutableListOf<String>()
        val controller = controller(first, events)

        assertTrue(controller.addOrReplace(second, expectedCurrent = first))
        assertEquals(second, controller.account.value)
        assertEquals(listOf(second, first), controller.accounts.value)

        assertTrue(controller.switchTo(first.id))
        assertEquals(first, controller.account.value)
        assertEquals(listOf(first, second), controller.accounts.value)
        assertEquals(
            listOf(
                "invalidate:1",
                "activate:2",
                "save:2,1",
                "invalidate:2",
                "activate:1",
                "save:1,2",
            ),
            events,
        )
    }

    @Test
    fun replacingCurrentAccountMetadataDoesNotRestartSession() = runTest {
        val first = account("1")
        val refreshed = first.copy(displayName = "Refreshed")
        val events = mutableListOf<String>()
        val controller = controller(first, events)

        assertFalse(controller.addOrReplace(refreshed, expectedCurrent = first))

        assertEquals(refreshed, controller.account.value)
        assertEquals(listOf("save:1"), events)
    }

    @Test
    fun removingCurrentAccountActivatesRemainingAccount() = runTest {
        val first = account("1")
        val second = account("2")
        val events = mutableListOf<String>()
        val controller = controller(first, events)
        controller.addOrReplace(second, expectedCurrent = first)
        events.clear()

        assertTrue(controller.removeCurrent(second))

        assertEquals(first, controller.account.value)
        assertEquals(listOf(first), controller.accounts.value)
        assertEquals(listOf("invalidate:2", "activate:1", "save:1"), events)
    }

    @Test
    fun removingOnlyAccountClearsPersistedState() = runTest {
        val first = account("1")
        val events = mutableListOf<String>()
        val controller = controller(first, events)

        assertTrue(controller.removeCurrent(first))

        assertEquals(null, controller.account.value)
        assertEquals(emptyList<Account>(), controller.accounts.value)
        assertEquals(listOf("invalidate:1", "clear"), events)
    }

    @Test
    fun staleLogoutDoesNotRemoveNewCurrentAccount() = runTest {
        val first = account("1")
        val second = account("2")
        val controller = controller(first, mutableListOf())
        controller.addOrReplace(second, expectedCurrent = first)

        assertFalse(controller.removeCurrent(first))

        assertEquals(second, controller.account.value)
        assertEquals(listOf(second, first), controller.accounts.value)
    }

    @Test
    fun persistenceFailureRestoresPreviousSession() = runTest {
        val first = account("1")
        val second = account("2")
        val events = mutableListOf<String>()
        val controller = AppAccountSessionController(
            initialState = AccountCredentialState().addOrReplace(first),
            saveState = {
                events += "save"
                error("disk full")
            },
            clearState = { events += "clear" },
            activateSession = { events += "activate:${it.id}" },
            invalidateAndDrain = { events += "invalidate:${it.id}" },
        )

        val error = runCatching { controller.addOrReplace(second, expectedCurrent = first) }.exceptionOrNull()

        assertTrue(error is IllegalStateException)
        assertEquals("disk full", error?.message)
        assertEquals(first, controller.account.value)
        assertEquals(listOf(first), controller.accounts.value)
        assertEquals(
            listOf("invalidate:1", "activate:2", "save", "invalidate:2", "activate:1"),
            events,
        )
    }

    @Test
    fun activationFailureReactivatesPreviousSession() = runTest {
        val first = account("1")
        val second = account("2")
        val events = mutableListOf<String>()
        val controller = AppAccountSessionController(
            initialState = AccountCredentialState().addOrReplace(first),
            saveState = { events += "save" },
            clearState = { events += "clear" },
            activateSession = {
                events += "activate:${it.id}"
                if (it.id == second.id) error("activation failed")
            },
            invalidateAndDrain = { events += "invalidate:${it.id}" },
        )

        val error = runCatching { controller.addOrReplace(second, expectedCurrent = first) }.exceptionOrNull()

        assertTrue(error is IllegalStateException)
        assertEquals(first, controller.account.value)
        assertEquals(listOf("invalidate:1", "activate:2", "activate:1"), events)
    }

    private fun controller(first: Account, events: MutableList<String>) = AppAccountSessionController(
        initialState = AccountCredentialState().addOrReplace(first),
        saveState = { events += "save:${it.accounts.joinToString(",") { account -> account.id }}" },
        clearState = { events += "clear" },
        activateSession = { events += "activate:${it.id}" },
        invalidateAndDrain = { events += "invalidate:${it.id}" },
    )

    private fun account(uid: String) = Account(
        uid = uid,
        name = "name-$uid",
        displayName = "display-$uid",
        portrait = "portrait-$uid",
        bduss = "bduss-$uid",
        stoken = "stoken-$uid",
        baiduId = "baiduid-$uid",
        tbs = "tbs-$uid",
    )
}
