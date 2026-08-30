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
import com.healthdataet.utdcredentials.ui.screens.CredentialPickerScreen
import com.healthdataet.utdcredentials.ui.screens.FullSiteScreen
import com.healthdataet.utdcredentials.ui.screens.LockScreen
import com.healthdataet.utdcredentials.ui.screens.LoginScreen
import com.healthdataet.utdcredentials.ui.screens.SecuritySettingsScreen
import com.healthdataet.utdcredentials.ui.screens.SequentialLoginScreen
import com.healthdataet.utdcredentials.ui.screens.SoundSettingsScreen
import com.healthdataet.utdcredentials.ui.screens.UpToDateLoginScreen

private const val ROUTE_LOGIN = "login"
private const val ROUTE_SITE = "site"
private const val ROUTE_APP_SETTINGS = "app_settings"
private const val ROUTE_SOUNDS = "sounds"
private const val ROUTE_SECURITY = "security"
private const val ROUTE_DIAGNOSTICS = "diagnostics"
// Round 48i: the UpToDate quick-login feature -- pick a stored credential
// (ROUTE_CREDENTIAL_PICKER), either open it once in a fill-only WebView
// (ROUTE_UPTODATE_LOGIN) or run several through an unattended sequential
// login-and-report batch (ROUTE_SEQUENTIAL_LOGIN, added per the follow-up
// request for "sequential auto login, log out with successful or failure
// report of login for each pair of credentials").
private const val ROUTE_CREDENTIAL_PICKER = "credential_picker"
private const val ROUTE_UPTODATE_LOGIN = "uptodate_login"
private const val ROUTE_SEQUENTIAL_LOGIN = "sequential_login"

/** Round 47b: how long the app stays unlocked after being backgrounded
 * (switched to another app, or minimized) before the lock gate re-arms.
 * Previously this was 0 -- every single ON_STOP re-locked instantly, so
 * even a brief switch to paste a code from an SMS app, or the system
 * simply re-maximizing the task, forced a fresh PIN/pattern/fingerprint
 * every time. */
private const val LOCK_GRACE_PERIOD_MS = 3 * 60 * 1000L

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
 * screen was showing before) -- not on every single ON_STOP any more
 * (round 47b), but only once the app has actually been away for at least
 * [LOCK_GRACE_PERIOD_MS].
 *
 * Round 48: [AppLockPrefs.lastBackgroundedAt] (persisted to disk) is what
 * actually records when the app left, not an in-memory Compose var -- many
 * phones' battery managers kill the whole process within seconds of it
 * being backgrounded, not just stop the Activity. An in-memory timestamp
 * is wiped out by that kill, so the very next launch had no way to know
 * any time had passed at all and always re-locked instantly regardless of
 * how briefly the app was actually away -- which is exactly the "locks
 * immediately every time" symptom this persisted version fixes. [unlocked]'s
 * OWN initial value is computed from that persisted timestamp too, so a
 * fresh process (after a kill) that's still within the grace window comes
 * back up already unlocked instead of re-showing the lock screen.
 */
@Composable
fun AppNavHost() {
    val context = LocalContext.current
    val session = remember { SessionManager(context) }
    val lockPrefs = remember { AppLockPrefs(context) }
    var unlocked by remember {
        val withinGrace = lockPrefs.lastBackgroundedAt != 0L &&
            (System.currentTimeMillis() - lockPrefs.lastBackgroundedAt) < LOCK_GRACE_PERIOD_MS
        mutableStateOf(!lockPrefs.isLockEnabled || withinGrace)
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> {
                    if (lockPrefs.isLockEnabled) {
                        lockPrefs.lastBackgroundedAt = System.currentTimeMillis()
                    }
                }
                Lifecycle.Event.ON_START -> {
                    val lastBg = lockPrefs.lastBackgroundedAt
                    if (lockPrefs.isLockEnabled && lastBg != 0L) {
                        val awayMs = System.currentTimeMillis() - lastBg
                        if (awayMs >= LOCK_GRACE_PERIOD_MS) {
                            unlocked = false
                        }
                        lockPrefs.lastBackgroundedAt = 0L
                    }
                }
                else -> {}
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
                onOpenDiagnostics = { navController.navigate(ROUTE_DIAGNOSTICS) },
                onOpenCredentialLogin = { navController.navigate(ROUTE_CREDENTIAL_PICKER) }
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
        composable(ROUTE_CREDENTIAL_PICKER) {
            CredentialPickerScreen(
                session = session,
                onBack = { navController.popBackStack() },
                onCredentialChosen = { navController.navigate(ROUTE_UPTODATE_LOGIN) },
                onRunSequential = { navController.navigate(ROUTE_SEQUENTIAL_LOGIN) }
            )
        }
        composable(ROUTE_UPTODATE_LOGIN) {
            UpToDateLoginScreen(onBack = { navController.popBackStack() })
        }
        composable(ROUTE_SEQUENTIAL_LOGIN) {
            SequentialLoginScreen(onBack = { navController.popBackStack() })
        }
    }
}
