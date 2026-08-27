package dev.infinityf4p.tiebapure

import dev.infinityf4p.tiebapure.core.data.AccountCredentialState
import dev.infinityf4p.tiebapure.core.model.Account
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal class AppAccountSessionController(
    initialState: AccountCredentialState,
    private val saveState: (AccountCredentialState) -> Unit,
    private val clearState: () -> Unit,
    private val activateSession: suspend (Account) -> Unit,
    private val invalidateAndDrain: suspend (Account) -> Unit,
) {
    private val mutex = Mutex()
    private var state = initialState
    private val mutableAccount = MutableStateFlow(initialState.activeAccount)
    private val mutableAccounts = MutableStateFlow(initialState.accounts)

    val account: StateFlow<Account?> = mutableAccount.asStateFlow()
    val accounts: StateFlow<List<Account>> = mutableAccounts.asStateFlow()

    suspend fun addOrReplace(account: Account, expectedCurrent: Account?): Boolean = mutex.withLock {
        check(state.activeAccount?.sessionIdentity() == expectedCurrent?.sessionIdentity()) {
            "登录状态已变化，请重试。"
        }
        transitionTo(state.addOrReplace(account))
    }

    suspend fun switchTo(accountId: String): Boolean = mutex.withLock {
        transitionTo(state.switchTo(accountId))
    }

    suspend fun remove(accountId: String): Boolean = mutex.withLock {
        transitionTo(state.remove(accountId))
    }

    suspend fun removeCurrent(expected: Account): Boolean = mutex.withLock {
        if (state.activeAccount?.sessionIdentity() != expected.sessionIdentity()) return@withLock false
        transitionTo(state.remove(expected.id))
        true
    }

    private suspend fun transitionTo(nextState: AccountCredentialState): Boolean {
        if (nextState == state) return false
        val previousAccount = state.activeAccount
        val nextAccount = nextState.activeAccount
        val sessionChanged = previousAccount?.sessionIdentity() != nextAccount?.sessionIdentity()
        if (!sessionChanged) {
            persist(nextState)
            publish(nextState)
            return false
        }

        var previousInvalidated = false
        var nextActivated = false
        try {
            previousAccount?.let {
                invalidateAndDrain(it)
                previousInvalidated = true
            }
            nextAccount?.let {
                activateSession(it)
                nextActivated = true
            }
            persist(nextState)
            publish(nextState)
            return true
        } catch (error: Throwable) {
            withContext(NonCancellable) {
                if (nextActivated && nextAccount != null) {
                    runCatching { invalidateAndDrain(nextAccount) }
                        .exceptionOrNull()
                        ?.let(error::addSuppressed)
                }
                if (previousInvalidated && previousAccount != null) {
                    runCatching { activateSession(previousAccount) }
                        .exceptionOrNull()
                        ?.let(error::addSuppressed)
                }
            }
            throw error
        }
    }

    private fun persist(nextState: AccountCredentialState) {
        if (nextState.accounts.isEmpty()) clearState() else saveState(nextState)
    }

    private fun publish(nextState: AccountCredentialState) {
        state = nextState
        mutableAccounts.value = nextState.accounts
        mutableAccount.value = nextState.activeAccount
    }
}
