// Full diagnostics — the ONE complete report (superprompt §6.6; Fase 6).
//
// The contextual "Status of reading" card in Explorar is NOT this: it
// stays a short notice there (§10.3). This screen, in Herramientas >
// Diagnostics, is the full report SignalX's buildDiagnosticText kept:
// app, device, database counters, map, settings, last LTE reading,
// antennas, last crash — plus the modem-and-locks section from
// BunnyLock (backend, access, active locks), so the two halves of the
// app report in one place instead of two.
//
// Share sends the whole text with ACTION_SEND (plain text), the same
// way SignalX did. The crash log can be deleted here, with
// confirmation; deleting it never touches the database or the maps.

@file:Suppress("FunctionName")

package dev.ririxi.bunnylock.ui

import android.content.Intent
import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.ririxi.bunnylock.R
import dev.ririxi.bunnylock.signalx.DbCounts
import dev.ririxi.bunnylock.signalx.MapCatalog
import dev.ririxi.bunnylock.signalx.MapSource
import dev.ririxi.bunnylock.signalx.servingReading
import dev.ririxi.bunnylock.ui.design.SxCard
import dev.ririxi.bunnylock.ui.design.SxDataRow
import dev.ririxi.bunnylock.ui.design.SxDialog
import dev.ririxi.bunnylock.ui.design.SxFormat
import dev.ririxi.bunnylock.ui.design.SxPrimaryButton
import dev.ririxi.bunnylock.ui.design.SxScreenHeader
import dev.ririxi.bunnylock.ui.design.SxSectionHeader
import dev.ririxi.bunnylock.ui.design.SxTextButton
import dev.ririxi.bunnylock.ui.design.mono
import kotlinx.coroutines.launch

/** What the Diagnóstico screen loads from the database (Fase 6). */
data class DiagnosticsData(
    val counts: DbCounts,
    val crashName: String?,
)

@Composable
fun DiagnosticsScreen(
    state: UiState,
    vm: MainViewModel,
    contentPadding: PaddingValues,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var data by remember { mutableStateOf<DiagnosticsData?>(null) }
    var refreshTick by remember { mutableIntStateOf(0) }
    var confirmDeleteCrash by remember { mutableStateOf(false) }

    LaunchedEffect(refreshTick) {
        data = vm.loadDiagnosticsData()
    }

    val version =
        remember {
            runCatching {
                val pi = context.packageManager.getPackageInfo(context.packageName, 0)
                "${pi.versionName} (vc ${pi.versionCode})"
            }.getOrNull() ?: SxFormat.Unavailable
        }
    val mapState = vm.mapManager.state
    val map by mapState.collectAsStateWithLifecycle()
    val modem = state.modem
    val reading = modem.servingReading()
    val counts = data?.counts

    LazyColumn(contentPadding = contentPadding.withScreenMargins(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { SxScreenHeader(stringResource(R.string.diag_title)) }

        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SxPrimaryButton(
                    text = stringResource(R.string.diag_share),
                    onClick = {
                        scope.launch {
                            val text = vm.buildDiagnosticsText()
                            val intent =
                                Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_SUBJECT, context.getString(R.string.diag_title))
                                    putExtra(Intent.EXTRA_TEXT, text)
                                }
                            runCatching { context.startActivity(Intent.createChooser(intent, context.getString(R.string.diag_title))) }
                        }
                    },
                    modifier = Modifier.weight(1f),
                )
            }
        }

        item {
            SxSectionHeader(stringResource(R.string.diag_section_app))
            SxCard {
                SxDataRow(stringResource(R.string.diag_version), version, monoValue = true)
                SxDataRow(stringResource(R.string.diag_package), context.packageName, monoValue = true)
            }
        }

        item {
            SxSectionHeader(stringResource(R.string.diag_section_device))
            SxCard {
                SxDataRow(
                    stringResource(R.string.diag_phone),
                    "${modem.device?.manufacturer ?: Build.MANUFACTURER} ${modem.device?.model ?: Build.MODEL}",
                )
                SxDataRow(
                    stringResource(R.string.diag_android),
                    "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
                    monoValue = true,
                )
                modem.device?.let { device ->
                    SxDataRow("SoC", device.socModel, monoValue = true)
                    SxDataRow("Platform", device.platform, monoValue = true)
                    SxDataRow(stringResource(R.string.common_baseband), device.baseband, monoValue = true)
                }
            }
        }

        item {
            SxSectionHeader(stringResource(R.string.diag_section_database))
            SxCard {
                SxDataRow(stringResource(R.string.diag_observations), counts?.observations?.toString() ?: SxFormat.Unavailable, monoValue = true)
                SxDataRow(stringResource(R.string.diag_neighbors), counts?.neighbors?.toString() ?: SxFormat.Unavailable, monoValue = true)
                SxDataRow(stringResource(R.string.diag_cells), counts?.cells?.toString() ?: SxFormat.Unavailable, monoValue = true)
                SxDataRow(stringResource(R.string.diag_points), counts?.points?.toString() ?: SxFormat.Unavailable, monoValue = true)
                SxDataRow(stringResource(R.string.diag_photos), counts?.photos?.toString() ?: SxFormat.Unavailable, monoValue = true)
                SxDataRow(stringResource(R.string.diag_positions), counts?.towerPositions?.toString() ?: SxFormat.Unavailable, monoValue = true)
            }
        }

        item {
            SxSectionHeader(stringResource(R.string.diag_section_map))
            SxCard {
                SxDataRow(
                    stringResource(R.string.diag_map_active),
                    map.activeMapName ?: stringResource(R.string.diag_map_none),
                    monoValue = true,
                )
                SxDataRow(
                    stringResource(R.string.diag_map_source),
                    when {
                        map.grid -> stringResource(R.string.orient_map_source_grid)
                        map.source == MapSource.FILE -> stringResource(R.string.orient_map_source_file)
                        else -> stringResource(R.string.orient_map_source_online)
                    },
                )
                val active = map.maps.firstOrNull { it.isActive }
                if (active != null) {
                    SxDataRow(stringResource(R.string.diag_map_size), MapCatalog.sizeLabel(active.sizeBytes), monoValue = true)
                }
                SxDataRow(stringResource(R.string.orient_maps_cache), MapCatalog.sizeLabel(map.cacheBytes), monoValue = true)
            }
        }

        item {
            SxSectionHeader(stringResource(R.string.diag_section_settings))
            SxCard {
                SxDataRow(
                    stringResource(R.string.diag_auto_collect),
                    stringResource(if (state.autoCollect) R.string.diag_on else R.string.diag_off),
                )
                SxDataRow(
                    stringResource(R.string.diag_backend),
                    modem.backend?.label?.let { stringResource(it) } ?: SxFormat.Unavailable,
                )
                SxDataRow(
                    stringResource(R.string.diag_access),
                    modem.access?.label ?: SxFormat.Unavailable,
                )
                SxDataRow(
                    stringResource(R.string.diag_target),
                    state.antennaTarget?.let { "eNB ${it.enb}" } ?: SxFormat.Unavailable,
                    monoValue = true,
                )
                SxDataRow(
                    stringResource(R.string.diag_antennas_filter),
                    stringResource(antennaFilterLabel(state.antennasFilter)),
                    monoValue = true,
                )
            }
        }

        item {
            SxSectionHeader(stringResource(R.string.diag_section_reading))
            SxCard {
                if (reading == null) {
                    Text(
                        stringResource(R.string.diag_no_reading),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    val eci = reading.cell?.eci
                    val nci = reading.cell?.nci
                    val identity =
                        when {
                            eci != null -> SxFormat.formatEnbSector(eci)
                            nci != null -> SxFormat.formatNci(nci)
                            else -> reading.carrier.bandLabel
                        }
                    SxDataRow(stringResource(R.string.diag_cell), identity, monoValue = true)
                    SxDataRow("RSRP", SxFormat.formatDbm(reading.signal?.rsrp), monoValue = true)
                    modem.readingAtMs?.let { at ->
                        val ageSec = (System.currentTimeMillis() - at) / 1000
                        SxDataRow(stringResource(R.string.diag_age), SxFormat.formatAge(ageSec), monoValue = true)
                    }
                }
            }
        }

        item {
            SxSectionHeader(stringResource(R.string.diag_section_antennas))
            SxCard {
                SxDataRow(
                    stringResource(R.string.diag_antennas_map),
                    state.antennas.count { it.hasPosition }.toString(),
                    monoValue = true,
                )
                SxDataRow(
                    stringResource(R.string.diag_antennas_list),
                    state.antennas.size.toString(),
                    monoValue = true,
                )
            }
        }

        item {
            SxSectionHeader(stringResource(R.string.diag_section_modem))
            SxCard {
                val locks =
                    buildList {
                        modem.lteLock?.let { add("LTE: $it") }
                        modem.nrLock?.let { add("NR: $it") }
                        modem.plmnLock?.let { add("PLMN: $it") }
                        modem.groups.filter { it.locked }.forEach { g ->
                            add("${g.group.label}: ${g.current.orEmpty().joinToString(" ") { g.group.bandLabel(it) }}")
                        }
                    }
                if (locks.isEmpty()) {
                    Text(
                        stringResource(R.string.diag_locks_none),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    locks.forEach { lock ->
                        Text(lock, style = MaterialTheme.typography.bodyMedium.mono())
                    }
                }
            }
        }

        item {
            SxSectionHeader(stringResource(R.string.diag_section_crash))
            SxCard {
                Text(
                    data?.crashName ?: stringResource(R.string.diag_crash_none),
                    style = MaterialTheme.typography.bodyMedium.mono(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (data?.crashName != null) {
                    SxTextButton(
                        text = stringResource(R.string.diag_crash_delete),
                        onClick = { confirmDeleteCrash = true },
                    )
                }
            }
        }
    }

    if (confirmDeleteCrash) {
        SxDialog(
            title = stringResource(R.string.diag_crash_delete_title),
            text = stringResource(R.string.diag_crash_delete_message, data?.crashName ?: ""),
            confirmLabel = stringResource(R.string.data_delete),
            onConfirm = {
                confirmDeleteCrash = false
                vm.deleteCrashLog()
                refreshTick++
            },
            onDismiss = { confirmDeleteCrash = false },
            dismissLabel = stringResource(R.string.common_cancel),
            destructiveConfirm = true,
        )
    }
}
