package com.xnotes.share.db

/**
 * What the host engine and the file backend enforce sharing against. Always
 * scoped to the ACTIVE companion: [setGranted] writes the active device's
 * grants, [isShared]/[sharedUris] read them.
 */
interface GrantSource {
    /** Whether the active companion may see [uri]. Hot path: must never touch disk. */
    fun isShared(uri: String): Boolean

    /** The active companion's granted ids. Hot path: must never touch disk. */
    fun sharedUris(): Set<String>

    /** Grant or revoke [uri] for the active companion (persists off-thread). */
    fun setGranted(uri: String, granted: Boolean)
}
