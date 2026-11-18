package com.example.bigbrother.data.camera

import android.content.Context
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.example.bigbrother.domain.model.VideoFrame
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class CameraXController(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val onFrame: (VideoFrame) -> Unit,
    private val onCameraReady: () -> Unit,
    private val onCameraError: (String) -> Unit,
) {

    private val analysisExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private var cameraProvider: ProcessCameraProvider? = null

    fun bind(previewView: PreviewView) {
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener(
            {
                try {
                    val provider = future.get()
                    cameraProvider = provider

                    val preview = Preview.Builder().build().also {
                        it.setSurfaceProvider(previewView.surfaceProvider)
                    }

                    val imageAnalysis = ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()
                        .also { it.setAnalyzer(analysisExecutor, ::analyze) }

                    provider.unbindAll()
                    provider.bindToLifecycle(
                        lifecycleOwner,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        imageAnalysis,
                    )
                    onCameraReady()
                } catch (e: Exception) {
                    onCameraError(e.message ?: "Не удалось запустить камеру")
                }
            },
            ContextCompat.getMainExecutor(context),
        )
    }

    fun release() {
        cameraProvider?.unbindAll()
        cameraProvider = null
        analysisExecutor.shutdown()
    }

    private fun analyze(image: ImageProxy) {
        try {
            onFrame(image.toVideoFrame())
        } catch (e: Exception) {
            onCameraError(e.message ?: "Ошибка обработки кадра")
        } finally {
            image.close()
        }
    }

    private fun ImageProxy.toVideoFrame(): VideoFrame = VideoFrame(
        planes = planes.map { plane ->
            VideoFrame.Plane(
                buffer = plane.buffer,
                rowStride = plane.rowStride,
                pixelStride = plane.pixelStride,
            )
        },
        width = width,
        height = height,
        rotationDegrees = imageInfo.rotationDegrees,
        format = format,
        timestampNs = imageInfo.timestamp,
    )
}
