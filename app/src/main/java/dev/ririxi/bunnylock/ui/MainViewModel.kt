package dev.ririxi.bunnylock.ui

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.ririxi.bunnylock.R
import dev.ririxi.bunnylock.core.Bands
import dev.ririxi.bunnylock.data.Cell
import dev.ririxi.bunnylock.data.LockNotifier
import dev.ririxi.bunnylock.data.Rat
import dev.ririxi.bunnylock.data.Updates
import dev.ririxi.bunnylock.modem.Backend
import dev.ririxi.bunnylock.modem.BandGroupState
import dev.ririxi.bunnylock.modem.CellLockRequest
import dev.ririxi.bunnylock.modem.Group
import dev.ririxi.bunnylock.modem.ImsFeature
import dev.ririxi.bunnylock.modem.LockRat
import dev.ririxi.bunnylock.modem.ModemController
import dev.ririxi.bunnylock.modem.ModemState
import dev.ririxi.bunnylock.modem.NetMode
import dev.ririxi.bunnylock.modem.NrMode
import dev.ririxi.bunnylock.modem.RootState
import dev.ririxi.bunnylock.modem.Text
import dev.ririxi.bunnylock.modem.asSentence
import dev.ririxi.bunnylock.modem.reason
import dev.ririxi.bunnylock.modem.string
import dev.ririxi.bunnylock.shizuku.RootOrShizuku
import dev.ririxi.bunnylock.shizuku.ShizukuClient
import dev.ririxi.bunnylock.shizuku.samsung.SamsungBandMenu
import dev.ririxi.bunnylock.signalx.AntennaFilter
import dev.ririxi.bunnylock.signalx.AntennaRow
import dev.ririxi.bunnylock.signalx.AntennaTarget
import dev.ririxi.bunnylock.signalx.CrashLog
import dev.ririxi.bunnylock.signalx.FieldDataSync
import dev.ririxi.bunnylock.signalx.HeadingProvider
import dev.ririxi.bunnylock.signalx.HeadingReading
import dev.ririxi.bunnylock.signalx.LocationProvider
import dev.ririxi.bunnylock.signalx.MapManager
import dev.ririxi.bunnylock.signalx.MeasurementLocation
import dev.ririxi.bunnylock.signalx.MeasurementSource
import dev.ririxi.bunnylock.signalx.ObservationDetail
import dev.ririxi.bunnylock.signalx.SeedAntenna
import dev.ririxi.bunnylock.signalx.SignalXDb
import dev.ririxi.bunnylock.signalx.UserPosition
import dev.ririxi.bunnylock.signalx.buildAntennaRows
import dev.ririxi.bunnylock.signalx.distanceMeters
import dev.ririxi.bunnylock.signalx.hasLocationPermission
import dev.ririxi.bunnylock.signalx.loadSeedAntennas
import dev.ririxi.bunnylock.signalx.measurementSnapshot
import dev.ririxi.bunnylock.signalx.servingReading
import dev.ririxi.bunnylock.ui.design.SxFormat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val MAX_TOOL_OUTPUT_CHARS = 8000

private const val EXCLUSIVE_BAND_LOCK_KEY = "noBandsTurnsOff"

private const val AUTO_COLLECT_KEY = "autoCollect"

private const val ANTENNAS_QUERY_KEY = "antenasQuery"

private const val ANTENNAS_FILTER_KEY = "antenasFilter"

private const val TARGET_ENB_KEY = "targetEnb"

private const val TARGET_LAT_KEY = "targetLatBits"

private const val TARGET_LON_KEY = "targetLonBits"

/** Auto-collection saves at most every 30 s, or sooner when the cell changes (SignalX 1.0.9 rule). */
private const val AUTO_SAVE_INTERVAL_MS = 30_000L

/** A reading older than this is stale: it is shown, never recorded as a new measurement. */
private const val AUTO_SAVE_MAX_READING_AGE_MS = 10_000L

class MainViewModel(
    private val app: Application,
) : AndroidViewModel(app) {
    private val prefs = app.getSharedPreferences("bunnylock", Context.MODE_PRIVATE)

    private val _ui =
        MutableStateFlow(
            UiState(
                dynamicColor = prefs.getBoolean("dynamicColor", true),
                themeMode = loadThemeMode(),
                pureBlack = prefs.getBoolean("pureBlack", false),
                checkUpdates = prefs.getBoolean("checkUpdates", true),
                exclusiveBandLock = prefs.getBoolean(EXCLUSIVE_BAND_LOCK_KEY, false),
                autoCollect = prefs.getBoolean(AUTO_COLLECT_KEY, true),
                favoriteBands = loadFavoriteBands(),
                antennaTarget = loadAntennaTarget(),
                antennasQuery = prefs.getString(ANTENNAS_QUERY_KEY, "") ?: "",
                antennasFilter = AntennaFilter.entries.firstOrNull { it.name == prefs.getString(ANTENNAS_FILTER_KEY, null) } ?: AntennaFilter.ALL,
            ),
        )

    private val ui get() = _ui.value

    private val latest: UiState get() = ui.copy(modem = modem.state.value)

    private inline fun updateState(transform: UiState.() -> UiState) = _ui.update(transform)

    private val _events = MutableSharedFlow<String>(extraBufferCapacity = 8)

    private var pendingUnlock = false

    private val modem =
        ModemController(
            app,
            viewModelScope,
            RootOrShizuku(app, ::shizukuLost),
            onConnected = ::unlockIfPending,
            onRefreshed = ::refreshed,
            shizukuMenuLauncher = { SamsungBandMenu.open(app) },
        )

    // SignalX field records (SQLite "signalx.db"). BunnyLock's own storage
    // (the "bunnylock" prefs above) is untouched; this base holds only
    // saved measurements, their neighbors, the observed-cells registry and
    // the manual antenna positions (tower_positions, Fase 4).
    private val signalXDb = SignalXDb(app)

    // Orientation (Fase 5): THE one location source and THE one heading
    // source of the whole app (Location.kt). Started when the app is in
    // the foreground and stopped on pause (MainActivity), like SignalX
    // 1.0.9 — the GPS never runs while the app is in the background.
    private val locationProvider = LocationProvider(app)
    private val headingProvider = HeadingProvider(app)
    private var orientationStarted = false

    /** False on phones without a rotation-vector sensor: the compass shows its no-sensor state instead of a fake heading. */
    val hasCompassSensor: Boolean get() = headingProvider.hasSensor

    /** Live heading, for the compass and the map only. It stays OUT of UiState: the sensor fires far more often than the UI state should churn. */
    private val _heading = MutableStateFlow<HeadingReading?>(null)
    val heading: StateFlow<HeadingReading?> = _heading.asStateFlow()
    private var lastHeadingEmitMs = 0L

    /** The map manager (Fase 5): the single owner of the MapView, the map source and the one download. */
    val mapManager = MapManager(app, viewModelScope)

    /** The position the antenna distances were last built from (Fase 5): rebuilt only when the user moved 50 m or on the first fix. */
    private var lastAntennaLocation: MeasurementLocation? = null

    /** The seed catalog, parsed once per process (assets/santiago-enb.json, 96 eNB). */
    private var seedAntennas: List<SeedAntenna>? = null

    /** Serving cell of the last auto-save, for the "or when the cell changes" rule. */
    private var lastAutoSavedKey: String? = null
    private var lastAutoSaveMs: Long = 0L

    // Shizuku hands over its binder a moment after the app starts, or whenever it is started later, so a failed attempt is redone then.
    private val stopWatchingShizuku = ShizukuClient.onBinderReceived(modem::reconnect)

    val state =
        combine(modem.state, _ui) { modemState, uiState -> uiState.copy(modem = modemState) }
            .stateIn(viewModelScope, SharingStarted.Eagerly, latest)

    val events = merge(_events, modem.events)

    init {
        modem.connect()
        checkForUpdate()
        refreshSavedState()
        startAutoCollection()
    }

    private fun appVersion(): String? = runCatching { app.packageManager.getPackageInfo(app.packageName, 0).versionName }.getOrNull()

    private fun checkForUpdate() =
        viewModelScope.launch {
            if (!ui.checkUpdates) return@launch
            val installed = appVersion() ?: return@launch
            try {
                val release = Updates.newerThan(installed)?.takeIf { it.version != prefs.getString("skippedUpdate", null) }
                if (ui.checkUpdates) updateState { copy(update = release) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                modem.log("Couldn't check for updates. ${e.message?.takeIf { it.isNotBlank() }?.asSentence() ?: "Something went wrong."}")
            }
        }

    fun dismissUpdate() = updateState { copy(update = null) }

    fun skipUpdate() {
        ui.update?.let { prefs.edit().putString("skippedUpdate", it.version).apply() }
        dismissUpdate()
    }

    fun setCheckUpdates(enabled: Boolean) {
        prefs.edit().putBoolean("checkUpdates", enabled).apply()
        updateState { copy(checkUpdates = enabled) }
        if (enabled) checkForUpdate() else dismissUpdate()
    }

    override fun onCleared() {
        stopWatchingShizuku()
        stopOrientation()
        mapManager.close()
        modem.close()
        signalXDb.close()
    }

    fun connect() = modem.connect()

    // ---- Orientation: location + heading (Fase 5) ----

    /**
     * Starts the single location + heading providers. Idempotent: safe
     * to call from onResume, from the permission grant and from the
     * Orientation screen. Without the location permission it does
     * nothing — the UI shows "no GPS fix", never a fake position.
     */
    fun startOrientation() {
        if (orientationStarted) return
        if (!hasLocationPermission(app)) return
        orientationStarted = true
        locationProvider.start { location ->
            headingProvider.setLocation(location.latitude, location.longitude, location.altitude.toFloat())
            val position =
                UserPosition(
                    latitude = location.latitude,
                    longitude = location.longitude,
                    accuracy = location.accuracy.takeIf { location.hasAccuracy() },
                    updatedAtMs = System.currentTimeMillis(),
                )
            updateState { copy(userPosition = position) }
            maybeRefreshAntennas(position)
        }
        headingProvider.start { degrees, reliable ->
            // Throttle: the sensor fires far more often than the compass needs.
            val now = SystemClock.elapsedRealtime()
            if (now - lastHeadingEmitMs < 200) return@start
            lastHeadingEmitMs = now
            _heading.value = HeadingReading(degrees, reliable)
        }
    }

    fun stopOrientation() {
        if (!orientationStarted) return
        orientationStarted = false
        locationProvider.stop()
        headingProvider.stop()
    }

    /** The fix + heading a save carries. Null while there is no fix — never 0. */
    fun currentMeasurementLocation(): MeasurementLocation? =
        ui.userPosition?.toMeasurementLocation(_heading.value?.degrees)

    /** Rebuilds the antenna distances only when the user really moved (50 m) or on the first fix. */
    private fun maybeRefreshAntennas(position: UserPosition) {
        val location = position.toMeasurementLocation()
        val last = lastAntennaLocation
        if (last?.latitude == null || last.longitude == null) {
            lastAntennaLocation = location
            refreshAntennas()
            return
        }
        val moved =
            distanceMeters(
                last.latitude ?: return, last.longitude ?: return,
                position.latitude, position.longitude,
            )
        if (moved >= 50.0) {
            lastAntennaLocation = location
            refreshAntennas()
        }
    }

    /** Starts placing an eNB by tapping the map (Fase 5). The map screen shows the placing banner until it is cleared. */
    fun setPlacingEnb(enb: Int) {
        updateState { copy(placingEnb = enb) }
    }

    fun clearPlacingEnb() {
        updateState { copy(placingEnb = null) }
    }

    /** Saves an install point from the compass: GPS + azimuth + live signal. The photo variant lives in armPointPhoto()/onPhotoTaken (Fase 6). */
    fun savePoint() =
        viewModelScope.launch {
            val location = currentMeasurementLocation()
            if (location?.latitude == null || location.longitude == null) {
                _events.emit(app.getString(R.string.orient_point_no_gps))
                return@launch
            }
            val rsrp = modem.state.value.servingReading()?.signal?.rsrp
            val id =
                withContext(Dispatchers.IO) {
                    runCatching {
                        signalXDb.savePoint(
                            location.latitude, location.longitude,
                            location.accuracy, location.azimuth, rsrp,
                        )
                    }.getOrNull() ?: -1L
                }
            if (id < 0) {
                _events.emit(app.getString(R.string.measure_saved_failed))
                return@launch
            }
            refreshSavedState()
            _events.emit(app.getString(R.string.orient_point_saved, SxFormat.formatCoordinates(location.latitude, location.longitude)))
        }

    // ---- Fase 6: camera, data, diagnostics ----

    /** The field camera's folder (internal files, no storage permission needed). */
    fun photosDir(): File = File(app.filesDir, "photos").also { it.mkdirs() }

    /** True while the next photo is armed to be attached to a saved point (set by the compass, read once by the camera screen). */
    private var savePointWithNextPhoto = false

    fun isPhotoPointArmed(): Boolean = savePointWithNextPhoto

    /**
     * Arms the next photo to be attached to a saved point. False when
     * there is no fix to attach — the compass button is disabled then,
     * so this is a guard, not a path the UI can reach by accident.
     */
    fun armPointPhoto(): Boolean {
        val location = currentMeasurementLocation()
        if (location?.latitude == null || location.longitude == null) {
            _events.tryEmit(app.getString(R.string.orient_point_no_gps_photo))
            return false
        }
        savePointWithNextPhoto = true
        return true
    }

    /**
     * Records a photo the field camera saved: always a photos row (GPS
     * + azimuth at capture time), and — when the compass armed it — a
     * saved point carrying the photo path (Fase 5's "Save point with
     * photo", finally wired). Without a fix the photo saves without a
     * position, never with 0.
     */
    fun onPhotoTaken(file: File) =
        viewModelScope.launch {
            val location = currentMeasurementLocation()
            val rsrp = modem.state.value.servingReading()?.signal?.rsrp
            val armed = savePointWithNextPhoto
            savePointWithNextPhoto = false
            withContext(Dispatchers.IO) {
                runCatching {
                    signalXDb.insertPhoto(file.absolutePath, location?.latitude, location?.longitude, location?.azimuth, location?.accuracy)
                }
                if (armed && location?.latitude != null && location.longitude != null) {
                    runCatching {
                        signalXDb.savePoint(
                            location.latitude, location.longitude,
                            location.accuracy, location.azimuth, rsrp,
                            photoPath = file.absolutePath,
                        )
                    }
                }
            }
            refreshSavedState()
            if (armed && location?.latitude != null && location.longitude != null) {
                _events.emit(app.getString(R.string.camera_point_saved, file.name))
            } else if (location?.latitude == null || location.longitude == null) {
                _events.emit(app.getString(R.string.camera_saved_no_gps))
            } else {
                _events.emit(app.getString(R.string.camera_saved, file.name))
            }
        }

    /** Everything Datos shows, in one read, filtered by [query] (eNB, band, file name or date — case-insensitive). */
    suspend fun loadDataSnapshot(query: String): DataSnapshot =
        withContext(Dispatchers.IO) {
            val q = query.trim().lowercase()
            fun matches(vararg haystacks: String?): Boolean {
                if (q.isEmpty()) return true
                return haystacks.any { it?.lowercase()?.contains(q) == true }
            }
            val counts = signalXDb.counts()
            val measurements =
                signalXDb.listObservations().filter { m ->
                    matches(
                        m.eci?.toString(),
                        m.eci?.let { SxFormat.formatEnbSector(it) },
                        m.nci?.toString(),
                        m.rat.name,
                        SxFormat.formatDateTime(m.timeMs),
                    )
                }
            val points =
                signalXDb.listPoints().filter { p ->
                    matches(SxFormat.formatCoordinates(p.lat, p.lon), SxFormat.formatDateTime(p.time), p.photoPath?.let { File(it).name })
                }
            val photos =
                signalXDb.listPhotos().filter { ph ->
                    matches(File(ph.path).name, SxFormat.formatCoordinates(ph.lat, ph.lon), SxFormat.formatDateTime(ph.time))
                }
            val cells =
                signalXDb.listObservedCells().filter { c ->
                    matches(c.eci.toString(), SxFormat.formatEnbSector(c.eci), c.band, SxFormat.formatDateTime(c.time ?: 0L))
                }
            DataSnapshot(measurements, points, photos, cells, counts)
        }

    suspend fun loadObservationDetail(id: Long): ObservationDetail? =
        withContext(Dispatchers.IO) {
            runCatching { signalXDb.observationDetail(id) }.getOrNull()
        }

    fun deleteObservation(id: Long) =
        viewModelScope.launch {
            withContext(Dispatchers.IO) { runCatching { signalXDb.deleteObservation(id) } }
            refreshSavedState()
            _events.emit(app.getString(R.string.data_deleted))
        }

    fun deletePoint(id: Long) =
        viewModelScope.launch {
            withContext(Dispatchers.IO) { runCatching { signalXDb.deletePoint(id) } }
            refreshSavedState()
            _events.emit(app.getString(R.string.data_deleted))
        }

    /** Deletes the photo row and its file. Points that referenced the file keep the path text; Datos shows "photo file missing" for them. */
    fun deletePhoto(id: Long) =
        viewModelScope.launch {
            val path = withContext(Dispatchers.IO) { runCatching { signalXDb.deletePhoto(id) }.getOrNull() }
            path?.let { runCatching { File(it).delete() } }
            refreshSavedState()
            _events.emit(app.getString(R.string.data_deleted))
        }

    fun deleteObservedCell(eci: Long) =
        viewModelScope.launch {
            withContext(Dispatchers.IO) { runCatching { signalXDb.deleteObservedCell(eci) } }
            refreshAntennas()
            _events.emit(app.getString(R.string.data_deleted))
        }

    /**
     * Deletes ALL SignalX field records (observations, cells, points,
     * photos, manual positions) and the photo files with them. Crash
     * logs and maps survive by design; BunnyLock's prefs are untouched.
     */
    fun deleteAllFieldData() =
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                runCatching { signalXDb.deleteAllFieldData() }
                runCatching { photosDir().listFiles()?.forEach { it.delete() } }
            }
            refreshSavedState()
            refreshAntennas()
            _events.emit(app.getString(R.string.data_delete_all_done))
        }

    /** Writes the full JSON export to [uri] (Storage Access Framework — no storage permission). Exporting never deletes anything. */
    fun exportTo(uri: Uri) =
        viewModelScope.launch {
            val result =
                withContext(Dispatchers.IO) {
                    runCatching {
                        val counts = signalXDb.counts()
                        val text = FieldDataSync.export(signalXDb)
                        app.contentResolver.openOutputStream(uri, "wt")?.use { it.write(text.toByteArray()) }
                            ?: return@runCatching null
                        counts
                    }.getOrNull()
                }
            if (result == null) {
                _events.emit(app.getString(R.string.data_export_failed))
            } else {
                _events.emit(app.getString(R.string.data_exported, result.observations, result.points, result.photos))
            }
        }

    /** Merges a SignalX JSON export from [uri] into the base (FieldDataSync rules: merge, never duplicate, never reset). */
    fun importFrom(uri: Uri) =
        viewModelScope.launch {
            val result =
                withContext(Dispatchers.IO) {
                    runCatching {
                        val text = app.contentResolver.openInputStream(uri)?.bufferedReader()?.readText() ?: return@runCatching null
                        FieldDataSync.import(signalXDb, text)
                    }.getOrNull()
                }
            if (result == null) {
                _events.emit(app.getString(R.string.data_import_failed))
                return@launch
            }
            refreshSavedState()
            refreshAntennas()
            _events.emit(
                app.getString(
                    R.string.data_imported,
                    result.observations, result.cells, result.points, result.towerPositions, result.photos,
                ),
            )
            if (result.skippedPhotos > 0) {
                _events.emit(app.getString(R.string.data_imported_skipped, result.skippedPhotos))
            }
        }

    // ---- Fase 6: diagnostics ----

    suspend fun loadDiagnosticsData(): DiagnosticsData =
        withContext(Dispatchers.IO) {
            DiagnosticsData(signalXDb.counts(), CrashLog.latest(app)?.name)
        }

    fun deleteCrashLog() =
        viewModelScope.launch {
            val deleted = withContext(Dispatchers.IO) { CrashLog.deleteLatest(app) }
            if (deleted) _events.emit(app.getString(R.string.diag_crash_deleted))
        }

    /**
     * The full diagnostic text (SignalX buildDiagnosticText shape, plus
     * the modem section from BunnyLock): app, device, database, map,
     * settings, last reading, antennas, last crash, then the modem
     * detail. Shared with ACTION_SEND from the Diagnóstico screen.
     */
    suspend fun buildDiagnosticsText(): String =
        withContext(Dispatchers.IO) {
            val counts = signalXDb.counts()
            val modemState = modem.state.value
            val map = mapManager.state.value
            val stamp = SxFormat.formatDateTime(System.currentTimeMillis())
            val reading = modemState.servingReading()
            buildString {
                appendLine("bunnylock ${appVersion() ?: "?"}")
                appendLine("Phone: ${Build.MANUFACTURER} ${Build.MODEL}")
                appendLine("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
                appendLine("Date: $stamp")
                appendLine()
                appendLine("DATABASE")
                appendLine("Measurements: ${counts.observations}")
                appendLine("Neighbor cells: ${counts.neighbors}")
                appendLine("Observed cells: ${counts.cells}")
                appendLine("Points: ${counts.points}")
                appendLine("Photos: ${counts.photos}")
                appendLine("Manual positions: ${counts.towerPositions}")
                appendLine()
                appendLine("MAP")
                appendLine("Active map: ${map.activeMapName ?: "none"}")
                appendLine("Source: ${map.source}")
                appendLine()
                appendLine("SETTINGS")
                appendLine("Auto collection: ${if (ui.autoCollect) "on" else "off"}")
                appendLine("Backend: ${modemState.backend?.label?.let { app.getString(it) } ?: "—"}")
                appendLine("Target: ${ui.antennaTarget?.let { "eNB ${it.enb}" } ?: "none"}")
                appendLine()
                appendLine("LAST READING")
                if (reading == null) {
                    appendLine("No reading yet")
                } else {
                    val identity =
                        when {
                            reading.cell?.eci != null -> SxFormat.formatEnbSector(reading.cell.eci)
                            reading.cell?.nci != null -> SxFormat.formatNci(reading.cell.nci)
                            else -> reading.carrier.bandLabel
                        }
                    appendLine("$identity · ${SxFormat.formatDbm(reading.signal?.rsrp)}")
                }
                appendLine()
                appendLine("ANTENNAS")
                appendLine("On the map: ${ui.antennas.count { it.hasPosition }}")
                appendLine("In the list: ${ui.antennas.size}")
                appendLine()
                appendLine("LAST CRASH")
                appendLine(CrashLog.latest(app)?.name ?: "none")
                appendLine()
                append(modem.diagnostics("Modem and locks", listOf("Shizuku: ${ShizukuClient.describe(app)}")))
            }
        }

    private var shownLocales = app.resources.configuration.locales

    /** Coming back from the Shizuku app after allowing bunnylock there shouldn't need a tap on "Try again". */
    fun resumed() {
        if (modem.state.value.root is RootState.Denied && ShizukuClient.granted()) modem.connect()
        val locales = app.resources.configuration.locales
        if (locales == shownLocales) return
        shownLocales = locales
        if (modem.state.value.root == RootState.Granted) refresh()
    }

    private fun shizukuLost() {
        modem.helperLost(Text(R.string.system_shizuku_lost))
    }

    fun refresh() = modem.refresh()

    suspend fun refreshConnection() = modem.refreshConnection()

    fun unlockFromNotification() {
        if (modem.state.value.root == RootState.Granted && modem.helperRunning) unlockEverything() else pendingUnlock = true
    }

    private fun unlockIfPending() {
        if (!pendingUnlock) return
        pendingUnlock = false
        unlockEverything()
    }

    private fun refreshed(modemState: ModemState) {
        val supported = modemState.groups.associate { it.group to it.supported.toSet() }
        updateState {
            val selections = bandSelections.filterKeys { it in supported }.mapValues { (group, bands) -> bands intersect supported.getValue(group) }
            copy(bandSelections = selections)
        }
        updateNotification(modemState)
    }

    private fun updateNotification(modemState: ModemState) {
        val lines =
            buildList {
                modemState.groups.filter { it.locked && it.stray === null }.forEach { groupState ->
                    val bands =
                        groupState.current
                            .orEmpty()
                            .sorted()
                            .joinToString(" ") { groupState.group.bandLabel(it) }
                    add(app.getString(R.string.system_notify_line_bands, groupState.group.label, bands))
                }
                modemState.plmnLock?.let { add(app.getString(R.string.system_notify_line_network, it)) }
                if (modemState.strayChannel === null) {
                    modemState.lteLock?.let { add(app.getString(R.string.system_notify_line_lte_cell, it)) }
                    modemState.nrLock?.let { add(app.getString(R.string.system_notify_line_nr_cell, it)) }
                    modemState.legacyCellLock?.let { add(app.getString(R.string.system_notify_line_legacy_cell, it)) }
                }
            }
        if (lines.isEmpty()) {
            prefs.edit().remove("lockedSince").apply()
            LockNotifier.cancel(app)
            return
        }
        val lockedSince =
            prefs.getLong("lockedSince", 0L).takeIf { it > 0 }
                ?: System.currentTimeMillis().also { prefs.edit().putLong("lockedSince", it).apply() }
        LockNotifier.show(app, lines, lockedSince)
    }

    fun loadSims() = modem.loadSims()

    fun setSim(sim: Int) {
        discardPendingBands()
        modem.setSim(sim)
    }

    fun setBackend(backend: Backend) {
        discardPendingBands()
        modem.setBackend(backend)
    }

    fun setPersist(enabled: Boolean) = modem.setPersist(enabled)

    fun forgetNeedsChannels() = modem.forgetNeedsChannels()

    fun forgetKeptOffBands() = modem.forgetKeptOffBands()

    fun forgetIgnoredCellLock() = modem.forgetIgnoredCellLock()

    fun stopWaiting() = modem.stopWaiting()

    fun setExclusiveBandLock(enabled: Boolean) {
        prefs.edit().putBoolean(EXCLUSIVE_BAND_LOCK_KEY, enabled).apply()
        updateState { copy(exclusiveBandLock = enabled) }
    }

    fun setAutoCollect(enabled: Boolean) {
        prefs.edit().putBoolean(AUTO_COLLECT_KEY, enabled).apply()
        updateState { copy(autoCollect = enabled) }
        // Turning it on saves the current reading right away (SignalX 1.0.9 behavior).
        if (enabled) viewModelScope.launch { maybeAutoSave(latest, force = true) }
    }

    /** Saves the current reading now (Explorar / Inicio). An explicit save always saves; only a missing serving cell stops it. */
    fun saveMeasurement() =
        viewModelScope.launch {
            val snapshot = modem.state.value.measurementSnapshot(MeasurementSource.MANUAL, currentMeasurementLocation())
            if (snapshot == null) {
                _events.emit(app.getString(R.string.measure_saved_no_cell))
                return@launch
            }
            val id =
                withContext(Dispatchers.IO) {
                    runCatching { signalXDb.insertMeasurement(snapshot) }.getOrNull() ?: -1L
                }
            if (id < 0) {
                _events.emit(app.getString(R.string.measure_saved_failed))
                return@launch
            }
            refreshSavedState()
            val identity = snapshot.serving.displayIdentity()
            val rsrp = SxFormat.formatDbm(snapshot.serving.rsrp)
            _events.emit(app.getString(R.string.measure_saved, identity, rsrp))
        }

    /** Watches the modem state and auto-saves: at most every 30 s, or sooner when the serving cell changes. */
    private fun startAutoCollection() =
        viewModelScope.launch {
            state.collect { uiState -> maybeAutoSave(uiState) }
        }

    /**
     * Auto-save gate (SignalX 1.0.9 maybeSaveServing): only when the
     * setting is on, the modem is connected, the reading is fresh and
     * there is a serving cell; then on a cell change or 30 s since the
     * last auto-save. A stale reading is never recorded as new.
     */
    private suspend fun maybeAutoSave(
        uiState: UiState,
        force: Boolean = false,
    ) {
        if (!uiState.autoCollect) return
        if (uiState.modem.root != RootState.Granted) return
        val readingAt = uiState.modem.readingAtMs ?: return
        if (System.currentTimeMillis() - readingAt > AUTO_SAVE_MAX_READING_AGE_MS) return
        val snapshot = uiState.modem.measurementSnapshot(MeasurementSource.AUTO, currentMeasurementLocation()) ?: return
        val key = snapshot.serving.let { "${it.rat}:${it.eci ?: it.nci ?: "-"}" }
        val now = SystemClock.elapsedRealtime()
        if (!force && key == lastAutoSavedKey && now - lastAutoSaveMs < AUTO_SAVE_INTERVAL_MS) return
        val id = withContext(Dispatchers.IO) { runCatching { signalXDb.insertMeasurement(snapshot) }.getOrNull() ?: -1L }
        if (id < 0) return
        lastAutoSavedKey = key
        lastAutoSaveMs = now
        refreshSavedState()
    }

    private fun refreshSavedState() =
        viewModelScope.launch {
            val (last, count, points, cells) =
                withContext(Dispatchers.IO) {
                    Quad(
                        signalXDb.lastMeasurement(),
                        signalXDb.measurementCount(),
                        signalXDb.listPoints(),
                        signalXDb.listObservedCellPositions(),
                    )
                }
            updateState { copy(lastMeasurement = last, measurementCount = count, mapPoints = points, observedCells = cells) }
            // A save changes the observed cells, so the antenna list changes with it.
            refreshAntennas()
        }

    private data class Quad<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)

    // ---- Antennas (Fase 4) ----

    /** Rebuilds the antenna list from the seed + the database. Distances use the live GPS fix (Fase 5); without a fix they stay unavailable. */
    fun refreshAntennas() =
        viewModelScope.launch {
            val userLocation = ui.userPosition?.toMeasurementLocation()
            val rows =
                withContext(Dispatchers.IO) {
                    val seed = seedAntennas ?: loadSeedAntennas(app).also { seedAntennas = it }
                    runCatching { buildAntennaRows(seed, signalXDb, app.getString(R.string.antenas_observed_zone), userLocation) }.getOrElse { emptyList() }
                }
            updateState { copy(antennas = rows) }
        }

    fun setAntennasQuery(query: String) {
        prefs.edit().putString(ANTENNAS_QUERY_KEY, query).apply()
        updateState { copy(antennasQuery = query) }
    }

    fun setAntennasFilter(filter: AntennaFilter) {
        prefs.edit().putString(ANTENNAS_FILTER_KEY, filter.name).apply()
        updateState { copy(antennasFilter = filter) }
    }

    /**
     * Picks an antenna as the orientation target (superprompt §10.2): the
     * target lives in shared state and survives restarts, so Fase 5's map
     * (Herramientas > Orientación) selects it, centers on it and shows
     * its info. Nothing navigates here — the caller navigates (tapping a
     * card or "View on map" in Antenas opens the map).
     */
    fun selectAntennaTarget(row: AntennaRow) {
        val lat = row.lat ?: return
        val lon = row.lon ?: return
        val target = AntennaTarget(enb = row.enb, lat = lat, lon = lon, name = "eNB ${row.enb}")
        prefs
            .edit()
            .putInt(TARGET_ENB_KEY, target.enb)
            .putLong(TARGET_LAT_KEY, target.lat.toRawBits())
            .putLong(TARGET_LON_KEY, target.lon.toRawBits())
            .apply()
        updateState { copy(antennaTarget = target) }
        _events.tryEmit(app.getString(R.string.antenas_target_set, target.enb))
    }

    /**
     * Clears the orientation target. No UI calls it yet: Fase 5's map
     * (Herramientas > Orientación) offers "clear target" on its target
     * card. The target chip in Antennas deliberately does NOT clear on
     * tap — it sits where fingers land when selecting, and clearing
     * there would be an accident, not a choice.
     */
    fun clearAntennaTarget() {
        prefs.edit().remove(TARGET_ENB_KEY).remove(TARGET_LAT_KEY).remove(TARGET_LON_KEY).apply()
        updateState { copy(antennaTarget = null) }
    }

    private fun loadAntennaTarget(): AntennaTarget? {
        val enb = prefs.getInt(TARGET_ENB_KEY, -1)
        if (enb < 0) return null
        if (!prefs.contains(TARGET_LAT_KEY) || !prefs.contains(TARGET_LON_KEY)) return null
        return AntennaTarget(
            enb = enb,
            lat = Double.fromBits(prefs.getLong(TARGET_LAT_KEY, 0L)),
            lon = Double.fromBits(prefs.getLong(TARGET_LON_KEY, 0L)),
            name = "eNB $enb",
        )
    }

    /** Saves (or moves) a manual antenna position, then rebuilds the list. */
    fun saveTowerPosition(
        enb: Int,
        lat: Double,
        lon: Double,
    ) = viewModelScope.launch {
        withContext(Dispatchers.IO) { runCatching { signalXDb.upsertTowerPosition(enb, lat, lon) } }
        refreshAntennas()
        _events.emit(app.getString(R.string.antenas_placed, enb))
    }

    /** Removes a manual position; the antenna falls back to its estimated (or observed) position. */
    fun removeTowerPosition(enb: Int) =
        viewModelScope.launch {
            withContext(Dispatchers.IO) { runCatching { signalXDb.deleteTowerPosition(enb) } }
            refreshAntennas()
            _events.emit(app.getString(R.string.antenas_pos_removed, enb))
        }

    fun setDynamicColor(enabled: Boolean) {
        prefs.edit().putBoolean("dynamicColor", enabled).apply()
        updateState { copy(dynamicColor = enabled) }
    }

    fun setThemeMode(themeMode: ThemeMode) {
        prefs.edit().putString("themeMode", themeMode.name).apply()
        updateState { copy(themeMode = themeMode) }
    }

    private fun loadThemeMode(): ThemeMode {
        val stored = prefs.getString("themeMode", null)
        return ThemeMode.entries.firstOrNull { it.name == stored } ?: ThemeMode.SYSTEM
    }

    fun setPureBlack(enabled: Boolean) {
        prefs.edit().putBoolean("pureBlack", enabled).apply()
        updateState { copy(pureBlack = enabled) }
    }

    fun setAtNode(node: String) = modem.setAtNode(node)

    private fun launchAction(
        refreshAfter: Boolean = true,
        block: suspend () -> String?,
    ) = viewModelScope.launch {
        if (!modem.helperRunning) {
            _events.emit(app.getString(R.string.system_helper_not_running))
            modem.reconnect()
            return@launch
        }
        updateState { copy(busy = true) }
        try {
            block()?.let { _events.emit(it) }
            if (refreshAfter) modem.refreshNow()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _events.emit(app.reason(e))
        } finally {
            updateState { copy(busy = false) }
        }
    }

    private fun groupState(group: Group): BandGroupState? = modem.state.value.groups.firstOrNull { it.group == group }

    private fun updateSelection(
        group: Group,
        pick: UiState.(groupState: BandGroupState, selected: Set<Int>) -> Set<Int>,
    ) {
        val groupState = groupState(group) ?: return
        val modemState = modem.state.value
        updateState {
            val current = copy(modem = modemState)
            val picked = current.pick(groupState, current.selectedBands(groupState))
            val untouched = picked == current.startingBands(groupState)
            copy(bandSelections = if (untouched) bandSelections - group else bandSelections + (group to picked))
        }
    }

    fun setShowAllBands(
        group: Group,
        shown: Boolean,
    ) = updateState { copy(showAllBands = if (shown) showAllBands + group else showAllBands - group) }

    fun toggleBand(
        group: Group,
        band: Int,
    ) = updateSelection(group) { _, selected ->
        if (band in selected) selected - band else selected + band
    }

    fun toggleFavorite(
        group: Group,
        band: Int,
    ) {
        val favorites = ui.favoritesOf(group).let { if (band in it) it - band else it + band }
        prefs.edit().putString(favoritesPrefKey(group.favoritesKey), favorites.sorted().joinToString(",")).apply()
        updateState { copy(favoriteBands = favoriteBands + (group.favoritesKey to favorites)) }
    }

    private fun loadFavoriteBands(): Map<String, Set<Int>> =
        Group.entries.map { it.favoritesKey }.distinct().associateWith { key ->
            prefs.getString(favoritesPrefKey(key), null).orEmpty().split(',').mapNotNull { it.toIntOrNull() }.toSet()
        }

    private fun favoritesPrefKey(key: String) = "favoriteBands.$key"

    fun selectAll(
        group: Group,
        all: Boolean,
    ) = updateSelection(group) { groupState, _ ->
        if (all) visibleBands(groupState).toSet() else emptySet()
    }

    fun unlockGroup(group: Group) = launchAction(refreshAfter = false) { sendGroup(group, unlock = true) }

    fun applyPendingGroups(including: Group? = null) =
        launchAction(refreshAfter = false) {
            val current = latest
            val pendingAtStart = current.modem.groups.filter(current::isPending).map { it.group }.toSet()
            val sendable = current.modem.groups.filter { it.group == including || it.group in pendingAtStart }
            // In exclusive mode each lock turns off the networks without one, so every card with bands picked stays on for the whole press.
            val keepOn = sendable.filter { current.selectedBands(it).isNotEmpty() }.map { it.group }
            val narrowing = sendable.filter(current::locksToFewerBands)
            val outcomes = mutableListOf<String>()
            val togetherOutcome = if (modem.locksBandsTogether && narrowing.size > 1) lockTogether(narrowing, keepOn) else null
            togetherOutcome?.let(outcomes::add)
            for (groupState in if (togetherOutcome === null) sendable else sendable - narrowing.toSet()) {
                val group = groupState.group
                // An earlier lock in this press may already have done what this card asks, such as turning its network off.
                if (group in pendingAtStart && !stillPending(group)) {
                    forgetSent(mapOf(group to latest.bandSelections[group]))
                    continue
                }
                val outcome =
                    try {
                        sendGroup(group, keepOn = keepOn)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // The next card works out the network mode from the state, so it has to see what this failure left.
                        modem.refreshNow()
                        app.reason(e)
                    }
                outcome?.let(outcomes::add)
            }
            outcomes.distinct().joinToString("\n").ifEmpty { null }
        }

    private suspend fun lockTogether(
        groupStates: List<BandGroupState>,
        keepOn: List<Group>,
    ): String? =
        try {
            sendGroups(groupStates, keepOn)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            modem.log("Locking ${groupStates.joinToString(" and ") { it.group.label }} together didn't work, so each network is locked on its own.")
            modem.refreshNow()
            null
        }

    private fun stillPending(group: Group): Boolean = groupState(group)?.let(latest::isPending) == true

    fun discardPendingBands() = updateState { copy(bandSelections = emptyMap()) }

    private suspend fun sendGroup(
        group: Group,
        unlock: Boolean = false,
        keepOn: List<Group> = emptyList(),
    ): String? {
        val groupState = groupState(group) ?: return null
        val current = latest
        val sent = current.bandSelections[group]
        val selected = current.selectedBands(groupState)
        val exclusive = current.exclusiveBandLock
        val outcome =
            when {
                unlock -> modem.unlockBands(group, exclusive)
                selected.isEmpty() && current.canTurnOff(group) -> modem.turnOff(group)
                else -> modem.lockBands(group, selected, exclusive, keepOn)
            }
        modem.refreshNow()
        forgetSent(mapOf(group to sent))
        return outcome
    }

    private suspend fun sendGroups(
        groupStates: List<BandGroupState>,
        keepOn: List<Group>,
    ): String {
        val current = latest
        val sent = groupStates.associate { it.group to current.bandSelections[it.group] }
        val locks = groupStates.associate { it.group to current.selectedBands(it) }
        val outcome = modem.lockBands(locks, current.exclusiveBandLock, keepOn)
        modem.refreshNow()
        forgetSent(sent)
        return outcome
    }

    // Picks made while the lock ran are newer than what was sent, so they stay.
    private fun forgetSent(sent: Map<Group, Set<Int>?>) =
        updateState { copy(bandSelections = bandSelections.filterNot { (group, bands) -> sent[group] == bands }) }

    fun setNetMode(mode: NetMode) = launchAction { modem.setNetMode(mode) }

    fun setNrMode(mode: NrMode) = launchAction { modem.setNrMode(mode) }

    fun setNrDc(enabled: Boolean) = launchAction { modem.setNrDc(enabled) }

    fun setIms(
        feature: ImsFeature,
        enabled: Boolean,
    ) = launchAction(refreshAfter = false) { modem.setIms(feature, enabled) }

    fun setCarrierAggregation(enabled: Boolean) = launchAction(refreshAfter = false) { modem.setCarrierAggregation(enabled) }

    fun refreshIms() = viewModelScope.launch { modem.refreshIms() }

    fun updateForm(transform: CellForm.() -> CellForm) = updateState { copy(form = form.transform()) }

    fun fillFrom(cell: Cell) =
        updateForm {
            when (cell.rat) {
                Rat.LTE -> {
                    copy(
                        lteEarfcn = cell.arfcn.toString(),
                        ltePcis = cell.pci.toString(),
                        rat = if (rat == LockRat.NSA) rat else LockRat.LTE,
                    )
                }

                Rat.NR -> {
                    val band = cell.band ?: Bands.nrBandsOf(cell.arfcn.toInt()).firstOrNull()
                    copy(
                        nrArfcn = cell.arfcn.toString(),
                        nrPcis = cell.pci.takeIf { it >= 0 }?.toString() ?: nrPcis,
                        nrBand = band,
                        nrScs = band?.let { Bands.nrDefaultScs(it) } ?: nrScs,
                        nrScsPicked = nrScsPicked && band === null,
                        rat = if (rat == LockRat.NSA) rat else LockRat.NR,
                    )
                }

                else -> {
                    this
                }
            }
        }

    private fun parseInts(text: String) = text.split(',', ' ', ';').mapNotNull { it.trim().toIntOrNull() }

    fun lockCells() =
        launchAction {
            val form = ui.form
            val request =
                CellLockRequest(
                    rat = latest.lockRat,
                    earfcn = form.lteEarfcn.toLongOrNull(),
                    ltePcis = parseInts(form.ltePcis),
                    nrArfcn = form.nrArfcn.toLongOrNull(),
                    nrPcis = parseInts(form.nrPcis),
                    nrBand = form.nrBand ?: form.nrCandidates.firstOrNull(),
                    nrScs = form.nrScs,
                )
            modem.lockCells(request)
        }

    fun unlockCells() = launchAction { modem.unlockCells() }

    fun setPlmnForm(
        mcc: String? = null,
        mnc: String? = null,
    ) = updateState {
        copy(plmnMcc = (mcc ?: plmnMcc).filter(Char::isDigit).take(3), plmnMnc = (mnc ?: plmnMnc).filter(Char::isDigit).take(3))
    }

    fun pickPlmn(numeric: String) = updateState { copy(plmnMcc = numeric.take(3), plmnMnc = numeric.drop(3)) }

    fun lockPlmn() = launchAction { modem.lockPlmn(ui.plmnMcc, ui.plmnMnc) }

    fun unlockPlmn() = launchAction { modem.unlockPlmn() }

    fun unlockEverything() =
        launchAction {
            modem.unlockEverything(ui.exclusiveBandLock).also { discardPendingBands() }
        }

    fun radioCycle() = launchAction { modem.radioCycle() }

    fun reattach() = launchAction { modem.reattach() }

    fun relock() = launchAction { modem.relock() }

    fun rebootModem() = launchAction(refreshAfter = false) { modem.rebootModem() }

    fun samsungMenu() = launchAction(refreshAfter = false) { modem.samsungMenu() }

    fun sendAt(command: String) =
        launchAction(refreshAfter = false) {
            val reply = modem.sendAt(command)
            updateState { copy(toolOutput = "${reply.node}> $command\n${reply.output}\n\n$toolOutput".take(MAX_TOOL_OUTPUT_CHARS)) }
            modem.log("The modem replied through ${reply.node}. See the output below.")
            app.getString(R.string.system_at_replied)
        }

    fun sendQmi(
        service: Int,
        msg: Int,
        payload: String,
    ) = launchAction(refreshAfter = false) {
        val reply = modem.sendQmi(service, msg, payload)
        val entry = "> svc $service msg 0x%04X %s\n%s".format(msg, payload, reply.output)
        updateState { copy(toolOutput = "$entry\n\n$toolOutput".take(MAX_TOOL_OUTPUT_CHARS)) }
        app.getString(R.string.system_qmi_replied, msg, reply.error?.let { app.string(it) }.orEmpty())
    }

    fun clearOutput() = updateState { copy(toolOutput = "") }

    private fun diagnostics(): String {
        val title = listOfNotNull("bunnylock", appVersion(), "diagnostics").joinToString(" ")
        return modem.diagnostics(title, listOf("Shizuku: ${ShizukuClient.describe(app)}"))
    }

    fun copyDiagnostics() {
        val clipboard = app.getSystemService(ClipboardManager::class.java)
        // Kotlin sees primaryClip as read-only because its getter is nullable and its setter isn't.
        @Suppress("UsePropertyAccessSyntax")
        clipboard.setPrimaryClip(ClipData.newPlainText(app.getString(R.string.system_clip_label_diagnostics), diagnostics()))
        // Android 13 and later confirm a copy on their own.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) _events.tryEmit(app.getString(R.string.system_diagnostics_copied))
    }

    fun logFileName(): String = "bunnylock-${SimpleDateFormat("yyyy-MM-dd-HHmmss", Locale.US).format(Date())}.log"

    fun saveLog(uri: Uri) =
        viewModelScope.launch {
            val saved =
                withContext(Dispatchers.IO) {
                    val text = diagnostics()
                    runCatching { checkNotNull(app.contentResolver.openOutputStream(uri, "wt")).use { it.write(text.toByteArray()) } }.isSuccess
                }
            _events.emit(app.getString(if (saved) R.string.system_log_saved else R.string.system_log_not_saved))
        }
}
