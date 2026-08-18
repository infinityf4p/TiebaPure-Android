package dev.infinityf4p.tiebapure.feature.account

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.infinityf4p.tiebapure.core.model.Account
import kotlinx.coroutines.CancellationException
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
    val visibleHistoryCount: Int = 0,
    val isLoggingOut: Boolean = false,
) {
    val account: Account?
        get() = (loginStatus as? AccountLoginStatus.LoggedIn)?.account
}

class MeViewModel(
    private val repository: MeRepository,
) : ViewModel() {
    private val _uiState = MutableStateFlow(MeUiState())
    val uiState: StateFlow<MeUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            combine(repository.account, repository.browsingHistory) { account, history ->
                MeUiState(
                    loginStatus = account?.let(AccountLoginStatus::LoggedIn) ?: AccountLoginStatus.LoggedOut,
                    visibleHistoryCount = history.size,
                )
            }.catch { error ->
                if (error is CancellationException) throw error
                emit(MeUiState(loginStatus = AccountLoginStatus.Failed(error.accountReadableMessage())))
            }.collect(_uiState)
        }
    }

    fun logout() {
        if (_uiState.value.isLoggingOut) return
        viewModelScope.launch {
            _uiState.update { it.copy(isLoggingOut = true) }
            runCatching { repository.logout() }
                .onSuccess {
                    _uiState.update {
                        it.copy(loginStatus = AccountLoginStatus.LoggedOut, isLoggingOut = false)
                    }
                }
                .onFailure { error ->
                    if (error is CancellationException) throw error
                    _uiState.update {
                        it.copy(
                            isLoggingOut = false,
                            loginStatus = AccountLoginStatus.Failed(error.accountReadableMessage()),
                        )
                    }
                }
        }
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
