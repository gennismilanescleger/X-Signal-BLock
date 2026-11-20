// SignalX visual system — Fase 1.
//
// Single source of truth for colour. Every raw hex lives here; screens and
// components only ever read MaterialTheme.colorScheme, LocalLockGreen /
// LocalSignalScale (wired in ui/theme/Theme.kt) or the helpers below.
//
// Palette: SignalX tokens (auditoria 06 §6.1) kept where they fit.
//   background #06101F, surface #0B1B2E, elevated #0F2438, text #EDF6F2,
//   secondary text #93AEC2, muted #6B8AA0, signal green #4ADE80,
//   info cyan #38BDF8, warning amber #FBBF24, error #F87171.
// Green means signal / connection / confirmation ONLY, never decoration.
// Cyan means orientation / location / maps / data. Amber means "attention".
// Red is controlled: failures and destructive actions only.
// No bright border around every card: cards rely on the surface step
// (#06101F -> #0B1B2E -> #0F2438) plus one subtle 1dp outline
// (SignalX border token #1E4D38).
//
// The dark scheme is the canonical SignalX identity. The light scheme is a
// legibility fallback with the same semantic mapping (same green = signal,
// same cyan = info); it is not a second identity.

package dev.ririxi.bunnylock.ui.design

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import dev.ririxi.bunnylock.data.Rat

object SxColors {
    // ---- Raw SignalX tokens (dark, canonical) ----
    val Background = Color(0xFF06101F)
    val Surface = Color(0xFF0B1B2E)
    val SurfaceElevated = Color(0xFF0F2438)
    val SurfaceHigh = Color(0xFF142E47)
    val SurfaceHighest = Color(0xFF1A3A58)
    val SurfaceLowest = Color(0xFF050D18)

    val TextPrimary = Color(0xFFEDF6F2)
    val TextSecondary = Color(0xFF93AEC2)
    val TextMuted = Color(0xFF6B8AA0)

    /** Signal / connection / confirmation. Never decoration. */
    val Signal = Color(0xFF4ADE80)
    val OnSignal = Color(0xFF04150A)
    val SignalContainer = Color(0xFF0E2E1D)
    val OnSignalContainer = Color(0xFFA7F3D0)

    /** Orientation / location / maps / data. */
    val Info = Color(0xFF38BDF8)
    val OnInfo = Color(0xFF082F49)
    val InfoContainer = Color(0xFF0C2B3D)
    val OnInfoContainer = Color(0xFFBAE6FD)
    val InfoSoft = Color(0xFF7DD3FC)

    /** Warning: limited precision, incomplete data, needs attention. */
    val Warning = Color(0xFFFBBF24)
    val WarningContainerDark = Color(0xFF3A2A05)
    val WarningOnContainerDark = Color(0xFFFDE68A)
    val WarningContainerLight = Color(0xFFFEF3C7)
    val WarningOnContainerLight = Color(0xFF713F12)

    /** Error: failures and destructive actions only. */
    val Error = Color(0xFFF87171)
    val ErrorContainerDark = Color(0xFF7F1D1D)
    val ErrorOnContainerDark = Color(0xFFFECACA)

    /** Subtle outline for cards (SignalX border token). Dark, not bright. */
    val Outline = Color(0xFF1E4D38)
    val OutlineVariant = Color(0xFF16283A)

    /** Muted steel blue: categorical third colour (LTE badge, tertiary). */
    val Steel = Color(0xFF7FB3D5)
    val OnSteel = Color(0xFF08283A)
    val SteelContainer = Color(0xFF14324A)
    val OnSteelContainer = Color(0xFFBFE0F2)

    /** Signal green on light surfaces (dark enough to read). */
    val SignalOnLight = Color(0xFF1FA34A)

    // ---- Material 3 schemes built from the tokens above ----
    val DarkScheme: ColorScheme =
        darkColorScheme(
            primary = Signal,
            onPrimary = OnSignal,
            primaryContainer = SignalContainer,
            onPrimaryContainer = OnSignalContainer,
            secondary = Info,
            onSecondary = OnInfo,
            secondaryContainer = InfoContainer,
            onSecondaryContainer = OnInfoContainer,
            tertiary = Steel,
            onTertiary = OnSteel,
            tertiaryContainer = SteelContainer,
            onTertiaryContainer = OnSteelContainer,
            error = Error,
            onError = Color(0xFF2D0505),
            errorContainer = ErrorContainerDark,
            onErrorContainer = ErrorOnContainerDark,
            background = Background,
            onBackground = TextPrimary,
            surface = Surface,
            onSurface = TextPrimary,
            surfaceVariant = SurfaceElevated,
            onSurfaceVariant = TextSecondary,
            outline = Outline,
            outlineVariant = OutlineVariant,
            inverseSurface = TextPrimary,
            inverseOnSurface = Background,
            inversePrimary = SignalOnLight,
            surfaceContainerLowest = SurfaceLowest,
            surfaceContainerLow = Surface,
            surfaceContainer = SurfaceElevated,
            surfaceContainerHigh = SurfaceHigh,
            surfaceContainerHighest = SurfaceHighest,
            surfaceDim = SurfaceLowest,
            surfaceBright = SurfaceHighest,
        )

    val LightScheme: ColorScheme =
        lightColorScheme(
            primary = SignalOnLight,
            onPrimary = Color.White,
            primaryContainer = Color(0xFFCDEED6),
            onPrimaryContainer = Color(0xFF0B3D1E),
            secondary = Color(0xFF0E7490),
            onSecondary = Color.White,
            secondaryContainer = Color(0xFFCDEFFA),
            onSecondaryContainer = Color(0xFF083344),
            tertiary = Color(0xFF2F6690),
            onTertiary = Color.White,
            tertiaryContainer = Color(0xFFD3E6F2),
            onTertiaryContainer = Color(0xFF0F2F4A),
            error = Color(0xFFB3261E),
            onError = Color.White,
            errorContainer = Color(0xFFF9DEDC),
            onErrorContainer = Color(0xFF410E0B),
            background = Color(0xFFEDF3F6),
            onBackground = Color(0xFF0B1B2E),
            surface = Color(0xFFF7FAFB),
            onSurface = Color(0xFF0B1B2E),
            surfaceVariant = Color(0xFFE6EEF2),
            onSurfaceVariant = Color(0xFF4A6578),
            outline = Color(0xFFB9CBD8),
            outlineVariant = Color(0xFFD2E0E8),
            inverseSurface = Color(0xFF0B1B2E),
            inverseOnSurface = Color(0xFFEDF6F2),
            inversePrimary = Signal,
            surfaceContainerLowest = Color.White,
            surfaceContainerLow = Color(0xFFEFF5F8),
            surfaceContainer = Color(0xFFE6EEF2),
            surfaceContainerHigh = Color(0xFFDCE8EE),
            surfaceContainerHighest = Color(0xFFD2E0E8),
            surfaceDim = Color(0xFFDCE8EE),
            surfaceBright = Color.White,
        )

    /** Warning container / content honouring the current theme. */
    @Composable
    fun warningContainer(): Color = if (isSystemInDarkTheme()) WarningContainerDark else WarningContainerLight

    @Composable
    fun warningOnContainer(): Color = if (isSystemInDarkTheme()) WarningOnContainerDark else WarningOnContainerLight
}

/**
 * Signal-quality thresholds, best first. A value belongs to the first step
 * it reaches; below every step is the worst band. The matching scale must
 * always have steps.size + 1 entries (best .. worst).
 */
object SxSignalThresholds {
    /** RSRP in dBm (LTE and NR). */
    val Rsrp = listOf(-80, -90, -100)

    /** Plain dBm (RSSI, 2G/3G) — runs higher than RSRP. */
    val PlainDbm = listOf(-70, -85, -100)

    /** RSRQ in dB. */
    val Rsrq = listOf(-10, -15, -20)

    /** SINR in dB. */
    val Sinr = listOf(20, 13, 0)
}

/** Pure colour lookup: no composable needed, scale comes from the theme. */
fun signalColorFor(
    value: Int,
    steps: List<Int>,
    scale: List<Color>,
): Color {
    val step = steps.indexOfFirst { value >= it }
    return scale[if (step < 0) steps.size else step]
}

/** Categorical RAT colours. Deliberately muted: RAT is a category, not a signal verdict. */
data class RatStyle(val container: Color, val content: Color)

@Composable
fun ratStyle(rat: Rat): RatStyle {
    val dark = isSystemInDarkTheme()
    return when (rat) {
        Rat.NR -> RatStyle(
            if (dark) SxColors.InfoContainer else Color(0xFFCDEFFA),
            if (dark) SxColors.Info else Color(0xFF0E7490),
        )
        Rat.LTE -> RatStyle(
            if (dark) SxColors.SteelContainer else Color(0xFFD3E6F2),
            if (dark) SxColors.OnSteelContainer else Color(0xFF2F6690),
        )
        Rat.WCDMA -> RatStyle(
            if (dark) SxColors.SurfaceHighest else Color(0xFFDCE8EE),
            if (dark) SxColors.TextSecondary else Color(0xFF4A6578),
        )
        Rat.GSM -> RatStyle(
            if (dark) SxColors.SurfaceHighest else Color(0xFFDCE8EE),
            if (dark) SxColors.TextMuted else Color(0xFF6B7F8F),
        )
    }
}
