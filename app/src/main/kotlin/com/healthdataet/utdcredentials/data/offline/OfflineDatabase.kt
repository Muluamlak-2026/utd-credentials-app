package com.healthdataet.utdcredentials.data.offline

import android.content.Context
import androidx.room.Database
import androidx.room.RoomDatabase

/**
 * Round 57: the phone's local offline database -- users + credentials
 * mirrors, plus the unresolved-conflicts table. A plain process-wide
 * singleton (same lifetime/visibility as SessionManager's SharedPreferences
 * file), built once and reused everywhere via [OfflineDatabase.get].
 */
@Database(
    entities = [OfflineUser::class, OfflineCredential::class, SyncConflict::class],
    // Round 58: bumped 1 -> 2 for the new pendingDelete column (offline
    // delete). fallbackToDestructiveMigration() below (rather than a real
    // Migration) is deliberate and safe here: this table is a MIRROR of
    // the server's users/credentials, rebuilt by the very next sync either
    // way -- the only thing an old install could lose on this one-time
    // schema bump is a not-yet-synced offline edit/add sitting on the
    // phone at the exact moment it updates, which is an acceptable
    // one-time reset for a version bump, versus the ongoing cost of a
    // hand-written column migration for a purely-local cache table.
    version = 2,
    exportSchema = false
)
abstract class OfflineDatabase : RoomDatabase() {
    abstract fun userDao(): OfflineUserDao
    abstract fun credentialDao(): OfflineCredentialDao
    abstract fun conflictDao(): SyncConflictDao

    companion object {
        @Volatile
        private var instance: OfflineDatabase? = null

        fun get(context: Context): OfflineDatabase =
            instance ?: synchronized(this) {
                instance ?: androidx.room.Room.databaseBuilder(
                    context.applicationContext,
                    OfflineDatabase::class.java,
                    "utd_credentials_offline.db"
                ).fallbackToDestructiveMigration().build().also { instance = it }
            }
    }
}
