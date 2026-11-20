// SignalX visual system — Fase 1.
//
// Reusable base components (superprompt §12.3). Every screen from Fase 3 on
// is built from these plus the segmented-list primitive that already exists
// in Components.kt (Segmented / SegmentedListItem). Nothing here draws an
// icon by hand: icons are Material Icons already used in this project
// (Check, Warning, Refresh, …) or the project's own BunnyIcons.
//
// States (superprompt §2.4): buttons carry the Material 3 pressed /
// disabled / selected handling; SxStatusChip and SxNotice always pair colour
// with text (and an icon where one exists), so a state never relies on
// colour alone. No shadows anywhere: hierarchy is surface step + outline.
// No bright border on every card: SxCard uses the subtle outline token.

@file:Suppress("FunctionName")

package dev.ririxi.bunnylock.ui.design

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** Semantic tone. Green = signal/positive only; cyan = info; amber = attention; red = failure/destructive. */
enum class SxTone {
    Signal,
    Info,
    Warning,
    Error,
    Neutral,
}

@Composable
private fun toneColors(tone: SxTone): Pair<Color, Color> {
    val scheme = MaterialTheme.colorScheme
    return when (tone) {
        SxTone.Signal -> scheme.primaryContainer to scheme.onPrimaryContainer
        SxTone.Info -> scheme.secondaryContainer to scheme.onSecondaryContainer
        SxTone.Warning -> SxColors.warningContainer() to SxColors.warningOnContainer()
        SxTone.Error -> scheme.errorContainer to scheme.onErrorContainer
        SxTone.Neutral -> scheme.surfaceContainerHighest to scheme.onSurfaceVariant
    }
}

/** Screen title + optional subtitle. The app bar already carries the wordmark, so this is for content screens. */
@Composable
fun SxScreenHeader(
    title: String,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
) {
    Column(modifier.padding(horizontal = SxSpacing.Tight), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, style = MaterialTheme.typography.titleLargeEmphasized, color = MaterialTheme.colorScheme.onSurface)
        subtitle?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

/**
 * Section label inside a scrolling screen. Colour is secondary text, NOT
 * signal green: a heading is a label, not a verdict. Optional count is
 * shown as "Label · N".
 */
@Composable
fun SxSectionHeader(
    text: String,
    count: Int? = null,
    modifier: Modifier = Modifier,
) {
    Text(
        text = if (count != null) "$text · $count" else text,
        modifier = modifier.padding(start = 20.dp, top = 12.dp, bottom = 2.dp),
        style = MaterialTheme.typography.titleSmallEmphasized,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** The one card: elevated surface, 16dp radius, one subtle outline, no shadow. */
@Composable
fun SxCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    padding: PaddingValues = PaddingValues(SxSpacing.CardPadding),
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(SxSpacing.CardRadius)
    val border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    val body: @Composable ColumnScope.() -> Unit = {
        Column(Modifier.padding(padding), verticalArrangement = Arrangement.spacedBy(SxSpacing.Group), content = content)
    }
    if (onClick != null) {
        Surface(
            onClick = onClick,
            modifier = modifier.fillMaxWidth(),
            shape = shape,
            color = MaterialTheme.colorScheme.surfaceContainer,
            border = border,
            content = body,
        )
    } else {
        Surface(
            modifier = modifier.fillMaxWidth(),
            shape = shape,
            color = MaterialTheme.colorScheme.surfaceContainer,
            border = border,
            content = body,
        )
    }
}

/** Label + value row. Technical values use mono; missing values show "—", never 0. */
@Composable
fun SxDataRow(
    label: String,
    value: String,
    monoValue: Boolean = false,
    showDivider: Boolean = false,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(SxSpacing.Group),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Text(
                value,
                style = if (monoValue) MaterialTheme.typography.bodyMedium.mono() else MaterialTheme.typography.bodyMediumEmphasized,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (showDivider) {
            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant,
                thickness = 1.dp,
                modifier = Modifier.padding(top = SxSpacing.Group),
            )
        }
    }
}

/**
 * One signal metric: label, value in its scale colour, 4-step bar and the
 * qualitative word. Colour is never the only indicator — the number and the
 * word are always shown with it. [scale] comes from the theme
 * (LocalSignalScale); [steps] from SxSignalThresholds.
 */
@Composable
fun SxSignalIndicator(
    label: String,
    value: Int?,
    unit: String,
    steps: List<Int>,
    scale: List<Color>,
    modifier: Modifier = Modifier,
) {
    val level = value?.let { v -> val i = steps.indexOfFirst { v >= it }; if (i < 0) steps.size else i }
    val bars = if (value == null || level == null) 0 else steps.size + 1 - level
    val color = if (value == null || level == null) MaterialTheme.colorScheme.onSurfaceVariant else scale[level]
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            if (value == null) SxFormat.Unavailable else "$value $unit",
            style = MaterialTheme.typography.titleMediumEmphasized,
            color = color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.Bottom) {
            repeat(steps.size + 1) { index ->
                Box(
                    Modifier
                        .width(5.dp)
                        .height((6 + index * 3).dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(if (index < bars) color else MaterialTheme.colorScheme.surfaceContainerHighest),
                )
            }
        }
        Text(
            if (value == null) SxFormat.Unavailable else SxFormat.signalQualityLabel(value, steps),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Small pill with a dBm value coloured by the signal scale (for list rows). */
@Composable
fun SxSignalPill(
    dbm: Int,
    steps: List<Int>,
    scale: List<Color>,
    modifier: Modifier = Modifier,
) {
    val color by animateColorAsState(signalColorFor(dbm, steps, scale), label = "signal")
    Surface(color = color.copy(alpha = 0.16f), contentColor = color, shape = RoundedCornerShape(50), modifier = modifier) {
        Text(SxFormat.formatDbm(dbm), style = MaterialTheme.typography.labelLargeEmphasized, modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp))
    }
}

/** Status chip: text first, colour second, icon only when one fits. No chip without a meaning. */
@Composable
fun SxStatusChip(
    text: String,
    tone: SxTone = SxTone.Neutral,
    icon: ImageVector? = null,
    onClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val (container, content) = toneColors(tone)
    val chip: @Composable () -> Unit = {
        Row(
            Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            icon?.let { Icon(it, contentDescription = null, tint = content, modifier = Modifier.size(SxSpacing.IconSmall)) }
            Text(text, style = MaterialTheme.typography.labelSmall, color = content, maxLines = 1, softWrap = false)
        }
    }
    if (onClick != null) {
        Surface(onClick = onClick, modifier = modifier, shape = RoundedCornerShape(8.dp), color = container, contentColor = content) { chip() }
    } else {
        Surface(modifier = modifier, shape = RoundedCornerShape(8.dp), color = container, contentColor = content) { chip() }
    }
}

/** Primary action: signal green, 48dp, 16dp radius. */
@Composable
fun SxPrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.height(SxSpacing.ButtonHeight),
        shape = RoundedCornerShape(SxSpacing.ButtonRadius),
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
        ),
    ) {
        icon?.let {
            Icon(it, contentDescription = null, modifier = Modifier.size(ButtonDefaults.iconSizeFor(SxSpacing.ButtonHeight)))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, style = ButtonDefaults.textStyleFor(SxSpacing.ButtonHeight), maxLines = 1)
    }
}

/** Secondary action: tonal (cyan-tinted) button, same size and radius. */
@Composable
fun SxSecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
) {
    FilledTonalButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.height(SxSpacing.ButtonHeight),
        shape = RoundedCornerShape(SxSpacing.ButtonRadius),
    ) {
        icon?.let {
            Icon(it, contentDescription = null, modifier = Modifier.size(ButtonDefaults.iconSizeFor(SxSpacing.ButtonHeight)))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, style = ButtonDefaults.textStyleFor(SxSpacing.ButtonHeight), maxLines = 1)
    }
}

/** Destructive action: error container. Only for actions that delete or unpin. */
@Composable
fun SxDestructiveButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.height(SxSpacing.ButtonHeight),
        shape = RoundedCornerShape(SxSpacing.ButtonRadius),
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.error,
            contentColor = MaterialTheme.colorScheme.onError,
        ),
    ) {
        icon?.let {
            Icon(it, contentDescription = null, modifier = Modifier.size(ButtonDefaults.iconSizeFor(SxSpacing.ButtonHeight)))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, style = ButtonDefaults.textStyleFor(SxSpacing.ButtonHeight), maxLines = 1)
    }
}

/** Quiet action, for dialog dismissals and inline links. */
@Composable
fun SxTextButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    color: Color = MaterialTheme.colorScheme.primary,
) {
    TextButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
        colors = ButtonDefaults.textButtonColors(contentColor = color),
    ) { Text(text) }
}

/** Determinate (progress 0..1) or indeterminate (progress null) progress bar. */
@Composable
fun SxProgressBar(
    progress: Float?,
    modifier: Modifier = Modifier,
) {
    if (progress != null) {
        LinearProgressIndicator(
            progress = { progress.coerceIn(0f, 1f) },
            modifier = modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.primary,
            trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        )
    } else {
        LinearProgressIndicator(
            modifier = modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.primary,
            trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        )
    }
}

/** Empty state: illustration slot (e.g. SleepyBunny) or a plain icon, title, detail, one action. */
@Composable
fun SxEmptyState(
    title: String,
    detail: String? = null,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    illustration: (@Composable () -> Unit)? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Column(
        modifier.fillMaxWidth().padding(vertical = SxSpacing.Section),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(SxSpacing.Group),
    ) {
        illustration?.invoke()
        if (illustration == null && icon != null) {
            Surface(color = MaterialTheme.colorScheme.surfaceContainerHighest, shape = CircleShape, modifier = Modifier.size(56.dp)) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(SxSpacing.IconLarge))
                }
            }
        }
        Text(title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
        detail?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        if (actionLabel != null && onAction != null) SxSecondaryButton(actionLabel, onAction)
    }
}

/** Inline notice banner: tinted container, icon where one exists, text, optional action. */
@Composable
fun SxNotice(
    text: String,
    tone: SxTone = SxTone.Info,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    val (container, content) = toneColors(tone)
    val icon =
        when (tone) {
            SxTone.Signal -> Icons.Rounded.Check
            SxTone.Warning, SxTone.Error -> Icons.Rounded.Warning
            else -> null
        }
    Surface(modifier = modifier.fillMaxWidth(), shape = RoundedCornerShape(SxSpacing.CardRadius), color = container, contentColor = content) {
        Row(
            Modifier.padding(SxSpacing.CardPadding),
            horizontalArrangement = Arrangement.spacedBy(SxSpacing.Group),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            icon?.let { Icon(it, contentDescription = null, tint = content, modifier = Modifier.size(SxSpacing.IconMedium)) }
            Text(text, style = MaterialTheme.typography.bodyMedium, color = content, modifier = Modifier.weight(1f))
            if (actionLabel != null && onAction != null) SxTextButton(actionLabel, onAction, color = content)
        }
    }
}

/**
 * The one dialog: icon, title, scrollable text (or custom [content] for
 * forms such as the place-antenna coordinates), filled confirm + quiet
 * dismiss. [destructiveConfirm] paints the confirm button with the error
 * colour (delete / unpin only). [dismissLabel] is optional: an
 * information dialog whose confirm already closes it ("Close") gets no
 * second button doing the same thing. [secondaryLabel]/[onSecondary] is
 * the third action (update dialog: "Skip this version") — it renders
 * next to the dismiss button, never as a second primary.
 */
@Composable
fun SxDialog(
    title: String,
    text: String? = null,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    dismissLabel: String? = null,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    destructiveConfirm: Boolean = false,
    secondaryLabel: String? = null,
    onSecondary: (() -> Unit)? = null,
    content: (@Composable ColumnScope.() -> Unit)? = null,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = icon?.let { image -> { Icon(image, contentDescription = null) } },
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                content?.invoke(this) ?: text?.let { Text(it) }
            }
        },
        confirmButton = {
            if (destructiveConfirm) SxDestructiveButton(confirmLabel, onConfirm) else SxPrimaryButton(confirmLabel, onConfirm)
        },
        dismissButton = {
            if (dismissLabel != null || secondaryLabel != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (secondaryLabel != null && onSecondary != null) SxTextButton(secondaryLabel, onSecondary)
                    if (dismissLabel != null) SxTextButton(dismissLabel, onDismiss)
                }
            }
        },
        modifier = modifier,
    )
}

/** Bottom sheet for filters and multi-action lists (Fases 4-6). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SxBottomSheet(
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(),
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(
            Modifier.padding(horizontal = SxSpacing.CardPadding).padding(bottom = SxSpacing.CardPadding),
            verticalArrangement = Arrangement.spacedBy(SxSpacing.Group),
            content = content,
        )
    }
}
