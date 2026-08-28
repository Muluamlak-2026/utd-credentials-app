package com.healthdataet.utdcredentials.ui.screens

import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.healthdataet.utdcredentials.data.AppLockPrefs
import com.healthdataet.utdcredentials.ui.components.NumericKeypad
import com.healthdataet.utdcredentials.ui.components.PatternLock
import com.healthdataet.utdcredentials.ui.components.PinDots

/**
 * The gate shown before any of the app's real content whenever a lock
 * method is configured -- see AppNavHost, which shows this instead of the
 * normal NavHost until [onUnlocked] fires, and re-arms it every time the
 * app returns from the background. Whichever method is configured (PIN or
 * pattern) is the required fallback; biometric, if also enabled, is
 * offered as a faster alternative on top of it -- never a replacement,
 * matching how professional password-manager/banking apps combine the two.
 */
private const val PIN_LENGTH = 4

@Composable
fun LockScreen(lockPrefs: AppLockPrefs, onUnlocked: () -> Unit) {
    val context = LocalContext.current
    var pin by remember { mutableStateOf("") }
    var errorText by remember { mutableStateOf<String?>(null) }
    var biometricAutoTried by remember { mutableStateOf(false) }

    fun tryBiometric() {
        val activity = context as? FragmentActivity ?: return
        val manager = BiometricManager.from(context)
        if (manager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK) != BiometricManager.BIOMETRIC_SUCCESS) {
            return
        }
        val executor = ContextCompat.getMainExecutor(context)
        val prompt = BiometricPrompt(activity, executor, object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                onUnlocked()
            }
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                // Silently fall back to PIN/pattern entry -- no dead-end
                // error state, the manual method is always right there.
            }
        })
        val fallbackLabel = if (lockPrefs.lockMethod == AppLockPrefs.METHOD_PIN) "PIN" else "Pattern"
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Unlock UTD Credentials")
            .setSubtitle("Use your fingerprint to continue")
            .setNegativeButtonText("Use $fallbackLabel instead")
            .build()
        prompt.authenticate(info)
    }

    // Offer biometric immediately on showing the gate, once per gate
    // appearance -- the admin can still back out to PIN/pattern via the
    // prompt's own negative button or by just starting to type/draw.
    LaunchedEffect(Unit) {
        if (lockPrefs.biometricEnabled && !biometricAutoTried) {
            biometricAutoTried = true
            tryBiometric()
        }
    }

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text("UTD Credentials", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(8.dp))
            Text(
                if (lockPrefs.lockMethod == AppLockPrefs.METHOD_PIN) "Enter your PIN" else "Draw your pattern",
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(Modifier.height(24.dp))

            if (lockPrefs.lockMethod == AppLockPrefs.METHOD_PATTERN) {
                PatternLock(modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp)) { nodes ->
                    if (lockPrefs.verifyPattern(nodes)) {
                        errorText = null
                        onUnlocked()
                    } else {
                        errorText = "Incorrect pattern -- try again."
                    }
                }
            } else {
                PinDots(enteredLength = pin.length, maxLength = PIN_LENGTH)
                Spacer(Modifier.height(24.dp))
                NumericKeypad(
                    onDigit = { digit ->
                        if (pin.length < PIN_LENGTH) {
                            pin += digit
                            if (pin.length == PIN_LENGTH) {
                                if (lockPrefs.verifyPin(pin)) {
                                    errorText = null
                                    onUnlocked()
                                } else {
                                    errorText = "Incorrect PIN -- try again."
                                    pin = ""
                                }
                            }
                        }
                    },
                    onBackspace = { if (pin.isNotEmpty()) pin = pin.dropLast(1) }
                )
            }

            if (errorText != null) {
                Spacer(Modifier.height(16.dp))
                Text(errorText ?: "", color = MaterialTheme.colorScheme.error)
            }

            if (lockPrefs.biometricEnabled) {
                Spacer(Modifier.height(20.dp))
                TextButton(onClick = { tryBiometric() }) {
                    Text("Use fingerprint instead")
                }
            }
        }
    }
}
