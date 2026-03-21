package com.chunkymonkey.chess.tournament.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val Navy = Color(0xFF1B3A5C)
private val NavyLight = Color(0xFF4A6FA5)
private val Gold = Color(0xFFD4A843)
private val GoldLight = Color(0xFFF5DEB3)
private val Teal = Color(0xFF2E7D6F)
private val Surface = Color(0xFFFAFAFA)
private val OnSurface = Color(0xFF1C1B1F)

private val LightColorScheme = lightColorScheme(
    primary = Navy,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD1E4FF),
    onPrimaryContainer = Color(0xFF001D36),
    secondary = Gold,
    onSecondary = Color.White,
    secondaryContainer = GoldLight,
    onSecondaryContainer = Color(0xFF3B2F00),
    tertiary = Teal,
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFB2F0E4),
    onTertiaryContainer = Color(0xFF00201B),
    background = Surface,
    onBackground = OnSurface,
    surface = Color.White,
    onSurface = OnSurface,
    surfaceVariant = Color(0xFFE7E0EC),
    onSurfaceVariant = Color(0xFF49454F),
    error = Color(0xFFBA1A1A),
    onError = Color.White,
    outline = Color(0xFF79747E)
)

private val DarkColorScheme = darkColorScheme(
    primary = NavyLight,
    onPrimary = Color(0xFF003258),
    primaryContainer = Navy,
    onPrimaryContainer = Color(0xFFD1E4FF),
    secondary = Gold,
    onSecondary = Color(0xFF3B2F00),
    secondaryContainer = Color(0xFF564500),
    onSecondaryContainer = GoldLight,
    tertiary = Color(0xFF97D4C7),
    onTertiary = Color(0xFF003730),
    background = Color(0xFF1C1B1F),
    onBackground = Color(0xFFE6E1E5),
    surface = Color(0xFF1C1B1F),
    onSurface = Color(0xFFE6E1E5),
    surfaceVariant = Color(0xFF49454F),
    onSurfaceVariant = Color(0xFFCAC4D0)
)

@Composable
fun ChessTournamentTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}
