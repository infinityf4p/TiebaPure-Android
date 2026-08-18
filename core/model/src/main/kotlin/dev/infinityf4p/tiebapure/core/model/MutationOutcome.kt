package dev.infinityf4p.tiebapure.core.model

/** Marks a write whose request may have reached Tieba but whose result was not confirmed. */
interface MutationOutcomeUnknown {
    val outcomeUnknownMessage: String
}

fun Throwable.mutationOutcomeUnknownMessageOrNull(): String? =
    (this as? MutationOutcomeUnknown)?.outcomeUnknownMessage
