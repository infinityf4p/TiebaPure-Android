package dev.infinityf4p.tiebapure.feature.account

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.infinityf4p.tiebapure.core.model.UserProfile
import dev.infinityf4p.tiebapure.core.model.UserProfileEditRequest
import dev.infinityf4p.tiebapure.core.model.UserProfileSex
import dev.infinityf4p.tiebapure.core.model.mutationOutcomeUnknownMessageOrNull
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class EditProfileUiState(
    val nickname: String,
    val introduction: String,
    val sex: UserProfileSex,
    val initialNickname: String,
    val initialIntroduction: String,
    val initialSex: UserProfileSex,
    val isSaving: Boolean = false,
    val savedProfile: UserProfile? = null,
    val errorMessage: String? = null,
    val isOutcomeUnknown: Boolean = false,
) {
    val nicknameError: String?
        get() = validateNickname(nickname)
    val canSave: Boolean
        get() = !isSaving && !isOutcomeUnknown && nicknameError == null && (
            nickname != initialNickname || introduction != initialIntroduction || sex != initialSex
        )
}

class EditProfileViewModel(
    profile: UserProfile,
    private val repository: ProfileEditRepository,
) : ViewModel() {
    private val _uiState = MutableStateFlow(
        EditProfileUiState(
            nickname = profile.user.resolvedDisplayName,
            introduction = profile.intro,
            sex = profile.sex,
            initialNickname = profile.user.resolvedDisplayName,
            initialIntroduction = profile.intro,
            initialSex = profile.sex,
        ),
    )
    val uiState: StateFlow<EditProfileUiState> = _uiState.asStateFlow()

    fun setNickname(value: String) {
        _uiState.update { it.copy(nickname = value, errorMessage = null, savedProfile = null) }
    }

    fun setIntroduction(value: String) {
        _uiState.update { it.copy(introduction = value, errorMessage = null, savedProfile = null) }
    }

    fun setSex(value: UserProfileSex) {
        _uiState.update { it.copy(sex = value, errorMessage = null, savedProfile = null) }
    }

    fun save() {
        val snapshot = _uiState.value
        if (!snapshot.canSave) return
        val request = UserProfileEditRequest(
            nickname = snapshot.nickname,
            introduction = snapshot.introduction.trim(),
            sex = snapshot.sex,
        )
        viewModelScope.launch {
            _uiState.update { it.copy(isSaving = true, errorMessage = null) }
            runCatching { repository.updateProfile(request) }
                .onSuccess { profile ->
                    _uiState.update {
                        it.copy(
                            nickname = profile.user.resolvedDisplayName,
                            introduction = profile.intro,
                            sex = profile.sex,
                            initialNickname = profile.user.resolvedDisplayName,
                            initialIntroduction = profile.intro,
                            initialSex = profile.sex,
                            isSaving = false,
                            savedProfile = profile,
                        )
                    }
                }
                .onFailure { error ->
                    if (error is CancellationException) throw error
                    val unknown = error.mutationOutcomeUnknownMessageOrNull()
                    _uiState.update {
                        it.copy(
                            isSaving = false,
                            isOutcomeUnknown = unknown != null,
                            errorMessage = unknown ?: error.accountReadableMessage(),
                        )
                    }
                }
        }
    }
}

internal fun validateNickname(value: String): String? {
    val nickname = value.trim()
    return when {
        nickname.isEmpty() -> "昵称不能为空"
        nickname.any { it == '\r' || it == '\n' } -> "昵称不能包含换行"
        else -> null
    }
}
