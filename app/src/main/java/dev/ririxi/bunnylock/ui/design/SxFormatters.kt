// SignalX visual system — Fase 1.
//
// Shared formatters (superprompt §12.4, §10.8): one way to write every
// technical value, so no screen invents its own format. All functions are
// pure and take nulls: a missing value is NEVER shown as 0, it is shown as
// Unavailable ("—" in compact places). UI code must not do these
// calculations inline.
//
// Language note: the app's strings are English (BunnyLock ships 6 locales,
// none of them Spanish yet). Formatters therefore use English words
// ("Excellent", "No signal"); a Spanish locale is a Fase 2 decision for
// Gennis, not something to hard-code here.
//
// eNB / sector: the arithmetic from SignalX (eNB = ECI >> 8,
// sector = ECI & 0xFF) is centralised here. Its first caller is Fase 3
// (Explorar), now that the reading layer exposes the cell identity
// (Cell.eci / Cell.nci, ModemState.readingAtMs).

package dev.ririxi.bunnylock.ui.design

import dev.ririxi.bunnylock.data.Rat

object SxFormat {
    /** Compact "no value" mark, for values inside rows and cards. */
    const val Unavailable = "—"

    fun formatDbm(value: Int?): String = value?.let { "$it dBm" } ?: Unavailable

    fun formatDb(value: Int?): String = value?.let { "$it dB" } ?: Unavailable

    /** "EARFCN 75" / "ARFCN 123456" / "UARFCN 10712". */
    fun channelName(rat: Rat): String =
        when (rat) {
            Rat.LTE -> "EARFCN"
            Rat.WCDMA -> "UARFCN"
            else -> "ARFCN"
        }

    fun formatChannel(
        rat: Rat,
        arfcn: Long?,
    ): String = arfcn?.let { "${channelName(rat)} $it" } ?: Unavailable

    /** kHz -> "20" or "19.8" (MHz, no unit — callers add "MHz"). */
    fun megahertz(khz: Int): String = if (khz % 1000 == 0) "${khz / 1000}" else "%.1f".format(khz / 1000.0)

    fun formatMegahertz(khz: Int?): String = khz?.let { "${megahertz(it)} MHz" } ?: Unavailable

    /** "850 m" below 1 km, "1.2 km" above. Null stays unavailable. "~" in front when the position is estimated (SignalX 1.0.8) — the one place that decides that prefix, so Antenas, Compass and Map never spell it three different ways (§10.7/§10.8). */
    fun formatDistance(
        meters: Double?,
        estimated: Boolean = false,
    ): String =
        when {
            meters == null -> Unavailable
            estimated -> "~${formatDistance(meters)}"
            meters < 1000 -> "${meters.toInt()} m"
            else -> "%.1f km".format(meters / 1000.0)
        }

    /** GPS accuracy: "±12 m". Null stays unavailable. */
    fun formatAccuracy(accuracy: Float?): String = accuracy?.let { "±${it.toInt()} m" } ?: Unavailable

    /** Download speed: "850 KB/s" / "1.2 MB/s". */
    fun formatSpeed(bytesPerSec: Long): String =
        if (bytesPerSec < 1_000_000) "${bytesPerSec / 1_000} KB/s" else "%.1f MB/s".format(bytesPerSec / 1_000_000.0)

    /** A frequency already in MHz: "1234.5 MHz". Null stays unavailable. */
    fun formatFrequencyMhz(mhz: Double?): String = mhz?.let { "%.1f MHz".format(it) } ?: Unavailable

    /** "123° NE". Cardinal is one of N NE E SE S SW W NW. */
    fun formatAzimuth(degrees: Double?): String {
        if (degrees == null) return Unavailable
        val shifted = ((degrees + 22.5) % 360 + 360) % 360
        val cardinal = arrayOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")[(shifted / 45).toInt() % 8]
        return "${degrees.toInt()}° $cardinal"
    }

    /** Float overload: same text, for the Float azimuths the database keeps. */
    fun formatAzimuth(degrees: Float?): String = formatAzimuth(degrees?.toDouble())

    /** "20.02453, -75.83912" (5 decimals). */
    fun formatCoordinates(
        latitude: Double?,
        longitude: Double?,
    ): String =
        if (latitude == null || longitude == null) Unavailable else "%.5f, %.5f".format(latitude, longitude)

    /** SignalX eNB / sector arithmetic (ECI is 28 bits: eNB = ECI >> 8, sector = ECI & 0xFF). First caller: Fase 3 (Explorar). */
    fun enbOf(eci: Long): Int = (eci shr 8).toInt()

    fun sectorOf(eci: Long): Int = (eci and 0xFF).toInt()

    fun formatEnbSector(eci: Long): String = "eNB ${enbOf(eci)}:${sectorOf(eci)}"

    /** NR cell identity, shown raw: unlike LTE, the gNB/cell split is not fixed, so nothing is derived from it. */
    fun formatNci(nci: Long): String = "NCI $nci"

    /** Qualitative word for a metric value. Never the only indicator: always shown with the number. */
    fun signalQualityLabel(
        value: Int,
        steps: List<Int>,
    ): String {
        val step = steps.indexOfFirst { value >= it }
        return when (if (step < 0) steps.size else step) {
            0 -> "Excellent"
            1 -> "Good"
            2 -> "Fair"
            else -> "Poor"
        }
    }

    /** "just now" / "12 s ago" / "3 min ago" / "2 h ago". */
    fun formatAge(seconds: Long): String =
        when {
            seconds < 5 -> "just now"
            seconds < 60 -> "$seconds s ago"
            seconds < 3600 -> "${seconds / 60} min ago"
            else -> "${seconds / 3600} h ago"
        }

    /** "2026-11-20 13:42" (local time). One date format for every record list (Fase 6). */
    fun formatDateTime(epochMs: Long): String =
        java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US).format(java.util.Date(epochMs))
}
