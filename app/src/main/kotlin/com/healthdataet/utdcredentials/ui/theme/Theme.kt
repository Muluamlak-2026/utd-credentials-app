package com.healthdataet.utdcredentials.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.healthdataet.utdcredentials.data.AppearancePrefs

/**
 * Round 33: theme mode (system/light/dark) and accent color are both
 * user-configurable from AppSettingsScreen (AppearancePrefs).
 *
 * Round 42 fix: this used to read `AppearancePrefs(context).themeMode` /
 * `.accentKey` as plain synchronous property reads. That is NOT observable
 * by Compose -- nothing here was a State/Flow read, so this composable had
 * no invalidation signal and simply never recomposed when a value changed
 * elsewhere (e.g. from AppSettingsScreen). Only AppSettingsScreen's own
 * local preview updated, which was the reported bug ("only demo colour
 * changes in setting"). AppearancePrefs.themeMode/accentKey are now backed
 * by process-wide StateFlows (see AppearancePrefs.kt), so collecting them
 * here with collectAsState() makes this root wrapper -- and therefore the
 * whole app, since MainActivity wraps AppNavHost in this composable --
 * recompose live the instant a theme/accent choice changes, no restart
 * needed.
 */
@Composable
fun UtdCredentialsTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    // Constructing this seeds the shared StateFlows below from whatever is
    // actually persisted on disk (see AppearancePrefs's init block) -- the
    // instance itself isn't otherwise needed since accentArgb/themeLabel
    // are companion functions.
    remember { AppearancePrefs(context) }

    val themeMode by AppearancePrefs.themeModeState.collectAsState()
    val accentKey by AppearancePrefs.accentKeyState.collectAsState()
    val accent = Color(AppearancePrefs.accentArgb(accentKey))

    val isDark = when (themeMode) {
        AppearancePrefs.THEME_LIGHT -> false
        AppearancePrefs.THEME_DARK -> true
        else -> isSystemInDarkTheme()
    }
    val colors = if (isDark) darkColorScheme(primary = accent) else lightColorScheme(primary = accent)
    MaterialTheme(colorScheme = colors, content = content)
}
