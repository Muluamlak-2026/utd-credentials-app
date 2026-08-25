package com.healthdataet.utdcredentials.data

/**
 * In-memory-only (never persisted to disk) holder so the web login form
 * embedded in FullSiteScreen's WebView can be auto-filled and submitted
 * ONCE, immediately after the native /api/v1/login call already succeeded
 * with the same credentials -- so the admin only ever types their password
 * a single time, instead of once for the app and again for the embedded
 * site. Consumed (and cleared) the instant it's used.
 */
object PendingWebLogin {
    var username: String? = null
    var password: String? = null

    fun consume(): Pair<String, String>? {
        val u = username
        val p = password
        username = null
        password = null
        return if (u != null && p != null) Pair(u, p) else null
    }
}
