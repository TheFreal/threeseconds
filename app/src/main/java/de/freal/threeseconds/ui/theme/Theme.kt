package de.freal.threeseconds.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Amber = Color(0xFFFFB74D)
private val AmberDeep = Color(0xFFE08A1E)

private val DarkColors = darkColorScheme(
    primary = Amber,
    onPrimary = Color(0xFF231600),
    secondary = Color(0xFF8FB8FF),
    background = Color(0xFF101014),
    surface = Color(0xFF17171C),
    surfaceVariant = Color(0xFF24242B),
    onBackground = Color(0xFFECECEF),
    onSurface = Color(0xFFECECEF),
    onSurfaceVariant = Color(0xFFB6B6BE),
)

private val LightColors = lightColorScheme(
    primary = AmberDeep,
    secondary = Color(0xFF2E5FA8),
    background = Color(0xFFFAF9F7),
    surface = Color(0xFFFFFFFF),
)

@Composable
fun ThreeSecondsTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}
