package com.healthdataet.utdcredentials.data.offline

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Round 57: the phone's own local mirror of the `users` table, so the
 * admin can view/add/edit clients with NO network at all. [serverId] is
 * null only for a row created offline that has never yet been pushed to
 * the server; every row pulled down from /api/v1/sync/users already has
 * one. [baseUpdatedAt] is the exact server `updated_at` this row's data
 * was last known to match -- the conflict-detection baseline sent back on
 * the next push (see admin/api_routes.py's sync_push doc comment for the
 * full reasoning). [dirty] marks a row with local edits not yet
 * successfully pushed; [pendingCreate] marks a row that was created
 * offline and has no server counterpart yet.
 */
@Entity(tableName = "offline_users")
data class OfflineUser(
    @PrimaryKey(autoGenerate = true) val localId: Long = 0,
    val serverId: Int? = null,
    val fullName: String,
    val phone: String,
    val telegramId: Long? = null,
    val state: String? = null,
    val notes: String? = null,
    val isBlocked: Boolean = false,
    val isRestricted: Boolean = false,
    val baseUpdatedAt: String? = null,
    val dirty: Boolean = false,
    val pendingCreate: Boolean = false,
    val lastLocalEditAt: Long = 0L,
)

/** Round 57: local mirror of the `credentials` table -- same shape/purpose
 * as [OfflineUser] above. */
@Entity(tableName = "offline_credentials")
data class OfflineCredential(
    @PrimaryKey(autoGenerate = true) val localId: Long = 0,
    val serverId: Int? = null,
    val username: String,
    val password: String,
    val email: String? = null,
    val credentialIdName: String? = null,
    val status: String? = null,
    val notes: String? = null,
    val baseUpdatedAt: String? = null,
    val dirty: Boolean = false,
    val pendingCreate: Boolean = false,
    val lastLocalEditAt: Long = 0L,
)

/**
 * Round 57: one unresolved sync conflict -- a push landed on a record that
 * had ALSO changed server-side since this phone last saw it (see
 * admin/api_routes.py.sync_push). Kept in its own table (rather than just
 * an in-memory list) so a conflict survives the app being closed/killed
 * before the admin gets around to resolving it -- per the admin's explicit
 * "ask me each time" choice, nothing here is ever auto-resolved.
 * [localFieldsJson]/[serverFieldsJson] are small flat JSON objects (field
 * name -> string value) so the Conflict screen can show a plain "yours vs.
 * theirs" comparison without needing separate typed columns per entity.
 */
@Entity(tableName = "sync_conflicts")
data class SyncConflict(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val entity: String, // "user" | "credential"
    val localId: Long,
    val serverId: Int,
    val localFieldsJson: String,
    val serverFieldsJson: String,
    val createdAt: Long,
)
