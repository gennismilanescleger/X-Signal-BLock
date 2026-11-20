// Data — saved field records (superprompt §6.4; Fase 6).
//
// One screen, four record types, kept apart (§6.4: never mix them as
// if they were the same thing):
//   - Measurements: saved observations (serving + neighbors), newest
//     first. Search filters them; tapping one opens its detail
//     (serving values + the neighbor list); the trash icon deletes it
//     with confirmation (measurement + its neighbors, one transaction).
//   - Points: saved install points (GPS + azimuth + signal), with
//     whether a photo is attached — and whether that photo file is
//     still on the phone. Deleting a point never deletes its photo.
//   - Photos: the field-camera registry (file name, position,
//     azimuth). Deleting one deletes its file too.
//   - Observed cells: the LTE registry built by saved measurements.
//     Deleting one only removes it from the registry.
//
// Export / import (JSON, FieldDataSync): export never deletes
// anything; import merges without duplicating or resetting the base.
// Delete-all is at the bottom, behind its own confirmation that says
// exactly what goes. Crash logs and maps are never touched here.

@file:Suppress("FunctionName")

package dev.ririxi.bunnylock.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.List
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import dev.ririxi.bunnylock.R
import dev.ririxi.bunnylock.signalx.DbCounts
import dev.ririxi.bunnylock.signalx.ObservationDetail
import dev.ririxi.bunnylock.signalx.ObservedCellRow
import dev.ririxi.bunnylock.signalx.PhotoRecord
import dev.ririxi.bunnylock.signalx.SavedPoint
import dev.ririxi.bunnylock.ui.design.SxCard
import dev.ririxi.bunnylock.ui.design.SxDestructiveButton
import dev.ririxi.bunnylock.ui.design.SxDialog
import dev.ririxi.bunnylock.ui.design.SxEmptyState
import dev.ririxi.bunnylock.ui.design.SxFormat
import dev.ririxi.bunnylock.ui.design.SxScreenHeader
import dev.ririxi.bunnylock.ui.design.SxSecondaryButton
import dev.ririxi.bunnylock.ui.design.SxSectionHeader
import dev.ririxi.bunnylock.ui.design.mono
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Everything Datos shows, loaded in one VM call (Fase 6). */
data class DataSnapshot(
    val measurements: List<dev.ririxi.bunnylock.signalx.MeasurementSummary>,
    val points: List<SavedPoint>,
    val photos: List<PhotoRecord>,
    val cells: List<ObservedCellRow>,
    val counts: DbCounts,
)

private sealed class DeleteTarget {
    data class Measurement(val id: Long, val neighbors: Int) : DeleteTarget()
    data class Point(val id: Long) : DeleteTarget()
    data class Photo(val id: Long) : DeleteTarget()
    data class Cell(val eci: Long) : DeleteTarget()
    data object All : DeleteTarget()
}

@Composable
fun DataScreen(
    vm: MainViewModel,
    contentPadding: PaddingValues,
) {
    val scope = rememberCoroutineScope()
    var query by rememberSaveable { mutableStateOf("") }
    var refreshTick by remember { mutableIntStateOf(0) }
    var snapshot by remember { mutableStateOf<DataSnapshot?>(null) }
    var detail by remember { mutableStateOf<ObservationDetail?>(null) }
    var deleteTarget by remember { mutableStateOf<DeleteTarget?>(null) }

    LaunchedEffect(query, refreshTick) {
        snapshot = vm.loadDataSnapshot(query)
    }
    fun refresh() {
        refreshTick++
    }

    val exportLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            if (uri == null) return@rememberLauncherForActivityResult
            scope.launch {
                vm.exportTo(uri)
                refresh()
            }
        }
    val importLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri == null) return@rememberLauncherForActivityResult
            scope.launch {
                vm.importFrom(uri)
                refresh()
            }
        }

    LazyColumn(contentPadding = contentPadding.withScreenMargins(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { SxScreenHeader(stringResource(R.string.data_title)) }

        item {
            Text(
                stringResource(R.string.data_export_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SxSecondaryButton(
                    text = stringResource(R.string.data_export),
                    onClick = {
                        val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())
                        exportLauncher.launch("signalx-data-$stamp.json")
                    },
                    modifier = Modifier.weight(1f),
                )
                SxSecondaryButton(
                    text = stringResource(R.string.data_import),
                    onClick = { importLauncher.launch(arrayOf("application/json")) },
                    modifier = Modifier.weight(1f),
                )
            }
            Text(
                stringResource(R.string.data_import_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }

        item {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                placeholder = { Text(stringResource(R.string.data_search_hint)) },
                leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { query = "" }) {
                            Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.common_close))
                        }
                    }
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            )
        }

        val snap = snapshot
        if (snap == null) {
            item {
                SxEmptyState(title = stringResource(R.string.data_empty_measurements), icon = Icons.Rounded.List)
            }
        } else {
            item { SxSectionHeader(stringResource(R.string.data_measurements), snap.measurements.size) }
            if (snap.measurements.isEmpty()) {
                item {
                    SxEmptyState(
                        title = if (query.isEmpty()) stringResource(R.string.data_empty_measurements) else stringResource(R.string.data_empty_search),
                        icon = Icons.Rounded.List,
                    )
                }
            } else {
                snap.measurements.forEach { m ->
                    item {
                        SxCard(onClick = { scope.launch { detail = vm.loadObservationDetail(m.id) } }) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        when {
                                            m.eci != null -> SxFormat.formatEnbSector(m.eci)
                                            m.nci != null -> SxFormat.formatNci(m.nci)
                                            else -> m.rat.name
                                        },
                                        style = MaterialTheme.typography.titleMediumEmphasized.mono(),
                                    )
                                    Text(
                                        SxFormat.formatDateTime(m.timeMs) + " · " +
                                            SxFormat.formatDbm(m.rsrp) +
                                            " · " + stringResource(R.string.data_neighbors) + ": ${m.neighborCount}",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                IconButton(onClick = { deleteTarget = DeleteTarget.Measurement(m.id, m.neighborCount) }) {
                                    Icon(Icons.Rounded.Delete, contentDescription = stringResource(R.string.data_delete))
                                }
                            }
                        }
                    }
                }
            }

            item { SxSectionHeader(stringResource(R.string.data_points), snap.points.size) }
            if (snap.points.isEmpty()) {
                item {
                    SxEmptyState(
                        title = if (query.isEmpty()) stringResource(R.string.data_empty_points) else stringResource(R.string.data_empty_search),
                        icon = Icons.Rounded.List,
                    )
                }
            } else {
                snap.points.forEach { p ->
                    item {
                        SxCard {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        SxFormat.formatCoordinates(p.lat, p.lon),
                                        style = MaterialTheme.typography.titleMediumEmphasized.mono(),
                                    )
                                    Text(
                                        SxFormat.formatDateTime(p.time) + " · " +
                                            SxFormat.formatDbm(p.rsrp) +
                                            (p.azimuth?.let { " · " + SxFormat.formatAzimuth(it) } ?: ""),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    // Only when the point carries a photo: no empty line otherwise.
                                    when {
                                        p.photoPath == null -> {}
                                        File(p.photoPath).exists() ->
                                            Text(
                                                stringResource(R.string.data_photo_attached) + " — " + File(p.photoPath).name,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )

                                        else ->
                                            Text(
                                                stringResource(R.string.data_photo_missing),
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                    }
                                }
                                IconButton(onClick = { deleteTarget = DeleteTarget.Point(p.id) }) {
                                    Icon(Icons.Rounded.Delete, contentDescription = stringResource(R.string.data_delete))
                                }
                            }
                        }
                    }
                }
            }

            item { SxSectionHeader(stringResource(R.string.data_photos), snap.photos.size) }
            if (snap.photos.isEmpty()) {
                item {
                    SxEmptyState(
                        title = if (query.isEmpty()) stringResource(R.string.data_empty_photos) else stringResource(R.string.data_empty_search),
                        icon = Icons.Rounded.List,
                    )
                }
            } else {
                snap.photos.forEach { ph ->
                    item {
                        SxCard {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        File(ph.path).name,
                                        style = MaterialTheme.typography.titleMediumEmphasized.mono(),
                                    )
                                    Text(
                                        SxFormat.formatDateTime(ph.time) + " · " +
                                            SxFormat.formatCoordinates(ph.lat, ph.lon) +
                                            (ph.azimuth?.let { " · " + SxFormat.formatAzimuth(it) } ?: ""),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                IconButton(onClick = { deleteTarget = DeleteTarget.Photo(ph.id) }) {
                                    Icon(Icons.Rounded.Delete, contentDescription = stringResource(R.string.data_delete))
                                }
                            }
                        }
                    }
                }
            }

            item { SxSectionHeader(stringResource(R.string.data_cells), snap.cells.size) }
            if (snap.cells.isEmpty()) {
                item {
                    SxEmptyState(
                        title = if (query.isEmpty()) stringResource(R.string.data_empty_cells) else stringResource(R.string.data_empty_search),
                        icon = Icons.Rounded.List,
                    )
                }
            } else {
                snap.cells.forEach { c ->
                    item {
                        SxCard {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        SxFormat.formatEnbSector(c.eci) + (c.band?.let { " · $it" } ?: ""),
                                        style = MaterialTheme.typography.titleMediumEmphasized.mono(),
                                    )
                                    Text(
                                        (c.time?.let { SxFormat.formatDateTime(it) } ?: SxFormat.Unavailable) + " · " +
                                            SxFormat.formatDbm(c.rsrp),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                IconButton(onClick = { deleteTarget = DeleteTarget.Cell(c.eci) }) {
                                    Icon(Icons.Rounded.Delete, contentDescription = stringResource(R.string.data_delete))
                                }
                            }
                        }
                    }
                }
            }

            item {
                SxDestructiveButton(
                    text = stringResource(R.string.data_delete_all),
                    onClick = { deleteTarget = DeleteTarget.All },
                    modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                )
            }
        }
    }

    detail?.let { d ->
        // Information only: one "Close" button, no second "Cancel" doing
        // the same thing (Fase 7).
        SxDialog(
            title = stringResource(R.string.data_measurements) + " — " +
                when {
                    d.eci != null -> SxFormat.formatEnbSector(d.eci)
                    d.nci != null -> SxFormat.formatNci(d.nci)
                    else -> d.rat.name
                },
            text = measurementDetailText(d),
            confirmLabel = stringResource(R.string.common_close),
            onConfirm = { detail = null },
            onDismiss = { detail = null },
        )
    }

    deleteTarget?.let { target ->
        val counts = snapshot?.counts
        SxDialog(
            title =
                when (target) {
                    is DeleteTarget.Measurement -> stringResource(R.string.data_delete_measurement_title)
                    is DeleteTarget.Point -> stringResource(R.string.data_delete_point_title)
                    is DeleteTarget.Photo -> stringResource(R.string.data_delete_photo_title)
                    is DeleteTarget.Cell -> stringResource(R.string.data_delete_cell_title)
                    DeleteTarget.All -> stringResource(R.string.data_delete_all_title)
                },
            text =
                when (target) {
                    is DeleteTarget.Measurement -> stringResource(R.string.data_delete_measurement, target.neighbors)
                    is DeleteTarget.Point -> stringResource(R.string.data_delete_point)
                    is DeleteTarget.Photo -> stringResource(R.string.data_delete_photo)
                    is DeleteTarget.Cell -> stringResource(R.string.data_delete_cell)
                    DeleteTarget.All ->
                        stringResource(
                            R.string.data_delete_all_message,
                            counts?.observations ?: 0,
                            counts?.points ?: 0,
                            counts?.photos ?: 0,
                            counts?.cells ?: 0,
                            counts?.towerPositions ?: 0,
                        )
                },
            confirmLabel = stringResource(R.string.data_delete),
            onConfirm = {
                when (target) {
                    is DeleteTarget.Measurement -> vm.deleteObservation(target.id)
                    is DeleteTarget.Point -> vm.deletePoint(target.id)
                    is DeleteTarget.Photo -> vm.deletePhoto(target.id)
                    is DeleteTarget.Cell -> vm.deleteObservedCell(target.eci)
                    DeleteTarget.All -> vm.deleteAllFieldData()
                }
                deleteTarget = null
                refresh()
            },
            onDismiss = { deleteTarget = null },
            dismissLabel = stringResource(R.string.common_cancel),
            destructiveConfirm = true,
        )
    }
}

/** Plain-text detail of one measurement: serving values, then the neighbors (§8.1: one dialog pattern, scrollable text). */
private fun measurementDetailText(d: ObservationDetail): String {
    val lines = ArrayList<String>()
    lines += "RAT: ${d.rat.name}"
    if (d.eci != null) lines += "ECI: ${d.eci} (${SxFormat.formatEnbSector(d.eci)})"
    if (d.nci != null) lines += "NCI: ${d.nci}"
    if (d.tac != null) lines += "TAC: ${d.tac}"
    if (d.pci != null) lines += "PCI: ${d.pci}"
    if (d.earfcn != null) lines += "${SxFormat.channelName(d.rat)}: ${d.earfcn}"
    if (d.band != null) lines += "Band: ${d.band}"
    if (d.plmn != null) lines += "PLMN: ${d.plmn}"
    lines += "RSRP: " + SxFormat.formatDbm(d.rsrp)
    lines += "RSRQ: " + SxFormat.formatDb(d.rsrq)
    lines += "RSSI: " + SxFormat.formatDbm(d.rssi)
    lines += "SINR: " + SxFormat.formatDb(d.sinr)
    lines += "Position: " + SxFormat.formatCoordinates(d.lat, d.lon)
    if (d.accuracy != null) lines += "GPS precision: " + SxFormat.formatAccuracy(d.accuracy)
    if (d.azimuth != null) lines += "Azimuth: " + SxFormat.formatAzimuth(d.azimuth)
    lines += "Source: ${d.source}"
    lines += "Saved: " + SxFormat.formatDateTime(d.time)
    if (d.readingTime != null) lines += "Reading: " + SxFormat.formatDateTime(d.readingTime)
    if (d.neighbors.isNotEmpty()) {
        lines += ""
        lines += "Neighbors (${d.neighbors.size}):"
        for (n in d.neighbors) {
            val id =
                when {
                    n.eci != null -> SxFormat.formatEnbSector(n.eci)
                    n.nci != null -> SxFormat.formatNci(n.nci)
                    else -> n.rat.name
                }
            lines += "· $id" + (n.band?.let { " · $it" } ?: "") +
                (n.pci?.let { " · PCI $it" } ?: "") +
                " · " + SxFormat.formatDbm(n.rsrp)
        }
    }
    return lines.joinToString("\n")
}
