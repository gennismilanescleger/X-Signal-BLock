package dev.ririxi.bunnylock.ui

import androidx.annotation.StringRes
import dev.ririxi.bunnylock.R
import dev.ririxi.bunnylock.core.Bands
import dev.ririxi.bunnylock.data.Release
import dev.ririxi.bunnylock.modem.BandGroupState
import dev.ririxi.bunnylock.modem.Group
import dev.ririxi.bunnylock.modem.LockRat
import dev.ririxi.bunnylock.modem.ModemState
import dev.ririxi.bunnylock.signalx.AntennaFilter
import dev.ririxi.bunnylock.signalx.AntennaRow
import dev.ririxi.bunnylock.signalx.AntennaTarget
import dev.ririxi.bunnylock.signalx.MeasurementSummary
import dev.ririxi.bunnylock.signalx.SavedPoint
import dev.ririxi.bunnylock.signalx.UserPosition

val Group.favoritesKey: String
    get() =
        when (this) {
            Group.LTE -> "lte"
            Group.NR, Group.NR_SA, Group.NR_NSA -> "nr"
            Group.WCDMA -> "wcdma"
            Group.GSM -> "gsm"
        }

enum class ThemeMode(
    @StringRes val label: Int,
) {
    SYSTEM(R.string.settings_theme_system),
    LIGHT(R.string.settings_theme_light),
    DARK(R.string.settings_theme_dark),
    ;

    fun isDark(systemDark: Boolean): Boolean =
        when (this) {
            SYSTEM -> systemDark
            LIGHT -> false
            DARK -> true
        }
}

data class CellForm(
    val rat: LockRat = LockRat.LTE,
    val lteEarfcn: String = "",
    val ltePcis: String = "",
    val nrArfcn: String = "",
    val nrPcis: String = "",
    /** Subcarrier spacing in kHz. */
    val nrScs: Int = 30,
    val nrBand: Int? = null,
    /** The user chose [nrScs] by hand, so typing a channel no longer replaces it with the band's usual spacing. */
    val nrScsPicked: Boolean = false,
) {
    val nrCandidates: List<Int> get() = nrArfcn.toIntOrNull()?.let { Bands.nrBandsOf(it) } ?: emptyList()
}

data class UiState(
    val modem: ModemState = ModemState(),
    val busy: Boolean = false,
    val bandSelections: Map<Group, Set<Int>> = emptyMap(),
    val form: CellForm = CellForm(),
    val plmnMcc: String = "",
    val plmnMnc: String = "",
    val dynamicColor: Boolean = true,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val pureBlack: Boolean = false,
    val checkUpdates: Boolean = true,
    /** Band locks turn off every network without a lock of its own. */
    val exclusiveBandLock: Boolean = false,
    /** SignalX auto-collection: save the serving cell every 30 s or when the cell changes, while the app is open. */
    val autoCollect: Boolean = true,
    /** The newest saved measurement, for Inicio's recent activity. Null when nothing has been saved yet. */
    val lastMeasurement: MeasurementSummary? = null,
    /** How many measurements have been saved (for Settings). */
    val measurementCount: Int = 0,
    /** SignalX antenna catalog (Fase 4): the 96 seed eNBs merged with manual positions and observed cells. */
    val antennas: List<AntennaRow> = emptyList(),
    /** The antenna picked as the orientation target (Fase 4 sets it; Fase 5's map centers on it). Null when none is picked. */
    val antennaTarget: AntennaTarget? = null,
    /** Antennas search text, kept as typed (SignalX 1.0.8 persistence). */
    val antennasQuery: String = "",
    /** Antennas filter, kept as chosen (SignalX 1.0.8 persistence). */
    val antennasFilter: AntennaFilter = AntennaFilter.ALL,
    /** The user's position (Fase 5): the single GPS fix, from LocationProvider. Null while there is no fix — never 0. */
    val userPosition: UserPosition? = null,
    /** Saved install points, for the map overlay (Fase 5). */
    val mapPoints: List<SavedPoint> = emptyList(),
    /** Positions of observed cells, for the map overlay (Fase 5). */
    val observedCells: List<Pair<Double, Double>> = emptyList(),
    /** eNB being placed by tapping the map (Fase 5). Null when not placing. */
    val placingEnb: Int? = null,
    val toolOutput: String = "",
    val update: Release? = null,
    val favoriteBands: Map<String, Set<Int>> = emptyMap(),
    val showAllBands: Set<Group> = emptySet(),
) {
    fun favoritesOf(group: Group): Set<Int> = favoriteBands[group.favoritesKey].orEmpty()

    val lockRat get() = if (modem.supports5g) form.rat else LockRat.LTE

    fun isOff(group: Group) = exclusiveBandLock && modem.isOff(group)

    fun canTurnOff(group: Group) = exclusiveBandLock && group.canTurnOff

    fun appliedBands(groupState: BandGroupState): Set<Int> = if (isOff(groupState.group)) emptySet() else groupState.allowed

    // Locked bands and picks outside the favorites stay visible, so a checked band is never hidden from Lock.
    fun visibleBands(groupState: BandGroupState): List<Int> {
        if (!showsFavorites(groupState)) return groupState.supported
        val favorites = favoritesOf(groupState.group)
        val lockedBands = if (groupState.locked) appliedBands(groupState) else emptySet()
        val keptVisible = bandSelections[groupState.group].orEmpty() + lockedBands
        return groupState.supported.filter { it in favorites || it in keptVisible }
    }

    fun startingBands(groupState: BandGroupState): Set<Int> {
        val applied = appliedBands(groupState)
        return if (showsFavorites(groupState) && !groupState.locked) applied intersect favoritesOf(groupState.group) else applied
    }

    fun selectedBands(groupState: BandGroupState): Set<Int> = bandSelections[groupState.group] ?: startingBands(groupState)

    fun isPending(groupState: BandGroupState): Boolean = selectedBands(groupState) != startingBands(groupState)

    fun locksToFewerBands(groupState: BandGroupState): Boolean {
        val selected = selectedBands(groupState)
        return selected.isNotEmpty() && !selected.containsAll(groupState.supported)
    }

    val changedBandCount: Int
        get() =
            modem.groups.sumOf { groupState ->
                val selected = selectedBands(groupState)
                val starting = startingBands(groupState)
                (selected - starting).size + (starting - selected).size
            }

    fun hasFavorites(groupState: BandGroupState): Boolean = groupState.supported.any { it in favoritesOf(groupState.group) }

    private fun showsFavorites(groupState: BandGroupState): Boolean = groupState.group !in showAllBands && hasFavorites(groupState)
}
