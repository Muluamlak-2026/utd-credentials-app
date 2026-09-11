package com.healthdataet.utdcredentials.data.offline

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Round 57: "the apk to have the backup files insidede it" -- a single,
 * always-overwritten local snapshot of the ENTIRE offline database (every
 * user + credential row the phone currently knows about, synced or still
 * pending), written to this app's own external-files directory (no storage
 * permission needed on any Android version -- this directory is the app's
 * own, per Android's scoped-storage rules, and it's still visible in a
 * Files app under Android/data/<package>/files on versions that show that
 * path). Every "Backup Now" tap OVERWRITES the same file -- per the
 * admin's explicit request, this deliberately never accumulates a pile of
 * timestamped backup files; there is exactly one, always current.
 *
 * This is distinct from (and much lighter than) the website's own full-SQL
 * database backup/restore (Round 25, admin/backup.py) -- that one captures
 * every table on the server; this one captures only what the offline
 * feature itself manages, AS the phone currently has it (including
 * anything added/edited offline that hasn't synced yet), so a restore here
 * can bring back exactly what this device had, even mid-flight, with the
 * server unreachable.
 */
object LocalBackupManager {
    private const val BACKUP_FILE_NAME = "utd_offline_backup.json"
    private const val SCHEMA_VERSION = 1

    private fun backupFile(context: Context): File {
        // getExternalFilesDir falls back to internal storage if external
        // media genuinely isn't available (e.g. no SD card mounted) --
        // never returns null in practice on a real phone, but the internal
        // filesDir fallback keeps this working even in that edge case.
        val dir = context.getExternalFilesDir(null) ?: context.filesDir
        return File(dir, BACKUP_FILE_NAME)
    }

    data class BackupInfo(val timestamp: Long, val userCount: Int, val credentialCount: Int, val path: String)

    /** Writes the current full local state (both tables) to the one backup
     * file, overwriting whatever was there before. Returns the resulting
     * [BackupInfo] on success, or null on any failure (disk full, I/O
     * error) -- never throws, so a failed backup just shows as "backup
     * failed, try again" rather than crashing the screen that requested it. */
    suspend fun backupNow(context: Context): BackupInfo? {
        return try {
            val db = OfflineDatabase.get(context)
            val users = db.userDao().getAll()
            val creds = db.credentialDao().getAll()
            val now = System.currentTimeMillis()

            val usersArr = JSONArray()
            for (u in users) {
                usersArr.put(JSONObject()
                    .put("localId", u.localId)
                    .put("serverId", u.serverId ?: JSONObject.NULL)
                    .put("fullName", u.fullName)
                    .put("phone", u.phone)
                    .put("telegramId", u.telegramId ?: JSONObject.NULL)
                    .put("state", u.state ?: JSONObject.NULL)
                    .put("notes", u.notes ?: JSONObject.NULL)
                    .put("isBlocked", u.isBlocked)
                    .put("isRestricted", u.isRestricted)
                    .put("baseUpdatedAt", u.baseUpdatedAt ?: JSONObject.NULL)
                    .put("dirty", u.dirty)
                    .put("pendingCreate", u.pendingCreate)
                    .put("pendingDelete", u.pendingDelete)
                    .put("lastLocalEditAt", u.lastLocalEditAt))
            }
            val credsArr = JSONArray()
            for (c in creds) {
                credsArr.put(JSONObject()
                    .put("localId", c.localId)
                    .put("serverId", c.serverId ?: JSONObject.NULL)
                    .put("username", c.username)
                    .put("password", c.password)
                    .put("email", c.email ?: JSONObject.NULL)
                    .put("credentialIdName", c.credentialIdName ?: JSONObject.NULL)
                    .put("status", c.status ?: JSONObject.NULL)
                    .put("notes", c.notes ?: JSONObject.NULL)
                    .put("baseUpdatedAt", c.baseUpdatedAt ?: JSONObject.NULL)
                    .put("dirty", c.dirty)
                    .put("pendingCreate", c.pendingCreate)
                    .put("pendingDelete", c.pendingDelete)
                    .put("lastLocalEditAt", c.lastLocalEditAt))
            }

            val root = JSONObject()
                .put("schemaVersion", SCHEMA_VERSION)
                .put("createdAt", now)
                .put("users", usersArr)
                .put("credentials", credsArr)

            val file = backupFile(context)
            file.writeText(root.toString())
            BackupInfo(now, users.size, creds.size, file.absolutePath)
        } catch (e: Exception) {
            null
        }
    }

    /** Reads the current backup file's metadata without restoring anything
     * -- what the Local Backup screen shows ("last backup: <time>, N users
     * / M credentials"). Null if no backup has ever been made, or the file
     * is unreadable/corrupt. */
    fun peek(context: Context): BackupInfo? {
        return try {
            val file = backupFile(context)
            if (!file.exists()) return null
            val root = JSONObject(file.readText())
            BackupInfo(
                timestamp = root.optLong("createdAt"),
                userCount = root.optJSONArray("users")?.length() ?: 0,
                credentialCount = root.optJSONArray("credentials")?.length() ?: 0,
                path = file.absolutePath,
            )
        } catch (e: Exception) {
            null
        }
    }

    /** Replaces the ENTIRE local database with what's in the backup file --
     * used to recover this device's offline data (e.g. after a reinstall,
     * or the app's storage being cleared) exactly as it was at the last
     * "Backup Now", including anything that was still pending/unsynced at
     * that moment. Returns true on success. The caller is expected to have
     * already gotten the admin's explicit confirmation before calling this
     * (it is destructive to whatever's currently in the local database). */
    suspend fun restoreFromBackup(context: Context): Boolean {
        return try {
            val file = backupFile(context)
            if (!file.exists()) return false
            val root = JSONObject(file.readText())
            val db = OfflineDatabase.get(context)

            db.userDao().clearAll()
            val usersArr = root.optJSONArray("users") ?: JSONArray()
            for (i in 0 until usersArr.length()) {
                val u = usersArr.optJSONObject(i) ?: continue
                db.userDao().upsert(OfflineUser(
                    localId = 0, // reassigned fresh -- avoids colliding with rows created since the backup
                    serverId = if (u.isNull("serverId")) null else u.optInt("serverId"),
                    fullName = u.optString("fullName"),
                    phone = u.optString("phone"),
                    telegramId = if (u.isNull("telegramId")) null else u.optLong("telegramId"),
                    state = if (u.isNull("state")) null else u.optString("state"),
                    notes = if (u.isNull("notes")) null else u.optString("notes"),
                    isBlocked = u.optBoolean("isBlocked"),
                    isRestricted = u.optBoolean("isRestricted"),
                    baseUpdatedAt = if (u.isNull("baseUpdatedAt")) null else u.optString("baseUpdatedAt"),
                    dirty = u.optBoolean("dirty"),
                    pendingCreate = u.optBoolean("pendingCreate"),
                    pendingDelete = u.optBoolean("pendingDelete"),
                    lastLocalEditAt = u.optLong("lastLocalEditAt"),
                ))
            }

            db.credentialDao().clearAll()
            val credsArr = root.optJSONArray("credentials") ?: JSONArray()
            for (i in 0 until credsArr.length()) {
                val c = credsArr.optJSONObject(i) ?: continue
                db.credentialDao().upsert(OfflineCredential(
                    localId = 0,
                    serverId = if (c.isNull("serverId")) null else c.optInt("serverId"),
                    username = c.optString("username"),
                    password = c.optString("password"),
                    email = if (c.isNull("email")) null else c.optString("email"),
                    credentialIdName = if (c.isNull("credentialIdName")) null else c.optString("credentialIdName"),
                    status = if (c.isNull("status")) null else c.optString("status"),
                    notes = if (c.isNull("notes")) null else c.optString("notes"),
                    baseUpdatedAt = if (c.isNull("baseUpdatedAt")) null else c.optString("baseUpdatedAt"),
                    dirty = c.optBoolean("dirty"),
                    pendingCreate = c.optBoolean("pendingCreate"),
                    pendingDelete = c.optBoolean("pendingDelete"),
                    lastLocalEditAt = c.optLong("lastLocalEditAt"),
                ))
            }
            true
        } catch (e: Exception) {
            false
        }
    }
}
