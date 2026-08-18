package dev.infinityf4p.tiebapure

import dev.infinityf4p.tiebapure.core.data.BlocklistEntity
import dev.infinityf4p.tiebapure.core.model.BlocklistEntry
import dev.infinityf4p.tiebapure.core.model.BlocklistEntryKind
import dev.infinityf4p.tiebapure.core.model.TiebaBlocklistSnapshot

internal fun List<BlocklistEntity>.toBlocklistSnapshot(): TiebaBlocklistSnapshot =
    TiebaBlocklistSnapshot.from(mapNotNull(BlocklistEntity::toBlocklistEntry))

private fun BlocklistEntity.toBlocklistEntry(): BlocklistEntry? {
    val entryKind = BlocklistEntryKind.entries.firstOrNull { it.name == kind } ?: return null
    return BlocklistEntry(entryKind, value, numericId)
}
