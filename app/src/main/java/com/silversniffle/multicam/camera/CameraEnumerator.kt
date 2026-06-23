package com.silversniffle.multicam.camera

import android.content.Context
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.os.Build
import android.util.Size
import kotlin.math.abs

/**
 * Reads every logical camera the device exposes and produces a [CameraInfo] for each,
 * plus the set of officially concurrent-capable camera-id combinations.
 */
class CameraEnumerator(context: Context) {

    private val cameraManager =
        context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

    /** Camera-id sets the hardware guarantees can stream together (API 30+); empty otherwise. */
    val concurrentCameraIds: Set<Set<String>> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching { cameraManager.concurrentCameraIds }.getOrDefault(emptySet())
        } else {
            emptySet()
        }

    private val concurrentMembers: Set<String> = concurrentCameraIds.flatten().toSet()

    /** Width of a 35mm full-frame sensor, used to compute equivalent focal length. */
    private val fullFrameWidthMm = 36f

    fun enumerate(targetTilePx: Size): List<CameraInfo> {
        val ids = runCatching { cameraManager.cameraIdList }.getOrDefault(emptyArray())
        // Pre-compute focal length ranking per facing for the fallback lens category.
        val focalByFacing = HashMap<String, MutableList<Float>>()
        val rawCharacteristics = ids.mapNotNull { id ->
            runCatching { id to cameraManager.getCameraCharacteristics(id) }.getOrNull()
        }
        rawCharacteristics.forEach { (_, chars) ->
            val facing = facingLabel(chars)
            focalLength(chars)?.let { focalByFacing.getOrPut(facing) { mutableListOf() }.add(it) }
        }

        return rawCharacteristics.map { (id, chars) ->
            val facing = facingLabel(chars)
            val focal = focalLength(chars)
            val sensorSize = chars.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
            val equiv = if (focal != null && sensorSize != null && sensorSize.width > 0f) {
                (focal * fullFrameWidthMm / sensorSize.width).toInt()
            } else {
                null
            }
            val category = lensCategory(
                equiv35 = equiv,
                focal = focal,
                sameFacingFocals = focalByFacing[facing].orEmpty(),
            )
            CameraInfo(
                cameraId = id,
                facingLabel = facing,
                lensCategory = category,
                focalLengthMm = focal,
                sensorSizeMm = sensorSize,
                equiv35Mm = equiv,
                hardwareLevelLabel = hardwareLevelLabel(chars),
                previewSize = choosePreviewSize(chars, targetTilePx),
                concurrentSupported = id in concurrentMembers,
            )
        }
    }

    private fun facingLabel(chars: CameraCharacteristics): String =
        when (chars.get(CameraCharacteristics.LENS_FACING)) {
            CameraMetadata.LENS_FACING_FRONT -> "Front"
            CameraMetadata.LENS_FACING_BACK -> "Back"
            CameraMetadata.LENS_FACING_EXTERNAL -> "External"
            else -> "Unknown"
        }

    private fun focalLength(chars: CameraCharacteristics): Float? =
        chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull()

    private fun lensCategory(equiv35: Int?, focal: Float?, sameFacingFocals: List<Float>): String {
        if (equiv35 != null) {
            return when {
                equiv35 < 24 -> "Ultrawide"
                equiv35 <= 55 -> "Wide"
                else -> "Tele"
            }
        }
        // Fallback: rank this lens against others facing the same way.
        if (focal != null && sameFacingFocals.size > 1) {
            val min = sameFacingFocals.min()
            val max = sameFacingFocals.max()
            return when {
                focal <= min -> "Ultrawide"
                focal >= max -> "Tele"
                else -> "Wide"
            }
        }
        return "Unknown"
    }

    private fun hardwareLevelLabel(chars: CameraCharacteristics): String =
        when (chars.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)) {
            CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY -> "Legacy"
            CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED -> "Limited"
            CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_FULL -> "Full"
            CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_3 -> "Level 3"
            CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_EXTERNAL -> "External"
            else -> "Unknown HW"
        }

    /**
     * Pick a modest preview size: smallest output (capped ~1280x720) that still covers the tile.
     * Smaller previews keep the per-camera footprint low, improving concurrent-open odds.
     */
    private fun choosePreviewSize(chars: CameraCharacteristics, targetTilePx: Size): Size {
        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val sizes = map?.getOutputSizes(SurfaceTexture::class.java)?.toList()
            ?: return Size(1280, 720)

        val maxArea = 1280 * 720
        val targetArea = (targetTilePx.width * targetTilePx.height).coerceAtLeast(1)

        val capped = sizes.filter { it.width * it.height <= maxArea }.ifEmpty { sizes }
        // Prefer the size whose area is closest to (but ideally >=) the tile area.
        return capped.minByOrNull { size ->
            val area = size.width * size.height
            val deficit = if (area >= targetArea) 0 else 1
            // Sort first by "covers the tile", then by closeness of area.
            deficit * 1_000_000_000L + abs(area - targetArea)
        } ?: capped.maxByOrNull { it.width * it.height } ?: Size(1280, 720)
    }
}
