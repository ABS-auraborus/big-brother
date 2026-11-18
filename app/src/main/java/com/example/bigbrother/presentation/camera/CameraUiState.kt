package com.example.bigbrother.presentation.camera

data class CameraUiState(
    val cameraStatus: String = "Камера не запущена",
    val nativeBridgeReady: Boolean = false,
    val framesSubmitted: Long = 0,
    val message: String? = null,
)
