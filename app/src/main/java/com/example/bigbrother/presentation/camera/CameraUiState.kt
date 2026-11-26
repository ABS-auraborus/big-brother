package com.example.bigbrother.presentation.camera

import com.example.bigbrother.domain.model.FrameAnalysisResult

data class CameraUiState(
    val cameraStatus: String = "Камера не запущена",
    val nativeBridgeReady: Boolean = false,
    val bridgeMessage: String? = null,
    val framesAnalyzed: Long = 0,
    val lastInferenceMs: Long? = null,
    val lastResult: FrameAnalysisResult? = null,
    val message: String? = null,
) {
    val detectionCount: Int get() = lastResult?.detections?.size ?: 0
}
