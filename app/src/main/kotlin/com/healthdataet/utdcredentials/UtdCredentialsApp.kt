package com.healthdataet.utdcredentials

import android.app.Application
import com.healthdataet.utdcredentials.util.CrashHandler

/**
 * Custom Application so CrashHandler.install() runs before ANY Activity
 * does -- including before MainActivity.onCreate, which is where the
 * previous OEM-specific RingtoneManager crash actually happened. Installing
 * it here means the safety net is in place for literally every line of app
 * code that ever runs, not just what happens to come after MainActivity's
 * own onCreate.
 */
class UtdCredentialsApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashHandler.install(this)
    }
}
