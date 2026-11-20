// Field camera, Camera2 direct (superprompt §6.3, §10.1; Fase 6).
//
// THE one camera experience: SignalX 1.0.9 had two — a screen and a
// modal with the same job and two layouts. Here there is one helper and
// one screen (ui/CameraScreen.kt). The helper is the port of SignalX's
// CameraHelper.kt, with the one thing SignalX got wrong fixed: its
// errors were swallowed (empty catch blocks), so a camera that would
// not open just sat there. Every failure here is reported through
// [onError] with a plain cause the screen turns into words + a retry —
// never a stack trace on screen.
//
// Camera2, not CameraX: the preview is 1280x720 and the capture is a
// JPEG 1920x1080, exactly as SignalX did it, and CameraX would add a
// dependency for nothing. If Camera2 ever blocks on a device, that is
// a new decision — not a silent fallback.
//
// The camera is NOT AR: the screen draws a centre crosshair + a
// horizon line (the port of SignalX's CrosshairView) as an aiming
// reference only. The photo file is named foto_<ms>.jpg in the app's
// filesDir/photos, and the caller (MainViewModel.onPhotoTaken) records
// GPS + azimuth with it. No location permission is needed here: the
// caller passes what it already has, and a photo without a fix is
// saved without a position, never with 0.

package dev.ririxi.bunnylock.signalx

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import android.view.TextureView
import java.io.File

/** Why the camera could not do what was asked — the screen maps each one to words and a retry. */
enum class CameraProblem {
    /** No camera on this phone at all (or the list could not be read). */
    NO_CAMERA,
    /** The camera would not open (in use by another app, or a device error). */
    OPEN_FAILED,
    /** The preview session would not configure. */
    SESSION_FAILED,
    /** The capture request failed. */
    CAPTURE_FAILED,
    /** The JPEG could not be written to disk. */
    SAVE_FAILED,
}

class FieldCamera(private val context: Context) {
    private var camera: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var previewView: TextureView? = null
    private var onPhoto: ((File) -> Unit)? = null
    private var onError: ((CameraProblem) -> Unit)? = null
    private var onReady: (() -> Unit)? = null
    private var photosDir: File? = null
    private var started = false

    fun start(
        view: TextureView,
        photosDir: File,
        onPhoto: (File) -> Unit,
        onError: (CameraProblem) -> Unit,
        onReady: () -> Unit,
    ) {
        if (started) return
        this.previewView = view
        this.photosDir = photosDir
        this.onPhoto = onPhoto
        this.onError = onError
        this.onReady = onReady
        thread = HandlerThread("signalx-camera").also { it.start() }
        handler = Handler(thread!!.looper)
        if (view.isAvailable) openCamera() else {
            view.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) = openCamera()
                override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {}
                override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean = true
                override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
            }
        }
        started = true
    }

    fun stop() {
        try { session?.close() } catch (_: Exception) { }
        try { camera?.close() } catch (_: Exception) { }
        try { imageReader?.close() } catch (_: Exception) { }
        thread?.quitSafely()
        session = null
        camera = null
        imageReader = null
        thread = null
        handler = null
        previewView = null
        started = false
    }

    @SuppressLint("MissingPermission")
    private fun openCamera() {
        val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val id =
            try {
                cm.cameraIdList.firstOrNull { cid ->
                    cm.getCameraCharacteristics(cid).get(CameraCharacteristics.LENS_FACING) ==
                        CameraCharacteristics.LENS_FACING_BACK
                } ?: cm.cameraIdList.firstOrNull()
            } catch (_: Exception) {
                null
            } ?: run {
                onError?.invoke(CameraProblem.NO_CAMERA)
                return
            }
        try {
            cm.openCamera(
                id,
                object : CameraDevice.StateCallback() {
                    override fun onOpened(c: CameraDevice) {
                        camera = c
                        startSession()
                    }

                    override fun onDisconnected(c: CameraDevice) {
                        // Another app took the camera: report it instead of going quiet.
                        try { c.close() } catch (_: Exception) { }
                        onError?.invoke(CameraProblem.OPEN_FAILED)
                    }

                    override fun onError(c: CameraDevice, error: Int) {
                        try { c.close() } catch (_: Exception) { }
                        onError?.invoke(CameraProblem.OPEN_FAILED)
                    }
                },
                handler,
            )
        } catch (_: SecurityException) {
            // The permission was revoked while opening: the screen asks again.
            onError?.invoke(CameraProblem.OPEN_FAILED)
        } catch (_: Exception) {
            onError?.invoke(CameraProblem.OPEN_FAILED)
        }
    }

    private fun startSession() {
        val view = previewView ?: return
        val st = view.surfaceTexture ?: return
        st.setDefaultBufferSize(1280, 720)
        val previewSurface = Surface(st)
        imageReader = ImageReader.newInstance(1920, 1080, ImageFormat.JPEG, 2).also { reader ->
            reader.setOnImageAvailableListener({ r ->
                val img = r.acquireLatestImage() ?: return@setOnImageAvailableListener
                val buf = img.planes[0].buffer
                val bytes = ByteArray(buf.remaining())
                buf.get(bytes)
                img.close()
                val dir = photosDir ?: return@setOnImageAvailableListener
                val file = File(dir, "foto_${System.currentTimeMillis()}.jpg")
                try {
                    file.writeBytes(bytes)
                } catch (_: Exception) {
                    onError?.invoke(CameraProblem.SAVE_FAILED)
                    return@setOnImageAvailableListener
                }
                onPhoto?.invoke(file)
            }, handler)
        }
        val targets = listOf(previewSurface, imageReader!!.surface)
        try {
            camera?.createCaptureSession(targets, object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) {
                    session = s
                    val req = camera!!.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                    req.addTarget(previewSurface)
                    req.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                    try {
                        s.setRepeatingRequest(req.build(), null, handler)
                        onReady?.invoke()
                    } catch (_: Exception) {
                        onError?.invoke(CameraProblem.SESSION_FAILED)
                    }
                }

                override fun onConfigureFailed(s: CameraCaptureSession) {
                    onError?.invoke(CameraProblem.SESSION_FAILED)
                }
            }, handler)
        } catch (_: Exception) {
            onError?.invoke(CameraProblem.SESSION_FAILED)
        }
    }

    fun takePhoto() {
        val s = session ?: run {
            onError?.invoke(CameraProblem.CAPTURE_FAILED)
            return
        }
        val cam = camera ?: run {
            onError?.invoke(CameraProblem.CAPTURE_FAILED)
            return
        }
        val reader = imageReader ?: run {
            onError?.invoke(CameraProblem.CAPTURE_FAILED)
            return
        }
        try {
            val req = cam.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
            req.addTarget(reader.surface)
            req.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            s.capture(req.build(), null, handler)
        } catch (_: Exception) {
            onError?.invoke(CameraProblem.CAPTURE_FAILED)
        }
    }
}
