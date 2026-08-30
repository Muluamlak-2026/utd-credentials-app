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
 *
 * Round 48k: also carries [source]/[id] now -- the exact (source, id) pair
 * this credential came from in /api/v1/credentials/list -- purely so
 * UpToDateLoginScreen can report the attempt's outcome back via
 * ApiClient.reportLoginAttempt once it resolves. Never guessed/derived:
 * always the same pair CredentialPickerScreen was itself handed.
 */
object PendingUpToDateLogin {
    var username: String? = null
    var password: String? = null
    var source: String? = null
    var id: Long? = null

    fun consume(): Pair<String, String>? {
        val u = username
        val p = password
        username = null
        password = null
        return if (u != null && p != null) Pair(u, p) else null
    }

    /** The (source, id) pair for whatever credential this attempt is for --
     * null/null if it was never set. UpToDateLoginScreen calls this
     * alongside consume(), inside the same `remember` block, so both are
     * read together exactly once per screen instance. */
    fun consumeSourceId(): Pair<String, Long>? {
        val s = source
        val i = id
        source = null
        id = null
        return if (s != null && i != null) Pair(s, i) else null
    }
}
