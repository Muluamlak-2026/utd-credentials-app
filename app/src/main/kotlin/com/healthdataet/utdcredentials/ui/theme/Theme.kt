package com.healthdataet.utdcredentials.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val BrandPrimary = Color(0xFF6366F1)

private val DarkColors = darkColorScheme(primary = BrandPrimary)
private val LightColors = lightColorScheme(primary = BrandPrimary)

@Composable
fun UtdCredentialsTheme(content: @Composable () -> Unit) {
    val colors = if (isSystemInDarkTheme()) DarkColors else LightColors
    MaterialTheme(colorScheme = colors, content = content)
}
