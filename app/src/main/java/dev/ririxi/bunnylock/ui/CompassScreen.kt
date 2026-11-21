// Compass + "Where to install?" (superprompt §6.2; Fase 5).
//
// A clean compass: a plain circle, no number inside it, the N E S O
// letters upright and never colliding, a marker for the target. The
// heading reads BELOW the circle, not inside it.
//
// "Where to install?" lives INSIDE the compass (SignalX 1.0.7, Gennis's
// order — not a separate mode): live signal + best of the session, a
// walking guide, and three points that light up (● met, ○ missing).
// When all three meet: "OPTIMAL CONNECTION — THIS SPOT IS RIGHT", and
// the point can be saved. Conditions (SignalX 1.0.9): RSRP ≥ −100 dBm,
// heading within ±5° of the target, GPS ≤ ±15 m. Without a target the
// direction point says so plainly — it never reads as met.
//
// The saved point carries GPS + azimuth + the live signal. Fase 6:
// "Save point with photo" arms the point, opens the one camera, and
// the point is saved carrying the photo (MainViewModel.onPhotoTaken).

@file:Suppress("FunctionName")

package dev.ririxi.bunnylock.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.ririxi.bunnylock.R
import dev.ririxi.bunnylock.signalx.AntennaSource
import dev.ririxi.bunnylock.signalx.angleDiffDeg
import dev.ririxi.bunnylock.signalx.bearingDeg
import dev.ririxi.bunnylock.signalx.distanceMeters
import dev.ririxi.bunnylock.signalx.servingReading
import dev.ririxi.bunnylock.ui.design.SxCard
import dev.ririxi.bunnylock.ui.design.SxDataRow
import dev.ririxi.bunnylock.ui.design.SxDialog
import dev.ririxi.bunnylock.ui.design.SxFormat
import dev.ririxi.bunnylock.ui.design.SxNotice
import dev.ririxi.bunnylock.ui.design.SxPrimaryButton
import dev.ririxi.bunnylock.ui.design.SxScreenHeader
import dev.ririxi.bunnylock.ui.design.SxSecondaryButton
import dev.ririxi.bunnylock.ui.design.SxTextButton
import dev.ririxi.bunnylock.ui.design.SxTone
import dev.ririxi.bunnylock.ui.design.mono
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/** RSRP at or above this counts as strong (SignalX 1.0.9). */
private const val INSTALL_MIN_RSRP_DBM = -100

/** Heading within this of the target bearing counts as lined up. */
private const val INSTALL_MAX_OFF_DEG = 5.0

/** GPS accuracy at or below this counts as precise. */
private const val INSTALL_MAX_GPS_M = 15f

/** Walking guide: only after moving this far, and only for a change of this much. */
private const val INSTALL_WALK_MIN_M = 5.0
private const val INSTALL_WALK_MIN_DELTA_DBM = 3

/** The walking guide, as data — the screen maps it to words. */
sealed class InstallGuide {
    data object Start : InstallGuide()
    data class Up(val azimuthDeg: Double) : InstallGuide()
    data object Down : InstallGuide()
    data object Same : InstallGuide()
}

/**
 * The install session: best signal seen + walking guide. Hoisted in the
 * Orientation screen so switching between Map and Compass does not reset
 * it; a new Orientation visit starts a fresh session.
 */
class InstallSession {
    var bestRsrp by mutableStateOf<Int?>(null)
        private set
    var guide by mutableStateOf<InstallGuide>(InstallGuide.Start)
        private set
    private var lastLat: Double? = null
    private var lastLon: Double? = null
    private var lastRsrp: Int? = null

    fun observe(
        rsrp: Int?,
        lat: Double?,
        lon: Double?,
    ) {
        if (rsrp != null) {
            bestRsrp = maxOf(bestRsrp ?: rsrp, rsrp)
        }
        if (lat == null || lon == null || rsrp == null) return
        val prevLat = lastLat
        val prevLon = lastLon
        val prevRsrp = lastRsrp
        if (prevLat == null || prevLon == null || prevRsrp == null) {
            lastLat = lat
            lastLon = lon
            lastRsrp = rsrp
            return
        }
        val moved = distanceMeters(prevLat, prevLon, lat, lon)
        if (moved >= INSTALL_WALK_MIN_M) {
            val delta = rsrp - prevRsrp
            guide =
                when {
                    delta >= INSTALL_WALK_MIN_DELTA_DBM -> InstallGuide.Up(bearingDeg(prevLat, prevLon, lat, lon))
                    delta <= -INSTALL_WALK_MIN_DELTA_DBM -> InstallGuide.Down
                    else -> InstallGuide.Same
                }
            lastLat = lat
            lastLon = lon
            lastRsrp = rsrp
        }
    }
}

@Composable
fun CompassScreen(
    state: UiState,
    vm: MainViewModel,
    contentPadding: PaddingValues,
    session: InstallSession,
    onOpenMap: () -> Unit,
    onGoToAntennas: () -> Unit,
    onSavePointWithPhoto: () -> Unit,
) {
    val heading by vm.heading.collectAsStateWithLifecycle()
    val target = state.antennaTarget
    val targetRow = target?.let { t -> state.antennas.firstOrNull { it.enb == t.enb } }
    val userPos = state.userPosition
    val rsrp = state.modem.servingReading()?.signal?.rsrp
    var showInfo by androidx.compose.runtime.remember { mutableStateOf(false) }

    // Session state updates in an effect, not during composition.
    LaunchedEffect(rsrp, userPos?.latitude, userPos?.longitude) {
        session.observe(rsrp, userPos?.latitude, userPos?.longitude)
    }

    val targetBearing =
        if (target != null && userPos != null) {
            bearingDeg(userPos.latitude, userPos.longitude, target.lat, target.lon)
        } else {
            null
        }
    val targetDistance =
        if (target != null && userPos != null) {
            distanceMeters(userPos.latitude, userPos.longitude, target.lat, target.lon)
        } else {
            null
        }
    val strong = rsrp != null && rsrp >= INSTALL_MIN_RSRP_DBM
    val headingNow = heading
    val offDeg =
        if (targetBearing != null && headingNow != null) {
            abs(angleDiffDeg(targetBearing, headingNow.degrees.toDouble()))
        } else {
            null
        }
    val aligned = offDeg != null && offDeg <= INSTALL_MAX_OFF_DEG
    val precise = userPos?.accuracy != null && userPos.accuracy <= INSTALL_MAX_GPS_M
    val optimal = strong && aligned && precise

    LazyColumn(contentPadding = contentPadding.withScreenMargins(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { SxScreenHeader(stringResource(R.string.orient_section_compass)) }

        item {
            SxCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.orient_compass_heading),
                        style = MaterialTheme.typography.titleMediumEmphasized,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = { showInfo = true }) {
                        Icon(Icons.Rounded.Info, contentDescription = stringResource(R.string.orient_compass_info_title))
                    }
                }
                if (!vm.hasCompassSensor) {
                    Text(
                        stringResource(R.string.orient_compass_no_sensor),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    CompassDial(
                        headingDeg = heading?.degrees,
                        targetBearingDeg = targetBearing?.toFloat(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        heading?.let { SxFormat.formatAzimuth(it.degrees.toDouble()) } ?: SxFormat.Unavailable,
                        style = MaterialTheme.typography.headlineMediumEmphasized.mono(),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (headingNow != null && !headingNow.reliable) {
                        SxNotice(
                            stringResource(R.string.orient_compass_unreliable),
                            SxTone.Warning,
                        )
                    }
                }
                if (target == null) {
                    SxNotice(
                        stringResource(R.string.orient_compass_no_target),
                        SxTone.Info,
                        actionLabel = stringResource(R.string.orient_map_go_antennas),
                        onAction = onGoToAntennas,
                    )
                } else {
                    targetDistance?.let { meters ->
                        SxDataRow(
                            stringResource(R.string.orient_map_distance),
                            SxFormat.formatDistance(meters, estimated = targetRow?.source == AntennaSource.ESTIMATED),
                            monoValue = true,
                        )
                    }
                    SxDataRow(
                        stringResource(R.string.orient_compass_target_bearing),
                        targetBearing?.let { SxFormat.formatAzimuth(it) } ?: SxFormat.Unavailable,
                        monoValue = true,
                    )
                    SxDataRow(
                        stringResource(R.string.orient_compass_gps),
                        userPos?.accuracy?.let { SxFormat.formatAccuracy(it) } ?: stringResource(R.string.orient_compass_no_gps),
                        monoValue = true,
                    )
                }
            }
        }

        item {
            SxCard {
                Text(stringResource(R.string.orient_install_title), style = MaterialTheme.typography.titleMediumEmphasized)
                Text(
                    stringResource(
                        R.string.orient_install_signal,
                        rsrp?.let { SxFormat.formatDbm(it) } ?: SxFormat.Unavailable,
                        session.bestRsrp?.let { SxFormat.formatDbm(it) } ?: SxFormat.Unavailable,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    when (val g = session.guide) {
                        is InstallGuide.Start -> stringResource(R.string.orient_install_guide_start)
                        is InstallGuide.Up -> stringResource(R.string.orient_install_guide_up, SxFormat.formatAzimuth(g.azimuthDeg))
                        is InstallGuide.Down -> stringResource(R.string.orient_install_guide_down)
                        is InstallGuide.Same -> stringResource(R.string.orient_install_guide_same)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
                InstallPoint(
                    met = strong,
                    text =
                        when {
                            rsrp == null -> stringResource(R.string.orient_install_signal_none)
                            strong -> stringResource(R.string.orient_install_signal_ok, SxFormat.formatDbm(rsrp))
                            else -> stringResource(R.string.orient_install_signal_no, SxFormat.formatDbm(rsrp))
                        },
                )
                InstallPoint(
                    met = aligned,
                    text =
                        when {
                            target == null -> stringResource(R.string.orient_install_dir_none)
                            offDeg == null -> stringResource(R.string.orient_install_dir_none)
                            aligned -> stringResource(R.string.orient_install_dir_ok, offDeg.toInt())
                            else -> stringResource(R.string.orient_install_dir_no, offDeg.toInt())
                        },
                )
                InstallPoint(
                    met = precise,
                    text =
                        when {
                            userPos?.accuracy == null -> stringResource(R.string.orient_install_gps_none)
                            precise -> stringResource(R.string.orient_install_gps_ok, userPos.accuracy.toInt())
                            else -> stringResource(R.string.orient_install_gps_no, userPos.accuracy.toInt())
                        },
                )
                if (optimal) {
                    SxNotice(stringResource(R.string.orient_install_optimal), SxTone.Signal)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SxPrimaryButton(
                        text = stringResource(R.string.orient_install_save_point),
                        onClick = vm::savePoint,
                        modifier = Modifier.weight(1f),
                        enabled = userPos != null,
                    )
                    SxSecondaryButton(
                        text = stringResource(R.string.orient_install_save_measure),
                        onClick = vm::saveMeasurement,
                        modifier = Modifier.weight(1f),
                        enabled = state.modem.carriers.isNotEmpty(),
                    )
                }
                // Fase 6: the point can carry a photo. Arming happens
                // here (it needs the fix); the camera takes the photo
                // and the point is saved with it.
                SxSecondaryButton(
                    text = stringResource(R.string.orient_install_save_point_photo),
                    onClick = onSavePointWithPhoto,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = userPos != null,
                )
                Text(
                    stringResource(R.string.orient_install_save_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                SxTextButton(
                    text = stringResource(R.string.orient_compass_open_map),
                    onClick = onOpenMap,
                )
            }
        }
    }

    if (showInfo) {
        // Information only: one "Close" button. A second "Cancel" doing
        // the same thing was a dead duplicate (Fase 7).
        SxDialog(
            title = stringResource(R.string.orient_compass_info_title),
            text = stringResource(R.string.orient_compass_info_text),
            confirmLabel = stringResource(R.string.common_close),
            onConfirm = { showInfo = false },
            onDismiss = { showInfo = false },
        )
    }
}

/** One of the three install points: ● met (signal green), ○ missing (muted). Colour never carries the meaning alone — the dot and the words agree. */
@Composable
private fun InstallPoint(
    met: Boolean,
    text: String,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            if (met) "●" else "○",
            color = if (met) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyLarge,
        )
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

/**
 * Clean compass dial: a plain circle, tick marks, and the N E S O
 * letters drawn UPRIGHT at their position on the rose (they move with
 * the heading but never rotate, so they never collide). No number
 * inside the circle — the heading reads below it. The target marker is
 * a green triangle on the rim at the target bearing; the fixed tick at
 * the top is the direction the phone points.
 */
@Composable
private fun CompassDial(
    headingDeg: Float?,
    targetBearingDeg: Float?,
    modifier: Modifier = Modifier,
) {
    val textMeasurer = rememberTextMeasurer()
    val onSurface = MaterialTheme.colorScheme.onSurface
    val onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant
    val signal = MaterialTheme.colorScheme.primary
    val secondary = MaterialTheme.colorScheme.secondary
    Canvas(modifier.height(280.dp)) {
        val cx = size.width / 2f
        val cy = size.height / 2f
        val radius = minOf(size.width, size.height) / 2f - 28.dp.toPx()
        val heading = headingDeg ?: 0f
        // Outer circle.
        drawCircle(color = onSurfaceVariant, radius = radius, style = Stroke(width = 2.dp.toPx()))
        // Tick marks, rotating with the heading.
        rotate(degrees = -heading, pivot = Offset(cx, cy)) {
            for (deg in 0 until 360 step 15) {
                val rad = Math.toRadians(deg.toDouble())
                val long = deg % 90 == 0
                val inner = radius - (if (long) 18.dp.toPx() else 10.dp.toPx())
                drawLine(
                    color = if (long) onSurface else onSurfaceVariant,
                    start = Offset((cx + sin(rad) * inner).toFloat(), (cy - cos(rad) * inner).toFloat()),
                    end = Offset((cx + sin(rad) * radius).toFloat(), (cy - cos(rad) * radius).toFloat()),
                    strokeWidth = if (long) 3.dp.toPx() else 2.dp.toPx(),
                )
            }
        }
        // Cardinal letters, upright: their POSITION turns with the
        // heading, the glyphs do not.
        val letterStyle = TextStyle(color = onSurface, fontSize = 20.sp)
        val letters = listOf("N" to 0f, "E" to 90f, "S" to 180f, "W" to 270f)
        for ((letter, cardinal) in letters) {
            val angle = Math.toRadians((cardinal - heading).toDouble())
            val lx = cx + sin(angle).toFloat() * (radius - 34.dp.toPx())
            val ly = cy - cos(angle).toFloat() * (radius - 34.dp.toPx())
            val layout = textMeasurer.measure(letter, letterStyle)
            drawText(
                layout,
                topLeft = Offset(lx - layout.size.width / 2f, ly - layout.size.height / 2f),
            )
        }
        // Target marker: green triangle on the rim at the target bearing.
        targetBearingDeg?.let { bearing ->
            val angle = Math.toRadians((bearing - heading).toDouble())
            val tx = cx + sin(angle).toFloat() * (radius - 4.dp.toPx())
            val ty = cy - cos(angle).toFloat() * (radius - 4.dp.toPx())
            val tip = Offset(tx, ty)
            val base = 10.dp.toPx()
            val side = 7.dp.toPx()
            val dirX = sin(angle).toFloat()
            val dirY = -cos(angle).toFloat()
            val path =
                Path().apply {
                    moveTo(tip.x, tip.y)
                    lineTo(tip.x - dirX * base - (-dirY) * side, tip.y - dirY * base - dirX * side)
                    lineTo(tip.x - dirX * base + (-dirY) * side, tip.y - dirY * base + dirX * side)
                    close()
                }
            drawPath(path, color = signal)
        }
        // Fixed tick at the top: where the phone points.
        val tickTop = Offset(cx, cy - radius - 14.dp.toPx())
        val tickPath =
            Path().apply {
                moveTo(tickTop.x, tickTop.y + 16.dp.toPx())
                lineTo(tickTop.x - 8.dp.toPx(), tickTop.y)
                lineTo(tickTop.x + 8.dp.toPx(), tickTop.y)
                close()
            }
        drawPath(tickPath, color = secondary)
    }
}
