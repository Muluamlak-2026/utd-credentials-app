package com.healthdataet.utdcredentials.data.offline

import android.content.Context
import androidx.security.crypto.EncryptedFile
import androidx.security.crypto.MasterKey
import com.healthdataet.utdcredentials.data.ApiClient
import com.healthdataet.utdcredentials.data.SessionManager
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipInputStream

/**
 * Round 58: "database and code backup and restore through the apk" --
 * downloads a zip built server-side by the SAME backup engine the web
 * admin panel's own Backup page already uses (admin/backup.py, Round 25 --
 * see build_selective_backup_zip and admin/api_routes.py's /backup/download),
 * lets the admin pick exactly which pieces to include (Users, Credentials,
 * the full database SQL dump, the site's source code -- any combination),
 * and stores the result on this phone ENCRYPTED at rest (Android Keystore-
 * backed AES-256-GCM, the same androidx.security.crypto library this app
 * already uses for the stored login token -- see SiteCredsStore/
 * AppLockPrefs). This is deliberate: the admin explicitly chose to include
 * real secrets in the code piece rather than have them redacted (see the
 * deploy notes for why that turned out to be less risky than it sounds --
 * this codebase's own code backup never actually includes .env, where the
 * real DB password/API keys live), and encrypting the file anyway costs
 * nothing and means a copied-off file is still useless without this exact
 * phone unlocking it.
 *
 * Entirely separate from (and heavier than) LocalBackupManager's own
 * lightweight JSON snapshot of just the offline feature's own local data
 * -- see that file's doc comment for the distinction. Every "Download
 * Backup" tap here OVERWRITES the one stored file -- same one-file-only
 * rule as LocalBackupManager, never a pile of timestamped backups.
 *
 * Only the database piece is restorable back to the site from here -- see
 * [restoreDatabase]'s own doc comment for why code restore isn't offered
 * at all (the web admin panel itself has never supported that either).
 */
object FullSiteBackupManager {
    private const val BACKUP_FILE_NAME = "utd_full_backup.zip.enc"
    private const val META_FILE_NAME = "utd_full_backup_meta.json"

    private fun backupDir(context: Context): File =
        context.getExternalFilesDir(null) ?: context.filesDir

    private fun masterKey(context: Context) =
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()

    private fun encryptedFile(context: Context): EncryptedFile {
        val file = File(backupDir(context), BACKUP_FILE_NAME)
        return EncryptedFile.Builder(
            context, file, masterKey(context), EncryptedFile.FileEncryptionScheme.AES256_GCM_HKDF_4KB
        ).build()
    }

    private fun metaFile(context: Context): File = File(backupDir(context), META_FILE_NAME)

    data class BackupMeta(
        val timestamp: Long,
        val sizeBytes: Long,
        val includedUsers: Boolean,
        val includedCredentials: Boolean,
        val includedDatabase: Boolean,
        val includedCode: Boolean,
    )

    /** Downloads a backup with exactly the selected pieces and stores it
     * (encrypted) on this phone, overwriting whatever was stored before.
     * Returns a human-readable error message on failure, or null on
     * success. Never throws. */
    suspend fun downloadAndStore(
        context: Context, session: SessionManager,
        includeUsers: Boolean, includeCredentials: Boolean,
        includeDatabase: Boolean, includeCode: Boolean
    ): String? {
        val token = session.apiToken ?: return "Not logged in"
        val result = ApiClient(session.baseUrl).downloadBackup(
            token, includeUsers, includeCredentials, includeDatabase, includeCode
        )
        val bytes = result.bytes ?: return result.error ?: "Download failed"

        val zipFile = File(backupDir(context), BACKUP_FILE_NAME)
        // EncryptedFile refuses to write over an existing file -- this is
        // what makes every download an overwrite of the one stored backup
        // rather than a growing pile of them.
        if (zipFile.exists()) zipFile.delete()
        try {
            encryptedFile(context).openFileOutput().use { it.write(bytes) }
        } catch (e: Exception) {
            return "Could not save backup: ${e.message}"
        }

        val meta = JSONObject()
            .put("timestamp", System.currentTimeMillis())
            .put("sizeBytes", bytes.size)
            .put("includedUsers", includeUsers)
            .put("includedCredentials", includeCredentials)
            .put("includedDatabase", includeDatabase)
            .put("includedCode", includeCode)
        try {
            // Plain (unencrypted) file deliberately -- this holds only
            // counts/timestamps/flags, never any actual backup content, so
            // there's nothing here worth encrypting.
            metaFile(context).writeText(meta.toString())
        } catch (e: Exception) {
            // Display metadata failing to save doesn't undo the backup
            // itself, which already succeeded above.
        }
        return null
    }

    /** Metadata only, no decryption needed. Null if no backup has ever
     * been downloaded, or the metadata is unreadable. */
    fun peek(context: Context): BackupMeta? {
        return try {
            val f = metaFile(context)
            if (!f.exists()) return null
            val j = JSONObject(f.readText())
            BackupMeta(
                timestamp = j.optLong("timestamp"),
                sizeBytes = j.optLong("sizeBytes"),
                includedUsers = j.optBoolean("includedUsers"),
                includedCredentials = j.optBoolean("includedCredentials"),
                includedDatabase = j.optBoolean("includedDatabase"),
                includedCode = j.optBoolean("includedCode"),
            )
        } catch (e: Exception) {
            null
        }
    }

    private fun readStoredZipBytes(context: Context): ByteArray? {
        val file = File(backupDir(context), BACKUP_FILE_NAME)
        if (!file.exists()) return null
        return try {
            encryptedFile(context).openFileInput().use { it.readBytes() }
        } catch (e: Exception) {
            null
        }
    }

    /** Extracts database/full_database_dump.sql from the stored backup and
     * sends it to /api/v1/backup/restore-database -- the exact same
     * restore_database_from_sql the web admin panel's own restore already
     * uses; no new restore logic anywhere in this app. The caller is
     * expected to have already gotten the admin's explicit, TYPED
     * confirmation before calling this (see FullBackupScreen) -- this
     * function makes the real, destructive call the instant it runs, with
     * no further confirmation of its own. Returns a human-readable result
     * message either way (success or failure), never throws.
     *
     * Deliberately DATABASE ONLY: there's no equivalent "restore code" or
     * "restore users/credentials from this zip" here. Code was never
     * restorable through the web admin panel either (see
     * admin/api_routes.py's Round 58 comment) -- writing arbitrary source
     * files back onto the running server is a fundamentally different, far
     * riskier feature than anything else in this app, and isn't offered
     * anywhere, on the phone or the web panel. Users/Credentials restore
     * has its own, separate, already-existing path: LocalBackupManager's
     * JSON snapshot + "Sync Now" (see LocalBackupScreen) -- reusing that
     * is safer than adding a second, xlsx/csv-based users/credentials
     * restore path here that a phone has no built-in way to even parse. */
    suspend fun restoreDatabase(context: Context, session: SessionManager): String {
        val token = session.apiToken ?: return "Not logged in"
        val zipBytes = readStoredZipBytes(context)
            ?: return "No backup stored on this phone yet -- download one first."

        val sqlBytes = try {
            ZipInputStream(zipBytes.inputStream()).use { zis ->
                var entry = zis.nextEntry
                var found: ByteArray? = null
                while (entry != null) {
                    if (entry.name == "database/full_database_dump.sql") {
                        found = zis.readBytes()
                        break
                    }
                    entry = zis.nextEntry
                }
                found
            }
        } catch (e: Exception) {
            return "Could not read the stored backup: ${e.message}"
        } ?: return "This backup doesn't include a database dump -- Database wasn't selected when it was downloaded."

        val result = ApiClient(session.baseUrl).restoreDatabase(token, sqlBytes)
        return if (result.ok) {
            result.json?.optString("message")?.ifBlank { "Database restored." } ?: "Database restored."
        } else {
            result.error ?: "Restore failed."
        }
    }
}
