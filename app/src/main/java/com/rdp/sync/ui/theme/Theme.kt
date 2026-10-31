package com.rdp.sync.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

// Deliberate, paired foreground/background tokens: remote content stays neutral,
// while local actions are teal. Never use accent color alone to communicate state.
internal val DarkColorScheme = darkColorScheme(
    primary = Color(0xFF7DDBC9), onPrimary = Color(0xFF00382F),
    primaryContainer = Color(0xFF005044), onPrimaryContainer = Color(0xFFA4F2E2),
    secondary = Color(0xFFB0CCC5), onSecondary = Color(0xFF1C3530),
    secondaryContainer = Color(0xFF334C46), onSecondaryContainer = Color(0xFFCCE8E0),
    tertiary = Color(0xFFAAC7F3), onTertiary = Color(0xFF113259),
    background = Color(0xFF0E1518), onBackground = Color(0xFFE1E9EC),
    surface = Color(0xFF121B1F), onSurface = Color(0xFFE1E9EC),
    surfaceVariant = Color(0xFF26353A), onSurfaceVariant = Color(0xFFB9C9CE),
    surfaceContainerLowest = Color(0xFF0A1215), surfaceContainerLow = Color(0xFF172226),
    surfaceContainer = Color(0xFF1C282D), surfaceContainerHigh = Color(0xFF263338),
    surfaceContainerHighest = Color(0xFF303E44),
    outline = Color(0xFF82979E), outlineVariant = Color(0xFF405359),
    error = Color(0xFFFFB4AB), onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A), onErrorContainer = Color(0xFFFFDAD6)
)
internal val LightColorScheme = lightColorScheme(
    primary = Color(0xFF006B5B), onPrimary = Color.White,
    primaryContainer = Color(0xFFB0F2E2), onPrimaryContainer = Color(0xFF002F27),
    secondary = Color(0xFF48665F), onSecondary = Color.White,
    secondaryContainer = Color(0xFFD0E9E1), onSecondaryContainer = Color(0xFF112E27),
    tertiary = Color(0xFF345F91), onTertiary = Color.White,
    background = Color(0xFFF3F7F8), onBackground = Color(0xFF14252B),
    surface = Color(0xFFFAFCFD), onSurface = Color(0xFF14252B),
    surfaceVariant = Color(0xFFE2ECEF), onSurfaceVariant = Color(0xFF40575F),
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFEDF3F5),
    surfaceContainer = Color(0xFFE7EFF1), surfaceContainerHigh = Color(0xFFE1EAED),
    surfaceContainerHighest = Color(0xFFD9E5E8),
    outline = Color(0xFF6D838A), outlineVariant = Color(0xFFB9CDD2),
    error = Color(0xFFBA1A1A), onError = Color.White,
    errorContainer = Color(0xFFFFDAD6), onErrorContainer = Color(0xFF410002)
)

@Composable
fun RdpSyncTheme(darkTheme: Boolean = isSystemInDarkTheme(), dynamicColor: Boolean = false, content: @Composable () -> Unit) {
    val colors = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            if (darkTheme) dynamicDarkColorScheme(LocalContext.current) else dynamicLightColorScheme(LocalContext.current)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }
    MaterialTheme(colorScheme = colors, typography = Typography, shapes = Shapes(
        extraSmall = RoundedCornerShape(8.dp), small = RoundedCornerShape(12.dp),
        medium = RoundedCornerShape(16.dp), large = RoundedCornerShape(24.dp), extraLarge = RoundedCornerShape(28.dp)
    ), content = content)
}
