// Antenas — SignalX antenna catalog + BunnyLock cell lock (superprompt §5, Fase 4).
//
// One tab, two sections, switched at the top (the same ConnectedChoice
// the app already uses elsewhere): the SignalX Catalog — search, filter,
// cards, manual placement, target selection — and BunnyLock's CellScreen
// reused as-is for the cell lock. The catalog is the exploration layer
// on top; it is NOT a second lock, and CellScreen is not duplicated or
// modified.
//
// "Ver en mapa" (§10.2) is real now (Fase 5): tapping a card or "View
// on map" stores the antenna as the target AND opens Herramientas >
// Orientación, where the map centers on it and shows its info. Placing
// a position by tapping the map is Fase 5 as well: the place dialog
// offers "Choose on map", which arms the map's placing mode for that eNB.

@file:Suppress("FunctionName")

package dev.ririxi.bunnylock.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.ririxi.bunnylock.R
import dev.ririxi.bunnylock.signalx.AntennaFilter
import dev.ririxi.bunnylock.signalx.AntennaRow
import dev.ririxi.bunnylock.signalx.AntennaSource
import dev.ririxi.bunnylock.signalx.antennaMatchesQuery
import dev.ririxi.bunnylock.signalx.matches
import dev.ririxi.bunnylock.signalx.parsePlaceInput
import dev.ririxi.bunnylock.ui.design.SxBottomSheet
import dev.ririxi.bunnylock.ui.design.SxCard
import dev.ririxi.bunnylock.ui.design.SxDataRow
import dev.ririxi.bunnylock.ui.design.SxDestructiveButton
import dev.ririxi.bunnylock.ui.design.SxDialog
import dev.ririxi.bunnylock.ui.design.SxEmptyState
import dev.ririxi.bunnylock.ui.design.SxFormat
import dev.ririxi.bunnylock.ui.design.SxPrimaryButton
import dev.ririxi.bunnylock.ui.design.SxScreenHeader
import dev.ririxi.bunnylock.ui.design.SxSecondaryButton
import dev.ririxi.bunnylock.ui.design.SxSpacing
import dev.ririxi.bunnylock.ui.design.SxStatusChip
import dev.ririxi.bunnylock.ui.design.SxTone

/** The two sections of the Antenas tab. */
enum class AntennasSection(
    @StringRes val label: Int,
) {
    CATALOG(R.string.antenas_section_catalog),
    CELL_LOCK(R.string.antenas_section_cell_lock),
}

@Composable
fun AntennasScreen(
    state: UiState,
    vm: MainViewModel,
    contentPadding: PaddingValues,
    section: AntennasSection,
    onSection: (AntennasSection) -> Unit,
    onViewOnMap: (AntennaRow) -> Unit,
    onPlaceOnMap: (Int) -> Unit,
) {
    val layoutDirection = LocalLayoutDirection.current
    Column(Modifier.fillMaxSize()) {
        ConnectedChoice(
            options = AntennasSection.entries,
            selected = section,
            label = { stringResource(it.label) },
            onSelect = onSection,
            modifier =
                Modifier.padding(
                    start = contentPadding.calculateStartPadding(layoutDirection) + SxSpacing.ScreenHorizontal,
                    end = contentPadding.calculateEndPadding(layoutDirection) + SxSpacing.ScreenHorizontal,
                    top = SxSpacing.ScreenTop,
                    bottom = SxSpacing.Group,
                ),
        )
        Box(Modifier.weight(1f)) {
            when (section) {
                AntennasSection.CATALOG -> AntennasCatalog(state, vm, contentPadding, onViewOnMap, onPlaceOnMap)
                AntennasSection.CELL_LOCK -> CellScreen(state, vm, contentPadding)
            }
        }
    }
}

@Composable
private fun AntennasCatalog(
    state: UiState,
    vm: MainViewModel,
    contentPadding: PaddingValues,
    onViewOnMap: (AntennaRow) -> Unit,
    onPlaceOnMap: (Int) -> Unit,
) {
    val rows = state.antennas
    val query = state.antennasQuery
    val filter = state.antennasFilter
    val target = state.antennaTarget
    val filtered =
        remember(rows, query, filter) {
            rows.filter { antennaMatchesQuery(it, query) && filter.matches(it) }
        }
    val onMapCount = remember(rows) { rows.count { it.hasPosition } }
    var filterSheet by rememberSaveable { mutableStateOf(false) }
    var placeEnb by rememberSaveable { mutableStateOf<Int?>(null) }
    var removeEnb by rememberSaveable { mutableStateOf<Int?>(null) }
    val placeRow = placeEnb?.let { enb -> rows.firstOrNull { it.enb == enb } }
    val removeRow = removeEnb?.let { enb -> rows.firstOrNull { it.enb == enb } }

    LazyColumn(contentPadding = contentPadding.withScreenMargins(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { SxScreenHeader(stringResource(R.string.shell_tab_antenas)) }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = query,
                    onValueChange = vm::setAntennasQuery,
                    modifier = Modifier.weight(1f),
                    placeholder = { Text(stringResource(R.string.antenas_search_hint)) },
                    leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                    trailingIcon = {
                        if (query.isNotEmpty()) {
                            IconButton(onClick = { vm.setAntennasQuery("") }) {
                                Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.antenas_search_clear))
                            }
                        }
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                )
                FilledTonalButton(
                    onClick = { filterSheet = true },
                    modifier = Modifier.height(56.dp),
                    shape = RoundedCornerShape(SxSpacing.ButtonRadius),
                ) {
                    Text(stringResource(R.string.antenas_filter_button, stringResource(antennaFilterLabel(filter))), maxLines = 1)
                    Spacer(Modifier.width(4.dp))
                    Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = null)
                }
            }
        }

        item {
            Text(
                if (query.isBlank() && filter == AntennaFilter.ALL) {
                    stringResource(R.string.antenas_count, rows.size, onMapCount)
                } else {
                    stringResource(R.string.antenas_results, filtered.size, rows.size)
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (rows.isEmpty()) {
            item {
                SxEmptyState(
                    title = stringResource(R.string.antenas_empty),
                    icon = BunnyIcons.Tower,
                )
            }
        } else if (filtered.isEmpty()) {
            item {
                SxEmptyState(
                    title =
                        if (query.isNotBlank()) {
                            stringResource(R.string.antenas_no_results, query)
                        } else {
                            stringResource(R.string.antenas_filter_no_results)
                        },
                    icon = BunnyIcons.Tower,
                    actionLabel = stringResource(R.string.antenas_clear_all),
                    onAction = {
                        vm.setAntennasQuery("")
                        vm.setAntennasFilter(AntennaFilter.ALL)
                    },
                )
            }
        } else {
            items(filtered, key = { it.enb }) { row ->
                AntennaCard(
                    row = row,
                    isTarget = target?.enb == row.enb,
                    onSelectTarget = { vm.selectAntennaTarget(row) },
                    onViewOnMap = { onViewOnMap(row) },
                    onPlace = { placeEnb = row.enb },
                    onRemove = { removeEnb = row.enb },
                )
            }
        }
    }

    if (filterSheet) {
        SxBottomSheet(onDismiss = { filterSheet = false }) {
            Text(stringResource(R.string.antenas_filter_title), style = MaterialTheme.typography.titleMediumEmphasized)
            Segmented {
                AntennaFilter.entries.forEach { option ->
                    item { shapes ->
                        SegmentedListItem(
                            onClick = {
                                vm.setAntennasFilter(option)
                                filterSheet = false
                            },
                            shapes = shapes,
                            colors = segmentColors(),
                            trailingContent = {
                                if (option == filter) Icon(Icons.Rounded.Check, contentDescription = null)
                            },
                        ) { Text(stringResource(antennaFilterLabel(option))) }
                    }
                }
            }
        }
    }

    placeRow?.let { row ->
        PlaceAntennaDialog(
            row = row,
            onDismiss = { placeEnb = null },
            onSave = { lat, lon ->
                vm.saveTowerPosition(row.enb, lat, lon)
                placeEnb = null
            },
            onPlaceOnMap = {
                placeEnb = null
                onPlaceOnMap(row.enb)
            },
        )
    }

    removeRow?.let { row ->
        SxDialog(
            title = stringResource(R.string.antenas_remove_title),
            text = stringResource(R.string.antenas_remove_message, row.enb),
            confirmLabel = stringResource(R.string.antenas_remove_pos),
            onConfirm = {
                vm.removeTowerPosition(row.enb)
                removeEnb = null
            },
            onDismiss = { removeEnb = null },
            dismissLabel = stringResource(R.string.common_cancel),
            destructiveConfirm = true,
        )
    }
}

@Composable
private fun AntennaCard(
    row: AntennaRow,
    isTarget: Boolean,
    onSelectTarget: () -> Unit,
    onViewOnMap: () -> Unit,
    onPlace: () -> Unit,
    onRemove: () -> Unit,
) {
    // Tapping the card navigates like "View on map" (D-F1 fix, §10.2):
    // it sets the target AND opens the map on it.
    SxCard(onClick = if (row.hasPosition && !isTarget) onViewOnMap else null) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                BunnyIcons.Tower,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(SxSpacing.IconMedium),
            )
            Text(
                "eNB ${row.enb}",
                style = MaterialTheme.typography.titleMediumEmphasized,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            if (isTarget) SxStatusChip(stringResource(R.string.antenas_target), SxTone.Signal)
            SxStatusChip(positionLabel(row), positionTone(row.source))
        }
        antennaSubLine(row)?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (row.areaNote.isNotBlank()) {
            Text(row.areaNote, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (row.sectors.isNotEmpty()) {
            Text(
                stringResource(R.string.antenas_sectors, row.sectors.joinToString(", ")),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (row.hasPosition) {
            SxDataRow(
                label = stringResource(R.string.antenas_position),
                value = SxFormat.formatCoordinates(row.lat, row.lon),
                monoValue = true,
            )
            // Distance only when the user has a fix — until then the row
            // stays hidden and nothing shows a fake distance.
            row.distanceMeters?.let { meters ->
                SxDataRow(
                    label = stringResource(R.string.antenas_distance),
                    value = SxFormat.formatDistance(meters, estimated = row.source == AntennaSource.ESTIMATED),
                    monoValue = true,
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (row.hasPosition) {
                SxPrimaryButton(
                    text = stringResource(R.string.antenas_set_target),
                    onClick = onSelectTarget,
                    modifier = Modifier.weight(1f),
                    enabled = !isTarget,
                )
                SxSecondaryButton(
                    text = stringResource(R.string.antenas_view_on_map),
                    onClick = onViewOnMap,
                    modifier = Modifier.weight(1f),
                )
            } else {
                SxPrimaryButton(
                    text = stringResource(R.string.antenas_place),
                    onClick = onPlace,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        if (row.source == AntennaSource.MANUAL) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SxSecondaryButton(
                    text = stringResource(R.string.antenas_move),
                    onClick = onPlace,
                    modifier = Modifier.weight(1f),
                )
                SxDestructiveButton(
                    text = stringResource(R.string.antenas_remove_pos),
                    onClick = onRemove,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/** "B1+3+8 · Centro-este · 3 observed cells" — parts that are empty are left out. */
@Composable
private fun antennaSubLine(row: AntennaRow): String? {
    val parts =
        buildList {
            if (row.bands.isNotEmpty()) add(row.bands)
            if (row.zone.isNotEmpty()) add(row.zone)
            if (row.observedCellCount == 1) add(stringResource(R.string.antenas_observed_cell_one))
            if (row.observedCellCount > 1) add(stringResource(R.string.antenas_observed_cells, row.observedCellCount))
        }
    return parts.joinToString(" · ").takeIf { it.isNotEmpty() }
}

/** The position chip, as in SignalX 1.0.8: an estimated position always says its margin, never reads as exact. */
@Composable
private fun positionLabel(row: AntennaRow): String =
    when (row.source) {
        AntennaSource.MANUAL -> stringResource(R.string.antenas_pos_manual)
        AntennaSource.OBSERVED -> stringResource(R.string.antenas_pos_observed)
        AntennaSource.ESTIMATED ->
            if (row.rangeM > 0) {
                stringResource(R.string.antenas_pos_estimated, row.rangeM)
            } else {
                stringResource(R.string.antenas_pos_estimated_no_range)
            }

        AntennaSource.NONE -> stringResource(R.string.antenas_no_pos)
    }

private fun positionTone(source: AntennaSource): SxTone =
    when (source) {
        AntennaSource.MANUAL -> SxTone.Info
        AntennaSource.OBSERVED -> SxTone.Signal
        AntennaSource.ESTIMATED, AntennaSource.NONE -> SxTone.Neutral
    }

/** The filter's label, shared with Diagnóstico (one name for a filter everywhere, §10.6). */
@StringRes
fun antennaFilterLabel(filter: AntennaFilter): Int =
    when (filter) {
        AntennaFilter.ALL -> R.string.antenas_filter_all
        AntennaFilter.ON_MAP -> R.string.antenas_filter_onmap
        AntennaFilter.NO_POSITION -> R.string.antenas_filter_nopos
        AntennaFilter.ESTIMATED -> R.string.antenas_filter_estimated
        AntennaFilter.MANUAL -> R.string.antenas_filter_manual
        AntennaFilter.OBSERVED -> R.string.antenas_filter_observed
    }

/**
 * Place / move dialog: coordinates typed by hand (decimal degrees, comma
 * accepted), or "Choose on map" to place by tapping the map (Fase 5).
 */
@Composable
private fun PlaceAntennaDialog(
    row: AntennaRow,
    onDismiss: () -> Unit,
    onSave: (Double, Double) -> Unit,
    onPlaceOnMap: () -> Unit,
) {
    val moving = row.source == AntennaSource.MANUAL
    var latText by rememberSaveable { mutableStateOf(row.lat?.toString() ?: "") }
    var lonText by rememberSaveable { mutableStateOf(row.lon?.toString() ?: "") }
    var invalid by rememberSaveable { mutableStateOf(false) }
    // One dialog pattern for the whole app (Fase 7): SxDialog with a
    // custom content slot for the coordinate fields. "Choose on map" is
    // the secondary action; Save validates before it closes anything.
    SxDialog(
        title =
            stringResource(
                if (moving) R.string.antenas_move_title else R.string.antenas_place_title,
                row.enb,
            ),
        confirmLabel = stringResource(R.string.common_save),
        onConfirm = {
            val coords = parsePlaceInput(latText, lonText)
            if (coords == null) {
                invalid = true
            } else {
                onSave(coords.first, coords.second)
            }
        },
        onDismiss = onDismiss,
        dismissLabel = stringResource(R.string.common_cancel),
        secondaryLabel = stringResource(R.string.antenas_place_on_map),
        onSecondary = onPlaceOnMap,
        content = {
            Text(
                stringResource(R.string.antenas_place_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = latText,
                onValueChange = { latText = it; invalid = false },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.antenas_place_lat)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Next),
            )
            OutlinedTextField(
                value = lonText,
                onValueChange = { lonText = it; invalid = false },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.antenas_place_lon)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
            )
            if (invalid) {
                Text(
                    stringResource(R.string.antenas_place_invalid),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        },
    )
}
