package com.healthdataet.utdcredentials.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.healthdataet.utdcredentials.data.AppearancePrefs

/**
 * Round 33: the app-wide settings hub -- notifications (per-category
 * enable + sound), appearance (theme + accent color -- see AppearancePrefs
 * for why this replaces literal "brightness"/"app logo" controls), and
 * security (site password + app lock) all in one place, reached from
 * FullSiteScreen's top-bar gear icon.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppSettingsScreen(
    onBack: () -> Unit,
    onOpenNotifications: () -> Unit,
    onOpenSecurity: () -> Unit,
    onOpenDiagnostics: () -> Unit
) {
    val context = LocalContext.current
    val appearancePrefs = remember { AppearancePrefs(context) }
    var refreshTick by remember { mutableStateOf(0) }
    val themeMode = remember(refreshTick) { appearancePrefs.themeMode }
    val accentKey = remember(refreshTick) { appearancePrefs.accentKey }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("App Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            SettingsLinkRow(
                title = "Notifications",
                subtitle = "Choose which alerts ring, and pick each one's sound",
                onClick = onOpenNotifications
            )
            HorizontalDivider()
            SettingsLinkRow(
                title = "Security",
                subtitle = "Site password auto-fill, PIN / pattern / fingerprint app lock",
                onClick = onOpenSecurity
            )
            HorizontalDivider()
            SettingsLinkRow(
                title = "Diagnostics",
                subtitle = "View saved crash logs, if this app has ever run into a problem",
                onClick = onOpenDiagnostics
            )
            HorizontalDivider()

            Spacer(Modifier.height(20.dp))
            Text("Appearance", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))
            Text(
                "Theme and accent color, applied instantly across the app.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(12.dp))

            Text("Theme", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AppearancePrefs.THEME_ORDER.forEach { mode ->
                    ChoiceChip(
                        label = AppearancePrefs.themeLabel(mode),
                        selected = mode == themeMode,
                        onClick = { appearancePrefs.themeMode = mode; refreshTick++ }
                    )
                }
            }

            Spacer(Modifier.height(20.dp))
            Text("Accent Color", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                AppearancePrefs.ACCENT_ORDER.forEach { key ->
                    val swatch = Color(AppearancePrefs.accentArgb(key))
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        val selected = key == accentKey
                        androidx.compose.foundation.layout.Box(
                            modifier = Modifier
                                .size(if (selected) 40.dp else 32.dp)
                                .background(swatch, CircleShape)
                                .clickable { appearancePrefs.accentKey = key; refreshTick++ }
                        )
                        Text(
                            AppearancePrefs.accentLabel(key),
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
            Text(
                "Note: this app deliberately doesn't override your phone's overall " +
                    "screen brightness or its home-screen launcher icon -- those need a " +
                    "separate special permission / configuration that's easy to get " +
                    "wrong on a niche admin app, so they're left to your phone's own " +
                    "Settings and Home screen instead.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun SettingsLinkRow(title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 14.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(Icons.Filled.ArrowForward, contentDescription = null)
    }
}

@Composable
private fun ChoiceChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .background(
                if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Text(
            label,
            color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelLarge
        )
    }
}
