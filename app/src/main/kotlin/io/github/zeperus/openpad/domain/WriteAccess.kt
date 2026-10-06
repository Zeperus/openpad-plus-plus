package io.github.zeperus.openpad.domain

/** Why a document cannot be changed. Shown to the user so "read only" is never a mystery. */
enum class ReadOnlyReason {
    /** The app that handed the document over (or the picker) gave read access only. Asking again through the picker can fix this. */
    NoWriteGrant,

    /** The provider itself does not allow writing this document. */
    ProviderRefuses,

    /** Write access could not be determined (the provider did not answer). Treated as read-only: the safe side. */
    Unavailable,
}

sealed interface WriteAccess {
    data object Writable : WriteAccess
    data class ReadOnly(val reason: ReadOnlyReason) : WriteAccess
}

/** What trying to open the document for writing (without changing it) said. */
enum class WriteProbe { Opened, Denied, Refused, Unknown }

/**
 * Decides whether an external document can be written from the several - individually unreliable - signals Android gives:
 * the granted/persisted URI permission, the provider's own capability flag, and what actually happens when the document is
 * opened for writing (in append mode, which changes nothing). No single hint decides alone:
 *  - a document provider's URI needs a *write grant*; without one it is read-only and no probing is needed
 *  - with a grant, `FLAG_SUPPORTS_WRITE` is trusted when present
 *  - if the flag is missing or says "no" (providers with incomplete metadata do that), the probe decides
 */
object WriteAccessPolicy {
    suspend fun evaluate(
        /** The URI is a Storage Access Framework document URI: it only works with a granted/persisted write permission. */
        requiresGrant: Boolean,
        hasWriteGrant: Boolean,
        /** `FLAG_SUPPORTS_WRITE` of the document: true/false if the provider reports flags, null if it does not. */
        supportsWriteFlag: Boolean?,
        probe: suspend () -> WriteProbe,
    ): WriteAccess {
        if (requiresGrant && !hasWriteGrant) return WriteAccess.ReadOnly(ReadOnlyReason.NoWriteGrant)
        if (supportsWriteFlag == true) return WriteAccess.Writable
        return when (probe()) {
            WriteProbe.Opened -> WriteAccess.Writable
            WriteProbe.Denied -> WriteAccess.ReadOnly(ReadOnlyReason.NoWriteGrant)
            WriteProbe.Refused -> WriteAccess.ReadOnly(ReadOnlyReason.ProviderRefuses)
            WriteProbe.Unknown -> WriteAccess.ReadOnly(ReadOnlyReason.Unavailable)
        }
    }
}
