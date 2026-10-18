package edu.ytu.cdtp.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Navy = Color(0xFF172430)
val Cream = Color(0xFFF5F1EA)
val Teal = Color(0xFF1D6A72)
val Coral = Color(0xFFE8765A)
val PassGreen = Color(0xFF2E7D32)
val FailRed = Color(0xFFC62828)
val WarnAmber = Color(0xFFF9A825)

private val Light = lightColorScheme(
    primary = Teal,
    onPrimary = Color.White,
    secondary = Coral,
    onSecondary = Color.White,
    background = Cream,
    onBackground = Navy,
    surface = Color.White,
    onSurface = Navy,
    surfaceVariant = Color(0xFFEDE7DD),
    onSurfaceVariant = Color(0xFF4A5560),
)

private val Dark = darkColorScheme(
    primary = Color(0xFF6FC2CB),
    onPrimary = Navy,
    secondary = Coral,
    background = Navy,
    onBackground = Cream,
    surface = Color(0xFF1F3040),
    onSurface = Cream,
    surfaceVariant = Color(0xFF2A3D4E),
    onSurfaceVariant = Color(0xFFB8C4CE),
)

@Composable
fun CdtpTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Dark else Light, content = content)
}
