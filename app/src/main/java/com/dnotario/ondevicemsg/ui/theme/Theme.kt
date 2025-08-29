package com.dnotario.ondevicemsg.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

private val DarkColorScheme = darkColorScheme(
    primary = Primary,
    secondary = Secondary,
    tertiary = Secondary,
    background = Background,
    surface = Surface,
    surfaceVariant = SurfaceVariant,
    onPrimary = OnPrimary,
    onSecondary = OnSecondary,
    onBackground = OnBackground,
    onSurface = OnSurface,
    onSurfaceVariant = OnSurfaceVariant
)

private val LightColorScheme = darkColorScheme(  // Force dark theme always
    primary = Primary,
    secondary = Secondary,
    tertiary = Secondary,
    background = Background,
    surface = Surface,
    surfaceVariant = SurfaceVariant,
    onPrimary = OnPrimary,
    onSecondary = OnSecondary,
    onBackground = OnBackground,
    onSurface = OnSurface,
    onSurfaceVariant = OnSurfaceVariant
)

@Composable
fun OndevicemsgTheme(
    darkTheme: Boolean = true,  // Always dark
    // Dynamic color is available on Android 12+
    dynamicColor: Boolean = false,  // Disable dynamic colors
    content: @Composable () -> Unit
) {
    val colorScheme = DarkColorScheme  // Always use our custom dark theme

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}