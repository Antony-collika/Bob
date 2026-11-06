package com.bob.ai.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.bob.ai.BobApp
import com.bob.ai.domain.AppSettings

private val LightColorScheme = lightColorScheme(
    primary = Indigo50,
    onPrimary = androidx.compose.ui.graphics.Color.White,
    primaryContainer = Indigo90,
    onPrimaryContainer = Indigo40,
    secondary = Violet40,
    onSecondary = androidx.compose.ui.graphics.Color.White,
    secondaryContainer = Violet90,
    onSecondaryContainer = Violet40,
    tertiary = Indigo40,
    background = NeutralLightBackground,
    onBackground = androidx.compose.ui.graphics.Color(0xFF1A1A24),
    surface = NeutralLightSurface,
    onSurface = androidx.compose.ui.graphics.Color(0xFF1A1A24),
    surfaceVariant = androidx.compose.ui.graphics.Color(0xFFE7E5F4),
    onSurfaceVariant = androidx.compose.ui.graphics.Color(0xFF4A4458),
    surfaceContainerHigh = androidx.compose.ui.graphics.Color(0xFFECEAF4),
    error = ErrorRed
)

private val DarkColorScheme = darkColorScheme(
    primary = Indigo80,
    onPrimary = androidx.compose.ui.graphics.Color(0xFF1E1B4B),
    primaryContainer = Indigo40,
    onPrimaryContainer = Indigo90,
    secondary = Violet80,
    onSecondary = androidx.compose.ui.graphics.Color(0xFF2E1065),
    secondaryContainer = Violet40,
    onSecondaryContainer = Violet90,
    tertiary = Indigo80,
    background = NeutralDarkBackground,
    onBackground = androidx.compose.ui.graphics.Color(0xFFE6E6F0),
    surface = NeutralDarkSurface,
    onSurface = androidx.compose.ui.graphics.Color(0xFFE6E6F0),
    surfaceVariant = androidx.compose.ui.graphics.Color(0xFF3A3A4E),
    onSurfaceVariant = androidx.compose.ui.graphics.Color(0xFFC7C5D6),
    surfaceContainerHigh = NeutralDarkSurfaceHigh,
    error = androidx.compose.ui.graphics.Color(0xFFF2B8B5)
)

/**
 * App theme. When [darkTheme] is null the saved setting from
 * [AppSettings.darkTheme] is used (null there = follow system).
 */
@Composable
fun BobTheme(
    darkTheme: Boolean? = null,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val container = remember {
        (context.applicationContext as BobApp).container
    }
    val settingsFlow = remember { container.settingsRepository.observeSettings() }
    val settings by settingsFlow.collectAsState(initial = AppSettings())
    val dark = darkTheme ?: settings.darkTheme ?: isSystemInDarkTheme()

    MaterialTheme(
        colorScheme = if (dark) DarkColorScheme else LightColorScheme,
        typography = Typography,
        content = content
    )
}
