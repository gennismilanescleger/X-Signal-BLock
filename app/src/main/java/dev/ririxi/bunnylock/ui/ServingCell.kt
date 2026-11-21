// Shared serving-cell presentation (Fase 3).
//
// One card for the serving cell, used by Explorar (detailed) and Inicio
// (summary): same title, same rows, same "—" for missing values, so the
// cell never reads differently in two places (superprompt §10.6).
// Plus the auto-collection line and the reading-age ticker, also shared.
//
// Title: "eNB 413082:2" in NetMonster style when the LTE ECI is known,
// "NCI …" for NR, the band label otherwise — never an invented identity.

@file:Suppress("FunctionName")

package dev.ririxi.bunnylock.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.ririxi.bunnylock.R
import dev.ririxi.bunnylock.core.Bands
import dev.ririxi.bunnylock.data.Carrier
import dev.ririxi.bunnylock.data.Cell
import dev.ririxi.bunnylock.data.Rat
import dev.ririxi.bunnylock.data.Signal
import dev.ririxi.bunnylock.modem.ModemState
import dev.ririxi.bunnylock.modem.formatPlmn
import dev.ririxi.bunnylock.signalx.CellSnapshot
import dev.ririxi.bunnylock.ui.design.SxCard
import dev.ririxi.bunnylock.ui.design.SxDataRow
import dev.ririxi.bunnylock.ui.design.SxFormat
import dev.ririxi.bunnylock.ui.design.SxSignalIndicator
import dev.ririxi.bunnylock.ui.design.SxSignalThresholds
import dev.ririxi.bunnylock.ui.design.SxSpacing
import dev.ririxi.bunnylock.ui.design.SxStatusChip
import dev.ririxi.bunnylock.ui.design.SxTone
import dev.ririxi.bunnylock.ui.design.mono
import dev.ririxi.bunnylock.ui.theme.LocalSignalScale
import kotlinx.coroutines.delay

/** "eNB 413082:2", "NCI …", band label, or the RAT name when nothing identifies the cell. */
fun CellSnapshot.displayIdentity(): String =
    when {
        eci != null -> SxFormat.formatEnbSector(eci)
        nci != null -> SxFormat.formatNci(nci)
        band != null -> band
        else -> ratDisplayName(rat)
    }

fun ratDisplayName(rat: Rat): String =
    when (rat) {
        Rat.LTE -> "LTE"
        Rat.NR -> "5G NR"
        Rat.WCDMA -> "WCDMA"
        Rat.GSM -> "GSM"
    }

/** Current time in ms, re-read every 5 s while [active], so reading ages tick instead of freezing. */
@Composable
fun rememberNowMs(active: Boolean): Long {
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(active) {
        if (active) {
            while (true) {
                delay(5000)
                nowMs = System.currentTimeMillis()
            }
        }
    }
    return nowMs
}

@Composable
fun ServingCellCard(
    carrier: Carrier,
    signal: Signal?,
    cell: Cell?,
    modem: ModemState,
    readingAgeSec: Long?,
    detailed: Boolean,
) {
    val network =
        modem.registeredName
            ?: modem.registeredPlmn?.let(::formatPlmn)
            ?: cell?.operator
            ?: stringResource(R.string.home_unknown_network)
    val eci = cell?.eci
    val nci = cell?.nci
    val title =
        when {
            eci != null -> SxFormat.formatEnbSector(eci)
            nci != null -> SxFormat.formatNci(nci)
            carrier.band != null -> carrier.bandLabel
            else -> stringResource(R.string.common_unknown_band)
        }
    val frequencyMhz =
        carrier.arfcn?.toInt()?.let { arfcn ->
            when (carrier.rat) {
                Rat.LTE -> Bands.lteFreqMhz(arfcn)
                Rat.NR -> Bands.nrFreqMhz(arfcn)
                else -> null
            }
        }
    val rssi = signal?.rssi ?: cell?.rssi
    SxCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            RatBadge(carrier.rat)
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.headlineSmallEmphasized.mono())
                Text(network, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            SxStatusChip(stringResource(R.string.home_tag_serving), SxTone.Signal)
        }
        SxSignalIndicator(
            "RSRP",
            signal?.rsrp ?: carrier.rsrp,
            "dBm",
            SxSignalThresholds.Rsrp,
            LocalSignalScale.current,
            Modifier.fillMaxWidth(),
        )
        if (detailed) {
            SxDataRow(stringResource(R.string.explorar_technology), ratDisplayName(carrier.rat))
        }
        if (carrier.rat == Rat.LTE) {
            SxDataRow(stringResource(R.string.explorar_eci), cell?.eci?.toString() ?: SxFormat.Unavailable, monoValue = true)
        }
        if (carrier.rat == Rat.NR) {
            SxDataRow(stringResource(R.string.explorar_nci), cell?.nci?.toString() ?: SxFormat.Unavailable, monoValue = true)
        }
        SxDataRow(stringResource(R.string.explorar_tac), cell?.tac?.toString() ?: SxFormat.Unavailable, monoValue = true)
        SxDataRow(stringResource(R.string.explorar_band), carrier.band?.let { carrier.bandLabel } ?: SxFormat.Unavailable, monoValue = true)
        if (detailed) {
            SxDataRow(stringResource(R.string.explorar_channel), SxFormat.formatChannel(carrier.rat, carrier.arfcn), monoValue = true)
            frequencyMhz?.let {
                SxDataRow(stringResource(R.string.explorar_frequency), SxFormat.formatFrequencyMhz(it), monoValue = true)
            }
        }
        SxDataRow(stringResource(R.string.explorar_pci), carrier.pci?.toString() ?: SxFormat.Unavailable, monoValue = true)
        if (detailed && carrier.rat == Rat.LTE) {
            SxDataRow(stringResource(R.string.explorar_rssi), SxFormat.formatDbm(rssi), monoValue = true)
        }
        if (detailed) {
            SxDataRow(stringResource(R.string.explorar_bandwidth), SxFormat.formatMegahertz(carrier.bandwidthKhz ?: cell?.bandwidthKhz), monoValue = true)
            SxDataRow(
                stringResource(R.string.explorar_plmn),
                modem.registeredPlmn?.let(::formatPlmn) ?: cell?.plmn?.let(::formatPlmn) ?: SxFormat.Unavailable,
                monoValue = true,
            )
            SxDataRow(stringResource(R.string.explorar_operator), modem.registeredName ?: cell?.operator ?: SxFormat.Unavailable)
        }
        SxDataRow(stringResource(R.string.explorar_updated), readingAgeSec?.let { SxFormat.formatAge(it) } ?: SxFormat.Unavailable)
    }
}

/**
 * Auto-collection state line (superprompt §4.1.E, §10.10): it shows
 * whether auto-collection is on and leads to the setting — it is not
 * the switch itself.
 */
@Composable
fun AutoCollectionRow(
    autoCollect: Boolean,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SxCard(modifier = modifier, onClick = onOpenSettings) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(
                if (autoCollect) Icons.Rounded.Check else Icons.Rounded.Close,
                contentDescription = null,
                tint = if (autoCollect) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(SxSpacing.IconMedium),
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    stringResource(if (autoCollect) R.string.explorar_auto_on else R.string.explorar_auto_off),
                    style = MaterialTheme.typography.bodyLargeEmphasized,
                )
                Text(
                    stringResource(if (autoCollect) R.string.explorar_auto_on_hint else R.string.explorar_auto_off_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(Icons.Rounded.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
