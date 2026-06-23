package com.silversniffle.multicam.camera

import android.util.Size
import android.util.SizeF

/**
 * Immutable description of a single logical camera on the device, derived once at
 * enumeration time from [android.hardware.camera2.CameraCharacteristics].
 */
data class CameraInfo(
    val cameraId: String,
    val facingLabel: String,
    val lensCategory: String,
    val focalLengthMm: Float?,
    val sensorSizeMm: SizeF?,
    val equiv35Mm: Int?,
    val hardwareLevelLabel: String,
    val previewSize: Size,
    /** True when this camera appears in at least one [CameraManager.getConcurrentCameraIds] set. */
    val concurrentSupported: Boolean,
) {
    /** One-line human label shown on the tile, e.g. "Cam 2 · Back · Ultrawide · 2.2mm (≈13mm eq)". */
    val displayLabel: String
        get() = buildString {
            append("Cam ").append(cameraId)
            append(" · ").append(facingLabel)
            append(" · ").append(lensCategory)
            focalLengthMm?.let { append(" · ").append(String.format("%.1fmm", it)) }
            equiv35Mm?.let { append(" (≈").append(it).append("mm eq)") }
        }
}
