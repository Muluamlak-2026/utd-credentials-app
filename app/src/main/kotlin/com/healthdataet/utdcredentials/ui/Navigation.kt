package com.healthdataet.utdcredentials.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.healthdataet.utdcredentials.data.SessionManager
import com.healthdataet.utdcredentials.ui.screens.FullSiteScreen
import com.healthdataet.utdcredentials.ui.screens.LoginScreen

private const val ROUTE_LOGIN = "login"
private const val ROUTE_SITE = "site"

/**
 * Two destinations only, on purpose -- the previous build's four native
 * tab screens (Dashboard/Payments/Users/Credentials) were already retired
 * in favor of just showing the full live admin site (round 20); this fresh
 * build never reintroduces them. Login handles native /api/v1/login (for
 * push + notification polling); Site is the WebView showing the real
 * panel, which is where every actual admin action still lives.
 */
@Composable
fun AppNavHost() {
    val context = LocalContext.current
    val session = remember { SessionManager(context) }
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
                }
            )
        }
    }
}
