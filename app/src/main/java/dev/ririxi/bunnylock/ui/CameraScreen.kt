// Field camera — THE one camera experience (superprompt §6.3, §10.1; Fase 6).
//
// SignalX 1.0.9 had two cameras (a screen and a modal, same job, two
// layouts). Here there is one screen and one helper (signalx/
// FieldCamera.kt, Camera2). It opens from Herramientas > Camera, and
// from the compass "Save point with photo" — same screen, same logic,
// the only difference is what happens to the photo afterwards.
//
// NOT AR: a centre crosshair + a horizon line (the port of SignalX's
// CrosshairView) is the aiming reference for the base of the antenna.
// The photo saves GPS + azimuth with it (MainViewModel.onPhotoTaken);
// without a fix it saves without a position, never with 0.
//
// States (§9.4): starting, ready, no permission, camera busy / open
// failed / capture failed / save failed (each with words + retry),
// saving, saved. The camera stops when the screen leaves — it is
// never left running in the background.

@file:Suppress("FunctionName")

package dev.ririxi.bunnylock.ui

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.view.TextureView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.ririxi.bunnylock.R
import dev.ririxi.bunnylock.signalx.CameraProblem
import dev.ririxi.bunnylock.signalx.FieldCamera
import dev.ririxi.bunnylock.ui.design.SxFormat
import dev.ririxi.bunnylock.ui.design.SxNotice
import dev.ririxi.bunnylock.ui.design.SxPrimaryButton
import dev.ririxi.bunnylock.ui.design.SxScreenHeader
import dev.ririxi.bunnylock.ui.design.SxSecondaryButton
import dev.ririxi.bunnylock.ui.design.SxTextButton
import dev.ririxi.bunnylock.ui.design.SxTone
import dev.ririxi.bunnylock.ui.design.mono
import kotlinx.coroutines.launch

private enum class CameraUi {
    STARTING,
    READY,
    SAVED,
}

@Composable
fun CameraScreen(
    state: UiState,
    vm: MainViewModel,
    contentPadding: PaddingValues,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val activity = context as? Activity
    val scope = rememberCoroutineScope()
    val heading by vm.heading.collectAsStateWithLifecycle()
    // Armed by the compass before navigating here: this photo is
    // attached to the saved point. Read once — the flag clears on save.
    val pointArmed = remember { vm.isPhotoPointArmed() }
    val camera = remember { FieldCamera(context) }
    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED,
        )
    }
    var denied by remember { mutableStateOf(false) }
    var asked by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf(CameraUi.STARTING) }
    var problem by remember { mutableStateOf<CameraProblem?>(null) }
    var savedName by remember { mutableStateOf<String?>(null) }
    var savedAttached by remember { mutableStateOf(false) }
    var startTick by remember { mutableIntStateOf(0) }
    var textureView by remember { mutableStateOf<TextureView?>(null) }

    val permissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
            granted = ok
            denied = !ok
            if (ok) {
                problem = null
                status = CameraUi.STARTING
                startTick++
            }
        }

    // Ask once on entry; a denial lands on the permission screen below,
    // with Try again / Open settings — never a silent black preview.
    LaunchedEffect(Unit) {
        if (!granted && !asked) {
            asked = true
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    DisposableEffect(textureView, granted, startTick) {
        val view = textureView
        if (view != null && granted) {
            status = CameraUi.STARTING
            camera.start(
                view = view,
                photosDir = vm.photosDir(),
                onPhoto = { file ->
                    scope.launch {
                        val attached = vm.isPhotoPointArmed()
                        vm.onPhotoTaken(file)
                        savedName = file.name
                        savedAttached = attached
                        status = CameraUi.SAVED
                    }
                },
                onError = { p ->
                    scope.launch {
                        problem = p
                    }
                },
                onReady = {
                    scope.launch {
                        problem = null
                        if (status != CameraUi.SAVED) status = CameraUi.READY
                    }
                },
            )
        }
        onDispose { camera.stop() }
    }

    val userPos = state.userPosition
    val permanentlyDenied =
        denied && activity?.shouldShowRequestPermissionRationale(Manifest.permission.CAMERA) == false

    Column(Modifier.fillMaxSize().padding(contentPadding)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) {
                Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.common_close))
            }
            SxScreenHeader(stringResource(R.string.camera_title))
            Spacer(Modifier.weight(1f))
        }
        Text(
            stringResource(R.string.camera_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        if (pointArmed) {
            SxNotice(
                stringResource(R.string.camera_point_armed),
                SxTone.Info,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }

        if (!granted) {
            // Permission screen: words first, then the real actions.
            Column(
                Modifier.fillMaxWidth().weight(1f).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Spacer(Modifier.weight(1f))
                Text(stringResource(R.string.camera_no_permission), style = MaterialTheme.typography.bodyLarge)
                Text(
                    stringResource(R.string.camera_permission_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                SxPrimaryButton(
                    text = stringResource(R.string.camera_grant),
                    onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) },
                )
                if (permanentlyDenied) {
                    SxSecondaryButton(
                        text = stringResource(R.string.camera_open_settings),
                        onClick = {
                            val intent =
                                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                    data = Uri.fromParts("package", context.packageName, null)
                                }
                            runCatching { context.startActivity(intent) }
                        },
                    )
                }
                Spacer(Modifier.weight(1f))
            }
        } else {
            CameraPreview(
                modifier = Modifier.weight(1f),
                status = status,
                problem = problem,
                savedName = savedName,
                savedAttached = savedAttached,
                onTextureView = { textureView = it },
                onRetry = {
                    problem = null
                    status = CameraUi.STARTING
                    startTick++
                },
                onTakeAnother = {
                    savedName = null
                    savedAttached = false
                    problem = null
                    status = CameraUi.READY
                },
                onCapture = {
                    if (status == CameraUi.READY || status == CameraUi.SAVED) {
                        status = CameraUi.STARTING
                        camera.takePhoto()
                    }
                },
                positionText =
                    userPos?.accuracy?.let { SxFormat.formatAccuracy(it) } ?: stringResource(R.string.camera_no_gps),
                headingText = heading?.let { SxFormat.formatAzimuth(it.degrees.toDouble()) } ?: "—",
            )
        }
        SxTextButton(
            text = stringResource(R.string.camera_done),
            onClick = onClose,
            modifier = Modifier.align(Alignment.CenterHorizontally),
        )
    }
}

@Composable
private fun CameraPreview(
    modifier: Modifier = Modifier,
    status: CameraUi,
    problem: CameraProblem?,
    savedName: String?,
    savedAttached: Boolean,
    onTextureView: (TextureView) -> Unit,
    onRetry: () -> Unit,
    onTakeAnother: () -> Unit,
    onCapture: () -> Unit,
    positionText: String,
    headingText: String,
) {
    // Read outside the semantics block: stringResource is @Composable
    // and the semantics lambda is not.
    val captureLabel = stringResource(R.string.camera_capture)
    Column(modifier.fillMaxSize()) {
        Box(Modifier.fillMaxWidth().weight(1f).padding(16.dp)) {
            AndroidView(
                factory = { ctx ->
                    TextureView(ctx).also { onTextureView(it) }
                },
                modifier = Modifier.fillMaxSize(),
            )
            // Crosshair + horizon line (SignalX CrosshairView, in Compose).
            // Green cross, white horizon — an aiming reference, not AR.
            val crossColor = MaterialTheme.colorScheme.primary
            val horizonColor = MaterialTheme.colorScheme.onSurface
            Canvas(Modifier.fillMaxSize()) {
                val cx = size.width / 2f
                val cy = size.height / 2f
                val arm = 48.dp.toPx()
                val gap = 14.dp.toPx()
                val stroke = 4.dp.toPx()
                drawLine(crossColor, Offset(cx - arm, cy), Offset(cx - gap, cy), strokeWidth = stroke)
                drawLine(crossColor, Offset(cx + gap, cy), Offset(cx + arm, cy), strokeWidth = stroke)
                drawLine(crossColor, Offset(cx, cy - arm), Offset(cx, cy - gap), strokeWidth = stroke)
                drawLine(crossColor, Offset(cx, cy + gap), Offset(cx, cy + arm), strokeWidth = stroke)
                drawCircle(crossColor, radius = 8.dp.toPx(), center = Offset(cx, cy), style = androidx.compose.ui.graphics.drawscope.Stroke(width = stroke))
                drawLine(horizonColor.copy(alpha = 0.78f), Offset(0f, cy), Offset(size.width, cy), strokeWidth = 3.dp.toPx())
            }
            if (status == CameraUi.STARTING && problem == null) {
                SxNotice(
                    stringResource(R.string.camera_starting),
                    SxTone.Info,
                    modifier = Modifier.align(Alignment.TopCenter).padding(12.dp),
                )
            }
            problem?.let { p ->
                Column(
                    Modifier.align(Alignment.TopCenter).padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    SxNotice(
                        when (p) {
                            CameraProblem.NO_CAMERA -> stringResource(R.string.camera_no_camera)
                            CameraProblem.OPEN_FAILED -> stringResource(R.string.camera_open_failed)
                            CameraProblem.SESSION_FAILED -> stringResource(R.string.camera_session_failed)
                            CameraProblem.CAPTURE_FAILED -> stringResource(R.string.camera_capture_failed)
                            CameraProblem.SAVE_FAILED -> stringResource(R.string.camera_save_failed)
                        },
                        SxTone.Error,
                    )
                    SxSecondaryButton(
                        text = stringResource(R.string.camera_try_again),
                        onClick = onRetry,
                    )
                }
            }
            if (status == CameraUi.SAVED) {
                SxNotice(
                    if (savedAttached) {
                        stringResource(R.string.camera_point_saved, savedName ?: "")
                    } else {
                        stringResource(R.string.camera_saved, savedName ?: "")
                    },
                    SxTone.Signal,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(12.dp),
                    actionLabel = stringResource(R.string.camera_take_another),
                    onAction = onTakeAnother,
                )
            }
        }

        // Live position + heading at capture time: what the photo carries.
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                stringResource(R.string.camera_position) + ": " + positionText,
                style = MaterialTheme.typography.bodySmall.mono(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Text(
                stringResource(R.string.camera_heading) + ": " + headingText,
                style = MaterialTheme.typography.bodySmall.mono(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.Center,
        ) {
            Surface(
                onClick = onCapture,
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                modifier =
                    Modifier.size(72.dp).semantics {
                        // The button is a "●" glyph: without a description
                        // TalkBack has nothing to say about it (Fase 7).
                        contentDescription = captureLabel
                    },
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text("●", style = MaterialTheme.typography.headlineMedium)
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.Center) {
            Text(
                stringResource(R.string.camera_capture),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
