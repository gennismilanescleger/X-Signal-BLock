// Map catalog by city (SignalX 1.0.9 MapCatalog.kt, ported; Fase 5).
//
// Only two maps really exist today: Santiago de Cuba (made in the VM,
// imported from the file that arrives with the app — 1,528,571 B, bbox
// 19.9,-75.95..20.1,-75.75) and whole Cuba (direct official download).
// The other cities are listed with their real state: not made yet.
// A size is NEVER invented: unknown is -1 and shows as "not made yet".
// The catalog accepts a URL for when a map gets a stable direct download.
//
// The .map file itself is NEVER inside the APK or in assets: it lives in
// the app's filesDir/maps after import, or is downloaded to the same
// place. The Santiago reference file
// (~/workspace/signalx/santiago-de-cuba.map) is only a test import source.

package dev.ririxi.bunnylock.signalx

object MapCatalog {
    data class MapEntry(
        val id: String,
        val name: String,
        val lat: Double,
        val lon: Double,
        val fileName: String,
        val url: String?,
        val sizeBytes: Long,
        val available: Boolean,
    )

    // Real size of the Santiago .map made in the VM (1,528,571 B).
    const val SANTIAGO_SIZE_BYTES = 1528571L

    /** Public URL of the Santiago map (1.5 MB). No hosting yet: null = import from file only. */
    val SANTIAGO_DOWNLOAD_URL: String? = null

    val SANTIAGO =
        MapEntry(
            id = "santiago", name = "Santiago de Cuba",
            lat = 20.02, lon = -75.83,
            fileName = "santiago-de-cuba.map", url = SANTIAGO_DOWNLOAD_URL,
            sizeBytes = SANTIAGO_SIZE_BYTES, available = true,
        )

    val CUBA =
        MapEntry(
            id = "cuba", name = "Cuba completa",
            lat = 21.5, lon = -79.5,
            fileName = "cuba.map", url = MapDownload.CUBA_MAP_URL,
            sizeBytes = 65_473_943L, available = true,
        )

    /** Main cities with their center (to detect "your place" by GPS). */
    val CITIES =
        listOf(
            SANTIAGO,
            MapEntry("habana", "La Habana", 23.11, -82.37, "la-habana.map", null, -1, false),
            MapEntry("holguin", "Holguín", 20.89, -76.26, "holguin.map", null, -1, false),
            MapEntry("camaguey", "Camagüey", 21.38, -77.91, "camaguey.map", null, -1, false),
            MapEntry("santaclara", "Santa Clara", 22.41, -79.97, "santa-clara.map", null, -1, false),
            MapEntry("guantanamo", "Guantánamo", 20.14, -75.21, "guantanamo.map", null, -1, false),
            MapEntry("bayamo", "Bayamo", 20.38, -76.64, "bayamo.map", null, -1, false),
            MapEntry("cienfuegos", "Cienfuegos", 22.15, -80.45, "cienfuegos.map", null, -1, false),
            MapEntry("matanzas", "Matanzas", 23.05, -81.58, "matanzas.map", null, -1, false),
            MapEntry("pinardelrio", "Pinar del Río", 22.42, -83.69, "pinar-del-rio.map", null, -1, false),
            MapEntry("lastunas", "Las Tunas", 20.96, -76.95, "las-tunas.map", null, -1, false),
            MapEntry("sanctispiritus", "Sancti Spíritus", 21.93, -79.44, "sancti-spiritus.map", null, -1, false),
            MapEntry("ciegodeavila", "Ciego de Ávila", 21.84, -78.76, "ciego-de-avila.map", null, -1, false),
            MapEntry("manzanillo", "Manzanillo", 20.34, -77.12, "manzanillo.map", null, -1, false),
            MapEntry("nuevagerona", "Nueva Gerona", 21.88, -82.80, "nueva-gerona.map", null, -1, false),
        )

    /** The nearest city to (lat, lon), available or not. */
    fun nearestCity(
        lat: Double,
        lon: Double,
    ): MapEntry =
        CITIES.minByOrNull { distanceMeters(lat, lon, it.lat, it.lon) } ?: SANTIAGO

    /** The nearest AVAILABLE map to (lat, lon). */
    fun nearestAvailable(
        lat: Double,
        lon: Double,
    ): MapEntry =
        CITIES.filter { it.available }
            .minByOrNull { distanceMeters(lat, lon, it.lat, it.lon) } ?: SANTIAGO

    fun sizeLabel(bytes: Long): String =
        if (bytes <= 0) "size to confirm"
        else if (bytes < 1_000_000) "${bytes / 1_000} KB"
        else "%.1f MB".format(bytes / 1_000_000.0)
}
