package com.silversniffle.multicam.camera

import android.graphics.SurfaceTexture
import android.view.TextureView

/**
 * Adapter that lets callers override only [onSurfaceTextureAvailable]. The remaining
 * callbacks have no-op defaults because preview teardown is driven by [CameraController.close].
 */
abstract class SimpleSurfaceTextureListener : TextureView.SurfaceTextureListener {
    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) = Unit
    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean = true
    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit
}
