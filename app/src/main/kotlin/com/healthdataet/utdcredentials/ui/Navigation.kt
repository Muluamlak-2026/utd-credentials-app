package com.healthdataet.utdcredentials.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.healthdataet.utdcredentials.data.AppLockPrefs
import com.healthdataet.utdcredentials.data.SessionManager
import com.healthdataet.utdcredentials.ui.screens.AppSettingsScreen
import com.healthdataet.utdcredentials.ui.screens.CrashLogScreen
import com.healthdataet.utdcredentials.ui.screens.FullSiteScreen
import com.healthdataet.utdcredentials.ui.screens.LockScreen
import com.healthdataet.utdcredentials.ui.screens.LoginScreen
import com.healthdataet.utdcredentials.ui.screens.SecuritySettingsScreen
import com.healthdataet.utdcredentials.ui.screens.SoundSettingsScreen

private const val ROUTE_LOGIN = "login"
private const val ROUTE_SITE = "site"
private const val ROUTE_APP_SETTINGS = "app_settings"
private const val ROUTE_SOUNDS = "sounds"
private const val ROUTE_SECURITY = "security"
private const val ROUTE_DIAGNOSTICS = "diagnostics"

/**
 * Destinations under the actual nav graph, plus a lock gate that sits
 * IN FRONT of all of them (round 32's app-lock feature) -- Login handles
 * native /api/v1/login (for push + notification polling); Site is the
 * WebView showing the real panel, which is where every actual admin
 * action still lives; App Settings, Sounds/Notifications and Security are
 * the genuinely native screens this app needs (per-channel notification
 * on/off + sounds, theme/accent appearance, and the PIN/pattern/biometric
 * app lock can't be done from inside a WebView) -- reached from the one
 * gear icon on Site's top bar (round 33: previously two separate icons).
 *
 * The lock gate is intentionally OUTSIDE the NavHost's own back stack: it
 * is re-armed every time the app returns from the background (whatever
 * screen was showing before), not just on cold start, by resetting
 * [unlocked] to false on Lifecycle.Event.ON_STOP whenever a lock method is
 * configured.
 */
@Composable
fun AppNavHost() {
    val context = LocalContext.current
    val session = remember { SessionManager(context) }
    val lockPrefs = remember { AppLockPrefs(context) }
    var unlocked by remember { mutableStateOf(!lockPrefs.isLockEnabled) }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP && lockPrefs.isLockEnabled) {
                unlocked = false
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    if (lockPrefs.isLockEnabled && !unlocked) {
        LockScreen(lockPrefs = lockPrefs, onUnlocked = { unlocked = true })
        return
    }

    val navController: NavHostController = rememberNavController()
    val startDestination = if (session.isLoggedIn) ROUTE_SITE else ROUTE_LOGIN

    NavHost(navController = navController, startDestination = startDestination) {
        composable(ROUTE_LOGIN) {
            LoginScreen(
                session = session,
                onLoggedIn = {
                    navController.navigate(ROUTE_SITE) {
                        popUpTo(ROUTE_LOGIN) { inclusive = true }
                    }
                }
            )
        }
        composable(ROUTE_SITE) {
            FullSiteScreen(
                session = session,
                onLoggedOut = {
                    navController.navigate(ROUTE_LOGIN) {
                        popUpTo(ROUTE_SITE) { inclusive = true }
                    }
                },
                onOpenAppSettings = { navController.navigate(ROUTE_APP_SETTINGS) }
            )
        }
        composable(ROUTE_APP_SETTINGS) {
            AppSettingsScreen(
                onBack = { navController.popBackStack() },
                onOpenNotifications = { navController.navigate(ROUTE_SOUNDS) },
                onOpenSecurity = { navController.navigate(ROUTE_SECURITY) },
                onOpenDiagnostics = { navController.navigate(ROUTE_DIAGNOSTICS) }
            )
        }
        composable(ROUTE_SOUNDS) {
            SoundSettingsScreen(onBack = { navController.popBackStack() })
        }
        composable(ROUTE_SECURITY) {
            SecuritySettingsScreen(onBack = { navController.popBackStack() })
        }
        composable(ROUTE_DIAGNOSTICS) {
            CrashLogScreen(onBack = { navController.popBackStack() })
        }
    }
}
