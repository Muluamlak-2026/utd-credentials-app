package com.healthdataet.utdcredentials.data.offline

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/**
 * Round 58: a small Compose-friendly "is this phone online right now"
 * signal, used by FullSiteScreen to show a persistent "you're offline --
 * edit offline instead" banner over the live admin panel WebView, keyed
 * off REAL connectivity state rather than only reacting after the WebView
 * itself fails to load a page (which can lag behind an actual
 * disconnect/reconnect, or never fire again once a page is already
 * showing cached content). Separate from ConnectivitySyncTrigger (which
 * reacts to the SAME kind of event to kick off a sync) -- this one is
 * purely a read-only UI signal, registered/unregistered with this
 * composable's own lifecycle rather than the whole app's.
 */
@Composable
fun rememberIsOnline(): State<Boolean> {
    val context = LocalContext.current
    val isOnline = remember {
        mutableStateOf(currentlyOnline(context))
    }

    DisposableEffect(Unit) {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                isOnline.value = true
            }

            override fun onLost(network: Network) {
                // Re-check via the manager rather than assuming false --
                // another network (e.g. Wi-Fi lost but mobile data still
                // up) may still be usable.
                isOnline.value = currentlyOnline(context)
            }
        }
        try {
            cm?.registerDefaultNetworkCallback(callback)
        } catch (e: Exception) {
            // Some OEM/older devices throw on registration -- the initial
            // value above still reflects a one-time check either way.
        }
        onDispose {
            try {
                cm?.unregisterNetworkCallback(callback)
            } catch (e: Exception) {
                // Already unregistered, or never successfully registered.
            }
        }
    }

    return isOnline
}

private fun currentlyOnline(context: Context): Boolean {
    return try {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val network = cm?.activeNetwork ?: return false
        val capabilities = cm.getNetworkCapabilities(network) ?: return false
        capabilities.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
    } catch (e: Exception) {
        true // fail open -- never show a wrong "offline" banner from a lookup error alone
    }
}
