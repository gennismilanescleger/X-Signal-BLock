// SignalX visual system — Fase 1.
//
// Typography decision (superprompt §2.3, Fase 0 §3.1): ONE principal family
// plus mono, never four families at once.
//
// Principal: Google Sans Flex, already in this project (res/font/
// google_sans_flex_rounded.ttf, OFL, licence file in assets). It is a
// variable font: its weight axis covers both roles SignalX split across two
// families — Manrope Bold for titles / big numbers and Inter Regular for
// body. Keeping it adds 0 bytes to the APK, needs no new licence, and avoids
// duplicating Manrope + Inter + JetBrains Mono (168 KB) on top of it.
// SignalX's three fonts are NOT copied in: their repo carries no licence
// files (Fase 0 §4.3), so distributing them would need verification first.
// If Gennis later prefers Manrope/Inter, swap SxSans here — every screen
// reads the typography from this one place.
//
// Mono: system monospace (FontFamily.Monospace), 0 bytes. Used ONLY for
// technical identifiers and values (ECI, EARFCN, coordinates, dBm values in
// data rows) — never for prose. A bundled JetBrains Mono would read better
// for tabular figures and can replace SxMono later if wanted.

package dev.ririxi.bunnylock.ui.design

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import dev.ririxi.bunnylock.R

val SxSans: FontFamily =
    FontFamily(
        listOf(FontWeight.Normal, FontWeight.Medium, FontWeight.Bold, FontWeight.ExtraBold).map { weight ->
            Font(R.font.google_sans_flex_rounded, weight, variationSettings = FontVariation.Settings(FontVariation.weight(weight.weight)))
        },
    )

val SxMono: FontFamily = FontFamily.Monospace

/** Every Material 3 text style on the one principal family. */
val SxTypography: Typography =
    Typography().run {
        fun TextStyle.sans() = copy(fontFamily = SxSans)
        copy(
            displayLarge = displayLarge.sans(),
            displayMedium = displayMedium.sans(),
            displaySmall = displaySmall.sans(),
            headlineLarge = headlineLarge.sans(),
            headlineMedium = headlineMedium.sans(),
            headlineSmall = headlineSmall.sans(),
            titleLarge = titleLarge.sans(),
            titleMedium = titleMedium.sans(),
            titleSmall = titleSmall.sans(),
            bodyLarge = bodyLarge.sans(),
            bodyMedium = bodyMedium.sans(),
            bodySmall = bodySmall.sans(),
            labelLarge = labelLarge.sans(),
            labelMedium = labelMedium.sans(),
            labelSmall = labelSmall.sans(),
            displayLargeEmphasized = displayLargeEmphasized.sans(),
            displayMediumEmphasized = displayMediumEmphasized.sans(),
            displaySmallEmphasized = displaySmallEmphasized.sans(),
            headlineLargeEmphasized = headlineLargeEmphasized.sans(),
            headlineMediumEmphasized = headlineMediumEmphasized.sans(),
            headlineSmallEmphasized = headlineSmallEmphasized.sans(),
            titleLargeEmphasized = titleLargeEmphasized.sans(),
            titleMediumEmphasized = titleMediumEmphasized.sans(),
            titleSmallEmphasized = titleSmallEmphasized.sans(),
        )
    }

/** Switch any text style to the technical mono family. */
fun TextStyle.mono(): TextStyle = copy(fontFamily = SxMono)

/** Named mono styles for technical values. Colour is always set at the call site. */
object SxTextStyles {
    val TechValue = TextStyle(fontFamily = SxMono, fontSize = 14.sp, fontWeight = FontWeight.Normal)
    val TechValueLarge = TextStyle(fontFamily = SxMono, fontSize = 18.sp, fontWeight = FontWeight.Medium)
    val TechCaption = TextStyle(fontFamily = SxMono, fontSize = 12.sp, fontWeight = FontWeight.Normal)
}
