package com.nayproductions.flightview.camera

import androidx.annotation.OptIn
import android.hardware.camera2.CameraCharacteristics
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlin.math.atan
import kotlin.math.max
import kotlin.math.min

/** What the projection needs to match the preview exactly. */
data class CameraOptics(val longSideFovDeg: Double, val imageAspect: Double) {
    companion object {
        val DEFAULT = CameraOptics(65.0, 4.0 / 3.0)
    }
}

/**
 * Rear camera preview, aspect-fill (FILL_CENTER), 4:3 stream, no zoom.
 * Reports the stream's field of view computed from the sensor's physical size and focal length.
 */
@Composable
fun CameraPreview(modifier: Modifier = Modifier, onOptics: (CameraOptics) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            implementationMode = PreviewView.ImplementationMode.PERFORMANCE
            importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
    }

    DisposableEffect(lifecycleOwner) {
        val providerFuture = ProcessCameraProvider.getInstance(context)
        var provider: ProcessCameraProvider? = null
        providerFuture.addListener({
            provider = providerFuture.get()
            val preview = Preview.Builder()
                .setResolutionSelector(
                    ResolutionSelector.Builder()
                        .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                        .build(),
                )
                .build()
                .also { it.setSurfaceProvider(previewView.surfaceProvider) }
            runCatching {
                provider?.unbindAll()
                val camera = provider?.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview)
                camera?.cameraControl?.setZoomRatio(1f)
                camera?.cameraInfo?.let { info ->
                    val res = preview.resolutionInfo?.resolution
                    val aspect = if (res != null && min(res.width, res.height) > 0) {
                        max(res.width, res.height).toDouble() / min(res.width, res.height)
                    } else {
                        4.0 / 3.0
                    }
                    onOptics(CameraOptics(longSideFov(info, aspect) ?: CameraOptics.DEFAULT.longSideFovDeg, aspect))
                }
            }
        }, ContextCompat.getMainExecutor(context))
        onDispose { provider?.unbindAll() }
    }

    AndroidView(factory = { previewView }, modifier = modifier)
}

/**
 * FOV along the stream's long side. The stream is a crop of the active sensor array: for a
 * 4:3 stream on a 4:3 sensor the long side uses the full sensor width.
 */
@OptIn(ExperimentalCamera2Interop::class)
private fun longSideFov(info: CameraInfo, streamAspect: Double): Double? {
    val c2 = Camera2CameraInfo.from(info)
    val size = c2.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE) ?: return null
    val focal = c2.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull()
        ?: return null
    val sensorLong = max(size.width, size.height).toDouble()
    val sensorShort = min(size.width, size.height).toDouble()
    val sensorAspect = sensorLong / sensorShort
    // A wider stream than the sensor crops the short side; a narrower one crops the long side.
    val usedLong = if (streamAspect >= sensorAspect) sensorLong else sensorShort * streamAspect
    return Math.toDegrees(2 * atan(usedLong / (2 * focal)))
}
