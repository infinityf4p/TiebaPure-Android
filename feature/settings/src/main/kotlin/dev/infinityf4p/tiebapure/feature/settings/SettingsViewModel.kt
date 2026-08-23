package dev.infinityf4p.tiebapure.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dev.infinityf4p.tiebapure.core.model.BlocklistEntry
import dev.infinityf4p.tiebapure.core.model.BlocklistEntryKind
import dev.infinityf4p.tiebapure.core.model.BlocklistPolicy
import dev.infinityf4p.tiebapure.core.model.ImportedReaderFont
import dev.infinityf4p.tiebapure.core.model.ReaderFontFamily
import dev.infinityf4p.tiebapure.core.model.ReadingPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SettingsFeatureState(
    val values: SettingsValues = SettingsValues(),
    val blocklist: List<BlocklistEntry> = emptyList(),
    val readerFonts: List<ImportedReaderFont> = emptyList(),
    val isSaving: Boolean = false,
    val isSigning: Boolean = false,
    val isLoggingOut: Boolean = false,
    val message: String? = null,
    val signStatus: String? = null,
    val errorMessage: String? = null,
)

class SettingsViewModel(
    private val repository: SettingsRepository,
    private val accountActions: SettingsAccountActions? = null,
) : ViewModel() {
    private val mutableState = MutableStateFlow(SettingsFeatureState())
    val state: StateFlow<SettingsFeatureState> = mutableState.asStateFlow()

    init {
        combine(repository.settings, repository.blocklist, repository.readerFonts) { values, blocklist, fonts ->
            Triple(values, blocklist, fonts)
        }
            .onEach { (values, blocklist, fonts) ->
                mutableState.update { it.copy(values = values, blocklist = blocklist, readerFonts = fonts) }
            }
            .launchIn(viewModelScope)
    }

    fun setAppearance(value: SettingsAppearance) = mutate { repository.setAppearance(value) }
    fun setPostingEnabled(value: Boolean) = mutate { repository.setPostingEnabled(value) }
    fun setReplyingEnabled(value: Boolean) = mutate { repository.setReplyingEnabled(value) }
    fun setLikingEnabled(value: Boolean) = mutate { repository.setLikingEnabled(value) }
    fun setAutomaticSignEnabled(value: Boolean) = mutate { repository.setAutomaticSignEnabled(value) }
    fun acknowledgeSubmissionRisk() = mutate { repository.acknowledgeSubmissionRisk() }
    fun setReadingPreferences(value: ReadingPreferences) = mutate { repository.setReadingPreferences(value) }
    fun resetReadingPreferences() = setReadingPreferences(ReadingPreferences())

    fun importReaderFont(uri: String) = mutate(successMessage = "字体已导入") {
        repository.importReaderFont(uri)
    }

    fun removeReaderFont(id: String) = mutate(successMessage = "字体已删除") {
        repository.removeReaderFont(id)
        val current = state.value.values.reading
        if (current.fontFamily.importedId == id) {
            repository.setReadingPreferences(current.copy(fontFamily = ReaderFontFamily.System))
        }
    }

    fun addBlocklistEntry(candidate: BlocklistEntry) {
        val normalized = BlocklistPolicy.normalize(candidate)
        if (normalized == null) {
            mutableState.update { it.copy(errorMessage = "请输入有效的屏蔽内容。") }
            return
        }
        if (!canAddBlocklistEntry(state.value.blocklist, normalized)) {
            val reachedLimit = state.value.blocklist.count { it.kind == normalized.kind } >=
                BlocklistPolicy.maximumEntriesPerKind
            mutableState.update {
                it.copy(errorMessage = if (reachedLimit) "每类最多保存 200 条屏蔽规则。" else "这条规则已经存在。")
            }
            return
        }
        mutate(successMessage = "已添加屏蔽规则") { repository.addBlocklistEntry(normalized) }
    }

    fun removeBlocklistEntry(value: BlocklistEntry) = mutate { repository.removeBlocklistEntry(value) }
    fun clearBlocklist(kind: BlocklistEntryKind) = mutate { repository.clearBlocklist(kind) }

    fun signNow() {
        val actions = accountActions ?: return
        if (state.value.isSigning) return
        viewModelScope.launch {
            mutableState.update { it.copy(isSigning = true, signStatus = null, errorMessage = null) }
            runCatching { actions.signAllFollowedForums() }
                .onSuccess { message -> mutableState.update { it.copy(isSigning = false, signStatus = message) } }
                .onFailure { error -> mutableState.update { it.copy(isSigning = false, signStatus = readable(error)) } }
        }
    }

    fun logOut() {
        val actions = accountActions ?: return
        if (state.value.isLoggingOut) return
        viewModelScope.launch {
            mutableState.update { it.copy(isLoggingOut = true, errorMessage = null) }
            runCatching { actions.logOut() }
                .onSuccess { mutableState.update { it.copy(isLoggingOut = false) } }
                .onFailure { error -> mutableState.update { it.copy(isLoggingOut = false, errorMessage = readable(error)) } }
        }
    }

    fun consumeMessage() = mutableState.update { it.copy(message = null, signStatus = null, errorMessage = null) }

    private fun mutate(successMessage: String? = null, action: suspend () -> Unit) {
        viewModelScope.launch {
            mutableState.update { it.copy(isSaving = true, errorMessage = null) }
            runCatching { action() }
                .onSuccess { mutableState.update { it.copy(isSaving = false, message = successMessage) } }
                .onFailure { error -> mutableState.update { it.copy(isSaving = false, errorMessage = readable(error)) } }
        }
    }

    companion object {
        fun factory(
            repository: SettingsRepository,
            accountActions: SettingsAccountActions? = null,
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                SettingsViewModel(repository, accountActions) as T
        }
    }
}

private fun readable(error: Throwable): String = error.message?.takeIf { it.isNotBlank() } ?: "操作失败，请稍后重试。"
