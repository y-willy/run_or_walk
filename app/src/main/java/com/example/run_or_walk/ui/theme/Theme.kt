package com.example.run_or_walk.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

private val LightColorScheme = lightColorScheme(
    primary = PaceNavy,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE7F7F4),
    onPrimaryContainer = Color(0xFF085C54),
    secondary = PaceDeepMint,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFDCF6F0),
    onSecondaryContainer = Color(0xFF064F47),
    tertiary = Color(0xFF315EA8),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFE7EEFF),
    onTertiaryContainer = Color(0xFF153D7B),
    background = PaceBackground,
    onBackground = PaceNavy,
    surface = Color.White,
    onSurface = PaceNavy,
    surfaceVariant = Color(0xFFF0F4F8),
    onSurfaceVariant = PaceSecondary,
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF8FAFC),
    surfaceContainer = Color(0xFFF1F5F9),
    surfaceContainerHigh = Color(0xFFEBF0F6),
    surfaceContainerHighest = Color(0xFFE5EBF2),
    outline = Color(0xFF718095),
    outlineVariant = PaceBorder,
    error = Color(0xFFB42334),
    onError = Color.White,
    errorContainer = Color(0xFFFFECEE),
    onErrorContainer = Color(0xFF7C1C2A),
    inverseSurface = PaceNavy,
    inverseOnSurface = Color(0xFFF1F5F9),
    inversePrimary = Color(0xFF71DDCD)
)

private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFF71DDCD),
    onPrimary = Color(0xFF003A33),
    primaryContainer = Color(0xFF134D46),
    onPrimaryContainer = Color(0xFFAEF1E5),
    secondary = Color(0xFF71DDCD),
    onSecondary = Color(0xFF003A33),
    secondaryContainer = Color(0xFF164B44),
    onSecondaryContainer = Color(0xFFA8EFDF),
    tertiary = Color(0xFFACC9FF),
    onTertiary = Color(0xFF102F60),
    tertiaryContainer = Color(0xFF244678),
    onTertiaryContainer = Color(0xFFDBE7FF),
    background = Color(0xFF0B1322),
    onBackground = Color(0xFFE8EEF7),
    surface = Color(0xFF131E30),
    onSurface = Color(0xFFE8EEF7),
    surfaceVariant = Color(0xFF253247),
    onSurfaceVariant = Color(0xFFAEBBD0),
    surfaceContainerLowest = Color(0xFF0C1524),
    surfaceContainerLow = Color(0xFF152033),
    surfaceContainer = Color(0xFF1A273A),
    surfaceContainerHigh = Color(0xFF213046),
    surfaceContainerHighest = Color(0xFF293950),
    outline = Color(0xFF8998AF),
    outlineVariant = Color(0xFF34435A),
    error = Color(0xFFFFB2BC),
    onError = Color(0xFF640D22),
    errorContainer = Color(0xFF5A2030),
    onErrorContainer = Color(0xFFFFD9DF),
    inverseSurface = Color(0xFFE8EEF7),
    inverseOnSurface = Color(0xFF152033),
    inversePrimary = PaceDeepMint
)

private val PaceShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(24.dp)
)

@Composable
fun RUN_or_WALKTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    // Brand colors are the default; callers may explicitly opt into system colors.
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
        typography = Typography,
        shapes = PaceShapes,
        content = content
    )
}
