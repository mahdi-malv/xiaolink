package wtf.mahdi.glassesconnect.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import wtf.mahdi.glassesconnect.R

/** Inter variable font (res/font/inter.ttf, OFL). Weight axis needs API 26+; older devices show the default weight. */
private fun inter(weight: FontWeight) = Font(
    R.font.inter, weight = weight,
    variationSettings = FontVariation.Settings(FontVariation.weight(weight.weight)),
)

val Inter = FontFamily(
    inter(FontWeight.Normal), inter(FontWeight.Medium), inter(FontWeight.SemiBold), inter(FontWeight.Bold),
)

private fun style(size: Int, weight: FontWeight, line: Int, tracking: Double = 0.0) = TextStyle(
    fontFamily = Inter, fontWeight = weight, fontSize = size.sp, lineHeight = line.sp, letterSpacing = tracking.em,
)

val XiaoTypography = Typography(
    displaySmall = style(28, FontWeight.Bold, 32, -0.025),
    headlineSmall = style(22, FontWeight.Bold, 28, -0.02),
    titleLarge = style(19, FontWeight.SemiBold, 24, -0.015),
    titleMedium = style(16, FontWeight.SemiBold, 22, -0.01),
    titleSmall = style(15, FontWeight.SemiBold, 20),
    bodyLarge = style(15, FontWeight.Normal, 22),
    bodyMedium = style(14, FontWeight.Normal, 20),
    bodySmall = style(12, FontWeight.Normal, 17),
    labelLarge = style(14, FontWeight.SemiBold, 20),
    labelMedium = style(12, FontWeight.Medium, 16),
    labelSmall = style(11, FontWeight.SemiBold, 14, 0.08),
)
