// Map manager (superprompt §6.5, §7; Fase 5).
//
// THE one owner of the map: the single MapView, the single source
// (offline .map file / online tiles / grid), the single download with the
// single progress strip, and the single place that manages maps. There is
// no second set of download controls anywhere (superprompt §10.4): the
// Manage-maps sheet is the only management UI and the strip inside it is
// the only progress display.
//
// Sources (SignalX 1.0.9 behavior, ported):
// - FILE: a .map file in filesDir/maps, imported by the user or
//   downloaded (Cuba). The .map is NEVER inside the APK or assets.
// - ONLINE: OSM tiles for the area on screen only, with a 7-day cache
//   (OnlineMap). No prefetch, no bulk download (OSM tile policy §4).
// - GRID: no map at all — dark background + grid + message, never black.
//   Shown when the online map failed repeatedly (5 bad tiles) or crashed
//   before, until the user retries Online by hand.
//
// The MapView itself is created by the Compose screen (AndroidView) and
// handed to bind(); this manager only drives it. All MapView calls run
// on the main thread; downloads run on IO.

package dev.ririxi.bunnylock.signalx

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.view.MotionEvent
import org.mapsforge.core.model.LatLong
import org.mapsforge.core.model.MapPosition
import org.mapsforge.core.model.Rotation
import org.mapsforge.map.android.graphics.AndroidGraphicFactory
import org.mapsforge.map.android.util.AndroidUtil
import org.mapsforge.map.android.view.MapView
import org.mapsforge.map.layer.cache.FileSystemTileCache
import org.mapsforge.map.layer.cache.TileCache
import org.mapsforge.map.layer.download.TileDownloadLayer
import org.mapsforge.map.layer.download.tilesource.OnlineTileSource
import org.mapsforge.map.model.common.Observer
import org.mapsforge.map.reader.MapFile
import org.mapsforge.map.rendertheme.XmlRenderTheme
import java.io.File
import kotlin.math.abs
import kotlin.math.cos

/** Chosen map source (persisted). GRID is a display fallback, not a choice. */
enum class MapSource {
    FILE,
    ONLINE,
}

/** One download's state — the single progress strip reads this and nothing else. */
sealed class TransferState {
    data object Idle : TransferState()
    data class Active(
        val downloaded: Long,
        val total: Long,
        val bytesPerSec: Long,
        val paused: Boolean,
    ) : TransferState()
    data class Failed(val reason: MapDownload.FailReason) : TransferState()
    data object Done : TransferState()
}

data class MapFileInfo(
    val name: String,
    val sizeBytes: Long,
    val isActive: Boolean,
)

data class MapUiState(
    val source: MapSource = MapSource.ONLINE,
    val grid: Boolean = false,
    /** "online_failed" | "no_map" | null — the screen maps it to words. */
    val gridReason: String? = null,
    val activeMapName: String? = null,
    val maps: List<MapFileInfo> = emptyList(),
    val transfer: TransferState = TransferState.Idle,
    val cacheBytes: Long = 0L,
    val rotationDeg: Float = 0f,
    val zoomLevel: Int = 16,
    /** Online is usable when there is a connection or saved tiles cover the view. */
    val onlineUsable: Boolean = false,
    val cachedTilesInView: Int = 0,
)

sealed class ImportOutcome {
    data class Ok(val name: String) : ImportOutcome()
    data object NotAMapFile : ImportOutcome()
    data object Failed : ImportOutcome()
}

class MapManager(
    context: Context,
    private val scope: CoroutineScope,
) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("bunnylock", Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(MapUiState())
    val state: StateFlow<MapUiState> = _state.asStateFlow()

    private var mapView: MapView? = null
    private var tileCache: TileCache? = null
    private var onlineTileLayer: TileDownloadLayer? = null
    private var onlineTileCache: FileSystemTileCache? = null
    private var onlineTileCacheSafe: SafeTileCache? = null
    private var onlineTileSource: OnlineTileSource? = null
    private var onlineTileFailures = 0
    private var graphicsReady = false

    /** Map tap (placing, or a tower pin): lat/lon in degrees + the tap position in MapView pixels. Set by the screen. */
    var onMapTap: ((Double, Double, Float, Float) -> Unit)? = null

    private val downloadControl = MapDownload.Control()
    private var downloadJobActive = false

    private val positionObserver =
        Observer {
            val view = mapView ?: return@Observer
            try {
                _state.update {
                    it.copy(
                        rotationDeg = view.mapRotation.degrees.toFloat(),
                        zoomLevel = view.model.mapViewPosition.zoomLevel.toInt(),
                    )
                }
            } catch (_: Exception) {
            }
        }

    /** Must run before creating a MapView (Mapsforge needs its graphic factory first). */
    fun ensureGraphics() {
        if (graphicsReady) return
        try {
            AndroidGraphicFactory.createInstance(appContext)
            graphicsReady = true
        } catch (_: Exception) {
        }
    }

    fun mapsDir(): File = File(appContext.filesDir, "maps").also { it.mkdirs() }

    fun bind(
        view: MapView,
        /** Center the map here on first open (e.g. the antenna target from Antennas), instead of the saved view. */
        initialCenter: LatLong? = null,
    ) {
        mapView = view
        try {
            view.touchGestureHandler.setRotationEnabled(true)
        } catch (_: Exception) {
        }
        try {
            view.model.mapViewPosition.addObserver(positionObserver)
        } catch (_: Exception) {
        }
        view.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.x
                    downY = event.y
                    downTime = System.currentTimeMillis()
                }
                MotionEvent.ACTION_UP -> {
                    val moved = abs(event.x - downX) + abs(event.y - downY)
                    val dt = System.currentTimeMillis() - downTime
                    if (moved < 24f && dt < 400) {
                        try {
                            val ll = view.mapViewProjection.fromPixels(event.x.toDouble(), event.y.toDouble())
                            onMapTap?.invoke(ll.latitude, ll.longitude, event.x, event.y)
                        } catch (_: Exception) {
                        }
                    }
                }
            }
            false
        }
        loadInitial(initialCenter)
        refreshMapsList()
        refreshCacheBytes()
    }

    private var downX = 0f
    private var downY = 0f
    private var downTime = 0L

    /** Called when the view goes away: saves the position and frees the caches. The screen calls MapView.onDestroy() itself. */
    fun unbind() {
        persistPosition()
        try {
            onlineTileLayer?.onPause()
        } catch (_: Exception) {
        }
        destroyCaches()
        try {
            mapView?.model?.mapViewPosition?.removeObserver(positionObserver)
        } catch (_: Exception) {
        }
        mapView = null
    }

    fun close() {
        downloadControl.cancelled = true
        try {
            downloadControl.conn?.disconnect()
        } catch (_: Exception) {
        }
        destroyCaches()
    }

    // ---- Initial source (SignalX 1.0.9 loadActiveMap) ----

    private fun loadInitial(initialCenter: LatLong? = null) {
        val view = mapView ?: return
        val active =
            prefs.getString("active_map", null)?.let { File(mapsDir(), it) }?.takeIf { it.exists() }
                ?: mapsDir().listFiles()?.firstOrNull { it.name.endsWith(".map") }
        val source =
            when (prefs.getString("map_source", null)) {
                "file" -> MapSource.FILE
                "online" -> MapSource.ONLINE
                else -> if (active != null) MapSource.FILE else MapSource.ONLINE
            }
        if (source == MapSource.FILE && active != null) {
            setupMap(active)
        } else if (source == MapSource.ONLINE && prefs.getBoolean(OnlineMap.PREF_ONLINE_TILE_CRASH, false)) {
            // The online map killed the app last time: open the grid
            // instead of retrying in a loop. Manage maps > Online retries by hand.
            openGrid("online_failed")
        } else {
            setupOnline()
        }
        // Restore the saved view (or Santiago at zoom 16 by default),
        // unless the caller asked to open on a specific point (the antenna target).
        val lat = initialCenter?.latitude
            ?: prefs.getString("map_lat", null)?.toDoubleOrNull()
        val lon = initialCenter?.longitude
            ?: prefs.getString("map_lon", null)?.toDoubleOrNull()
        val zoom = prefs.getString("map_zoom", null)?.toIntOrNull()
        val rotation = prefs.getFloat("map_rotation", 0f)
        try {
            if (lat != null && lon != null) {
                view.model.mapViewPosition.mapPosition =
                    MapPosition(LatLong(lat, lon), (zoom ?: 16).toByte())
                view.setCenter(LatLong(lat, lon))
                if (rotation != 0f) {
                    view.rotate(Rotation(rotation, view.width / 2f, view.height / 2f))
                }
            } else {
                view.model.mapViewPosition.mapPosition = MapPosition(LatLong(20.02, -75.83), 16.toByte())
                view.setCenter(LatLong(20.02, -75.83))
            }
        } catch (_: Exception) {
        }
        _state.update { it.copy(rotationDeg = rotation, zoomLevel = zoom ?: 16) }
    }

    // ---- Offline .map (SignalX 1.0.9 setupMap) ----

    fun setupMap(
        file: File,
        preservePosition: Boolean = false,
    ): Boolean {
        val view = mapView ?: return false
        return try {
            val theme: XmlRenderTheme = osmarenderTheme()
            destroyCaches()
            try {
                onlineTileLayer?.onPause()
            } catch (_: Exception) {
            }
            onlineTileLayer = null
            onlineTileCache = null
            onlineTileCacheSafe = null
            onlineTileSource = null
            try {
                view.layerManager.layers.clear()
            } catch (_: Exception) {
            }
            val mapData = MapFile(file)
            // 512-px tiles (fewer tiles per frame) and a 2x buffer, so
            // panning shows already-drawn ground instead of waiting.
            view.model.displayModel.setFixedTileSize(512)
            view.model.frameBufferModel.setOverdrawFactor(2.0)
            val tileSize = view.model.displayModel.tileSize
            val overdraw = view.model.frameBufferModel.overdrawFactor
            tileCache = AndroidUtil.createTileCache(appContext, "signalx", tileSize, 1f, overdraw)
            val layer =
                AndroidUtil.createTileRendererLayer(
                    tileCache, view.model.mapViewPosition, mapData, theme, false, true, false,
                )
            view.layerManager.layers.add(layer)
            view.setZoomLevelMin(7.toByte())
            view.setZoomLevelMax(18.toByte())
            val z = view.model.mapViewPosition.zoomLevel
            if (z < 7) view.model.mapViewPosition.setZoomLevel(7.toByte())
            if (z > 18) view.model.mapViewPosition.setZoomLevel(18.toByte())
            if (!preservePosition) {
                view.model.mapViewPosition.mapPosition = MapPosition(LatLong(20.02, -75.83), 16.toByte())
                view.setCenter(LatLong(20.02, -75.83))
            }
            setMapSource(MapSource.FILE)
            prefs.edit().putString("active_map", file.name).apply()
            _state.update { it.copy(source = MapSource.FILE, grid = false, gridReason = null, activeMapName = file.name) }
            refreshMapsList()
            true
        } catch (_: Exception) {
            openGrid("no_map")
            false
        }
    }

    // ---- Online tiles (SignalX 1.0.9 setupOnlineMap) ----

    /** Manual retry: clears the crash marker and the failure counter; if it fails again, it is marked again. */
    fun setupOnline() {
        val view = mapView ?: return
        try {
            prefs.edit().remove(OnlineMap.PREF_ONLINE_TILE_CRASH).apply()
            onlineTileFailures = 0
            try {
                onlineTileLayer?.onPause()
            } catch (_: Exception) {
            }
            destroyCaches()
            try {
                view.layerManager.layers.clear()
            } catch (_: Exception) {
            }
            // OSM tiles are 256 px (the .map uses 512): otherwise every tile stretches double and looks blurry.
            view.model.displayModel.setFixedTileSize(OnlineMap.TILE_SIZE)
            view.model.frameBufferModel.setOverdrawFactor(2.0)
            val (layer, safeCache, source) =
                OnlineMap.createLayer(
                    appContext, view.model.mapViewPosition, AndroidGraphicFactory.INSTANCE,
                    onTileFailure = { onOnlineTileFailure() },
                    onTileStored = { onlineTileFailures = 0 },
                )
            onlineTileLayer = layer
            onlineTileCache = safeCache.raw
            onlineTileCacheSafe = safeCache
            onlineTileSource = source
            view.layerManager.layers.add(layer)
            layer.start()
            safeCache.addObserver(Observer { refreshOnlineStatus() })
            view.setZoomLevelMin(OnlineMap.ZOOM_MIN)
            view.setZoomLevelMax(OnlineMap.ZOOM_MAX)
            val z = view.model.mapViewPosition.zoomLevel
            if (z < OnlineMap.ZOOM_MIN) view.model.mapViewPosition.setZoomLevel(OnlineMap.ZOOM_MIN)
            if (z > OnlineMap.ZOOM_MAX) view.model.mapViewPosition.setZoomLevel(OnlineMap.ZOOM_MAX)
            setMapSource(MapSource.ONLINE)
            _state.update { it.copy(source = MapSource.ONLINE, grid = false, gridReason = null) }
            refreshOnlineStatus()
        } catch (_: Exception) {
            openGrid("online_failed")
        }
    }

    private fun onOnlineTileFailure() {
        onlineTileFailures++
        if (onlineTileFailures >= 5) {
            prefs.edit().putBoolean(OnlineMap.PREF_ONLINE_TILE_CRASH, true).apply()
            openGrid("online_failed")
        }
    }

    /** Grid: no map for this area. Dark background + grid + message, never black. */
    fun openGrid(reason: String) {
        val view = mapView
        try {
            onlineTileLayer?.onPause()
        } catch (_: Exception) {
        }
        destroyCaches()
        try {
            view?.layerManager?.layers?.clear()
        } catch (_: Exception) {
        }
        onlineTileLayer = null
        onlineTileCache = null
        onlineTileCacheSafe = null
        onlineTileSource = null
        _state.update { it.copy(grid = true, gridReason = reason) }
    }

    // ---- View controls ----

    fun centerOn(
        lat: Double,
        lon: Double,
        zoom: Byte? = null,
    ) {
        val view = mapView ?: return
        try {
            if (zoom != null) view.model.mapViewPosition.setZoomLevel(zoom)
            view.setCenter(LatLong(lat, lon))
            persistPosition()
        } catch (_: Exception) {
        }
    }

    fun zoomIn() {
        try {
            mapView?.model?.mapViewPosition?.zoomIn()
        } catch (_: Exception) {
        }
    }

    fun zoomOut() {
        try {
            mapView?.model?.mapViewPosition?.zoomOut()
        } catch (_: Exception) {
        }
    }

    fun resetNorth() {
        val view = mapView ?: return
        try {
            view.rotate(Rotation(0f, view.width / 2f, view.height / 2f))
        } catch (_: Exception) {
        }
    }

    /** Project a geographic point to screen pixels, or null when it cannot be projected. */
    fun project(
        lat: Double,
        lon: Double,
    ): org.mapsforge.core.model.Point? {
        return try {
            mapView?.mapViewProjection?.toPixels(LatLong(lat, lon))
        } catch (_: Exception) {
            null
        }
    }

    // ---- Management: import / delete / download / cache ----

    fun refreshMapsList() {
        val active = prefs.getString("active_map", null)
        val list =
            mapsDir().listFiles()
                ?.filter { it.isFile && it.name.endsWith(".map") }
                ?.sortedBy { it.name }
                ?.map { MapFileInfo(it.name, it.length(), it.name == active) }
                ?: emptyList()
        _state.update { it.copy(maps = list, activeMapName = active) }
    }

    fun refreshCacheBytes() {
        _state.update { it.copy(cacheBytes = OnlineMap.cacheSizeBytes(appContext)) }
    }

    /**
     * Imports a .map file picked by the user (Storage Access Framework).
     * Validates the "mapsforge binary OSM" magic before accepting it; a
     * bad file is refused, never opened as a map. On success the file
     * becomes the active offline map.
     */
    suspend fun importMap(uri: Uri): ImportOutcome =
        withContext(Dispatchers.IO) {
            try {
                val name = displayNameOf(uri) ?: "imported.map"
                val safeName = name.replace(Regex("[^A-Za-z0-9._-]"), "_").takeIf { it.endsWith(".map") } ?: "$name.map"
                val dest = File(mapsDir(), safeName)
                appContext.contentResolver.openInputStream(uri)?.use { input ->
                    dest.outputStream().use { output -> input.copyTo(output) }
                } ?: return@withContext ImportOutcome.Failed
                if (!isMapFile(dest)) {
                    dest.delete()
                    return@withContext ImportOutcome.NotAMapFile
                }
                withContext(Dispatchers.Main) {
                    setupMap(dest)
                }
                refreshMapsList()
                ImportOutcome.Ok(dest.name)
            } catch (_: Exception) {
                ImportOutcome.Failed
            }
        }

    private fun isMapFile(file: File): Boolean {
        if (!file.isFile || file.length() == 0L) return false
        return try {
            val magic = "mapsforge binary OSM".toByteArray(Charsets.UTF_8)
            val head = ByteArray(magic.size)
            file.inputStream().use { it.read(head) }
            head.contentEquals(magic)
        } catch (_: Exception) {
            false
        }
    }

    private fun displayNameOf(uri: Uri): String? {
        return try {
            appContext.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        } catch (_: Exception) {
            null
        }
    }

    /** Deletes an offline map. If it was active, falls back to another .map, or to online. */
    fun deleteMap(name: String) {
        val wasActive = prefs.getString("active_map", null) == name
        try {
            File(mapsDir(), name).delete()
        } catch (_: Exception) {
        }
        if (wasActive) {
            prefs.edit().remove("active_map").apply()
            val next = mapsDir().listFiles()?.firstOrNull { it.name.endsWith(".map") }
            if (next != null) setupMap(next) else setupOnline()
        }
        refreshMapsList()
    }

    /** Switches to an already-imported offline map. */
    fun useMap(name: String): Boolean {
        val file = File(mapsDir(), name)
        if (!file.exists()) return false
        return setupMap(file, preservePosition = true)
    }

    /** Starts the Cuba download (the only download offered). Returns false when one is already running or the entry has no URL. */
    fun startDownload(entry: MapCatalog.MapEntry): Boolean {
        val url = entry.url ?: return false
        if (downloadJobActive) return false
        val dest = File(mapsDir(), entry.fileName)
        downloadControl.paused = false
        downloadControl.cancelled = false
        downloadJobActive = true
        _state.update { it.copy(transfer = TransferState.Active(0L, entry.sizeBytes, 0L, false)) }
        scope.launch(Dispatchers.IO) {
            var lastEmit = 0L
            val result =
                MapDownload.download(url, dest, downloadControl) { downloaded, total, speed ->
                    val now = System.currentTimeMillis()
                    if (now - lastEmit >= 250) {
                        lastEmit = now
                        _state.update { it.copy(transfer = TransferState.Active(downloaded, total, speed, downloadControl.paused)) }
                    }
                }
            downloadJobActive = false
            when (result) {
                is MapDownload.Result.Done -> {
                    _state.update { it.copy(transfer = TransferState.Done) }
                    withContext(Dispatchers.Main) { setupMap(dest) }
                }
                is MapDownload.Result.Paused -> {
                    _state.update {
                        val current = (it.transfer as? TransferState.Active)
                        it.copy(transfer = current?.copy(paused = true) ?: TransferState.Active(0L, entry.sizeBytes, 0L, true))
                    }
                }
                is MapDownload.Result.Cancelled -> {
                    _state.update { it.copy(transfer = TransferState.Idle) }
                }
                is MapDownload.Result.Failed -> {
                    _state.update { it.copy(transfer = TransferState.Failed(result.reason)) }
                }
            }
        }
        return true
    }

    fun pauseDownload() {
        downloadControl.paused = true
        _state.update {
            val current = (it.transfer as? TransferState.Active)
            if (current != null) it.copy(transfer = current.copy(paused = true)) else it
        }
    }

    fun resumeDownload() {
        // Resume restarts the download loop, which continues the partial file with Range.
        val entry = MapCatalog.CUBA
        if (downloadJobActive) {
            downloadControl.paused = false
            return
        }
        downloadControl.paused = false
        startDownload(entry)
    }

    fun cancelDownload() {
        downloadControl.cancelled = true
        try {
            downloadControl.conn?.disconnect()
        } catch (_: Exception) {
        }
    }

    fun clearTransferState() {
        _state.update { it.copy(transfer = TransferState.Idle) }
    }

    fun clearTileCache() {
        try {
            onlineTileCacheSafe?.purge()
        } catch (_: Exception) {
        }
        try {
            onlineTileCache?.purge()
        } catch (_: Exception) {
        }
        refreshCacheBytes()
        refreshOnlineStatus()
    }

    /** Recomputes whether online is usable for the current view (connection, or saved tiles covering it). */
    fun refreshOnlineStatus() {
        val view = mapView
        val cache = onlineTileCache
        val source = onlineTileSource
        if (view == null || cache == null || source == null) {
            _state.update { it.copy(onlineUsable = OnlineMap.isOnline(appContext), cachedTilesInView = 0) }
            return
        }
        try {
            val pos = view.model.mapViewPosition
            val center = pos.center
            val zoom = pos.zoomLevel.toInt()
            // Visible span from center + zoom + view size (Mercator),
            // so no Mapsforge bounding-box API is needed.
            val mpp = 156543.03392 * cos(Math.toRadians(center.latitude)) / (1 shl zoom)
            val latSpan = (view.height * mpp) / 111_320.0
            val lonSpan = (view.width * mpp) / (111_320.0 * cos(Math.toRadians(center.latitude)).coerceAtLeast(0.01))
            val nw = LatLong((center.latitude + latSpan / 2).coerceIn(-85.0, 85.0), center.longitude - lonSpan / 2)
            val se = LatLong((center.latitude - latSpan / 2).coerceIn(-85.0, 85.0), center.longitude + lonSpan / 2)
            val cached = OnlineMap.cachedTilesInView(cache, source, nw, se, zoom)
            _state.update { it.copy(cachedTilesInView = cached, onlineUsable = OnlineMap.isOnline(appContext) || cached > 0) }
        } catch (_: Exception) {
            _state.update { it.copy(onlineUsable = OnlineMap.isOnline(appContext)) }
        }
    }

    // ---- Persistence ----

    private fun setMapSource(source: MapSource) {
        prefs.edit().putString("map_source", if (source == MapSource.FILE) "file" else "online").apply()
    }

    private fun persistPosition() {
        val view = mapView ?: return
        try {
            val center = view.model.mapViewPosition.center
            prefs.edit()
                .putString("map_lat", center.latitude.toString())
                .putString("map_lon", center.longitude.toString())
                .putString("map_zoom", view.model.mapViewPosition.zoomLevel.toString())
                .putFloat("map_rotation", view.mapRotation.degrees.toFloat())
                .apply()
        } catch (_: Exception) {
        }
    }

    private fun destroyCaches() {
        try {
            tileCache?.destroy()
        } catch (_: Exception) {
        }
        tileCache = null
        try {
            onlineTileCache?.destroy()
        } catch (_: Exception) {
        }
        onlineTileCache = null
        onlineTileCacheSafe = null
    }

    /** OSMARENDER theme by reflection: the `internal` package cannot be imported in Kotlin. */
    private fun osmarenderTheme(): XmlRenderTheme {
        val cls = Class.forName("org.mapsforge.map.rendertheme.internal.MapsforgeThemes")
        return cls.getField("OSMARENDER").get(null) as XmlRenderTheme
    }
}
