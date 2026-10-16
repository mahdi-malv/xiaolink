package wtf.mahdi.glassesconnect.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/** App palette: dark-first neutrals with a slight teal bias, one teal accent (from the icon background #18313C). */
@Immutable
data class XiaoColors(
    val bg: Color,
    val surface: Color,
    val surface2: Color,
    val line: Color,
    val text: Color,
    val muted: Color,
    val accent: Color,
    val onAccent: Color,
    val accentSoft: Color,
    val rec: Color,
    val recSoft: Color,
    val ok: Color,
    val warn: Color,
    val heroTop: Color,
    val heroBottom: Color,
    val nav: Color,
    val isDark: Boolean,
)

val DarkXiao = XiaoColors(
    bg = Color(0xFF0A0D0E), surface = Color(0xFF141819), surface2 = Color(0xFF1D2325), line = Color(0xFF273133),
    text = Color(0xFFEEF3F4), muted = Color(0xFF93A1A5),
    accent = Color(0xFF6CCBD9), onAccent = Color(0xFF04222A), accentSoft = Color(0x246CCBD9),
    rec = Color(0xFFFF5B5B), recSoft = Color(0x29FF5B5B), ok = Color(0xFF5AD69A), warn = Color(0xFFF2B84B),
    heroTop = Color(0xFF1B2427), heroBottom = Color(0xFF0D1213), nav = Color(0xFF0E1213), isDark = true,
)

val LightXiao = XiaoColors(
    bg = Color(0xFFF2F5F6), surface = Color(0xFFFFFFFF), surface2 = Color(0xFFE7EDEF), line = Color(0xFFD7E0E3),
    text = Color(0xFF0D1517), muted = Color(0xFF5A696E),
    accent = Color(0xFF17707F), onAccent = Color(0xFFFFFFFF), accentSoft = Color(0x1A17707F),
    rec = Color(0xFFDC3434), recSoft = Color(0x1ADC3434), ok = Color(0xFF1C9A60), warn = Color(0xFFB07418),
    heroTop = Color(0xFFFFFFFF), heroBottom = Color(0xFFE2EAED), nav = Color(0xFFFFFFFF), isDark = false,
)

val LocalXiao = staticCompositionLocalOf { DarkXiao }
