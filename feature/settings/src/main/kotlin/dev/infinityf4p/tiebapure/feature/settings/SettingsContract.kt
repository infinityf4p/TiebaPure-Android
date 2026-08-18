package dev.infinityf4p.tiebapure.feature.settings

import dev.infinityf4p.tiebapure.core.model.Account
import dev.infinityf4p.tiebapure.core.model.BlocklistEntry
import dev.infinityf4p.tiebapure.core.model.BlocklistEntryKind
import dev.infinityf4p.tiebapure.core.model.ReadingPreferences
import kotlinx.coroutines.flow.Flow

enum class SettingsAppearance { System, Light, Dark }

data class SettingsValues(
    val appearance: SettingsAppearance = SettingsAppearance.System,
    val postingEnabled: Boolean = true,
    val replyingEnabled: Boolean = false,
    val likingEnabled: Boolean = true,
    val automaticSignEnabled: Boolean = false,
    val submissionRiskAcknowledged: Boolean = false,
    val reading: ReadingPreferences = ReadingPreferences(),
)

/**
 * Feature boundary for locally persisted preferences. The Android app can adapt
 * DataStore and Room to this contract without making the settings UI depend on
 * either persistence technology.
 */
interface SettingsRepository {
    val settings: Flow<SettingsValues>
    val blocklist: Flow<List<BlocklistEntry>>

    suspend fun setAppearance(value: SettingsAppearance)
    suspend fun setPostingEnabled(value: Boolean)
    suspend fun setReplyingEnabled(value: Boolean)
    suspend fun setLikingEnabled(value: Boolean)
    suspend fun setAutomaticSignEnabled(value: Boolean)
    suspend fun acknowledgeSubmissionRisk()
    suspend fun setReadingPreferences(value: ReadingPreferences)
    suspend fun addBlocklistEntry(value: BlocklistEntry)
    suspend fun removeBlocklistEntry(value: BlocklistEntry)
    suspend fun clearBlocklist(kind: BlocklistEntryKind)
}

interface SettingsAccountActions {
    suspend fun signAllFollowedForums(): String
    suspend fun logOut()
}

data class SettingsHostState(
    val account: Account? = null,
    val versionName: String = "未知",
)

data class SettingsAboutInfo(
    val versionName: String,
    val projectUrl: String = "https://github.com/infinityf4p/TiebaPure-iOS",
    val authorUrl: String = "https://github.com/infinityf4p",
    val licenseUrl: String = "https://www.gnu.org/licenses/gpl-3.0.html",
    val protobufLicenseUrl: String = "https://github.com/protocolbuffers/protobuf/blob/main/LICENSE",
)

internal fun canAddBlocklistEntry(
    entries: List<BlocklistEntry>,
    candidate: BlocklistEntry,
): Boolean {
    val normalized = dev.infinityf4p.tiebapure.core.model.BlocklistPolicy.normalize(candidate) ?: return false
    val sameKind = entries.filter { it.kind == normalized.kind }
    return sameKind.size < dev.infinityf4p.tiebapure.core.model.BlocklistPolicy.maximumEntriesPerKind &&
        sameKind.none { it.identity == normalized.identity }
}
