package com.healthdataet.utdcredentials.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Persists the admin's WEB PANEL username/password (round 32's "site
 * password save" request) so the embedded WebView in FullSiteScreen can
 * auto-fill and submit `/admin/login` every time it's shown, not just once
 * right after a fresh native login (see PendingWebLogin, which stays as
 * the one-shot fast-path for a login that JUST happened in this process;
 * this store is what makes it work again on every later visit -- app
 * restarts, session-expiry redirects back to the login page, etc).
 *
 * Uses EncryptedSharedPreferences (Android Keystore-backed AES-256) rather
 * than the plain SharedPreferences SessionManager uses for the non-secret
 * bearer token/base URL -- this file holds an actual password, so it gets
 * the stronger guarantee even though both ultimately live in per-app
 * private storage.
 */
class SiteCredsStore(context: Context) {
    private val prefs: SharedPreferences = try {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "utd_credentials_site_login_secure",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    } catch (e: Exception) {
        // Extremely rare (corrupted Keystore entry, OEM bug) -- fall back to
        // plain prefs under a distinct name rather than crashing the app;
        // "remember my password" degrading to less-encrypted storage is far
        // better than the whole screen refusing to open.
        context.applicationContext.getSharedPreferences("utd_credentials_site_login_fallback", Context.MODE_PRIVATE)
    }

    fun save(username: String, password: String) {
        prefs.edit()
            .putString(KEY_USERNAME, username)
            .putString(KEY_PASSWORD, password)
            .apply()
    }

    /** The saved (username, password), or null if nothing is saved. */
    fun get(): Pair<String, String>? {
        val u = prefs.getString(KEY_USERNAME, null)
        val p = prefs.getString(KEY_PASSWORD, null)
        return if (!u.isNullOrBlank() && !p.isNullOrBlank()) Pair(u, p) else null
    }

    val hasSaved: Boolean
        get() = get() != null

    fun clear() {
        prefs.edit().remove(KEY_USERNAME).remove(KEY_PASSWORD).apply()
    }

    companion object {
        private const val KEY_USERNAME = "site_username"
        private const val KEY_PASSWORD = "site_password"
    }
}
