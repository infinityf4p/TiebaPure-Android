package dev.infinityf4p.tiebapure.core.model

enum class ReaderFontSize(val scale: Float) {
    Small(0.90f),
    Standard(1.0f),
    Large(1.12f),
    ExtraLarge(1.25f),
}

@JvmInline
value class ReaderFontFamily private constructor(val rawValue: String) {
    val importedId: String?
        get() = rawValue.removePrefix(IMPORTED_PREFIX).takeIf { rawValue.startsWith(IMPORTED_PREFIX) }

    companion object {
        val System = ReaderFontFamily("system")
        val Serif = ReaderFontFamily("serif")
        val Rounded = ReaderFontFamily("rounded")
        val Monospace = ReaderFontFamily("monospace")
        val builtIn = listOf(System, Serif, Rounded, Monospace)

        private const val IMPORTED_PREFIX = "imported:"
        private val digestPattern = Regex("[0-9a-f]{64}")

        fun imported(id: String): ReaderFontFamily? = id.lowercase()
            .takeIf(digestPattern::matches)
            ?.let { ReaderFontFamily(IMPORTED_PREFIX + it) }

        fun fromRaw(rawValue: String?): ReaderFontFamily {
            val normalized = rawValue?.trim().orEmpty()
            return builtIn.firstOrNull { it.rawValue == normalized }
                ?: normalized.removePrefix(IMPORTED_PREFIX)
                    .takeIf { normalized.startsWith(IMPORTED_PREFIX) }
                    ?.let(::imported)
                ?: System
        }
    }
}

data class ImportedReaderFont(
    val id: String,
    val displayName: String,
    val fileExtension: String,
    val byteCount: Long,
) {
    val family: ReaderFontFamily?
        get() = ReaderFontFamily.imported(id)
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
    val fontFamily: ReaderFontFamily = ReaderFontFamily.System,
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
