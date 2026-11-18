package com.example.bigbrother.presentation.camera

import androidx.lifecycle.ViewModel
import com.example.bigbrother.domain.model.VideoFrame
import com.example.bigbrother.domain.usecase.ForwardFrameToNativeUseCase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class CameraViewModel(
    private val forwardFrame: ForwardFrameToNativeUseCase,
    nativeBridgeReady: Boolean,
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        CameraUiState(nativeBridgeReady = nativeBridgeReady),
    )
    val uiState: StateFlow<CameraUiState> = _uiState.asStateFlow()

    fun onFrame(frame: VideoFrame) {
        forwardFrame(frame)
        _uiState.update { it.copy(framesSubmitted = it.framesSubmitted + 1) }
    }

    fun onCameraReady() {
        _uiState.update {
            it.copy(cameraStatus = "Камера активна", message = null)
        }
    }

    fun onCameraError(message: String) {
        _uiState.update {
            it.copy(cameraStatus = "Ошибка камеры", message = message)
        }
    }
}
