package com.example.bigbrother.di

import android.content.Context
import androidx.lifecycle.LifecycleOwner
import com.example.bigbrother.data.camera.CameraXController
import com.example.bigbrother.data.jni.NativeFrameBridge
import com.example.bigbrother.domain.model.VideoFrame
import com.example.bigbrother.domain.usecase.ForwardFrameToNativeUseCase
import com.example.bigbrother.presentation.camera.CameraViewModel

class AppContainer(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
) {

    private val frameProcessor = NativeFrameBridge(context = context.applicationContext)
    private val forwardFrame = ForwardFrameToNativeUseCase(frameProcessor)

    val cameraViewModelFactory: ViewModelFactory = ViewModelFactory {
        CameraViewModel(forwardFrame = forwardFrame)
    }

    fun createCameraController(
        onFrame: (VideoFrame) -> Unit,
        onCameraReady: () -> Unit,
        onCameraError: (String) -> Unit,
    ): CameraXController = CameraXController(
        context = context.applicationContext,
        lifecycleOwner = lifecycleOwner,
        onFrame = onFrame,
        onCameraReady = onCameraReady,
        onCameraError = onCameraError,
    )
}
