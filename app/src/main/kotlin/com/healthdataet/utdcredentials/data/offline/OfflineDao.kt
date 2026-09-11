package com.healthdataet.utdcredentials.data.offline

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update

@Dao
interface OfflineUserDao {
    // Round 58: WHERE pendingDelete = 0 -- a row marked for offline deletion
    // disappears from the list immediately, even though it still physically
    // exists in this table until the delete actually syncs to the server.
    @Query("SELECT * FROM offline_users WHERE pendingDelete = 0 ORDER BY fullName")
    suspend fun getAll(): List<OfflineUser>

    @Query("SELECT * FROM offline_users WHERE localId = :localId LIMIT 1")
    suspend fun getByLocalId(localId: Long): OfflineUser?

    @Query("SELECT * FROM offline_users WHERE serverId = :serverId LIMIT 1")
    suspend fun getByServerId(serverId: Int): OfflineUser?

    @Query("SELECT * FROM offline_users WHERE dirty = 1 OR pendingCreate = 1 OR pendingDelete = 1")
    suspend fun getPending(): List<OfflineUser>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(user: OfflineUser): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(users: List<OfflineUser>)

    @Update
    suspend fun update(user: OfflineUser)

    @Delete
    suspend fun delete(user: OfflineUser)

    @Query("DELETE FROM offline_users")
    suspend fun clearAll()
}

@Dao
interface OfflineCredentialDao {
    @Query("SELECT * FROM offline_credentials WHERE pendingDelete = 0 ORDER BY username")
    suspend fun getAll(): List<OfflineCredential>

    @Query("SELECT * FROM offline_credentials WHERE localId = :localId LIMIT 1")
    suspend fun getByLocalId(localId: Long): OfflineCredential?

    @Query("SELECT * FROM offline_credentials WHERE serverId = :serverId LIMIT 1")
    suspend fun getByServerId(serverId: Int): OfflineCredential?

    @Query("SELECT * FROM offline_credentials WHERE dirty = 1 OR pendingCreate = 1 OR pendingDelete = 1")
    suspend fun getPending(): List<OfflineCredential>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(credential: OfflineCredential): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(credentials: List<OfflineCredential>)

    @Update
    suspend fun update(credential: OfflineCredential)

    @Delete
    suspend fun delete(credential: OfflineCredential)

    @Query("DELETE FROM offline_credentials")
    suspend fun clearAll()
}

@Dao
interface SyncConflictDao {
    @Query("SELECT * FROM sync_conflicts ORDER BY createdAt")
    suspend fun getAll(): List<SyncConflict>

    @Query("SELECT COUNT(*) FROM sync_conflicts")
    suspend fun count(): Int

    @Insert
    suspend fun insert(conflict: SyncConflict): Long

    @Delete
    suspend fun delete(conflict: SyncConflict)
}
