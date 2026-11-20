// Orientation — map as a field tool (superprompt §7; Fase 5).
//
// Herramientas > Orientación holds two sections, Map and Compass, switched
// at the top (the same ConnectedChoice Antenas uses). The map is the field
// tool: offline .map / online tiles / grid, the serving cell and the
// target as compact cards (superprompt §7.2/§7.3), blue pointer lines
// with a legend, the user's own position, antenna pins, uncertainty
// circles, tap-to-place, full screen and rotation.
//
// One map, one management experience: Manage maps (one sheet) is the only
// place that imports, downloads, deletes or switches maps, and its strip
// is the only progress display (superprompt §10.4).
//
// The map is a Mapsforge MapView (classic View) inside AndroidView; the
// overlay is the ported SignalX MapOverlayView. Nothing is drawn by hand
// except the overlay the app already had: pins come from tower_pin.xml.

@file:Suppress("FunctionName")

package dev.ririxi.bunnylock.ui

import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.ririxi.bunnylock.R
import dev.ririxi.bunnylock.signalx.AntennaRow
import dev.ririxi.bunnylock.signalx.AntennaSource
import dev.ririxi.bunnylock.signalx.AntennaTarget
import dev.ririxi.bunnylock.signalx.ImportOutcome
import dev.ririxi.bunnylock.signalx.MapCatalog
import dev.ririxi.bunnylock.signalx.MapDownload
import dev.ririxi.bunnylock.signalx.MapManager
import dev.ririxi.bunnylock.signalx.MapOverlayView
import dev.ririxi.bunnylock.signalx.MapSource
import dev.ririxi.bunnylock.signalx.ServingReading
import dev.ririxi.bunnylock.signalx.TransferState
import dev.ririxi.bunnylock.signalx.UserPosition
import dev.ririxi.bunnylock.signalx.bearingDeg
import dev.ririxi.bunnylock.signalx.distanceMeters
import dev.ririxi.bunnylock.signalx.servingReading
import dev.ririxi.bunnylock.ui.design.SxBottomSheet
import dev.ririxi.bunnylock.ui.design.SxCard
import dev.ririxi.bunnylock.ui.design.SxDataRow
import dev.ririxi.bunnylock.ui.design.SxDialog
import dev.ririxi.bunnylock.ui.design.SxFormat
import dev.ririxi.bunnylock.ui.design.SxNotice
import dev.ririxi.bunnylock.ui.design.SxPrimaryButton
import dev.ririxi.bunnylock.ui.design.SxProgressBar
import dev.ririxi.bunnylock.ui.design.SxSecondaryButton
import dev.ririxi.bunnylock.ui.design.SxSignalPill
import dev.ririxi.bunnylock.ui.design.SxSignalThresholds
import dev.ririxi.bunnylock.ui.design.SxSpacing
import dev.ririxi.bunnylock.ui.design.SxStatusChip
import dev.ririxi.bunnylock.ui.design.SxTextButton
import dev.ririxi.bunnylock.ui.design.SxTone
import dev.ririxi.bunnylock.ui.design.mono
import dev.ririxi.bunnylock.ui.theme.LocalSignalScale
import kotlinx.coroutines.launch
import org.mapsforge.map.android.view.MapView
import kotlin.math.hypot

/** The two sections of Herramientas > Orientación. */
enum class OrientationSection(
    @StringRes val label: Int,
) {
    MAP(R.string.orient_section_map),
    COMPASS(R.string.orient_section_compass),
}

@Composable
fun OrientationScreen(
    state: UiState,
    vm: MainViewModel,
    contentPadding: PaddingValues,
    goToAntennas: () -> Unit,
    onOpenCamera: () -> Unit,
) {
    var section by rememberSaveable { mutableStateOf(OrientationSection.MAP) }
    // The install session survives switching between Map and Compass.
    val installSession = remember { InstallSession() }
    // Belt and braces: MainActivity already starts orientation on resume,
    // but opening this screen with a permission granted in Settings must
    // start it too. Never stopped here — MainActivity stops it on pause.
    DisposableEffect(Unit) {
        vm.startOrientation()
        onDispose { }
    }
    val layoutDirection = LocalLayoutDirection.current
    Column(Modifier.fillMaxSize()) {
        ConnectedChoice(
            options = OrientationSection.entries,
            selected = section,
            label = { stringResource(it.label) },
            onSelect = { section = it },
            modifier =
                Modifier.padding(
                    start = contentPadding.calculateStartPadding(layoutDirection) + SxSpacing.ScreenHorizontal,
                    end = contentPadding.calculateEndPadding(layoutDirection) + SxSpacing.ScreenHorizontal,
                    top = SxSpacing.ScreenTop,
                    bottom = SxSpacing.Group,
                ),
        )
        Box(Modifier.weight(1f)) {
            when (section) {
                OrientationSection.MAP -> MapScreen(state, vm, contentPadding, goToAntennas)
                OrientationSection.COMPASS ->
                    CompassScreen(
                        state, vm, contentPadding, installSession,
                        onOpenMap = { section = OrientationSection.MAP },
                        onGoToAntennas = goToAntennas,
                        // Fase 6: arm the point first — without a fix
                        // there is nothing to attach the photo to, and
                        // the camera does not open.
                        onSavePointWithPhoto = { if (vm.armPointPhoto()) onOpenCamera() },
                    )
            }
        }
    }
}

private class MapHolder {
    var mapView: MapView? = null
    var overlay: MapOverlayView? = null
}

@Composable
private fun MapScreen(
    state: UiState,
    vm: MainViewModel,
    contentPadding: PaddingValues,
    goToAntennas: () -> Unit,
) {
    val manager = vm.mapManager
    val mapState by manager.state.collectAsStateWithLifecycle()
    val heading by vm.heading.collectAsStateWithLifecycle()
    val density = LocalDensity.current.density
    val holder = remember { MapHolder() }
    var fullscreen by rememberSaveable { mutableStateOf(false) }
    var manageSheet by rememberSaveable { mutableStateOf(false) }
    var pendingPlace by remember { mutableStateOf<Pair<Double, Double>?>(null) }
    var centeredTargetEnb by remember { mutableStateOf<Int?>(null) }
    var centeredOnUser by remember { mutableStateOf(false) }
    // Shown when "my location" is tapped without a fix; cleared on the next fix.
    var noLocationNote by remember { mutableStateOf(false) }

    val target = state.antennaTarget
    val targetRow = target?.let { t -> state.antennas.firstOrNull { it.enb == t.enb } }
    val userPos = state.userPosition
    val reading = state.modem.servingReading()
    val servingEnb = reading?.cell?.eci?.let { SxFormat.enbOf(it) }
    val servingRow = servingEnb?.let { enb -> state.antennas.firstOrNull { it.enb == enb } }
    val placingEnb = state.placingEnb

    // Center on a new target (once per target). The target card shows its info.
    LaunchedEffect(target?.enb) {
        if (target != null && centeredTargetEnb != target.enb) {
            manager.centerOn(target.lat, target.lon, 16.toByte())
            centeredTargetEnb = target.enb
        } else if (target == null) {
            centeredTargetEnb = null
        }
    }
    // First GPS fix centers the map on the user — but only when there is
    // no target and the user has not been centered yet.
    LaunchedEffect(userPos) {
        val pos = userPos
        if (pos != null) {
            noLocationNote = false
            if (target == null && !centeredOnUser) {
                manager.centerOn(pos.latitude, pos.longitude)
                centeredOnUser = true
            }
        }
    }

    BackHandler(enabled = fullscreen) { fullscreen = false }

    Column(Modifier.fillMaxSize()) {
        if (!fullscreen) {
            Row(
                Modifier.fillMaxWidth().padding(
                    start = contentPadding.calculateStartPadding(LocalLayoutDirection.current) + SxSpacing.ScreenHorizontal,
                    end = contentPadding.calculateEndPadding(LocalLayoutDirection.current) + SxSpacing.ScreenHorizontal,
                    top = SxSpacing.ScreenTop,
                    bottom = SxSpacing.Group,
                ),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SxStatusChip(
                    when {
                        mapState.grid -> stringResource(R.string.orient_map_source_grid)
                        mapState.source == MapSource.FILE -> stringResource(R.string.orient_map_source_file)
                        else -> stringResource(R.string.orient_map_source_online)
                    },
                    if (mapState.grid) SxTone.Warning else SxTone.Info,
                )
                Spacer(Modifier.weight(1f))
                SxSecondaryButton(
                    text = stringResource(R.string.orient_map_manage),
                    onClick = { manageSheet = true },
                )
                SxSecondaryButton(
                    text = stringResource(R.string.orient_map_fullscreen),
                    onClick = { fullscreen = true },
                )
            }
            if (mapState.source == MapSource.ONLINE && !mapState.grid) {
                Text(
                    if (mapState.onlineUsable) {
                        stringResource(R.string.orient_map_source_online) + " · " + stringResource(R.string.orient_map_cached_tiles, mapState.cachedTilesInView)
                    } else {
                        stringResource(R.string.orient_map_online_no_cache)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier =
                        Modifier.padding(
                            start = contentPadding.calculateStartPadding(LocalLayoutDirection.current) + SxSpacing.ScreenHorizontal,
                            end = contentPadding.calculateEndPadding(LocalLayoutDirection.current) + SxSpacing.ScreenHorizontal,
                            bottom = SxSpacing.Group,
                        ),
                )
            }
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            AndroidView(
                factory = { context ->
                    manager.ensureGraphics()
                    val mapView = MapView(context)
                    val overlay = MapOverlayView(context)
                    FrameLayout(context).apply {
                        addView(mapView, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
                        addView(overlay, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
                        holder.mapView = mapView
                        holder.overlay = overlay
                    }.also {
                        // The factory runs once: pass the target now, so opening the map
                        // from Antennas centers on it even though bind happens here.
                        // (Fase 6 fix: this read state.target, which does not exist
                        // in UiState — the target is antennaTarget.)
                        val t = state.antennaTarget
                        manager.bind(
                            mapView,
                            initialCenter = t?.let { org.mapsforge.core.model.LatLong(it.lat, it.lon) },
                        )
                    }
                },
                update = { _ ->
                    val overlay = holder.overlay ?: return@AndroidView
                    val view = holder.mapView ?: return@AndroidView
                    manager.onMapTap = { lat, lon, x, y ->
                        if (placingEnb != null) {
                            pendingPlace = lat to lon
                        } else {
                            // Tapping a tower pin picks it as the target (72 dp touch area).
                            val tapped =
                                state.antennas.mapNotNull { row ->
                                    val rlat = row.lat ?: return@mapNotNull null
                                    val rlon = row.lon ?: return@mapNotNull null
                                    val p = manager.project(rlat, rlon) ?: return@mapNotNull null
                                    val d = hypot((p.x - x).toDouble(), (p.y - y).toDouble())
                                    if (d < 72f * density) row to d else null
                                }.minByOrNull { it.second }?.first
                            if (tapped != null) vm.selectAntennaTarget(tapped)
                        }
                    }
                    overlay.towers = state.antennas.filter { it.hasPosition }
                    overlay.userPos =
                        userPos?.let { org.mapsforge.core.model.LatLong(it.latitude, it.longitude) }
                    overlay.targetPos =
                        target?.let { org.mapsforge.core.model.LatLong(it.lat, it.lon) }
                    overlay.headingDeg = heading?.degrees
                    overlay.placingPos = pendingPlace?.let { org.mapsforge.core.model.LatLong(it.first, it.second) }
                    overlay.connectedEnb = servingEnb
                    overlay.mapRotationDeg = mapState.rotationDeg
                    overlay.zoomLevel = mapState.zoomLevel.toByte()
                    overlay.showGrid = mapState.grid
                    overlay.project = { ll -> manager.project(ll.latitude, ll.longitude) }
                    overlay.savedPoints = state.mapPoints.map { it.lat to it.lon }
                    overlay.observedCells = state.observedCells
                    overlay.invalidate()
                    view.invalidate()
                },
                onRelease = { _ ->
                    manager.unbind()
                    try {
                        holder.mapView?.onDestroy()
                    } catch (_: Exception) {
                    }
                    holder.mapView = null
                    holder.overlay = null
                },
                modifier = Modifier.fillMaxSize(),
            )

            // Zoom + north, floating on the map (text buttons: no icon set to guess).
            Column(
                Modifier.align(Alignment.CenterEnd).padding(end = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                MapControlButton("+", stringResource(R.string.orient_map_zoom_in)) { manager.zoomIn() }
                MapControlButton("−", stringResource(R.string.orient_map_zoom_out)) { manager.zoomOut() }
                MapControlButton("N", stringResource(R.string.orient_map_north)) { manager.resetNorth() }
                MapControlButton("◎", stringResource(R.string.orient_map_my_location)) {
                    val pos = userPos
                    if (pos != null) manager.centerOn(pos.latitude, pos.longitude) else noLocationNote = true
                }
            }

            if (noLocationNote) {
                SxNotice(
                    text = stringResource(R.string.orient_map_no_location),
                    tone = SxTone.Warning,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(12.dp),
                )
            }

            if (mapState.grid) {
                SxNotice(
                    text = stringResource(R.string.orient_map_grid),
                    tone = SxTone.Warning,
                    modifier = Modifier.align(Alignment.TopCenter).padding(12.dp),
                    actionLabel =
                        if (mapState.gridReason == "online_failed") {
                            stringResource(R.string.orient_map_try_online)
                        } else {
                            stringResource(R.string.orient_map_manage)
                        },
                    onAction = {
                        if (mapState.gridReason == "online_failed") manager.setupOnline() else manageSheet = true
                    },
                )
            }

            if (placingEnb != null) {
                SxNotice(
                    text = stringResource(R.string.orient_map_placing, placingEnb),
                    tone = SxTone.Info,
                    modifier = Modifier.align(Alignment.TopCenter).padding(12.dp),
                    actionLabel = stringResource(R.string.orient_map_placing_cancel),
                    onAction = {
                        pendingPlace = null
                        vm.clearPlacingEnb()
                    },
                )
            }

            if (fullscreen) {
                SxSecondaryButton(
                    text = stringResource(R.string.orient_map_exit_fullscreen),
                    onClick = { fullscreen = false },
                    modifier = Modifier.align(Alignment.TopStart).padding(12.dp),
                )
            }

            // Legend for the two blue pointer lines (superprompt §7.1).
            if (!fullscreen && target != null && userPos != null) {
                Column(
                    Modifier.align(Alignment.BottomStart).padding(12.dp)
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.85f), RoundedCornerShape(8.dp))
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                ) {
                    LegendLine(color = MaterialTheme.colorScheme.secondary, dashed = false, text = stringResource(R.string.orient_map_legend_target))
                    LegendLine(color = MaterialTheme.colorScheme.secondary, dashed = true, text = stringResource(R.string.orient_map_legend_heading))
                }
            }

            // OSM attribution (OnlineMap.kt promises visible attribution;
            // the string existed since Fase 5 but nothing showed it —
            // Fase 7 wires it). Grid shows no tiles, so it hides there.
            if (!mapState.grid) {
                Text(
                    stringResource(R.string.orient_map_attribution),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier =
                        Modifier.align(Alignment.BottomEnd).padding(12.dp)
                            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.85f), RoundedCornerShape(8.dp))
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
        }

        if (!fullscreen) {
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).weight(1f, fill = false)
                    .padding(
                        start = contentPadding.calculateStartPadding(LocalLayoutDirection.current) + SxSpacing.ScreenHorizontal,
                        end = contentPadding.calculateEndPadding(LocalLayoutDirection.current) + SxSpacing.ScreenHorizontal,
                        top = SxSpacing.Group,
                        bottom = SxSpacing.Group,
                    ),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (reading != null) {
                    ServingMiniCard(reading, servingRow, userPos)
                }
                if (target != null) {
                    TargetCard(target, targetRow, userPos, reading, onClearTarget = vm::clearAntennaTarget)
                } else {
                    SxNotice(
                        stringResource(R.string.orient_map_no_target),
                        SxTone.Info,
                        actionLabel = stringResource(R.string.orient_map_go_antennas),
                        onAction = goToAntennas,
                    )
                }
            }
        }
    }

    if (manageSheet) {
        ManageMapsSheet(
            manager = manager,
            onDismiss = { manageSheet = false },
        )
    }

    pendingPlace?.let { (lat, lon) ->
        val enb = placingEnb ?: return@let
        SxDialog(
            title = stringResource(R.string.orient_map_place_title, enb),
            text = stringResource(R.string.orient_map_place_message, SxFormat.formatCoordinates(lat, lon)),
            confirmLabel = stringResource(R.string.common_save),
            onConfirm = {
                vm.saveTowerPosition(enb, lat, lon)
                vm.clearPlacingEnb()
                pendingPlace = null
            },
            onDismiss = { pendingPlace = null },
            dismissLabel = stringResource(R.string.common_cancel),
        )
    }
}

@Composable
private fun MapControlButton(
    label: String,
    description: String,
    onClick: () -> Unit,
) {
    androidx.compose.material3.Surface(
        onClick = onClick,
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
            Text(label, style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { contentDescription = description })
        }
    }
}

@Composable
private fun LegendLine(
    color: Color,
    dashed: Boolean,
    text: String,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            if (dashed) "╌╌" else "——",
            color = color,
            style = MaterialTheme.typography.bodySmall,
        )
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
    }
}

/** Compact serving-cell card (§7.2): same title and RSRP format as Explorar, plus distance/azimuth only when the serving antenna has a position. */
@Composable
private fun ServingMiniCard(
    reading: ServingReading,
    servingRow: AntennaRow?,
    userPos: UserPosition?,
) {
    val title =
        when {
            reading.cell?.eci != null -> SxFormat.formatEnbSector(reading.cell.eci)
            reading.carrier.band != null -> reading.carrier.bandLabel
            else -> SxFormat.Unavailable
        }
    val rsrp = reading.signal?.rsrp ?: reading.carrier.rsrp
    SxCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMediumEmphasized.mono(), modifier = Modifier.weight(1f))
            SxStatusChip(stringResource(R.string.orient_map_serving), SxTone.Signal)
            if (rsrp != null) {
                SxSignalPill(rsrp, SxSignalThresholds.Rsrp, LocalSignalScale.current)
            }
        }
        if (servingRow?.hasPosition == true && userPos != null) {
            val meters = distanceMeters(userPos.latitude, userPos.longitude, servingRow.lat!!, servingRow.lon!!)
            SxDataRow(
                stringResource(R.string.orient_map_distance),
                SxFormat.formatDistance(meters, estimated = servingRow.source == AntennaSource.ESTIMATED),
                monoValue = true,
            )
            SxDataRow(
                stringResource(R.string.orient_map_azimuth),
                SxFormat.formatAzimuth(bearingDeg(userPos.latitude, userPos.longitude, servingRow.lat, servingRow.lon)),
                monoValue = true,
            )
        }
    }
}

/** Target card (§7.3): identity, bands, distance and azimuth — shown once, here, not twice on this screen (§10.7). */
@Composable
private fun TargetCard(
    target: AntennaTarget,
    targetRow: AntennaRow?,
    userPos: UserPosition?,
    reading: ServingReading?,
    onClearTarget: () -> Unit,
) {
    val distance =
        if (userPos != null) distanceMeters(userPos.latitude, userPos.longitude, target.lat, target.lon) else null
    val azimuth =
        if (userPos != null) bearingDeg(userPos.latitude, userPos.longitude, target.lat, target.lon) else null
    val isServing = reading?.cell?.eci?.let { SxFormat.enbOf(it) } == target.enb
    SxCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "eNB ${target.enb}",
                style = MaterialTheme.typography.titleMediumEmphasized.mono(),
                modifier = Modifier.weight(1f),
            )
            SxStatusChip(stringResource(R.string.orient_map_target), SxTone.Info)
        }
        targetRow?.let { row ->
            if (row.bands.isNotEmpty()) {
                SxDataRow(stringResource(R.string.explorar_band), row.bands, monoValue = true)
            }
            if (row.zone.isNotEmpty()) {
                Text(row.zone, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            SxDataRow(stringResource(R.string.antenas_position), SxFormat.formatCoordinates(row.lat, row.lon), monoValue = true)
        }
        SxDataRow(
            stringResource(R.string.orient_map_distance),
            distance?.let { SxFormat.formatDistance(it, estimated = targetRow?.source == AntennaSource.ESTIMATED) } ?: SxFormat.Unavailable,
            monoValue = true,
        )
        SxDataRow(
            stringResource(R.string.orient_map_azimuth),
            azimuth?.let { SxFormat.formatAzimuth(it) } ?: SxFormat.Unavailable,
            monoValue = true,
        )
        if (isServing) {
            val rsrp = reading?.signal?.rsrp ?: reading?.carrier?.rsrp
            SxDataRow("RSRP", SxFormat.formatDbm(rsrp), monoValue = true)
            Text(
                stringResource(R.string.orient_map_serving_here),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        SxTextButton(text = stringResource(R.string.orient_map_clear_target), onClick = onClearTarget)
    }
}

/** Manage maps: the ONE map-management experience (§6.5). Import, download, delete, switch source and tile cache live here and nowhere else. */
@Composable
private fun ManageMapsSheet(
    manager: MapManager,
    onDismiss: () -> Unit,
) {
    val mapState by manager.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var message by remember { mutableStateOf<String?>(null) }
    var deleteName by remember { mutableStateOf<String?>(null) }
    val importLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (uri == null) return@rememberLauncherForActivityResult
            scope.launch {
                message =
                    when (val outcome = manager.importMap(uri)) {
                        is ImportOutcome.Ok -> "OK:${outcome.name}"
                        is ImportOutcome.NotAMapFile -> "NOTMAP"
                        is ImportOutcome.Failed -> "FAILED"
                    }
            }
        }
    SxBottomSheet(onDismiss = onDismiss) {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.orient_maps_title), style = MaterialTheme.typography.titleMediumEmphasized)
            Text(
                stringResource(R.string.orient_maps_active) + ": " + (mapState.activeMapName ?: stringResource(R.string.orient_maps_none)),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                // The active source is a chip, not a disabled button: a button that does nothing is a dead control.
                if (mapState.source == MapSource.FILE) {
                    SxStatusChip(stringResource(R.string.orient_map_source_file), SxTone.Info)
                } else {
                    SxSecondaryButton(
                        text = stringResource(R.string.orient_map_source_file),
                        onClick = {
                            val first = mapState.maps.firstOrNull()
                            if (first != null) manager.useMap(first.name) else message = "NOMAP"
                        },
                        modifier = Modifier.weight(1f),
                    )
                }
                if (mapState.source == MapSource.ONLINE) {
                    SxStatusChip(stringResource(R.string.orient_map_source_online), SxTone.Info)
                } else {
                    SxSecondaryButton(
                        text = stringResource(R.string.orient_map_source_online),
                        onClick = { manager.setupOnline() },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            Text(stringResource(R.string.orient_maps_online_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

            if (mapState.maps.isEmpty()) {
                Text(stringResource(R.string.orient_maps_none), style = MaterialTheme.typography.bodyMedium)
            } else {
                mapState.maps.forEach { info ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text(info.name, style = MaterialTheme.typography.bodyMediumEmphasized)
                            Text(MapCatalog.sizeLabel(info.sizeBytes), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (!info.isActive) {
                            SxTextButton(text = stringResource(R.string.orient_maps_use), onClick = { manager.useMap(info.name) })
                        }
                        SxTextButton(text = stringResource(R.string.orient_maps_delete), onClick = { deleteName = info.name })
                    }
                }
            }

            SxSecondaryButton(
                text = stringResource(R.string.orient_maps_import),
                onClick = { importLauncher.launch("*/*") },
                modifier = Modifier.fillMaxWidth(),
            )
            Text(stringResource(R.string.orient_maps_import_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

            // Cuba download: the single progress strip (§10.4).
            when (val transfer = mapState.transfer) {
                is TransferState.Idle -> {
                    SxSecondaryButton(
                        text = stringResource(R.string.orient_maps_download_cuba),
                        onClick = {
                            if (!manager.startDownload(MapCatalog.CUBA)) message = "BUSY"
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(stringResource(R.string.orient_maps_download_cuba_size), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(stringResource(R.string.orient_maps_santiago_no_url), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                is TransferState.Active -> {
                    Text(
                        stringResource(R.string.orient_maps_downloading, "Cuba") +
                            " — " + MapCatalog.sizeLabel(transfer.downloaded) +
                            (if (transfer.total > 0) " / " + MapCatalog.sizeLabel(transfer.total) else "") +
                            " · " + SxFormat.formatSpeed(transfer.bytesPerSec),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    SxProgressBar(
                        if (transfer.total > 0) (transfer.downloaded.toFloat() / transfer.total).coerceIn(0f, 1f) else null,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (transfer.paused) {
                            SxPrimaryButton(text = stringResource(R.string.orient_maps_resume), onClick = { manager.resumeDownload() }, modifier = Modifier.weight(1f))
                        } else {
                            SxSecondaryButton(text = stringResource(R.string.orient_maps_pause), onClick = { manager.pauseDownload() }, modifier = Modifier.weight(1f))
                        }
                        SxSecondaryButton(text = stringResource(R.string.orient_maps_cancel), onClick = { manager.cancelDownload() }, modifier = Modifier.weight(1f))
                    }
                    if (transfer.paused) {
                        Text(stringResource(R.string.orient_maps_paused), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                is TransferState.Failed -> {
                    SxNotice(
                        when (transfer.reason) {
                            MapDownload.FailReason.NO_CONNECTION -> stringResource(R.string.orient_maps_failed_no_connection)
                            MapDownload.FailReason.CUT -> stringResource(R.string.orient_maps_failed_cut)
                            MapDownload.FailReason.NO_RESPONSE -> stringResource(R.string.orient_maps_failed_no_response)
                            MapDownload.FailReason.SERVER_ERROR -> stringResource(R.string.orient_maps_failed_server)
                            MapDownload.FailReason.NO_SPACE -> stringResource(R.string.orient_maps_failed_no_space)
                        },
                        SxTone.Error,
                        actionLabel = stringResource(R.string.orient_maps_resume),
                        onAction = { manager.resumeDownload() },
                    )
                }
                is TransferState.Done -> {
                    SxNotice(
                        stringResource(R.string.orient_maps_downloaded, "Cuba"),
                        SxTone.Signal,
                        actionLabel = stringResource(R.string.common_close),
                        onAction = { manager.clearTransferState() },
                    )
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.orient_maps_cache), style = MaterialTheme.typography.bodyMediumEmphasized)
                    Text(MapCatalog.sizeLabel(mapState.cacheBytes), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                SxTextButton(
                    text = stringResource(R.string.orient_maps_clear_cache),
                    onClick = {
                        manager.clearTileCache()
                        message = "CACHE_CLEARED"
                    },
                )
            }

            message?.let { code ->
                val text =
                    when {
                        code.startsWith("OK:") -> stringResource(R.string.orient_maps_imported, code.removePrefix("OK:"))
                        code == "NOTMAP" -> stringResource(R.string.orient_maps_import_failed)
                        code == "FAILED" -> stringResource(R.string.orient_maps_import_failed)
                        code == "NOMAP" -> stringResource(R.string.orient_maps_none)
                        code == "BUSY" -> stringResource(R.string.orient_maps_already_downloading)
                        code == "CACHE_CLEARED" -> stringResource(R.string.orient_maps_cache_cleared)
                        else -> null
                    }
                text?.let {
                    SxNotice(it, if (code == "NOTMAP" || code == "FAILED") SxTone.Error else SxTone.Info)
                }
            }
        }
    }

    deleteName?.let { name ->
        SxDialog(
            title = stringResource(R.string.orient_maps_delete_title),
            text = stringResource(R.string.orient_maps_delete_message, name),
            confirmLabel = stringResource(R.string.orient_maps_delete),
            onConfirm = {
                manager.deleteMap(name)
                deleteName = null
            },
            onDismiss = { deleteName = null },
            dismissLabel = stringResource(R.string.common_cancel),
            destructiveConfirm = true,
        )
    }
}
