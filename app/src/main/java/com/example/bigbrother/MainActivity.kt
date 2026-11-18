package com.example.bigbrother

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.ViewModelProvider
import com.example.bigbrother.data.camera.CameraXController
import com.example.bigbrother.di.AppContainer
import com.example.bigbrother.presentation.camera.CameraScreen
import com.example.bigbrother.presentation.camera.CameraViewModel
import com.example.bigbrother.presentation.theme.AppTheme

class MainActivity : ComponentActivity() {

    private lateinit var appContainer: AppContainer
    private var cameraController: CameraXController? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        appContainer = AppContainer(context = this, lifecycleOwner = this)
        val viewModel = ViewModelProvider(
            this,
            appContainer.cameraViewModelFactory,
        )[CameraViewModel::class.java]

        cameraController = appContainer.createCameraController(
            onFrame = viewModel::onFrame,
            onCameraReady = viewModel::onCameraReady,
            onCameraError = viewModel::onCameraError,
        )

        setContent {
            AppTheme {
                CameraScreen(
                    viewModel = viewModel,
                    cameraController = requireNotNull(cameraController),
                )
            }
        }
    }

    override fun onDestroy() {
        cameraController?.release()
        cameraController = null
        super.onDestroy()
    }
}
