package com.healthdataet.utdcredentials.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Row of filled/empty dots showing how many of [maxLength] digits have
 * been entered so far -- the standard PIN-entry visual, used by both
 * SecuritySettingsScreen (setting/confirming a new PIN) and LockScreen
 * (verifying it).
 */
@Composable
fun PinDots(enteredLength: Int, maxLength: Int, modifier: Modifier = Modifier) {
    Row(modifier = modifier, horizontalArrangement = Arrangement.Center) {
        for (i in 0 until maxLength) {
            Box(
                modifier = Modifier
                    .padding(6.dp)
                    .size(14.dp)
                    .background(
                        color = if (i < enteredLength) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.outlineVariant,
                        shape = CircleShape
                    )
            )
        }
    }
}

/**
 * A standard 3x4 numeric keypad (1-9, blank, 0, backspace). Stateless --
 * the caller owns the entered-digits string and decides when a PIN is
 * "complete" (e.g. at 4 or 6 digits).
 */
@Composable
fun NumericKeypad(onDigit: (Char) -> Unit, onBackspace: () -> Unit, modifier: Modifier = Modifier) {
    val rows = listOf(
        listOf('1', '2', '3'),
        listOf('4', '5', '6'),
        listOf('7', '8', '9')
    )
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        rows.forEach { row ->
            Row {
                row.forEach { digit ->
                    KeypadButton(label = digit.toString(), onClick = { onDigit(digit) })
                }
            }
        }
        Row {
            Box(modifier = Modifier.size(72.dp)) // empty bottom-left cell
            KeypadButton(label = "0", onClick = { onDigit('0') })
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .clickable { onBackspace() },
                contentAlignment = Alignment.Center
            ) {
                Text("⌫", style = MaterialTheme.typography.headlineSmall)
            }
        }
    }
}

@Composable
private fun KeypadButton(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(72.dp)
            .padding(6.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant, CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(label, style = MaterialTheme.typography.headlineSmall)
    }
}
