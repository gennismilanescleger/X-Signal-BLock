// SignalX visual system — Fase 1.
//
// One spacing / radius / size scale (superprompt §2.4). No screen invents
// its own paddings: screens use these tokens or the screen-margin helper in
// App.kt (16dp sides, which matches ScreenHorizontal here).
//
// SignalX radius was a single 16dp everywhere (auditoria 06 §6.2); the
// system keeps it. The one exception is the bottom sheet, which keeps the
// Material 3 expressive 28dp top radius.

package dev.ririxi.bunnylock.ui.design

import androidx.compose.ui.unit.dp

object SxSpacing {
    /** Screen side margin (matches App.kt withScreenMargins). */
    val ScreenHorizontal = 16.dp

    /** Extra top / bottom margin inside a scrolling screen. */
    val ScreenTop = 4.dp
    val ScreenBottom = 24.dp

    /** Gap between sections, between cards in a list, inside a card. */
    val Section = 12.dp

    /** Gap between grouped items / rows. */
    val Group = 8.dp

    /** Smallest gap (icon to label, chip padding). */
    val Tight = 4.dp

    /** Card inner padding (standard / roomy / compact). */
    val CardPadding = 16.dp
    val CardPaddingLarge = 20.dp
    val CardPaddingCompact = 12.dp

    /** The one card / button / field radius. */
    val CardRadius = 16.dp

    /** Touch target height for every button (Material 3 medium). */
    val ButtonHeight = 48.dp
    val ButtonRadius = 16.dp

    /** Icon sizes: small (in chips), medium (standard), large (empty states). */
    val IconSmall = 18.dp
    val IconMedium = 24.dp
    val IconLarge = 32.dp

    /** Minimum touch target, per accessibility (§14). */
    val TouchTarget = 48.dp
}
