package com.healthdataet.utdcredentials.ui.screens

import androidx.biometric.BiometricManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.healthdataet.utdcredentials.data.AppLockPrefs
import com.healthdataet.utdcredentials.data.SiteCredsStore
import com.healthdataet.utdcredentials.ui.components.NumericKeypad
import com.healthdataet.utdcredentials.ui.components.PatternLock
import com.healthdataet.utdcredentials.ui.components.PinDots

private const val PIN_LENGTH = 4
private enum class SetupStep { NONE, PIN_FIRST, PIN_CONFIRM, PATTERN_FIRST, PATTERN_CONFIRM }

/**
 * Round 32: site-password management ("Add site Password save feature")
 * and app-lock setup ("pattern, PIN, and fingerprint, all switchable")
 * live together on this one screen, reached via the new lock icon on
 * FullSiteScreen's top bar.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SecuritySettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val lockPrefs = remember { AppLockPrefs(context) }
    val siteCredsStore = remember { SiteCredsStore(context) }
    var refreshTick by remember { mutableStateOf(0) }

    val currentMethod = remember(refreshTick) { lockPrefs.lockMethod }
    val biometricOn = remember(refreshTick) { lockPrefs.biometricEnabled }
    val hasSavedSitePassword = remember(refreshTick) { siteCredsStore.hasSaved }
    val biometricAvailable = remember {
        BiometricManager.from(context).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK) ==
            BiometricManager.BIOMETRIC_SUCCESS
    }

    var step by remember { mutableStateOf(SetupStep.NONE) }
    var pendingPin by remember { mutableStateOf("") }
    var enteredPin by remember { mutableStateOf("") }
    var pendingPattern by remember { mutableStateOf(listOf<Int>()) }
    var setupError by remember { mutableStateOf<String?>(null) }

    fun resetSetup() {
        step = SetupStep.NONE
        pendingPin = ""
        enteredPin = ""
        pendingPattern = emptyList()
        setupError = null
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Security") },
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
            Text("Site Password", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))
            Text(
                if (hasSavedSitePassword)
                    "Your admin panel login is saved -- the web view fills it in automatically every time."
                else
                    "No saved login yet. Check \"Save password\" the next time you log in to enable this.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (hasSavedSitePassword) {
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = {
                    siteCredsStore.clear()
                    refreshTick++
                }) {
                    Text("Forget saved password")
                }
            }

            Spacer(Modifier.height(24.dp))
            HorizontalDivider()
            Spacer(Modifier.height(24.dp))

            Text("App Lock", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))
            Text(
                "Require a PIN or pattern (with fingerprint as a fast unlock on top, if available) before the app opens.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(12.dp))

            if (step == SetupStep.NONE) {
                Text(
                    "Current method: " + when (currentMethod) {
                        AppLockPrefs.METHOD_PIN -> "PIN"
                        AppLockPrefs.METHOD_PATTERN -> "Pattern"
                        else -> "None"
                    },
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { resetSetup(); step = SetupStep.PIN_FIRST }) { Text("Set PIN") }
                    Button(onClick = { resetSetup(); step = SetupStep.PATTERN_FIRST }) { Text("Set Pattern") }
                }
                if (currentMethod != AppLockPrefs.METHOD_NONE) {
                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = {
                        lockPrefs.disableLock()
                        refreshTick++
                    }) {
                        Text("Turn off App Lock")
                    }

                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Unlock with fingerprint", style = MaterialTheme.typography.bodyMedium)
                            if (!biometricAvailable) {
                                Text(
                                    "No fingerprint enrolled on this device.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        Switch(
                            checked = biometricOn,
                            enabled = biometricAvailable,
                            onCheckedChange = { checked ->
                                lockPrefs.biometricEnabled = checked
                                refreshTick++
                            }
                        )
                    }
                }
            } else {
                // ── PIN setup: enter once, then confirm ──
                if (step == SetupStep.PIN_FIRST || step == SetupStep.PIN_CONFIRM) {
                    val target = if (step == SetupStep.PIN_FIRST) pendingPin else enteredPin
                    Text(
                        if (step == SetupStep.PIN_FIRST) "Choose a $PIN_LENGTH-digit PIN" else "Confirm your PIN",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(Modifier.height(16.dp))
                    PinDots(enteredLength = target.length, maxLength = PIN_LENGTH)
                    Spacer(Modifier.height(16.dp))
                    NumericKeypad(
                        onDigit = { digit ->
                            if (step == SetupStep.PIN_FIRST) {
                                if (pendingPin.length < PIN_LENGTH) pendingPin += digit
                                if (pendingPin.length == PIN_LENGTH) step = SetupStep.PIN_CONFIRM
                            } else {
                                if (enteredPin.length < PIN_LENGTH) enteredPin += digit
                                if (enteredPin.length == PIN_LENGTH) {
                                    if (enteredPin == pendingPin) {
                                        lockPrefs.setPin(pendingPin)
                                        refreshTick++
                                        resetSetup()
                                    } else {
                                        setupError = "PINs didn't match -- try again."
                                        pendingPin = ""
                                        enteredPin = ""
                                        step = SetupStep.PIN_FIRST
                                    }
                                }
                            }
                        },
                        onBackspace = {
                            if (step == SetupStep.PIN_FIRST && pendingPin.isNotEmpty()) pendingPin = pendingPin.dropLast(1)
                            if (step == SetupStep.PIN_CONFIRM && enteredPin.isNotEmpty()) enteredPin = enteredPin.dropLast(1)
                        }
                    )
                }

                // ── Pattern setup: draw once, then confirm ──
                if (step == SetupStep.PATTERN_FIRST || step == SetupStep.PATTERN_CONFIRM) {
                    Text(
                        if (step == SetupStep.PATTERN_FIRST) "Draw a new pattern" else "Draw it again to confirm",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(Modifier.height(16.dp))
                    PatternLock(modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp)) { nodes ->
                        if (step == SetupStep.PATTERN_FIRST) {
                            if (nodes.size < 4) {
                                setupError = "Connect at least 4 dots."
                            } else {
                                pendingPattern = nodes
                                setupError = null
                                step = SetupStep.PATTERN_CONFIRM
                            }
                        } else {
                            if (nodes == pendingPattern) {
                                lockPrefs.setPattern(pendingPattern)
                                refreshTick++
                                resetSetup()
                            } else {
                                setupError = "Patterns didn't match -- try again."
                                pendingPattern = emptyList()
                                step = SetupStep.PATTERN_FIRST
                            }
                        }
                    }
                }

                if (setupError != null) {
                    Spacer(Modifier.height(12.dp))
                    Text(setupError ?: "", color = MaterialTheme.colorScheme.error)
                }
                Spacer(Modifier.height(12.dp))
                TextButton(onClick = { resetSetup() }) { Text("Cancel") }
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}
