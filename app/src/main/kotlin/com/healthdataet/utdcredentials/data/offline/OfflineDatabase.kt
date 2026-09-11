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
    version = 1,
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
                ).build().also { instance = it }
            }
    }
}
