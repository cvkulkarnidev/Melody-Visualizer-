package dev.cvkulkarnidev.melodyvisualizer.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Ink = Color(0xFF0B0C14)
val SurfaceDeep = Color(0xFF121421)
val SurfaceRaised = Color(0xFF1A1D2E)
val Violet = Color(0xFFA78BFA)
val VioletBright = Color(0xFFC4B5FD)
val Aqua = Color(0xFF67E8F9)
val Mint = Color(0xFF6EE7B7)
val TextPrimary = Color(0xFFF7F7FC)
val TextSecondary = Color(0xFFA7A9BB)
val ErrorCoral = Color(0xFFFF8A8A)

private val MelodyColorScheme = darkColorScheme(
    primary = Violet,
    onPrimary = Ink,
    secondary = Aqua,
    tertiary = Mint,
    background = Ink,
    onBackground = TextPrimary,
    surface = SurfaceDeep,
    onSurface = TextPrimary,
    surfaceVariant = SurfaceRaised,
    onSurfaceVariant = TextSecondary,
    error = ErrorCoral,
)

@Composable
fun MelodyVisualizerTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = MelodyColorScheme,
        content = content,
    )
}
