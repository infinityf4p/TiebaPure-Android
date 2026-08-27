package dev.infinityf4p.tiebapure.feature.account

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.infinityf4p.tiebapure.core.model.Account
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface AccountLoginStatus {
    data object Loading : AccountLoginStatus
    data object LoggedOut : AccountLoginStatus
    data class LoggedIn(val account: Account) : AccountLoginStatus
    data class Failed(val message: String) : AccountLoginStatus
}

data class MeUiState(
    val loginStatus: AccountLoginStatus = AccountLoginStatus.Loading,
    val savedAccounts: List<SavedAccountSummary> = emptyList(),
    val maximumSavedAccountCount: Int = 0,
    val visibleHistoryCount: Int = 0,
    val isLoggingOut: Boolean = false,
    val switchingAccountId: String? = null,
    val removingAccountId: String? = null,
    val accountActionErrorMessage: String? = null,
) {
    val account: Account?
        get() = (loginStatus as? AccountLoginStatus.LoggedIn)?.account

    val isAccountActionInProgress: Boolean
        get() = isLoggingOut || switchingAccountId != null || removingAccountId != null

    val canAddAccount: Boolean
        get() = maximumSavedAccountCount > 0 && savedAccounts.size < maximumSavedAccountCount
}

class MeViewModel internal constructor(
    private val repository: MeRepository,
    coroutineScope: CoroutineScope?,
) : ViewModel() {
    constructor(repository: MeRepository) : this(repository, coroutineScope = null)

    private val _uiState = MutableStateFlow(MeUiState())
    val uiState: StateFlow<MeUiState> = _uiState.asStateFlow()
    private val modelScope = coroutineScope ?: viewModelScope

    init {
        modelScope.launch {
            combine(repository.session, repository.browsingHistory) { session, history ->
                session to history
            }.catch { error ->
                if (error is CancellationException) throw error
                _uiState.update {
                    it.copy(loginStatus = AccountLoginStatus.Failed(error.accountReadableMessage()))
                }
            }.collect { (session, history) ->
                _uiState.update {
                    it.copy(
                        loginStatus = session.activeAccount
                            ?.let(AccountLoginStatus::LoggedIn)
                            ?: AccountLoginStatus.LoggedOut,
                        savedAccounts = session.savedAccounts,
                        maximumSavedAccountCount = session.maximumSavedAccountCount,
                        visibleHistoryCount = history.size,
                    )
                }
            }
        }
    }

    fun logout() {
        if (_uiState.value.isAccountActionInProgress) return
        _uiState.update { it.copy(isLoggingOut = true, accountActionErrorMessage = null) }
        modelScope.launch {
            runCatching { repository.logout() }
                .onSuccess {
                    _uiState.update { it.copy(isLoggingOut = false) }
                }
                .onFailure { error ->
                    if (error is CancellationException) throw error
                    _uiState.update {
                        it.copy(
                            isLoggingOut = false,
                            accountActionErrorMessage = error.accountReadableMessage(),
                        )
                    }
                }
        }
    }

    fun switchAccount(accountId: String) {
        val snapshot = _uiState.value
        if (snapshot.isAccountActionInProgress) return
        if (snapshot.savedAccounts.none { it.id == accountId && !it.isActive }) return
        _uiState.update {
            it.copy(switchingAccountId = accountId, accountActionErrorMessage = null)
        }
        modelScope.launch {
            runCatching { repository.switchAccount(accountId) }
                .onSuccess {
                    _uiState.update { it.copy(switchingAccountId = null) }
                }
                .onFailure { error ->
                    if (error is CancellationException) throw error
                    _uiState.update {
                        it.copy(
                            switchingAccountId = null,
                            accountActionErrorMessage = error.accountReadableMessage(),
                        )
                    }
                }
        }
    }

    fun removeAccount(accountId: String) {
        val snapshot = _uiState.value
        if (snapshot.isAccountActionInProgress) return
        if (snapshot.savedAccounts.none { it.id == accountId }) return
        _uiState.update {
            it.copy(removingAccountId = accountId, accountActionErrorMessage = null)
        }
        modelScope.launch {
            runCatching { repository.removeAccount(accountId) }
                .onSuccess {
                    _uiState.update { it.copy(removingAccountId = null) }
                }
                .onFailure { error ->
                    if (error is CancellationException) throw error
                    _uiState.update {
                        it.copy(
                            removingAccountId = null,
                            accountActionErrorMessage = error.accountReadableMessage(),
                        )
                    }
                }
        }
    }

    fun consumeAccountActionError() {
        _uiState.update { it.copy(accountActionErrorMessage = null) }
    }
}

data class LoginUiState(
    val isValidating: Boolean = false,
    val errorMessage: String? = null,
    val account: Account? = null,
)

class LoginViewModel(
    private val repository: LoginRepository,
) : ViewModel() {
    private val _uiState = MutableStateFlow(LoginUiState())
    val uiState: StateFlow<LoginUiState> = _uiState.asStateFlow()

    fun complete(cookies: BaiduLoginCookies) {
        if (_uiState.value.isValidating || _uiState.value.account != null) return
        viewModelScope.launch {
            _uiState.value = LoginUiState(isValidating = true)
            runCatching { repository.completeLogin(cookies) }
                .onSuccess { account -> _uiState.value = LoginUiState(account = account) }
                .onFailure { error ->
                    if (error is CancellationException) throw error
                    _uiState.value = LoginUiState(errorMessage = error.accountReadableMessage())
                }
        }
    }

    fun reportWebError(message: String) {
        if (!_uiState.value.isValidating) {
            _uiState.update { it.copy(errorMessage = message) }
        }
    }

    fun consumeError() {
        _uiState.update { it.copy(errorMessage = null) }
    }
}
