package com.healthdataet.utdcredentials.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Round 32's app-lock feature: PIN, pattern, and biometric, switchable,
 * exactly like a professional password-manager app. Never stores a PIN or
 * pattern in plaintext -- both are salted and SHA-256 hashed before being
 * written, even though the underlying prefs file is already
 * Keystore-encrypted, so a second layer never has to rely on that alone.
 *
 * Biometric is a fast unlock ON TOP OF a configured PIN or pattern, not a
 * replacement for one -- BiometricPrompt always needs a device-credential
 * fallback for when a fingerprint doesn't read (wet finger, sensor issue,
 * a new print not yet enrolled), so [biometricEnabled] can only be turned
 * on once [lockMethod] is PIN or PATTERN, matching how banking/password
 * apps offer "unlock with fingerprint" as an option under a real PIN.
 */
class AppLockPrefs(context: Context) {
    private val prefs: SharedPreferences = try {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "utd_credentials_app_lock_secure",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    } catch (e: Exception) {
        context.applicationContext.getSharedPreferences("utd_credentials_app_lock_fallback", Context.MODE_PRIVATE)
    }

    /** METHOD_NONE / METHOD_PIN / METHOD_PATTERN. */
    var lockMethod: String
        get() = prefs.getString(KEY_METHOD, METHOD_NONE) ?: METHOD_NONE
        private set(value) = prefs.edit().putString(KEY_METHOD, value).apply()

    val isLockEnabled: Boolean
        get() = lockMethod != METHOD_NONE

    /** Round 48: when the app was last backgrounded (epoch millis), persisted
     * to disk rather than kept only in Compose state -- many phones (battery
     * optimization / "aggressive" OEM app management) kill the whole app
     * process seconds after it's backgrounded, not just stop the Activity.
     * An in-memory-only timestamp is wiped out by that kill, which made the
     * 3-minute grace period (see ui/Navigation.kt) silently do nothing on
     * exactly those phones -- it would always look like the app had "just"
     * been foregrounded with no away-time on record, so it locked every
     * time regardless of how long it was actually away. Persisting it here
     * survives a full process restart, so the very first Composition after
     * a kill can still see how long ago the app left. 0L means "not
     * currently backgrounded / no record yet".*/
    var lastBackgroundedAt: Long
        get() = prefs.getLong(KEY_LAST_BACKGROUNDED_AT, 0L)
        set(value) = prefs.edit().putLong(KEY_LAST_BACKGROUNDED_AT, value).apply()

    var biometricEnabled: Boolean
        get() = prefs.getBoolean(KEY_BIOMETRIC, false) && isLockEnabled
        set(value) = prefs.edit().putBoolean(KEY_BIOMETRIC, value && isLockEnabled).apply()

    fun setPin(pin: String) {
        val salt = newSalt()
        prefs.edit()
            .putString(KEY_METHOD, METHOD_PIN)
            .putString(KEY_PIN_SALT, salt)
            .putString(KEY_PIN_HASH, hash(pin, salt))
            .apply()
    }

    fun verifyPin(pin: String): Boolean {
        val salt = prefs.getString(KEY_PIN_SALT, null) ?: return false
        val stored = prefs.getString(KEY_PIN_HASH, null) ?: return false
        return stored == hash(pin, salt)
    }

    /** [nodes] is the sequence of dot indices (0-8, a 3x3 grid) the user
     * drew, in order -- joined with a separator before hashing so e.g.
     * [1,23] can never collide with [12,3]. */
    fun setPattern(nodes: List<Int>) {
        val salt = newSalt()
        prefs.edit()
            .putString(KEY_METHOD, METHOD_PATTERN)
            .putString(KEY_PATTERN_SALT, salt)
            .putString(KEY_PATTERN_HASH, hash(nodes.joinToString(","), salt))
            .apply()
    }

    fun verifyPattern(nodes: List<Int>): Boolean {
        val salt = prefs.getString(KEY_PATTERN_SALT, null) ?: return false
        val stored = prefs.getString(KEY_PATTERN_HASH, null) ?: return false
        return stored == hash(nodes.joinToString(","), salt)
    }

    /** Turns the whole app lock off -- PIN/pattern hashes and the
     * biometric toggle are all cleared together so switching back on
     * later always starts from a clean, deliberate setup step. */
    fun disableLock() {
        prefs.edit()
            .putString(KEY_METHOD, METHOD_NONE)
            .putBoolean(KEY_BIOMETRIC, false)
            .remove(KEY_PIN_HASH).remove(KEY_PIN_SALT)
            .remove(KEY_PATTERN_HASH).remove(KEY_PATTERN_SALT)
            .apply()
    }

    private fun newSalt(): String {
        val bytes = ByteArray(16)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun hash(value: String, salt: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(salt.toByteArray())
        val bytes = digest.digest(value.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }

    companion object {
        const val METHOD_NONE = "none"
        const val METHOD_PIN = "pin"
        const val METHOD_PATTERN = "pattern"

        private const val KEY_METHOD = "lock_method"
        private const val KEY_BIOMETRIC = "biometric_enabled"
        private const val KEY_PIN_HASH = "pin_hash"
        private const val KEY_PIN_SALT = "pin_salt"
        private const val KEY_PATTERN_HASH = "pattern_hash"
        private const val KEY_PATTERN_SALT = "pattern_salt"
        private const val KEY_LAST_BACKGROUNDED_AT = "last_backgrounded_at"
    }
}
