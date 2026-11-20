// SignalX antenna catalog (superprompt §5, Fase 4).
//
// One antenna = one eNB from the Santiago seed (assets/santiago-enb.json,
// 96 rows: 38 with an estimated OpenCellID position, 58 list-only) merged
// with what the phone itself knows: manual positions from tower_positions
// and observed cells from the cells table. Position priority, as in
// SignalX 1.0.9: manual > observed > estimated > none. A position is
// never invented: without any of the three, the antenna has no position.
//
// Everything in this file is pure: no Android framework, no database, no
// map. The search and filter logic is the one that worked in SignalX
// 1.0.8 (Geo.kt enbMatchesQuery / normalizeQuery), adapted to the seed's
// band spelling ("1,3,8" instead of "B1+3+8").

package dev.ririxi.bunnylock.signalx

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Where an antenna's position comes from. */
enum class AntennaSource {
    ESTIMATED,
    MANUAL,
    OBSERVED,
    NONE,
}

/** One row of the seed catalog (assets/santiago-enb.json). */
data class SeedAntenna(
    val enb: Int,
    /** Raw bands as stored in the seed: "1,3,8" or "3" (empty when unknown). */
    val bands: String,
    val zone: String,
    val areaNote: String,
    val lat: Double?,
    val lon: Double?,
    /** Uncertainty radius in metres of the estimated position; 0 = unknown. */
    val rangeM: Int,
    /** Cells OpenCellID reports at this site (informational, not shown). */
    val cells: Int,
)

/** One antenna as the UI shows it: seed + manual + observed merged. */
data class AntennaRow(
    val enb: Int,
    /** Display bands: "B1+3+8" (or "?" for an observed-only eNB with no band). */
    val bands: String,
    val zone: String,
    val areaNote: String,
    val lat: Double?,
    val lon: Double?,
    val source: AntennaSource,
    /** Uncertainty radius in metres; only meaningful when source is ESTIMATED. */
    val rangeM: Int,
    /** Observed LTE cells (rows in the cells table) for this eNB. */
    val observedCellCount: Int,
    /** Sectors seen for this eNB (ECI & 0xFF per observed cell), sorted. */
    val sectors: List<Int>,
    /** Distance from the user's position, or null while there is no location (Fase 5 wires it). */
    val distanceMeters: Double?,
) {
    val hasPosition: Boolean get() = lat != null && lon != null
}

/** The antenna the user picked as the orientation target. */
data class AntennaTarget(
    val enb: Int,
    val lat: Double,
    val lon: Double,
    val name: String,
)

/** Filter of the Antennas dropdown (SignalX 1.0.8 keys, kept). */
enum class AntennaFilter {
    ALL,
    ON_MAP,
    NO_POSITION,
    ESTIMATED,
    MANUAL,
    OBSERVED,
}

/** "1,3,8" -> "B1+3+8" (SignalX 1.0.9 display: one B for the list, not one per band). Empty stays empty. */
fun displayBands(rawBands: String): String {
    val cleaned =
        rawBands
            .split(',', ' ', '+')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .joinToString("+")
    return cleaned.takeIf { it.isNotEmpty() }?.let { "B$it" } ?: ""
}

/**
 * Search match for a catalog antenna: eNB (a "413082:2" query matches the
 * eNB, the part after ":" is the sector and is ignored), zone, area
 * (barrio) or bands — case- and accent-insensitive (SignalX 1.0.8).
 * "b3" matches a "1,3,8" band list through the token rule below.
 */
fun antennaMatchesQuery(
    row: AntennaRow,
    query: String,
): Boolean {
    val q = normalizeQuery(query)
    if (q.isEmpty()) return true
    val qEnb = q.substringBefore(':')
    if (row.enb.toString().contains(qEnb)) return true
    if (normalizeQuery(row.zone).contains(q)) return true
    if (normalizeQuery(row.areaNote).contains(q)) return true
    val nb = normalizeQuery(row.bands)
    if (nb.contains(q)) return true
    // Bands as a list ("B1+3+8" / "1,3,8"): "b3" has to match the 3 even
    // though the text never contains "b3" in a row.
    val qBand = q.removePrefix("b")
    if (qBand.isNotEmpty() && qBand.all { it.isDigit() }) {
        val tokens = nb.split('+', ' ', ',').map { it.removePrefix("b") }
        if (qBand in tokens) return true
    }
    // Same list, comma spelling: "1,3" matches "B1+3+8".
    if (qBand.isNotEmpty() && nb.replace('+', ',').contains(qBand)) return true
    return false
}

fun AntennaFilter.matches(row: AntennaRow): Boolean =
    when (this) {
        AntennaFilter.ALL -> true
        AntennaFilter.ON_MAP -> row.hasPosition
        AntennaFilter.NO_POSITION -> !row.hasPosition
        AntennaFilter.ESTIMATED -> row.source == AntennaSource.ESTIMATED
        AntennaFilter.MANUAL -> row.source == AntennaSource.MANUAL
        AntennaFilter.OBSERVED -> row.source == AntennaSource.OBSERVED
    }

/** Lowercase, no accents, no extra spaces: "B3", "b3", "José" and "jose" match alike. */
fun normalizeQuery(text: String): String {
    val t = text.trim().lowercase()
    if (t.isEmpty()) return t
    return try {
        java.text.Normalizer.normalize(t, java.text.Normalizer.Form.NFD)
            .replace("\\p{Mn}+".toRegex(), "")
    } catch (_: Exception) {
        t
    }
}

/** Initial bearing (true azimuth) from one point to another, 0..359. */
fun bearingDeg(
    fromLat: Double,
    fromLon: Double,
    toLat: Double,
    toLon: Double,
): Double {
    val lat1 = Math.toRadians(fromLat)
    val lat2 = Math.toRadians(toLat)
    val dLon = Math.toRadians(toLon - fromLon)
    val y = sin(dLon) * cos(lat2)
    val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLon)
    var deg = Math.toDegrees(atan2(y, x))
    if (deg < 0) deg += 360.0
    return deg
}

/** Distance in metres (haversine). */
fun distanceMeters(
    fromLat: Double,
    fromLon: Double,
    toLat: Double,
    toLon: Double,
): Double {
    val r = 6_371_000.0
    val dLat = Math.toRadians(toLat - fromLat)
    val dLon = Math.toRadians(toLon - fromLon)
    val a =
        sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(fromLat)) * cos(Math.toRadians(toLat)) *
            sin(dLon / 2) * sin(dLon / 2)
    return 2 * r * atan2(sqrt(a), sqrt(1 - a))
}

/**
 * Reads a decimal accepting comma or point ("20,02" and "20.02" are the
 * same: the phone is in Spanish and types with a comma). SignalX 1.0.8.
 */
fun parseCoord(text: String): Double? {
    val t = text.trim().replace(" ", "")
    if (t.isEmpty()) return null
    // A single comma and no point: it is a decimal comma ("20,02").
    val norm = if (t.count { it == ',' } == 1 && !t.contains('.')) t.replace(',', '.') else t
    return norm.toDoubleOrNull()
}

/** "20.02, -75.83" pasted in one field -> (lat, lon). Null when it is not two valid numbers. */
fun splitCoords(text: String): Pair<Double, Double>? {
    val parts = text.split(',', ';').map { it.trim() }.filter { it.isNotEmpty() }
    if (parts.size != 2) return null
    val lat = parseCoord(parts[0]) ?: return null
    val lon = parseCoord(parts[1]) ?: return null
    return lat to lon
}

/**
 * Validates the two fields of the place/move dialog: latitude -90..90,
 * longitude -180..180 (comma decimals accepted). Both values may be
 * pasted into the latitude field alone ("20.03133, -75.83933"). Null
 * when anything is missing or out of range.
 */
fun parsePlaceInput(
    latText: String,
    lonText: String,
): Pair<Double, Double>? {
    if (lonText.isBlank()) {
        splitCoords(latText)?.let { (lat, lon) ->
            if (lat in -90.0..90.0 && lon in -180.0..180.0) return lat to lon
        }
    }
    val lat = parseCoord(latText) ?: return null
    val lon = parseCoord(lonText) ?: return null
    if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return null
    return lat to lon
}
