package dev.infinityf4p.tiebapure.feature.account

import dev.infinityf4p.tiebapure.core.model.Account
import dev.infinityf4p.tiebapure.core.model.BrowsingHistoryEntry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MeViewModelTest {
    @Test
    fun constructorPublishesSavedAccountsWithoutCredentials() {
        withScope { scope ->
            val repository = FakeMeRepository(twoAccountSession())

            val viewModel = MeViewModel(repository, scope)

            assertEquals("1", viewModel.uiState.value.account?.id)
            assertEquals(listOf("1", "2"), viewModel.uiState.value.savedAccounts.map(SavedAccountSummary::id))
            assertEquals(listOf(true, false), viewModel.uiState.value.savedAccounts.map(SavedAccountSummary::isActive))
            assertEquals(2, viewModel.uiState.value.maximumSavedAccountCount)
            assertFalse(viewModel.uiState.value.canAddAccount)
        }
    }

    @Test
    fun switchMarksBusySynchronouslyAndIgnoresDuplicateRequest() {
        withScope { scope ->
            val repository = FakeMeRepository(twoAccountSession()).apply {
                switchGate = CompletableDeferred()
            }
            val viewModel = MeViewModel(repository, scope)

            viewModel.switchAccount("2")
            viewModel.switchAccount("2")

            assertEquals(listOf("2"), repository.switchCalls)
            assertEquals("2", viewModel.uiState.value.switchingAccountId)
            assertTrue(viewModel.uiState.value.isAccountActionInProgress)

            repository.switchGate?.complete(Unit)

            assertEquals("2", viewModel.uiState.value.account?.id)
            assertTrue(viewModel.uiState.value.savedAccounts.single { it.id == "2" }.isActive)
            assertNull(viewModel.uiState.value.switchingAccountId)
            assertFalse(viewModel.uiState.value.isAccountActionInProgress)
        }
    }

    @Test
    fun switchFailureKeepsCurrentAccountAndCanBeConsumed() {
        withScope { scope ->
            val repository = FakeMeRepository(twoAccountSession()).apply {
                switchFailure = IllegalStateException("切换失败")
            }
            val viewModel = MeViewModel(repository, scope)

            viewModel.switchAccount("2")

            assertEquals("1", viewModel.uiState.value.account?.id)
            assertEquals("切换失败", viewModel.uiState.value.accountActionErrorMessage)
            assertNull(viewModel.uiState.value.switchingAccountId)

            viewModel.consumeAccountActionError()

            assertNull(viewModel.uiState.value.accountActionErrorMessage)
        }
    }

    @Test
    fun removingCurrentAccountActivatesRemainingAccount() {
        withScope { scope ->
            val repository = FakeMeRepository(twoAccountSession()).apply {
                removeGate = CompletableDeferred()
            }
            val viewModel = MeViewModel(repository, scope)

            viewModel.removeAccount("1")
            viewModel.removeAccount("1")

            assertEquals(listOf("1"), repository.removeCalls)
            assertEquals("1", viewModel.uiState.value.removingAccountId)

            repository.removeGate?.complete(Unit)

            assertEquals("2", viewModel.uiState.value.account?.id)
            assertEquals(listOf("2"), viewModel.uiState.value.savedAccounts.map(SavedAccountSummary::id))
            assertTrue(viewModel.uiState.value.canAddAccount)
            assertNull(viewModel.uiState.value.removingAccountId)
        }
    }

    @Test
    fun logoutOfCurrentAccountKeepsRemainingAccountLoggedIn() {
        withScope { scope ->
            val repository = FakeMeRepository(twoAccountSession())
            val viewModel = MeViewModel(repository, scope)

            viewModel.logout()

            assertEquals("2", viewModel.uiState.value.account?.id)
            assertEquals(listOf("2"), viewModel.uiState.value.savedAccounts.map(SavedAccountSummary::id))
            assertFalse(viewModel.uiState.value.isLoggingOut)
        }
    }

    @Test
    fun currentAndUnknownAccountsAreNotSwitched() {
        withScope { scope ->
            val repository = FakeMeRepository(twoAccountSession())
            val viewModel = MeViewModel(repository, scope)

            viewModel.switchAccount("1")
            viewModel.switchAccount("missing")

            assertTrue(repository.switchCalls.isEmpty())
            assertFalse(viewModel.uiState.value.isAccountActionInProgress)
        }
    }

    private fun withScope(block: (CoroutineScope) -> Unit) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            block(scope)
        } finally {
            scope.cancel()
        }
    }
}

private class FakeMeRepository(initialSession: MeAccountSession) : MeRepository {
    override val session = MutableStateFlow(initialSession)
    override val browsingHistory = MutableStateFlow<List<BrowsingHistoryEntry>>(emptyList())
    val switchCalls = mutableListOf<String>()
    val removeCalls = mutableListOf<String>()
    var switchGate: CompletableDeferred<Unit>? = null
    var removeGate: CompletableDeferred<Unit>? = null
    var switchFailure: Throwable? = null

    override suspend fun logout() {
        session.value.activeAccount?.id?.let(::removeFromSession)
    }

    override suspend fun switchAccount(accountId: String) {
        switchCalls += accountId
        switchGate?.await()
        switchFailure?.let { throw it }
        val current = session.value
        session.value = current.copy(
            activeAccount = testAccount(accountId),
            savedAccounts = current.savedAccounts.map { it.copy(isActive = it.id == accountId) },
        )
    }

    override suspend fun removeAccount(accountId: String) {
        removeCalls += accountId
        removeGate?.await()
        removeFromSession(accountId)
    }

    private fun removeFromSession(accountId: String) {
        val current = session.value
        val remaining = current.savedAccounts.filterNot { it.id == accountId }
        val nextActiveId = when {
            remaining.isEmpty() -> null
            current.activeAccount?.id == accountId -> remaining.first().id
            else -> current.activeAccount?.id
        }
        session.value = current.copy(
            activeAccount = nextActiveId?.let(::testAccount),
            savedAccounts = remaining.map { it.copy(isActive = it.id == nextActiveId) },
        )
    }
}

private fun twoAccountSession() = MeAccountSession(
    activeAccount = testAccount("1"),
    savedAccounts = listOf(
        SavedAccountSummary("1", "用户1", "", isActive = true),
        SavedAccountSummary("2", "用户2", "", isActive = false),
    ),
    maximumSavedAccountCount = 2,
)

private fun testAccount(id: String) = Account(
    uid = id,
    name = "user$id",
    displayName = "用户$id",
    portrait = "",
    bduss = "bduss$id",
    stoken = "stoken$id",
    tbs = "tbs$id",
)
