package com.healthdataet.utdcredentials

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.healthdataet.utdcredentials.ui.theme.UtdCredentialsTheme

/**
 * Shown instead of a bare system "keeps stopping" dialog whenever
 * CrashHandler catches an uncaught exception anywhere in the app. This
 * activity's own code is deliberately as small and dependency-free as
 * possible -- no WebView, no Firebase, no WorkManager, no
 * EncryptedSharedPreferences, no navigation graph -- so a bug in any of
 * THOSE can never also take down the one screen whose entire job is to
 * still work when something else just crashed.
 */
class CrashReportActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val text = intent.getStringExtra(CrashHandler.LAST_CRASH_EXTRA)
            ?: "No crash details were captured for this run."
        setContent {
            UtdCredentialsTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    CrashReportScreen(
                        crashText = text,
                        onCopy = { copyToClipboard(text) },
                        onRestart = { restartApp() }
                    )
                }
            }
        }
    }

    private fun copyToClipboard(text: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("UTD Credentials crash log", text))
    }

    private fun restartApp() {
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }
        startActivity(intent)
        finish()
    }
}

@Composable
private fun CrashReportScreen(crashText: String, onCopy: () -> Unit, onRestart: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize().padding(20.dp)) {
        Text("Something went wrong", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        Text(
            "UTD Credentials hit an unexpected error and had to restart. The details " +
                "below are saved on this device -- copy them and send them over so this " +
                "can be fixed, then tap Restart to keep using the app.",
            style = MaterialTheme.typography.bodyMedium
        )
        Spacer(Modifier.height(16.dp))
        Surface(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            tonalElevation = 1.dp
        ) {
            SelectionContainer {
                Text(
                    crashText,
                    modifier = Modifier
                        .padding(12.dp)
                        .verticalScroll(rememberScrollState()),
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = onCopy, modifier = Modifier.weight(1f)) {
                Text("Copy details")
            }
            Button(onClick = onRestart, modifier = Modifier.weight(1f)) {
                Text("Restart app")
            }
        }
    }
}
