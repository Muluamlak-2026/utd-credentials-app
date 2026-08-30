package com.healthdataet.utdcredentials.data

import com.healthdataet.utdcredentials.ui.screens.CredentialEntry

/**
 * Round 48i follow-up: holds the full list of credentials the admin
 * checked off in CredentialPickerScreen for an unattended sequential
 * login run -- handed off in-memory only (never persisted to disk, same
 * reasoning as PendingUpToDateLogin -- these are real uptodate.com
 * passwords) to SequentialLoginScreen, which logs into each one in turn,
 * clears the session (cookies + cache) before the next, and builds a
 * per-credential success/failure report as it goes.
 */
object PendingSequentialLogins {
    var queue: List<CredentialEntry>? = null

    fun consume(): List<CredentialEntry>? {
        val q = queue
        queue = null
        return q
    }
}
