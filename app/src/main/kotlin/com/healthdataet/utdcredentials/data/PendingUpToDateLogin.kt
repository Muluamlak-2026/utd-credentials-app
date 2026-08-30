package com.healthdataet.utdcredentials.data

/**
 * Round 48i: in-memory-only (never persisted to disk -- these are real
 * uptodate.com passwords) holder so CredentialPickerScreen can hand off
 * exactly one chosen credential to UpToDateLoginScreen's WebView, which
 * auto-fills it into uptodate.com's own login form the moment the page
 * loads -- the entire point being the admin never has to copy a username,
 * switch apps, and paste it (then do the same again for the password) by
 * hand. Consumed (and cleared) the instant it's used, exactly like
 * PendingWebLogin's existing pattern for this app's own site login.
 */
object PendingUpToDateLogin {
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
