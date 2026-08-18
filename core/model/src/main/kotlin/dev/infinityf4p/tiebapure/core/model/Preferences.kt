package dev.infinityf4p.tiebapure.core.model

enum class ReaderFontSize(val scale: Float) {
    Small(0.90f),
    Standard(1.0f),
    Large(1.12f),
    ExtraLarge(1.25f),
}

enum class ReaderLineSpacing(val multiplier: Float) {
    Compact(0.75f),
    Standard(1.0f),
    Relaxed(1.5f),
}

enum class ReaderMediaLoadingPolicy {
    Automatic,
    DataSaving,
    Manual,
}

data class ReadingPreferences(
    val fontSize: ReaderFontSize = ReaderFontSize.Standard,
    val lineSpacing: ReaderLineSpacing = ReaderLineSpacing.Standard,
    val defaultReplySort: ThreadReplySort = ThreadReplySort.Hot,
    val mediaLoading: ReaderMediaLoadingPolicy = ReaderMediaLoadingPolicy.Automatic,
)

enum class BlocklistEntryKind { Keyword, User, Forum }

data class BlocklistEntry(
    val kind: BlocklistEntryKind,
    val value: String,
    val numericId: Long? = null,
) {
    val identity: String
        get() = when {
            numericId != null && numericId > 0 -> "${kind.name.lowercase()}:id:$numericId"
            else -> "${kind.name.lowercase()}:name:${value.trim().lowercase()}"
        }
}

object BlocklistPolicy {
    const val maximumEntriesPerKind = 200

    fun normalize(entry: BlocklistEntry): BlocklistEntry? {
        val limit = when (entry.kind) {
            BlocklistEntryKind.Keyword -> 200
            BlocklistEntryKind.User, BlocklistEntryKind.Forum -> 100
        }
        val value = entry.value.trim().take(limit)
        if (value.isEmpty()) return null
        return entry.copy(value = value, numericId = entry.numericId?.takeIf { it > 0 })
    }
}
