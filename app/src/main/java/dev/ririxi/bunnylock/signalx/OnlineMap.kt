// Online map with cache (SignalX 1.0.9 OnlineMap.kt, ported; Fase 5).
//
// The tiles of the area on screen are downloaded from
// tile.openstreetmap.org and kept on disk; revisiting the same area serves
// them from the cache. Only what is looked at is downloaded — never a
// bulk download or a prefetch.
//
// OSM TILE POLICY (operations.osmfoundation.org/policies/tiles/, §4):
// deliberate OFFLINE use of tile.openstreetmap.org is NOT permitted.
// What is permitted is normal interactive viewing + revisits from the
// local cache, with a TTL of at least 7 days. So the cache has a 7-day
// TTL (an old tile keeps DRAWING from the cache offline and refreshes
// itself when there is network), a 150 MB disk cap with LRU eviction, a
// User-Agent that identifies the app, and visible attribution. The real
// offline map is the .map file.

package dev.ririxi.bunnylock.signalx

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import org.mapsforge.core.graphics.GraphicFactory
import org.mapsforge.core.model.LatLong
import org.mapsforge.core.model.Tile
import org.mapsforge.map.layer.cache.FileSystemTileCache
import org.mapsforge.map.layer.download.DownloadJob
import org.mapsforge.map.layer.download.TileDownloadLayer
import org.mapsforge.map.layer.download.tilesource.OnlineTileSource
import org.mapsforge.map.model.MapViewPosition
import java.io.File
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.tan

object OnlineMap {
    /**
     * Marker for "do not retry the online map in a loop" (SignalX 1.0.9):
     * set when the online map crashes the app or several tiles in a row
     * fail; on open the app shows the grid instead of retrying. Cleared
     * when the user picks Online by hand in Manage maps.
     */
    const val PREF_ONLINE_TILE_CRASH = "online_tile_crash"

    /**
     * In-memory index capacity: NUMBER OF TILES, not bytes (SignalX 1.0.9:
     * passing 150,000,000 here made Mapsforge size its LRU table for
     * 250M entries and the first tile put OOM-killed the app). 4000
     * tiles ≈ 40–200 MB on disk depending on tile weight; the disk cap
     * is enforced separately by SafeTileCache.
     */
    const val CACHE_MAX_ENTRIES = 4000

    /** Disk cap: 150 MB. FileSystemTileCache does not enforce it alone. */
    const val CACHE_MAX_BYTES = 150_000_000

    /** Cap per saved tile: a real OSM tile is 5–50 KB; over 1 MB is a bad response and is dropped. */
    const val MAX_TILE_FILE_BYTES = 1_048_576L

    /** Zooms that are downloaded: 10–17. Outside that range the online layer draws nothing. */
    const val ZOOM_MIN = 10.toByte()
    const val ZOOM_MAX = 17.toByte()

    const val TILE_SIZE = 256

    /** 7 days in ms: the minimum the OSM policy asks for. */
    const val CACHE_TTL_MS = 7L * 24 * 3600 * 1000

    /** User-Agent that identifies the app (the policy FORBIDS the library default). */
    const val USER_AGENT = "SignalX/2.1.0 (+https://github.com/gennismilanescleger/X-Signal-Block)"

    /** Parallel requests: few, so the server is not hammered. */
    const val PARALLEL_REQUESTS = 4

    fun tileCacheDir(context: Context): File =
        File(context.filesDir, "tilecache").also { it.mkdirs() }

    /** Real cache size on disk (sum of the files). */
    fun cacheSizeBytes(context: Context): Long {
        var total = 0L
        try {
            tileCacheDir(context).walkTopDown().forEach { f ->
                if (f.isFile) total += f.length()
            }
        } catch (_: Exception) {
        }
        return total
    }

    /**
     * Standard OSM tile source, HTTPS only, only what is looked at.
     * getTileUrl builds protocol://host:port + baseUrl + z/x/y.png, so
     * baseUrl stays "/" (its default): putting the full URL there
     * DUPLICATES the host. Result: https://tile.openstreetmap.org:443/{z}/{x}/{y}.png.
     */
    fun createTileSource(): OnlineTileSource {
        val source = OnlineTileSource(arrayOf("tile.openstreetmap.org"), 443)
        source.setProtocol("https")
        source.setExtension("png")
        source.setName("SignalX-OSM")
        source.setTileSize(TILE_SIZE)
        source.setZoomLevelMin(ZOOM_MIN)
        source.setZoomLevelMax(ZOOM_MAX)
        source.setUserAgent(USER_AGENT)
        source.setTimeoutConnect(10_000)
        source.setTimeoutRead(20_000)
        source.setParallelRequestsLimit(PARALLEL_REQUESTS)
        return source
    }

    /**
     * Online layer + its disk cache. The layer must be started (start())
     * after adding it to the map. The cache handed to the layer is
     * SafeTileCache (SignalX 1.0.9): a bad tile never kills the app and
     * the disk stays under 150 MB. The raw cache is returned too, for
     * purging and counting saved tiles.
     */
    fun createLayer(
        context: Context,
        mapViewPosition: MapViewPosition,
        graphicFactory: GraphicFactory,
        onTileFailure: (() -> Unit)? = null,
        onTileStored: (() -> Unit)? = null,
    ): Triple<TileDownloadLayer, SafeTileCache, OnlineTileSource> {
        val source = createTileSource()
        val rawCache = FileSystemTileCache(CACHE_MAX_ENTRIES, tileCacheDir(context), graphicFactory)
        val safeCache = SafeTileCache(rawCache, tileCacheDir(context), onTileFailure = onTileFailure, onTileStored = onTileStored)
        val layer = TileDownloadLayer(safeCache, mapViewPosition, source, graphicFactory)
        layer.setCacheTimeToLive(CACHE_TTL_MS)
        return Triple(layer, safeCache, source)
    }

    fun isOnline(context: Context): Boolean {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val net = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(net) ?: return false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))
        } catch (_: Exception) {
            false
        }
    }

    /** Slippy tile (x, y) of a coordinate at a zoom. */
    fun lonLatToTile(
        lon: Double,
        lat: Double,
        zoom: Int,
    ): Pair<Int, Int> {
        val n = 1 shl zoom
        val x = ((lon + 180.0) / 360.0 * n).toInt().coerceIn(0, n - 1)
        val latRad = lat * PI / 180.0
        val y = ((1.0 - ln(tan(latRad) + 1.0 / cos(latRad)) / PI) / 2.0 * n)
            .toInt().coerceIn(0, n - 1)
        return x to y
    }

    /** How many tiles of the current view are already in the disk cache. */
    fun cachedTilesInView(
        cache: FileSystemTileCache,
        source: OnlineTileSource,
        northWest: LatLong,
        southEast: LatLong,
        zoom: Int,
    ): Int {
        return try {
            val (x0, y0) = lonLatToTile(northWest.longitude, northWest.latitude, zoom)
            val (x1, y1) = lonLatToTile(southEast.longitude, southEast.latitude, zoom)
            var count = 0
            for (x in minOf(x0, x1)..maxOf(x0, x1)) {
                for (y in minOf(y0, y1)..maxOf(y0, y1)) {
                    val tile = Tile(x, y, zoom.toByte(), TILE_SIZE)
                    if (cache.containsKey(DownloadJob(tile, source))) count++
                }
            }
            count
        } catch (_: Exception) {
            0
        }
    }
}
