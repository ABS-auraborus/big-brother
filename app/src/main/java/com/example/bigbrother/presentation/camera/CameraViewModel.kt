package com.example.bigbrother.presentation.camera

import android.os.SystemClock
import androidx.lifecycle.ViewModel
import com.example.bigbrother.domain.model.VideoFrame
import com.example.bigbrother.domain.usecase.ForwardFrameToNativeUseCase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Принимает кадры от камеры, запускает обработку и публикует результат для UI.
 *
 * Никакой обработки изображения здесь нет: только вызов use case, замер времени
 * и обновление состояния экрана.
 */
class CameraViewModel(
    private val forwardFrame: ForwardFrameToNativeUseCase,
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        CameraUiState(
            nativeBridgeReady = forwardFrame.isReady,
            bridgeMessage = forwardFrame.statusMessage(),
        ),
    )
    val uiState: StateFlow<CameraUiState> = _uiState.asStateFlow()

    fun onFrame(frame: VideoFrame) {
        val startedAtMs = SystemClock.elapsedRealtime()
        val result = forwardFrame(frame)
        val elapsedMs = SystemClock.elapsedRealtime() - startedAtMs

        _uiState.update { state ->
            state.copy(
                framesAnalyzed = state.framesAnalyzed + 1,
                lastInferenceMs = elapsedMs,
                lastResult = result,
                nativeBridgeReady = forwardFrame.isReady,
                bridgeMessage = forwardFrame.statusMessage(),
            )
        }
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
