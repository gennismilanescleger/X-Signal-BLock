// SignalX antenna catalog loading (superprompt §5, Fase 4).
//
// The seed is static reference data, so it lives as an asset
// (santiago-enb.json, 96 eNB converted from the Santiago seed CSV) and is
// read, never written — exactly like SignalX 1.0.9's enb-santiago.json.
// The mutable layer is the database: manual positions in tower_positions
// and observed cells in cells. Merging happens here, in one place, with
// the SignalX 1.0.9 priority: manual > observed > estimated > none.
// A load failure returns an empty list, never a crash: the tab then shows
// its empty state instead of dying.

package dev.ririxi.bunnylock.signalx

import android.content.Context
import org.json.JSONObject

/**
 * Reads the seed catalog from assets. The asset is loaded once per
 * process (the caller caches the result); a broken or missing asset
 * yields an empty list.
 */
fun loadSeedAntennas(context: Context): List<SeedAntenna> {
    return try {
        val json = context.assets.open("santiago-enb.json").bufferedReader().use { it.readText() }
        val arr = JSONObject(json).getJSONArray("enb")
        List(arr.length()) { i ->
            val o = arr.getJSONObject(i)
            SeedAntenna(
                enb = o.getInt("enb"),
                bands = o.optString("bands", ""),
                zone = o.optString("zone", ""),
                areaNote = o.optString("area_note", ""),
                lat = if (o.isNull("lat")) null else o.getDouble("lat"),
                lon = if (o.isNull("lon")) null else o.getDouble("lon"),
                rangeM = o.optInt("range_m", 0),
                cells = o.optInt("cells", 0),
            )
        }.sortedBy { it.enb }
    } catch (_: Exception) {
        emptyList()
    }
}

/**
 * Merges the seed with the database into the rows the UI shows.
 *
 * Position priority (SignalX 1.0.9): a manual position wins; then the
 * best observed position (lowest GPS accuracy, strongest RSRP on a tie,
 * from the cells table — LTE only, NR has no ECI to key on); then the
 * estimated seed position; otherwise no position. The estimated range
 * only applies while the source is ESTIMATED: a manual or observed
 * position never inherits the seed's uncertainty.
 *
 * eNBs the phone observed that are not in the seed get their own rows
 * (zone [observedZoneLabel], bands from the observed cells). [userLocation] is
 * null until Fase 5 wires a location provider; without it distances
 * stay null and are shown as unavailable, never 0.
 */
fun buildAntennaRows(
    seed: List<SeedAntenna>,
    db: SignalXDb,
    observedZoneLabel: String,
    userLocation: MeasurementLocation? = null,
): List<AntennaRow> {
    val manual = db.listTowerPositions().associateBy { it.enb }
    val observed = db.observedCellsByEnb()
    val rows = ArrayList<AntennaRow>(seed.size + observed.size)
    for (e in seed) {
        val m = manual[e.enb]
        val obs = observed[e.enb]
        val pos =
            when {
                m != null -> ResolvedPosition(m.lat, m.lon, AntennaSource.MANUAL, 0)
                obs?.bestLat != null && obs.bestLon != null -> ResolvedPosition(obs.bestLat, obs.bestLon, AntennaSource.OBSERVED, 0)
                e.lat != null && e.lon != null -> ResolvedPosition(e.lat, e.lon, AntennaSource.ESTIMATED, e.rangeM)
                else -> ResolvedPosition(null, null, AntennaSource.NONE, 0)
            }
        rows +=
            AntennaRow(
                enb = e.enb,
                bands = displayBands(e.bands),
                zone = e.zone,
                areaNote = e.areaNote,
                lat = pos.lat,
                lon = pos.lon,
                source = pos.source,
                rangeM = pos.rangeM,
                observedCellCount = obs?.count ?: 0,
                sectors = obs?.sectors ?: emptyList(),
                distanceMeters = distanceTo(userLocation, pos.lat, pos.lon),
            )
    }
    val seedEnbs = seed.map { it.enb }.toSet()
    for ((enb, obs) in observed) {
        if (enb in seedEnbs) continue
        val m = manual[enb]
        val (lat, lon, source) =
            when {
                m != null -> Triple(m.lat, m.lon, AntennaSource.MANUAL)
                obs.bestLat != null && obs.bestLon != null -> Triple(obs.bestLat, obs.bestLon, AntennaSource.OBSERVED)
                else -> Triple(null, null, AntennaSource.NONE)
            }
        rows +=
            AntennaRow(
                enb = enb,
                bands = obs.bands.ifEmpty { "?" },
                zone = observedZoneLabel,
                areaNote = "",
                lat = lat,
                lon = lon,
                source = source,
                rangeM = 0,
                observedCellCount = obs.count,
                sectors = obs.sectors,
                distanceMeters = distanceTo(userLocation, lat, lon),
            )
    }
    rows.sortBy { it.enb }
    return rows
}

/** The position that won the priority, with its source and uncertainty. */
private data class ResolvedPosition(
    val lat: Double?,
    val lon: Double?,
    val source: AntennaSource,
    val rangeM: Int,
)

private fun distanceTo(
    userLocation: MeasurementLocation?,
    lat: Double?,
    lon: Double?,
): Double? {
    val userLat = userLocation?.latitude ?: return null
    val userLon = userLocation?.longitude ?: return null
    if (lat == null || lon == null) return null
    return distanceMeters(userLat, userLon, lat, lon)
}
