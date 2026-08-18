package dev.infinityf4p.tiebapure.core.network

object TiebaRequestValuePolicy {
    fun page(value: Int): Int {
        if (value <= 0) throw TiebaNetworkException.InvalidRequest("Page must be positive: $value")
        return value
    }

    fun signedIdentifier(value: ULong): Long {
        if (value > Long.MAX_VALUE.toULong()) {
            throw TiebaNetworkException.InvalidRequest("Identifier is too large: $value")
        }
        return value.toLong()
    }
}
