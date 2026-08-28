package com.healthdataet.utdcredentials.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.healthdataet.utdcredentials.data.AppearancePrefs

/**
 * Round 33: theme mode (system/light/dark) and accent color are both
 * user-configurable from AppSettingsScreen (AppearancePrefs) -- read fresh
 * on every recomposition of the root so a change there is reflected the
 * moment the admin backs out of that screen, no restart needed.
 */
@Composable
fun UtdCredentialsTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val prefs = AppearancePrefs(context)
    val accent = Color(AppearancePrefs.accentArgb(prefs.accentKey))

    val isDark = when (prefs.themeMode) {
        AppearancePrefs.THEME_LIGHT -> false
        AppearancePrefs.THEME_DARK -> true
        else -> isSystemInDarkTheme()
    }
    val colors = if (isDark) darkColorScheme(primary = accent) else lightColorScheme(primary = accent)
    MaterialTheme(colorScheme = colors, content = content)
}
