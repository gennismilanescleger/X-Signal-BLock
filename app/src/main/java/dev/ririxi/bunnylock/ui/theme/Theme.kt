@file:Suppress("FunctionName")

package dev.ririxi.bunnylock.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import dev.ririxi.bunnylock.ui.design.SxColors
import dev.ririxi.bunnylock.ui.design.SxTypography

/**
 * SignalX theme — Fase 1. The colour schemes and typography live in
 * ui/design (SxColors / SxTypography); this file only wires them into
 * Material 3 expressive.
 *
 * Dynamic colour is a BunnyLock function and stays (superprompt §11.3: the
 * function stays, the design adapts). When it is ON, the Material You
 * scheme (wallpaper colours) may only tint the NEUTRAL slots — background,
 * surfaces, surface containers, outlines, inverse surfaces and the
 * on-surface text colours that go with them. The SEMANTIC slots are
 * re-pinned to the fixed SignalX tokens afterwards (see
 * withFixedSemantics): green #4ADE80 = signal, cyan #38BDF8 = info,
 * steel = categorical third colour, red #F87171 = error. So the wallpaper
 * can never repaint "green = signal" into another colour. When it is OFF,
 * the canonical SxColors dark/light scheme applies exactly as-is.
 */

val dynamicColorAvailable: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

// Green for the lock state and the wordmark. Darker on light surfaces so it stays readable.
val LocalLockGreen = staticCompositionLocalOf { SxColors.SignalOnLight }

// Signal scale, best to worst: green, lime, amber, red. Must stay
// steps.size + 1 long for SxSignalThresholds (see SxColors.signalColorFor).
val LocalSignalScale =
    staticCompositionLocalOf {
        listOf(SxColors.SignalOnLight, Color(0xFF6E8E00), Color(0xFFB86E00), Color(0xFFCC3B2C))
    }

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun BunnyTheme(
    dynamicColor: Boolean = true,
    darkTheme: Boolean = isSystemInDarkTheme(),
    pureBlack: Boolean = false,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val canonicalScheme = if (darkTheme) SxColors.DarkScheme else SxColors.LightScheme
    // Dynamic colour ON: wallpaper palette for the neutrals only, then the
    // semantic slots are pinned back to the fixed SignalX tokens.
    // Dynamic colour OFF: the canonical scheme exactly as it is.
    val baseScheme =
        when {
            dynamicColor && dynamicColorAvailable && darkTheme ->
                dynamicDarkColorScheme(context).withFixedSemantics(canonicalScheme)
            dynamicColor && dynamicColorAvailable ->
                dynamicLightColorScheme(context).withFixedSemantics(canonicalScheme)
            else -> canonicalScheme
        }
    val scheme = if (darkTheme && pureBlack) baseScheme.withPureBlack() else baseScheme
    CompositionLocalProvider(
        LocalLockGreen provides if (darkTheme) SxColors.Signal else SxColors.SignalOnLight,
        LocalSignalScale provides
            if (darkTheme) {
                listOf(SxColors.Signal, Color(0xFFC3E35A), SxColors.Warning, SxColors.Error)
            } else {
                listOf(SxColors.SignalOnLight, Color(0xFF6E8E00), Color(0xFFB86E00), Color(0xFFCC3B2C))
            },
    ) {
        MaterialExpressiveTheme(
            colorScheme = scheme,
            motionScheme = MotionScheme.expressive(),
            typography = SxTypography,
            content = content,
        )
    }
}

// Dynamic colour adaptation (superprompt §11.3): the wallpaper palette may
// tint neutral surfaces, but the semantic slots stay FIXED to the SignalX
// tokens, so green always means signal, cyan always means info, steel stays
// the categorical third colour and red stays error. LocalLockGreen and
// LocalSignalScale are fixed tokens too and never come from this scheme.
// Slots NOT listed here stay dynamic: background, surface and its
// containers, surfaceVariant, outline, inverse surfaces, and the on-surface
// text colours (they are tuned to the dynamic background, so keeping them
// dynamic is what keeps text readable).
private fun ColorScheme.withFixedSemantics(canonical: ColorScheme): ColorScheme =
    copy(
        primary = canonical.primary,
        onPrimary = canonical.onPrimary,
        primaryContainer = canonical.primaryContainer,
        onPrimaryContainer = canonical.onPrimaryContainer,
        secondary = canonical.secondary,
        onSecondary = canonical.onSecondary,
        secondaryContainer = canonical.secondaryContainer,
        onSecondaryContainer = canonical.onSecondaryContainer,
        tertiary = canonical.tertiary,
        onTertiary = canonical.onTertiary,
        tertiaryContainer = canonical.tertiaryContainer,
        onTertiaryContainer = canonical.onTertiaryContainer,
        error = canonical.error,
        onError = canonical.onError,
        errorContainer = canonical.errorContainer,
        onErrorContainer = canonical.onErrorContainer,
        inversePrimary = canonical.inversePrimary,
    )

// The page turns black and every container takes the tone of the one below it, so cards still stand out from the page.
private fun ColorScheme.withPureBlack(): ColorScheme =
    copy(
        background = Color.Black,
        surface = Color.Black,
        surfaceDim = Color.Black,
        surfaceContainerHighest = surfaceContainerHigh,
        surfaceContainerHigh = surfaceContainer,
        surfaceContainer = surfaceContainerLow,
        surfaceContainerLow = surfaceContainerLowest,
        surfaceContainerLowest = Color.Black,
    )
