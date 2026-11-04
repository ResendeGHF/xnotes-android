package com.xnotes.share.db

/** Synchronous-ish lookup surface the hot sync paths use (backed by Room off-thread). */
interface GrantLookup {
    suspend fun grantIds(fp: String): List<String>
    suspend fun setGranted(fp: String, uri: String, granted: Boolean)
}
