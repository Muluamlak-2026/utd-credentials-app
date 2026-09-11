package com.healthdataet.utdcredentials.data.offline

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import com.healthdataet.utdcredentials.data.SessionManager
import com.healthdataet.utdcredentials.push.SyncForegroundService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Round 57: "synchronise to the site immediately upon reconnection" --
 * registered once from UtdCredentialsApp.onCreate (process-wide, so it
 * catches reconnection even if the app is only in the background, not
 * fully closed) via ConnectivityManager.registerNetworkCallback. The
 * moment any network with real internet capability becomes available,
 * this starts SyncForegroundService (which itself no-ops quickly if
 * there's nothing pending) -- there's no polling/timer involved here at
 * all, this is the OS telling the app the instant connectivity actually
 * changes.
 */
object ConnectivitySyncTrigger {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var registered = false

    fun register(context: Context) {
        if (registered) return
        val appContext = context.applicationContext
        val manager = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return

        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            .build()

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                scope.launch {
                    try {
                        val session = SessionManager(appContext)
                        if (session.isLoggedIn && SyncRepository.hasPendingChanges(appContext)) {
                            SyncForegroundService.start(appContext)
                        }
                    } catch (e: Exception) {
                        // Never let a connectivity callback crash the process.
                    }
                }
            }
        }

        try {
            manager.registerNetworkCallback(request, callback)
            registered = true
        } catch (e: Exception) {
            // Some OEM builds restrict this -- the periodic SyncWorker
            // backstop still covers pending changes either way.
        }
    }
}
