// Explorar — technical analysis centre (superprompt §4, Fase 3).
//
// Shows only what BunnyLock's reader really delivers: the serving cell
// (eNB/sector from the ECI, TAC, band, EARFCN/ARFCN, PCI, PLMN, operator,
// RSSI) plus the per-RAT signal readings (RSRP/RSRQ/SINR/RSSI) and the
// neighbor list. Missing values render as "—", never 0. RSSI is LTE-only:
// Android reports no RSSI for NR, so the row is omitted there instead of
// showing a dash for a metric that cannot exist.
// The notice is called "Reading state", never "Diagnóstico": the full
// report lives in Herramientas. Saving a measurement and the
// auto-collection setting are real here: the save button writes the
// current reading to the SignalX base, and the auto-collection line leads
// to its switch in Settings.

@file:Suppress("FunctionName")

package dev.ririxi.bunnylock.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import dev.ririxi.bunnylock.R
import dev.ririxi.bunnylock.data.Rat
import dev.ririxi.bunnylock.modem.ModemState
import dev.ririxi.bunnylock.signalx.neighborCells
import dev.ririxi.bunnylock.signalx.servingReading
import dev.ririxi.bunnylock.ui.design.SxCard
import dev.ririxi.bunnylock.ui.design.SxNotice
import dev.ririxi.bunnylock.ui.design.SxPrimaryButton
import dev.ririxi.bunnylock.ui.design.SxScreenHeader
import dev.ririxi.bunnylock.ui.design.SxSecondaryButton
import dev.ririxi.bunnylock.ui.design.SxSectionHeader
import dev.ririxi.bunnylock.ui.design.SxSignalIndicator
import dev.ririxi.bunnylock.ui.design.SxSignalThresholds
import dev.ririxi.bunnylock.ui.design.SxTone
import dev.ririxi.bunnylock.ui.theme.LocalSignalScale

/** A reading is stale when the modem hasn't delivered a fresh one for this long. */
private const val STALE_READING_AGE_SEC = 30L

@Composable
fun ExplorarScreen(
    state: UiState,
    vm: MainViewModel,
    contentPadding: PaddingValues,
    goToCellLock: () -> Unit,
    goToSettings: () -> Unit,
) {
    val modem = state.modem
    val reading = modem.servingReading()
    val servingCarrier = reading?.carrier
    val servingSignal = reading?.signal
    val servingCell = reading?.cell
    val neighbors = modem.neighborCells()
    val context = LocalContext.current
    val nowMs = rememberNowMs(modem.readingAtMs != null)
    val readingAgeSec = modem.readingAtMs?.let { ((nowMs - it) / 1000).coerceAtLeast(0) }
    val hasCellPermission =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED
    val readingNoticeResult = readingNotice(modem, readingAgeSec, hasCellPermission) { openAppSettings(context) }

    LazyColumn(contentPadding = contentPadding.withScreenMargins(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { SxScreenHeader(stringResource(R.string.shell_tab_explorar)) }

        // F. Estado de lectura — only when something is actually missing.
        // Never called "Diagnóstico": the full report lives in Herramientas.
        readingNoticeResult?.let { notice ->
            item {
                SxSectionHeader(stringResource(R.string.explorar_reading))
                SxNotice(notice.text, notice.tone, actionLabel = notice.actionLabel, onAction = notice.onAction)
            }
        }

        item {
            SxSectionHeader(stringResource(R.string.explorar_serving))
            if (servingCarrier == null) {
                SxCard { SleepyBunny(stringResource(R.string.home_no_serving_cell)) }
            } else {
                ServingCellCard(servingCarrier, servingSignal, servingCell, modem, readingAgeSec, detailed = true)
            }
        }

        // E. Acciones.
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SxPrimaryButton(
                        stringResource(R.string.explorar_save),
                        onClick = vm::saveMeasurement,
                        modifier = Modifier.weight(1f),
                        enabled = servingCarrier != null && !state.busy,
                    )
                    SxSecondaryButton(
                        stringResource(R.string.shell_refresh),
                        onClick = vm::refresh,
                        modifier = Modifier.weight(1f),
                        icon = Icons.Rounded.Refresh,
                        enabled = !state.busy,
                    )
                }
                SxSecondaryButton(
                    stringResource(R.string.explorar_cell_lock),
                    onClick = {
                        servingCell?.let { vm.fillFrom(it) }
                        goToCellLock()
                    },
                    modifier = Modifier.fillMaxWidth(),
                    icon = BunnyIcons.Tower,
                    enabled = servingCell !== null,
                )
            }
        }

        // E (cont.). Auto-collection state line — tappable, leads to the setting (superprompt §10.10).
        item {
            AutoCollectionRow(state.autoCollect, onOpenSettings = goToSettings)
        }

        item {
            SxSectionHeader(stringResource(R.string.explorar_signal))
            SxCard {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    val scale = LocalSignalScale.current
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                        SxSignalIndicator("RSRP", servingSignal?.rsrp ?: servingCarrier?.rsrp, "dBm", SxSignalThresholds.Rsrp, scale, Modifier.weight(1f))
                        SxSignalIndicator("RSRQ", servingSignal?.rsrq, "dB", SxSignalThresholds.Rsrq, scale, Modifier.weight(1f))
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                        SxSignalIndicator("SINR", servingSignal?.sinr, "dB", SxSignalThresholds.Sinr, scale, Modifier.weight(1f))
                        if (servingCarrier?.rat == Rat.LTE) {
                            SxSignalIndicator("RSSI", servingSignal?.rssi ?: servingCell?.rssi, "dBm", SxSignalThresholds.PlainDbm, scale, Modifier.weight(1f))
                        } else {
                            Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }
        }

        item {
            SxSectionHeader(stringResource(R.string.explorar_neighbors), count = neighbors.size)
            when {
                modem.cells.isEmpty() -> {
                    SxCard { SleepyBunny(stringResource(R.string.cell_none_found), stringResource(R.string.cell_none_found_hint)) }
                }

                neighbors.isEmpty() -> {
                    SxCard {
                        Text(
                            stringResource(R.string.explorar_no_neighbors),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                else -> {
                    Segmented {
                        neighbors.forEach { cell ->
                            item { shapes ->
                                CellItem(shapes, cell) {
                                    vm.fillFrom(cell)
                                    goToCellLock()
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** One contextual notice: the concrete reason the reading is incomplete, or null when nothing is missing. */
private data class ReadingNotice(
    val text: String,
    val tone: SxTone,
    val actionLabel: String? = null,
    val onAction: (() -> Unit)? = null,
)

@Composable
private fun readingNotice(
    modem: ModemState,
    readingAgeSec: Long?,
    hasCellPermission: Boolean,
    onOpenSettings: () -> Unit,
): ReadingNotice? {
    val strayChannel = modem.strayChannel
    val strayBand = modem.strayBand
    return when {
        !hasCellPermission ->
            ReadingNotice(
                stringResource(R.string.explorar_notice_no_permission),
                SxTone.Warning,
                stringResource(R.string.explorar_notice_open_settings),
                onOpenSettings,
            )

        modem.carriers.isEmpty() -> ReadingNotice(stringResource(R.string.explorar_notice_no_serving), SxTone.Warning)
        readingAgeSec != null && readingAgeSec > STALE_READING_AGE_SEC -> ReadingNotice(stringResource(R.string.explorar_notice_stale), SxTone.Warning)
        strayChannel !== null -> ReadingNotice(stringResource(R.string.home_stray_channel, strayChannel), SxTone.Warning)
        strayBand !== null -> ReadingNotice(stringResource(R.string.home_stray_band, strayBand), SxTone.Warning)
        else -> null
    }
}

private fun openAppSettings(context: Context) {
    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    context.startActivity(intent)
}
