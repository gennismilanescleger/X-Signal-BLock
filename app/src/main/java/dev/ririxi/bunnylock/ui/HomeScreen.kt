@file:Suppress("FunctionName")

package dev.ririxi.bunnylock.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonGroup
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ButtonGroupScope
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItemShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.ririxi.bunnylock.R
import dev.ririxi.bunnylock.data.Carrier
import dev.ririxi.bunnylock.data.Cell
import dev.ririxi.bunnylock.data.Rat
import dev.ririxi.bunnylock.data.Signal
import dev.ririxi.bunnylock.modem.ImsFeature
import dev.ririxi.bunnylock.modem.ImsSetting
import dev.ririxi.bunnylock.modem.ModemState
import dev.ririxi.bunnylock.modem.formatPlmn
import dev.ririxi.bunnylock.signalx.MeasurementSummary
import dev.ririxi.bunnylock.signalx.servingReading
import dev.ririxi.bunnylock.ui.design.SxCard
import dev.ririxi.bunnylock.ui.design.SxDataRow
import dev.ririxi.bunnylock.ui.design.SxDialog
import dev.ririxi.bunnylock.ui.design.SxFormat
import dev.ririxi.bunnylock.ui.design.SxPrimaryButton
import dev.ririxi.bunnylock.ui.design.SxSecondaryButton
import dev.ririxi.bunnylock.ui.design.SxSectionHeader
import dev.ririxi.bunnylock.ui.design.SxSignalIndicator
import dev.ririxi.bunnylock.ui.design.SxSignalPill
import dev.ririxi.bunnylock.ui.design.SxSignalThresholds
import dev.ririxi.bunnylock.ui.design.SxSpacing
import dev.ririxi.bunnylock.ui.design.SxStatusChip
import dev.ririxi.bunnylock.ui.design.SxTone
import dev.ririxi.bunnylock.ui.design.mono
import dev.ririxi.bunnylock.ui.design.ratStyle
import dev.ririxi.bunnylock.ui.theme.LocalLockGreen
import dev.ririxi.bunnylock.ui.theme.LocalSignalScale

// Home pilot — Fase 1. Same data, same actions, new visual system.
// Everything on this screen still comes from the real modem state
// (state.modem) and every button still calls the real MainViewModel.
// What changed is presentation only: SxCard / SxSectionHeader / SxDataRow /
// SxSignalIndicator / SxStatusChip / SxSignalPill / SxDialog from
// ui/design, colours and type from the SignalX tokens.
// One addition, no removal: the visible-cells list (state.modem.cells,
// same sort and same CellItem as CellScreen) is now shown here too — the
// Fase 0 map said Home had it, but the old Home never rendered it; tapping
// a cell fills the lock form (vm.fillFrom) and goes to the Antenas tab.
//
// Fase 3: the Inicio summary (superprompt §3.2) — serving-cell summary
// (same card as Explorar, summary form), signal metrics with RSSI, quick
// actions (Explore / Antenas / Save measurement), the auto-collection
// line and the last saved measurement. Location is not shown on Inicio:
// the fix lives in the map and compass, where it is used.

@Composable
fun HomeScreen(
    state: UiState,
    vm: MainViewModel,
    contentPadding: PaddingValues,
    goTo: (Tab) -> Unit,
    goToBands: () -> Unit,
    goToSettings: () -> Unit,
) {
    val nowMs = rememberNowMs(state.modem.readingAtMs != null)
    val readingAgeSec = state.modem.readingAtMs?.let { ((nowMs - it) / 1000).coerceAtLeast(0) }
    LazyColumn(contentPadding = contentPadding.withScreenMargins(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Hero(state, onUnlock = vm::unlockEverything, onRelock = vm::relock, onBands = goToBands, onCell = { goTo(Tab.ANTENAS) })
        }

        val carriers = state.modem.carriers
        item {
            SxSectionHeader(stringResource(R.string.home_connection))
            if (carriers.isEmpty()) SxCard { SleepyBunny(stringResource(R.string.home_no_serving_cell)) } else ConnectionCard(state.modem)
        }

        // Fase 3: serving-cell summary — the same card as Explorar, in
        // summary form (eNB/sector, band, PCI, TAC, ECI, reading age).
        item {
            val reading = state.modem.servingReading()
            val carrier = reading?.carrier
            SxSectionHeader(stringResource(R.string.explorar_serving))
            if (carrier == null) {
                SxCard { SleepyBunny(stringResource(R.string.home_no_serving_cell)) }
            } else {
                ServingCellCard(carrier, reading.signal, reading.cell, state.modem, readingAgeSec, detailed = false)
            }
        }

        item {
            SxSectionHeader(stringResource(R.string.home_quick_actions))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SxSecondaryButton(
                        stringResource(R.string.home_explore),
                        onClick = { goTo(Tab.EXPLORAR) },
                        modifier = Modifier.weight(1f),
                        icon = Icons.Rounded.Search,
                    )
                    SxSecondaryButton(
                        stringResource(R.string.home_antennas),
                        onClick = { goTo(Tab.ANTENAS) },
                        modifier = Modifier.weight(1f),
                        icon = BunnyIcons.Tower,
                    )
                }
                SxPrimaryButton(
                    stringResource(R.string.explorar_save),
                    onClick = vm::saveMeasurement,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = state.modem.carriers.isNotEmpty() && !state.busy,
                )
            }
        }

        item {
            AutoCollectionRow(state.autoCollect, onOpenSettings = goToSettings)
        }

        // Recent activity, only when something has been saved.
        state.lastMeasurement?.let { last ->
            item {
                SxSectionHeader(stringResource(R.string.home_last_measurement))
                SxCard {
                    Text(lastIdentity(last), style = MaterialTheme.typography.titleMediumEmphasized.mono())
                    SxDataRow("RSRP", SxFormat.formatDbm(last.rsrp), monoValue = true)
                    SxDataRow(
                        stringResource(R.string.home_last_saved),
                        SxFormat.formatAge(((nowMs - last.timeMs) / 1000).coerceAtLeast(0)),
                    )
                    SxDataRow(stringResource(R.string.explorar_neighbors), last.neighborCount.toString(), monoValue = true)
                }
            }
        }

        if (carriers.isNotEmpty()) {
            item {
                val anchoredOnLte = carriers.any { it.primary && it.rat == Rat.LTE }
                SxSectionHeader(pluralStringResource(R.plurals.home_carriers_header, carriers.size), count = carriers.size)
                Segmented(Modifier.animateContentSize()) {
                    carriers.forEach { carrier -> item { shapes -> CarrierItem(shapes, carrier, anchoredOnLte) } }
                }
            }
        }

        item {
            val lockableCells =
                state.modem.cells
                    .filter { it.rat == Rat.LTE || it.rat == Rat.NR }
                    .sortedWith(compareByDescending<Cell> { it.registered }.thenByDescending { it.rat.ordinal }.thenBy { it.arfcn }.thenBy { it.pci })
            SxSectionHeader(pluralStringResource(R.plurals.home_visible_cells_header, lockableCells.size), count = lockableCells.size)
            if (lockableCells.isEmpty()) {
                SxCard { SleepyBunny(stringResource(R.string.cell_none_found), stringResource(R.string.cell_none_found_hint)) }
            } else {
                Segmented {
                    lockableCells.forEach { cell ->
                        item { shapes ->
                            CellItem(shapes, cell) {
                                vm.fillFrom(cell)
                                goTo(Tab.ANTENAS)
                            }
                        }
                    }
                }
            }
        }

        item {
            val device = state.modem.device
            val chipset = device?.let { stringResource(it.chipset.label) }.orEmpty()
            val deviceName = "${device?.manufacturer.orEmpty()} ${device?.model.orEmpty()}".trim()
            val platform =
                listOfNotNull(
                    device?.socModel?.ifEmpty { null },
                    device?.platform?.ifEmpty { null },
                ).joinToString(", ")
            val unknown = stringResource(R.string.common_unknown)
            SxSectionHeader(stringResource(R.string.common_modem))
            SxCard {
                SxDataRow(stringResource(R.string.home_chipset), chipset.ifEmpty { unknown })
                SxDataRow(stringResource(R.string.common_device), deviceName.ifEmpty { unknown })
                SxDataRow(stringResource(R.string.common_platform), platform.ifEmpty { unknown })
                SxDataRow(stringResource(R.string.common_baseband), device?.baseband.orEmpty().ifEmpty { unknown })
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun Hero(
    state: UiState,
    onUnlock: () -> Unit,
    onRelock: () -> Unit,
    onBands: () -> Unit,
    onCell: () -> Unit,
) {
    val locked = state.modem.anyBandLock || state.modem.anyCellLock || state.modem.anyPlmnLock
    val strayNote =
        state.modem.strayBand?.let { stringResource(R.string.home_stray_band, it) }
            ?: state.modem.strayChannel?.let { stringResource(R.string.home_stray_channel, it) }
    val lockHeld = locked && strayNote === null
    val green = LocalLockGreen.current
    val containerColor by animateColorAsState(
        if (lockHeld) {
            green.copy(alpha = 0.2f).compositeOver(MaterialTheme.colorScheme.surfaceContainerHigh)
        } else {
            MaterialTheme.colorScheme.surfaceContainerHigh
        },
        MaterialTheme.motionScheme.defaultEffectsSpec(),
        label = "hero",
    )
    val unlockLabel = stringResource(R.string.common_unlock_all)
    val relockLabel = stringResource(R.string.home_relock)
    val bandsLabel = stringResource(R.string.common_bands)
    val cellLabel = stringResource(R.string.common_cell)
    var confirmUnlock by rememberSaveable { mutableStateOf(false) }
    val askToUnlock = { confirmUnlock = true }
    val idle = !state.busy
    if (confirmUnlock) {
        SxDialog(
            title = stringResource(R.string.home_unlock_all_title),
            text = stringResource(R.string.home_unlock_all_message),
            confirmLabel = unlockLabel,
            onConfirm = {
                confirmUnlock = false
                onUnlock()
            },
            onDismiss = { confirmUnlock = false },
            dismissLabel = stringResource(R.string.common_cancel),
            icon = BunnyIcons.LockOpen,
            destructiveConfirm = true,
        )
    }
    Surface(color = containerColor, shape = RoundedCornerShape(SxSpacing.CardRadius), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(SxSpacing.CardPaddingLarge), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(
                            when {
                                strayNote !== null -> R.string.home_state_ignored
                                locked -> R.string.home_state_locked
                                else -> R.string.home_state_free
                            },
                        ),
                        style = MaterialTheme.typography.displaySmallEmphasized.copy(fontWeight = FontWeight.Bold),
                        color =
                            when {
                                strayNote !== null -> MaterialTheme.colorScheme.error
                                locked -> green
                                else -> MaterialTheme.colorScheme.onSurface
                            },
                    )
                    Text(
                        strayNote ?: stringResource(pinnedSummary(state)),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                BouncyLockBadge(locked = locked, ignored = strayNote !== null, userAction = state.busy, size = 88.dp)
            }

            val lockRows = activeLockRows(state)
            if (lockRows.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    lockRows.forEach { (label, value) -> SxDataRow(label, value) }
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val buttonHeight = ButtonDefaults.MediumContainerHeight
                if (locked) {
                    ButtonGroup(overflowIndicator = { ButtonGroupDefaults.OverflowIndicator(it) }) {
                        groupItem(unlockLabel, enabled = idle, onClick = askToUnlock) { modifier, interactionSource ->
                            Button(
                                onClick = askToUnlock,
                                enabled = idle,
                                shapes = ButtonDefaults.shapes(),
                                interactionSource = interactionSource,
                                modifier = modifier.height(buttonHeight),
                                contentPadding = ButtonDefaults.contentPaddingFor(buttonHeight),
                                colors =
                                    ButtonDefaults.buttonColors(
                                        containerColor = MaterialTheme.colorScheme.error,
                                        contentColor = MaterialTheme.colorScheme.onError,
                                    ),
                            ) {
                                Icon(
                                    BunnyIcons.LockOpen,
                                    contentDescription = null,
                                    modifier = Modifier.size(ButtonDefaults.iconSizeFor(buttonHeight)),
                                )
                                Spacer(Modifier.width(10.dp))
                                Text(unlockLabel, style = ButtonDefaults.textStyleFor(buttonHeight), maxLines = 1)
                            }
                        }
                        groupItem(relockLabel, enabled = idle, onClick = onRelock, weight = null) { modifier, interactionSource ->
                            FilledTonalButton(
                                onClick = onRelock,
                                enabled = idle,
                                shapes = ButtonDefaults.shapes(),
                                interactionSource = interactionSource,
                                modifier = modifier.height(buttonHeight),
                                contentPadding = ButtonDefaults.contentPaddingFor(buttonHeight),
                            ) {
                                Icon(
                                    Icons.Rounded.Refresh,
                                    contentDescription = relockLabel,
                                    modifier = Modifier.size(ButtonDefaults.iconSizeFor(buttonHeight)),
                                )
                            }
                        }
                    }
                }
                ButtonGroup(overflowIndicator = { ButtonGroupDefaults.OverflowIndicator(it) }) {
                    groupItem(bandsLabel, onClick = onBands) { modifier, interactionSource ->
                        FilledTonalButton(onClick = onBands, shapes = ButtonDefaults.shapes(), interactionSource = interactionSource, modifier = modifier) {
                            Text(bandsLabel)
                        }
                    }
                    groupItem(cellLabel, onClick = onCell) { modifier, interactionSource ->
                        FilledTonalButton(onClick = onCell, shapes = ButtonDefaults.shapes(), interactionSource = interactionSource, modifier = modifier) {
                            Text(cellLabel)
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
private fun ButtonGroupScope.groupItem(
    label: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    weight: Float? = 1f,
    button: @Composable (Modifier, MutableInteractionSource) -> Unit,
) = customItem(
    buttonGroupContent = {
        val interactionSource = remember { MutableInteractionSource() }
        val sized = if (weight !== null) Modifier.weight(weight) else Modifier
        button(sized.animateWidth(interactionSource), interactionSource)
    },
    menuContent = { menu ->
        DropdownMenuItem(
            text = { Text(label) },
            enabled = enabled,
            onClick = {
                menu.dismiss()
                onClick()
            },
        )
    },
)

@androidx.annotation.StringRes
private fun pinnedSummary(state: UiState): Int {
    val bands = state.modem.anyBandLock
    val cell = state.modem.anyCellLock
    val network = state.modem.anyPlmnLock
    return when {
        bands && cell && network -> R.string.home_pinned_all
        bands && cell -> R.string.home_pinned_bands_cell
        bands && network -> R.string.home_pinned_bands_network
        cell && network -> R.string.home_pinned_cell_network
        bands -> R.string.home_pinned_bands
        cell -> R.string.home_pinned_cell
        network -> R.string.home_pinned_network
        else -> R.string.home_pinned_nothing
    }
}

@Composable
private fun activeLockRows(state: UiState): List<Pair<String, String>> =
    buildList {
        state.modem.groups.filter { it.locked }.forEach { bandGroup ->
            val bands = bandGroup.current!!.sorted().joinToString(", ") { bandGroup.group.bandLabel(it) }
            add(bandGroup.group.label to bands)
        }
        state.modem.plmnLock?.let { add(stringResource(R.string.common_network) to it) }
        state.modem.lteLock?.let { add(stringResource(R.string.home_row_lte_cell) to it) }
        state.modem.nrLock?.let { add(stringResource(R.string.home_row_nr_cell) to it) }
        state.modem.legacyCellLock?.let { add(stringResource(R.string.home_row_legacy_cell) to it) }
    }

@Composable
private fun techSummary(carriers: List<Carrier>): String {
    val lteCount = carriers.count { it.rat == Rat.LTE }
    val technologies =
        listOfNotNull(
            when {
                lteCount > 1 -> "LTE-A"
                lteCount == 1 -> "LTE"
                else -> null
            },
            when {
                carriers.none { it.rat == Rat.NR } -> null
                lteCount > 0 -> "5G NSA"
                else -> "5G SA"
            },
            "3G".takeIf { carriers.any { it.rat == Rat.WCDMA } },
            "2G".takeIf { carriers.any { it.rat == Rat.GSM } },
        )
    val count = pluralStringResource(R.plurals.home_carrier_count, carriers.size, carriers.size)
    return stringResource(R.string.home_tech_summary, technologies.joinToString(" + "), count)
}

private fun totalBandwidth(carriers: List<Carrier>): String? {
    val known = carriers.mapNotNull { it.bandwidthKhz }
    if (known.isEmpty()) return null
    val more = if (known.size < carriers.size) "+" else ""
    return "${SxFormat.megahertz(known.sum())}$more MHz"
}

// 2G and 3G report RSSI, which runs higher than RSRP.
private fun dbmSteps(rat: Rat) = if (rat == Rat.LTE || rat == Rat.NR) SxSignalThresholds.Rsrp else SxSignalThresholds.PlainDbm

@Composable
private fun ConnectionCard(modem: ModemState) {
    val carriers = modem.carriers
    val network = modem.registeredName ?: modem.registeredPlmn?.let(::formatPlmn) ?: stringResource(R.string.home_unknown_network)
    val primaryRat = carriers.first().rat
    val signals = modem.signals.filter { it.rsrp !== null || it.rsrq !== null || it.sinr !== null }.sortedBy { it.rat != primaryRat }
    val motion = MaterialTheme.motionScheme
    SxCard(Modifier.animateContentSize(motion.defaultSpatialSpec()), padding = PaddingValues(SxSpacing.CardPaddingLarge)) {
        Row {
            Column(Modifier.weight(1f)) {
                Text(network, style = MaterialTheme.typography.titleMediumEmphasized)
                Text(techSummary(carriers), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            totalBandwidth(carriers)?.let { total ->
                Column(horizontalAlignment = Alignment.End) {
                    AnimatedContent(
                        targetState = total,
                        transitionSpec = {
                            (slideInVertically(motion.defaultSpatialSpec()) { it / 2 } + fadeIn(motion.defaultEffectsSpec())) togetherWith
                                (slideOutVertically(motion.defaultSpatialSpec()) { -it / 2 } + fadeOut(motion.fastEffectsSpec()))
                        },
                        label = "total bandwidth",
                    ) { shown -> Text(shown, style = MaterialTheme.typography.headlineSmallEmphasized) }
                    Text(
                        stringResource(R.string.home_total_bandwidth),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        if (carriers.all { it.bandwidthKhz !== null }) BandwidthBar(carriers)
        signals.forEach { signal -> SignalRow(signal, showRat = signals.size > 1) }
        FeatureTags(modem)
    }
}

@Composable
private fun FeatureTags(modem: ModemState) {
    val imsFeatures = ImsFeature.entries.mapNotNull { feature -> (modem.ims[feature] as? ImsSetting.Readable)?.let { feature.label to it.enabled } }
    val features = imsFeatures + listOfNotNull(modem.carrierAggregation?.let { "CA" to it })
    if (features.isEmpty()) return
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        features.forEach { (name, on) ->
            SxStatusChip(
                stringResource(if (on) R.string.home_feature_on else R.string.home_feature_off, name),
                tone = if (on) SxTone.Signal else SxTone.Neutral,
            )
        }
    }
}

@Composable
private fun BandwidthBar(carriers: List<Carrier>) {
    Row(Modifier.fillMaxWidth().height(10.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        val motion = MaterialTheme.motionScheme
        carriers.forEach { carrier ->
            val width = carrier.bandwidthKhz ?: return@forEach
            key(carrier.rat, carrier.arfcn, carrier.pci) {
                val shownWidth = remember { Animatable(0f) }
                LaunchedEffect(width) { shownWidth.animateTo(width.toFloat(), motion.defaultSpatialSpec()) }
                val color by animateColorAsState(ratStyle(carrier.rat).content.copy(alpha = if (carrier.primary) 1f else 0.55f), label = "carrier")
                Box(
                    Modifier
                        .weight(shownWidth.value.coerceAtLeast(1f))
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(5.dp))
                        .background(color),
                )
            }
        }
    }
}

@Composable
private fun SignalRow(
    signal: Signal,
    showRat: Boolean,
) {
    val scale = LocalSignalScale.current
    // Two rows of two: RSRP/RSRQ over SINR/RSSI. Every metric renders
    // with its number and word even when the modem reports nothing
    // (SxSignalIndicator shows "—"); RSSI is LTE-only, since Android
    // reports no RSSI for NR.
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically) {
            if (showRat) RatBadge(signal.rat, size = 32.dp)
            SxSignalIndicator("RSRP", signal.rsrp, "dBm", SxSignalThresholds.Rsrp, scale, Modifier.weight(1f))
            if (signal.rat == Rat.LTE || signal.rat == Rat.NR) {
                SxSignalIndicator("RSRQ", signal.rsrq, "dB", SxSignalThresholds.Rsrq, scale, Modifier.weight(1f))
            }
        }
        if (signal.rat == Rat.LTE || signal.rat == Rat.NR) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                SxSignalIndicator("SINR", signal.sinr, "dB", SxSignalThresholds.Sinr, scale, Modifier.weight(1f))
                if (signal.rat == Rat.LTE) {
                    SxSignalIndicator("RSSI", signal.rssi, "dBm", SxSignalThresholds.PlainDbm, scale, Modifier.weight(1f))
                } else {
                    Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

private fun lastIdentity(last: MeasurementSummary): String =
    when {
        last.eci != null -> SxFormat.formatEnbSector(last.eci)
        last.nci != null -> SxFormat.formatNci(last.nci)
        else -> ratDisplayName(last.rat)
    }

@Composable
private fun RoleTag(
    carrier: Carrier,
    anchoredOnLte: Boolean,
) {
    when {
        carrier.primary -> SxStatusChip(stringResource(R.string.home_tag_primary), SxTone.Neutral)
        carrier.rat == Rat.NR && anchoredOnLte -> SxStatusChip("5G NSA", SxTone.Info)
        else -> SxStatusChip(stringResource(R.string.home_tag_secondary), SxTone.Neutral)
    }
}

@Composable
private fun CellTag(cell: Cell) {
    when {
        cell.note == "NSA SCG" -> SxStatusChip("5G NSA", SxTone.Info)
        cell.registered -> SxStatusChip(stringResource(R.string.home_tag_serving), SxTone.Signal)
    }
}

private fun radioDetails(
    rat: Rat,
    arfcn: Long?,
    pci: Int?,
    bandwidthKhz: Int?,
): String =
    listOfNotNull(
        arfcn?.let { "${SxFormat.channelName(rat)} $it" },
        pci?.let { "PCI $it" },
        bandwidthKhz?.let { "${SxFormat.megahertz(it)} MHz" },
    ).joinToString(" · ")

@Composable
private fun RadioRow(
    shapes: ListItemShapes,
    rat: Rat,
    title: String,
    frequencyName: String?,
    details: String,
    rsrp: Int?,
    tag: @Composable () -> Unit,
    onClick: (() -> Unit)? = null,
) {
    val leading: @Composable () -> Unit = { RatBadge(rat) }
    val trailing: @Composable () -> Unit = { rsrp?.let { SxSignalPill(it, dbmSteps(rat), LocalSignalScale.current) } }
    val supporting: (@Composable () -> Unit)? = if (details.isEmpty()) null else ({ Text(details) })
    val content: @Composable () -> Unit = {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, style = MaterialTheme.typography.bodyLargeEmphasized)
            frequencyName?.let { Text(it, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            tag()
        }
    }
    if (onClick !== null) {
        SegmentedListItem(
            onClick = onClick,
            shapes = shapes,
            colors = segmentColors(),
            leadingContent = leading,
            trailingContent = trailing,
            supportingContent = supporting,
            content = content,
        )
    } else {
        SegmentedListItem(
            shapes = shapes,
            colors = segmentColors(),
            leadingContent = leading,
            trailingContent = trailing,
            supportingContent = supporting,
            content = content,
        )
    }
}

@Composable
private fun CarrierItem(
    shapes: ListItemShapes,
    carrier: Carrier,
    anchoredOnLte: Boolean,
) = RadioRow(
    shapes,
    carrier.rat,
    if (carrier.band !== null) carrier.bandLabel else stringResource(R.string.common_unknown_band),
    carrier.frequencyName,
    radioDetails(carrier.rat, carrier.arfcn, carrier.pci, carrier.bandwidthKhz),
    carrier.rsrp,
    tag = { RoleTag(carrier, anchoredOnLte) },
)

@Composable
fun CellItem(
    shapes: ListItemShapes,
    cell: Cell,
    onClick: (() -> Unit)? = null,
) = RadioRow(
    shapes,
    cell.rat,
    if (cell.band !== null) cell.bandLabel else stringResource(R.string.common_unknown_band),
    cell.frequencyName,
    radioDetails(cell.rat, cell.arfcn, cell.pci.takeIf { it >= 0 }, cell.bandwidthKhz),
    cell.rsrp,
    tag = { CellTag(cell) },
    onClick = onClick,
)
