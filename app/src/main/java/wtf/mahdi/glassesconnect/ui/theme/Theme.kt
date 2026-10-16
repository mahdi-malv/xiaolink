package wtf.mahdi.glassesconnect.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable

/** Fixed brand palette (no dynamic color). M3 components read the mapped [MaterialTheme.colorScheme]. */
@Composable
fun XiaoTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val x = if (dark) DarkXiao else LightXiao
    val base = if (dark) darkColorScheme() else lightColorScheme()
    val scheme = base.copy(
        primary = x.accent, onPrimary = x.onAccent, primaryContainer = x.accentSoft, onPrimaryContainer = x.text,
        secondary = x.accent, onSecondary = x.onAccent,
        background = x.bg, onBackground = x.text,
        surface = x.bg, onSurface = x.text, surfaceVariant = x.surface2, onSurfaceVariant = x.muted,
        surfaceContainerLowest = x.bg, surfaceContainerLow = x.surface, surfaceContainer = x.surface,
        surfaceContainerHigh = x.surface, surfaceContainerHighest = x.surface2,
        inverseSurface = x.text, inverseOnSurface = x.bg, inversePrimary = x.accent,
        outline = x.line, outlineVariant = x.line,
        error = x.rec, onError = androidx.compose.ui.graphics.Color.White,
    )
    CompositionLocalProvider(LocalXiao provides x) {
        // Text and icons outside a Surface read LocalContentColor, which defaults to black.
        MaterialTheme(colorScheme = scheme, typography = XiaoTypography) {
            CompositionLocalProvider(androidx.compose.material3.LocalContentColor provides x.text, content = content)
        }
    }
}

/** Shortcut: `Xiao.colors.accent`. */
object Xiao {
    val colors: XiaoColors @Composable @ReadOnlyComposable get() = LocalXiao.current
}
