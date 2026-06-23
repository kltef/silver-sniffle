package com.silversniffle.multicam.camera

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import androidx.core.content.ContextCompat
import com.silversniffle.multicam.ui.AutoFitTextureView

/**
 * Owns the full Camera2 lifecycle for a single camera/tile on its own background thread,
 * so a slow or blocked open on one camera never stalls the others.
 *
 * State changes are reported back via [onStateChange], always posted to [mainHandler].
 */
class CameraController(
    private val context: Context,
    private val info: CameraInfo,
    private val mainHandler: Handler,
    private val onStateChange: (CameraTileState) -> Unit,
) {
    private val cameraManager =
        context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null

    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var previewSurface: Surface? = null

    private var textureView: AutoFitTextureView? = null
    private var closed = false
    private var startRequested = false

    /** Attach (or re-attach) the tile's view. Opens immediately if a start was already requested. */
    fun bind(view: AutoFitTextureView) {
        textureView = view
        view.setAspectRatio(info.previewSize.width, info.previewSize.height)
        if (startRequested && cameraDevice == null) {
            attemptOpen()
        }
    }

    /**
     * Request that the camera open. Safe to call before or after [bind]; the open happens as
     * soon as both a start is requested and a surface is available.
     */
    fun start() {
        closed = false
        startRequested = true
        startBackgroundThread()
        attemptOpen()
    }

    private fun attemptOpen() {
        val view = textureView ?: return
        val st = view.surfaceTexture
        if (view.isAvailable && st != null) {
            openCamera(st)
        } else {
            view.surfaceTextureListener = object : SimpleSurfaceTextureListener() {
                override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                    if (startRequested) openCamera(st)
                }
            }
        }
    }

    private fun startBackgroundThread() {
        if (backgroundThread != null) return
        backgroundThread = HandlerThread("cam-${info.cameraId}").also {
            it.start()
            backgroundHandler = Handler(it.looper)
        }
    }

    private fun openCamera(surfaceTexture: SurfaceTexture) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            publish(CameraTileState.Error("Camera permission needed"))
            return
        }
        publish(CameraTileState.Opening)
        surfaceTexture.setDefaultBufferSize(info.previewSize.width, info.previewSize.height)
        val surface = Surface(surfaceTexture)
        previewSurface = surface
        try {
            cameraManager.openCamera(info.cameraId, stateCallback, backgroundHandler)
        } catch (e: CameraAccessException) {
            publish(CameraTileState.Error(accessReason(e)))
        } catch (e: SecurityException) {
            publish(CameraTileState.Error("Camera permission needed"))
        } catch (e: IllegalArgumentException) {
            publish(CameraTileState.Error("Camera unavailable"))
        }
    }

    private val stateCallback = object : CameraDevice.StateCallback() {
        override fun onOpened(device: CameraDevice) {
            cameraDevice = device
            if (closed) {
                device.close()
                return
            }
            createSession(device)
        }

        override fun onDisconnected(device: CameraDevice) {
            device.close()
            cameraDevice = null
            publish(CameraTileState.Error("Disconnected (taken by another app?)"))
        }

        override fun onError(device: CameraDevice, error: Int) {
            device.close()
            cameraDevice = null
            publish(CameraTileState.Error(errorReason(error)))
        }
    }

    private fun createSession(device: CameraDevice) {
        val surface = previewSurface ?: return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val config = SessionConfiguration(
                    SessionConfiguration.SESSION_REGULAR,
                    listOf(OutputConfiguration(surface)),
                    { runnable -> backgroundHandler?.post(runnable) },
                    sessionCallback,
                )
                device.createCaptureSession(config)
            } else {
                @Suppress("DEPRECATION")
                device.createCaptureSession(listOf(surface), sessionCallback, backgroundHandler)
            }
        } catch (e: CameraAccessException) {
            publish(CameraTileState.Error(accessReason(e)))
        }
    }

    private val sessionCallback = object : CameraCaptureSession.StateCallback() {
        override fun onConfigured(session: CameraCaptureSession) {
            val device = cameraDevice ?: return
            val surface = previewSurface ?: return
            if (closed) {
                session.close()
                return
            }
            captureSession = session
            try {
                val builder = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                    addTarget(surface)
                    set(
                        CaptureRequest.CONTROL_AF_MODE,
                        CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE,
                    )
                }
                session.setRepeatingRequest(builder.build(), null, backgroundHandler)
                publish(CameraTileState.Streaming)
            } catch (e: CameraAccessException) {
                publish(CameraTileState.Error(accessReason(e)))
            }
        }

        override fun onConfigureFailed(session: CameraCaptureSession) {
            publish(CameraTileState.Error("Couldn't configure preview session"))
        }
    }

    /** Tear everything down. Idempotent; called from onPause and on Retry. */
    fun close() {
        closed = true
        startRequested = false
        runCatching { captureSession?.close() }
        captureSession = null
        runCatching { cameraDevice?.close() }
        cameraDevice = null
        runCatching { previewSurface?.release() }
        previewSurface = null
        backgroundThread?.quitSafely()
        backgroundThread = null
        backgroundHandler = null
    }

    private fun publish(state: CameraTileState) {
        if (closed && state != CameraTileState.Idle) return
        mainHandler.post { onStateChange(state) }
    }

    private fun accessReason(e: CameraAccessException): String = when (e.reason) {
        CameraAccessException.MAX_CAMERAS_IN_USE ->
            "Device's concurrent camera limit reached"
        CameraAccessException.CAMERA_IN_USE -> "Camera busy (in use elsewhere)"
        CameraAccessException.CAMERA_DISABLED -> "Camera disabled by device policy"
        CameraAccessException.CAMERA_DISCONNECTED -> "Camera disconnected"
        else -> "Camera error (${e.reason})"
    }

    private fun errorReason(error: Int): String = when (error) {
        CameraDevice.StateCallback.ERROR_MAX_CAMERAS_IN_USE ->
            "Device's concurrent camera limit reached"
        CameraDevice.StateCallback.ERROR_CAMERA_IN_USE -> "Camera busy (in use elsewhere)"
        CameraDevice.StateCallback.ERROR_CAMERA_DISABLED -> "Camera disabled by device policy"
        CameraDevice.StateCallback.ERROR_CAMERA_DEVICE -> "Fatal camera device error"
        CameraDevice.StateCallback.ERROR_CAMERA_SERVICE -> "Camera service error"
        else -> "Camera error ($error)"
    }
}
