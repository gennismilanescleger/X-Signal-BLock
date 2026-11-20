// SignalX field measurements on top of BunnyLock's reader (superprompt §11.4, Fase 3).
//
// One snapshot = the serving cell + the visible neighbors + the optional
// position, taken from one ModemState. The reader stays BunnyLock's
// (Cells.kt / Carriers.signals + QMI); this file only picks the serving
// reading out of it and freezes it for storage, so there is exactly one
// reading path and no second modem scan.
//
// No GPS/azimuth provider exists in this app yet (Fase 5 brings
// LocationProvider/HeadingProvider), so location is null until then and is
// stored as "not available", never 0.
//
// Fase 5: the providers exist now (Location.kt, the single location and
// heading source). MainViewModel passes the current fix + heading here;
// without a fix the location stays null, never 0.

package dev.ririxi.bunnylock.signalx

import dev.ririxi.bunnylock.data.Carrier
import dev.ririxi.bunnylock.data.Cell
import dev.ririxi.bunnylock.data.Rat
import dev.ririxi.bunnylock.data.Signal
import dev.ririxi.bunnylock.modem.ModemState

enum class MeasurementSource {
    MANUAL,
    AUTO,
}

/** The serving cell, signal and identity of one reading. Null when nothing is camped on. */
data class ServingReading(
    val carrier: Carrier,
    val signal: Signal?,
    val cell: Cell?,
)

/** The serving reading of this state, or null when nothing is camped on. */
fun ModemState.servingReading(): ServingReading? {
    val carrier = carriers.firstOrNull() ?: return null
    val signal = signals.firstOrNull { it.rat == carrier.rat }
    return ServingReading(carrier, signal, servingCellFor(carrier))
}

/**
 * The cell behind a carrier. Prefers the registered cell; when Android
 * doesn't mark the 5G NSA cell as registered, the single agreeing cell
 * still counts (same rule as Carriers.cellOf).
 */
fun ModemState.servingCellFor(carrier: Carrier): Cell? {
    val agreeing =
        cells.filter { cell ->
            cell.rat == carrier.rat &&
                (carrier.arfcn == null || cell.arfcn == carrier.arfcn) &&
                (carrier.pci == null || cell.pci < 0 || cell.pci == carrier.pci)
        }
    return agreeing.firstOrNull { it.registered } ?: agreeing.singleOrNull()
}

/** Visible neighbors, strongest first (same order as Explorar). */
fun ModemState.neighborCells(): List<Cell> =
    cells
        .filter { !it.registered }
        .sortedWith(
            compareByDescending<Cell> { it.rsrp ?: Int.MIN_VALUE }
                .thenByDescending { it.rat.ordinal }
                .thenBy { it.arfcn }
                .thenBy { it.pci },
        )

/** One cell frozen for storage. A missing value is null, never 0. */
data class CellSnapshot(
    val rat: Rat,
    val eci: Long?,
    val nci: Long?,
    val tac: Int?,
    val pci: Int?,
    val earfcn: Long?,
    /** "B3" / "n78" style label, or null when the band is unknown. */
    val band: String?,
    /** MCC+MNC digits, e.g. "26201". */
    val plmn: String?,
    val rsrp: Int?,
    val rsrq: Int?,
    val rssi: Int?,
    val sinr: Int?,
)

/** Position at save time. All null until Fase 5 wires a location provider. */
data class MeasurementLocation(
    val latitude: Double?,
    val longitude: Double?,
    val accuracy: Float?,
    val azimuth: Float?,
) {
    /** True while there is a real fix (a position, not a placeholder). */
    val hasFix: Boolean get() = latitude != null && longitude != null
}

data class MeasurementSnapshot(
    val serving: CellSnapshot,
    val neighbors: List<CellSnapshot>,
    val location: MeasurementLocation?,
    val source: MeasurementSource,
    /** Epoch ms of the reading the snapshot was taken from; null when the reading has no stamp yet. */
    val readingAtMs: Long?,
    /** Epoch ms when the snapshot was taken. */
    val timeMs: Long,
)

/** Builds the snapshot for the current reading, or null when nothing is camped on. */
fun ModemState.measurementSnapshot(
    source: MeasurementSource,
    location: MeasurementLocation? = null,
): MeasurementSnapshot? {
    val reading = servingReading() ?: return null
    return MeasurementSnapshot(
        serving = reading.toSnapshot(),
        neighbors = neighborCells().map { it.toSnapshot() },
        location = location,
        source = source,
        readingAtMs = readingAtMs,
        timeMs = System.currentTimeMillis(),
    )
}

private fun ServingReading.toSnapshot(): CellSnapshot =
    CellSnapshot(
        rat = carrier.rat,
        eci = cell?.eci,
        nci = cell?.nci,
        tac = cell?.tac,
        pci = carrier.pci ?: cell?.pci?.takeIf { it >= 0 },
        earfcn = carrier.arfcn ?: cell?.arfcn,
        band = carrier.bandLabel.takeIf { carrier.band != null },
        plmn = cell?.plmn,
        rsrp = signal?.rsrp ?: carrier.rsrp ?: cell?.rsrp,
        rsrq = signal?.rsrq,
        rssi = signal?.rssi ?: cell?.rssi,
        sinr = signal?.sinr,
    )

private fun Cell.toSnapshot(): CellSnapshot =
    CellSnapshot(
        rat = rat,
        eci = eci,
        nci = nci,
        tac = tac,
        pci = pci.takeIf { it >= 0 },
        earfcn = arfcn,
        band = bandLabel.takeIf { band != null },
        plmn = plmn,
        rsrp = rsrp,
        rsrq = null,
        rssi = rssi,
        sinr = null,
    )
