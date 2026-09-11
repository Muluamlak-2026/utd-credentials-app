package com.healthdataet.utdcredentials.data.offline

import android.content.Context
import com.healthdataet.utdcredentials.data.ApiClient
import com.healthdataet.utdcredentials.data.SessionManager
import org.json.JSONArray
import org.json.JSONObject

/**
 * Round 57: the whole offline-sync brain in one place -- pulling the
 * server's current state down into the local Room mirror, and pushing
 * whatever the admin added/edited offline back up, with per-record
 * conflict detection (see admin/api_routes.py's sync_push doc comment for
 * the server half of this contract). Every entry point here is a plain
 * suspend fun so it can be called equally from a Composable's
 * coroutineScope (a manual "Sync Now" tap), the connectivity watcher (the
 * instant the network comes back), or the WorkManager backstop -- there is
 * exactly one sync implementation, not three.
 */
object SyncRepository {

    /** True if there's nothing locally that still needs pushing -- lets a
     * caller skip starting a foreground service / showing a syncing
     * indicator for a no-op sync (e.g. the periodic backstop firing while
     * nothing has changed since the last successful run). */
    suspend fun hasPendingChanges(context: Context): Boolean {
        val db = OfflineDatabase.get(context)
        return db.userDao().getPending().isNotEmpty() || db.credentialDao().getPending().isNotEmpty()
    }

    suspend fun conflictCount(context: Context): Int = OfflineDatabase.get(context).conflictDao().count()

    /** Full sync cycle: pull first (so a push right after a fresh pull has
     * the freshest possible conflict-detection baseline for anything NOT
     * already dirty locally), then push whatever's still pending. Returns
     * true if the whole cycle completed without a network/server error
     * (individual per-record problems -- a conflict, a bad create -- are
     * not failures of the CYCLE, they're expected outcomes handled below). */
    suspend fun fullSync(context: Context, session: SessionManager): Boolean {
        val token = session.apiToken ?: return false
        val client = ApiClient(session.baseUrl)
        val pullOk = pullUsers(context, client, token) && pullCredentials(context, client, token)
        val pushOk = pushPending(context, client, token)
        return pullOk && pushOk
    }

    // ---------------------------------------------------------------
    // PULL -- refresh local rows that have no unsynced local edit. A row
    // that's currently dirty (or a pending offline create not yet pushed)
    // is left completely untouched here -- overwriting it on a pull would
    // silently throw away the admin's own not-yet-synced edit, exactly the
    // kind of silent data loss the whole conflict system exists to avoid.
    // That row's own eventual PUSH is what runs the real conflict check.
    // ---------------------------------------------------------------

    private suspend fun pullUsers(context: Context, client: ApiClient, token: String): Boolean {
        val result = client.syncPullUsers(token)
        if (!result.ok || result.json == null) return false
        val dao = OfflineDatabase.get(context).userDao()
        val arr = result.json.optJSONArray("users") ?: JSONArray()
        for (i in 0 until arr.length()) {
            val row = arr.optJSONObject(i) ?: continue
            val serverId = row.optInt("id")
            val existing = dao.getByServerId(serverId)
            if (existing != null && (existing.dirty || existing.pendingCreate)) continue
            dao.upsert(
                OfflineUser(
                    localId = existing?.localId ?: 0,
                    serverId = serverId,
                    fullName = row.optString("full_name"),
                    phone = row.optString("phone"),
                    telegramId = row.optLong("telegram_id").takeIf { it != 0L },
                    state = row.optString("state").ifBlank { null },
                    notes = row.optString("notes").ifBlank { null },
                    isBlocked = row.optInt("is_blocked", 0) == 1,
                    isRestricted = row.optInt("is_restricted", 0) == 1,
                    baseUpdatedAt = row.optString("updated_at").ifBlank { null },
                    dirty = false,
                    pendingCreate = false,
                    lastLocalEditAt = existing?.lastLocalEditAt ?: 0L,
                )
            )
        }
        return true
    }

    private suspend fun pullCredentials(context: Context, client: ApiClient, token: String): Boolean {
        val result = client.syncPullCredentials(token)
        if (!result.ok || result.json == null) return false
        val dao = OfflineDatabase.get(context).credentialDao()
        val arr = result.json.optJSONArray("credentials") ?: JSONArray()
        for (i in 0 until arr.length()) {
            val row = arr.optJSONObject(i) ?: continue
            val serverId = row.optInt("id")
            val existing = dao.getByServerId(serverId)
            if (existing != null && (existing.dirty || existing.pendingCreate)) continue
            dao.upsert(
                OfflineCredential(
                    localId = existing?.localId ?: 0,
                    serverId = serverId,
                    username = row.optString("username"),
                    password = row.optString("password"),
                    email = row.optString("email").ifBlank { null },
                    credentialIdName = row.optString("credential_id_name").ifBlank { null },
                    status = row.optString("status").ifBlank { null },
                    notes = row.optString("notes").ifBlank { null },
                    baseUpdatedAt = row.optString("updated_at").ifBlank { null },
                    dirty = false,
                    pendingCreate = false,
                    lastLocalEditAt = existing?.lastLocalEditAt ?: 0L,
                )
            )
        }
        return true
    }

    // ---------------------------------------------------------------
    // PUSH -- send every dirty/pendingCreate row that doesn't already
    // have an unresolved conflict recorded against it (a row with an open
    // conflict is intentionally excluded from every further push attempt
    // until the admin resolves it on the Conflicts screen -- otherwise the
    // exact same conflict would just be re-reported on every sync cycle).
    // ---------------------------------------------------------------

    private suspend fun pushPending(context: Context, client: ApiClient, token: String): Boolean {
        val db = OfflineDatabase.get(context)
        val userDao = db.userDao()
        val credDao = db.credentialDao()
        val conflictDao = db.conflictDao()
        val existingConflicts = conflictDao.getAll()
        val conflictedUserLocalIds = existingConflicts.filter { it.entity == "user" }.map { it.localId }.toSet()
        val conflictedCredLocalIds = existingConflicts.filter { it.entity == "credential" }.map { it.localId }.toSet()

        val pendingUsers = userDao.getPending().filterNot { it.localId in conflictedUserLocalIds }
        val pendingCreds = credDao.getPending().filterNot { it.localId in conflictedCredLocalIds }
        if (pendingUsers.isEmpty() && pendingCreds.isEmpty()) return true

        val changes = JSONArray()
        for (u in pendingUsers) {
            val item = JSONObject()
                .put("entity", "user")
                .put("op", if (u.pendingCreate) "create" else "update")
                .put("local_id", u.localId)
            if (!u.pendingCreate) {
                item.put("server_id", u.serverId)
                item.put("base_updated_at", u.baseUpdatedAt ?: JSONObject.NULL)
            }
            item.put("fields", JSONObject()
                .put("full_name", u.fullName)
                .put("phone", u.phone)
                .put("notes", u.notes ?: JSONObject.NULL))
            changes.put(item)
        }
        for (c in pendingCreds) {
            val item = JSONObject()
                .put("entity", "credential")
                .put("op", if (c.pendingCreate) "create" else "update")
                .put("local_id", c.localId)
            if (!c.pendingCreate) {
                item.put("server_id", c.serverId)
                item.put("base_updated_at", c.baseUpdatedAt ?: JSONObject.NULL)
            }
            item.put("fields", JSONObject()
                .put("username", c.username)
                .put("password", c.password)
                .put("email", c.email ?: JSONObject.NULL)
                .put("credential_id_name", c.credentialIdName ?: JSONObject.NULL)
                .put("notes", c.notes ?: JSONObject.NULL)
                .put("status", c.status ?: "available"))
            changes.put(item)
        }

        val result = client.syncPush(token, changes)
        if (!result.ok || result.json == null) return false

        val results = result.json.optJSONArray("results") ?: JSONArray()
        for (i in 0 until results.length()) {
            val r = results.optJSONObject(i) ?: continue
            val localId = r.optLong("local_id")
            val status = r.optString("status")
            val serverRow = r.optJSONObject("server_row")

            val isUser = pendingUsers.any { it.localId == localId }
            if (isUser) {
                val row = pendingUsers.first { it.localId == localId }
                when (status) {
                    "created", "applied" -> {
                        userDao.update(row.copy(
                            serverId = serverRow?.optInt("id") ?: row.serverId,
                            baseUpdatedAt = serverRow?.optString("updated_at") ?: row.baseUpdatedAt,
                            dirty = false,
                            pendingCreate = false,
                        ))
                    }
                    "conflict" -> {
                        conflictDao.insert(SyncConflict(
                            entity = "user",
                            localId = row.localId,
                            serverId = serverRow?.optInt("id") ?: (row.serverId ?: 0),
                            localFieldsJson = JSONObject()
                                .put("full_name", row.fullName)
                                .put("phone", row.phone)
                                .put("notes", row.notes ?: "").toString(),
                            serverFieldsJson = JSONObject()
                                .put("full_name", serverRow?.optString("full_name") ?: "")
                                .put("phone", serverRow?.optString("phone") ?: "")
                                .put("notes", serverRow?.optString("notes") ?: "")
                                .put("updated_at", serverRow?.optString("updated_at") ?: "").toString(),
                            createdAt = System.currentTimeMillis(),
                        ))
                    }
                    // "error" -- leave dirty/pendingCreate as-is, retried next sync.
                }
            } else {
                val row = pendingCreds.firstOrNull { it.localId == localId } ?: continue
                when (status) {
                    "created", "applied" -> {
                        credDao.update(row.copy(
                            serverId = serverRow?.optInt("id") ?: row.serverId,
                            baseUpdatedAt = serverRow?.optString("updated_at") ?: row.baseUpdatedAt,
                            dirty = false,
                            pendingCreate = false,
                        ))
                    }
                    "conflict" -> {
                        conflictDao.insert(SyncConflict(
                            entity = "credential",
                            localId = row.localId,
                            serverId = serverRow?.optInt("id") ?: (row.serverId ?: 0),
                            localFieldsJson = JSONObject()
                                .put("username", row.username)
                                .put("password", row.password)
                                .put("notes", row.notes ?: "").toString(),
                            serverFieldsJson = JSONObject()
                                .put("username", serverRow?.optString("username") ?: "")
                                .put("password", serverRow?.optString("password") ?: "")
                                .put("notes", serverRow?.optString("notes") ?: "")
                                .put("updated_at", serverRow?.optString("updated_at") ?: "").toString(),
                            createdAt = System.currentTimeMillis(),
                        ))
                    }
                }
            }
        }
        return true
    }

    // ---------------------------------------------------------------
    // Conflict resolution -- the admin's explicit choice (per-record,
    // "ask me each time"), never automatic.
    // ---------------------------------------------------------------

    /** "Keep mine": re-arms this record for push, using the SERVER's
     * updated_at (from the conflict's own snapshot) as the new baseline --
     * so the next sync cycle's conflict check passes and the admin's
     * offline edit overwrites what changed server-side. */
    suspend fun resolveKeepLocal(context: Context, conflict: SyncConflict) {
        val db = OfflineDatabase.get(context)
        val serverFields = JSONObject(conflict.serverFieldsJson)
        val newBaseUpdatedAt = serverFields.optString("updated_at").ifBlank { null }
        if (conflict.entity == "user") {
            db.userDao().getByLocalId(conflict.localId)?.let {
                db.userDao().update(it.copy(dirty = true, baseUpdatedAt = newBaseUpdatedAt))
            }
        } else {
            db.credentialDao().getByLocalId(conflict.localId)?.let {
                db.credentialDao().update(it.copy(dirty = true, baseUpdatedAt = newBaseUpdatedAt))
            }
        }
        db.conflictDao().delete(conflict)
    }

    /** "Keep theirs": discards the offline edit entirely, overwrites the
     * local row with the server's version captured at conflict time. */
    suspend fun resolveKeepServer(context: Context, conflict: SyncConflict) {
        val db = OfflineDatabase.get(context)
        val serverFields = JSONObject(conflict.serverFieldsJson)
        if (conflict.entity == "user") {
            db.userDao().getByLocalId(conflict.localId)?.let {
                db.userDao().update(it.copy(
                    fullName = serverFields.optString("full_name", it.fullName),
                    phone = serverFields.optString("phone", it.phone),
                    notes = serverFields.optString("notes").ifBlank { null },
                    baseUpdatedAt = serverFields.optString("updated_at").ifBlank { it.baseUpdatedAt },
                    dirty = false,
                ))
            }
        } else {
            db.credentialDao().getByLocalId(conflict.localId)?.let {
                db.credentialDao().update(it.copy(
                    username = serverFields.optString("username", it.username),
                    password = serverFields.optString("password", it.password),
                    notes = serverFields.optString("notes").ifBlank { null },
                    baseUpdatedAt = serverFields.optString("updated_at").ifBlank { it.baseUpdatedAt },
                    dirty = false,
                ))
            }
        }
        db.conflictDao().delete(conflict)
    }
}
