package com.example.bigbrother.presentation.camera

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.bigbrother.data.ai.ModelLabels
import com.example.bigbrother.data.camera.CameraXController
import com.example.bigbrother.presentation.camera.components.CameraPreview
import com.example.bigbrother.presentation.camera.components.DetectionOverlay

/**
 * Экран сканирования ЛКП: камера + рамки дефектов + статус обработки.
 */
@Composable
fun CameraScreen(
    viewModel: CameraViewModel,
    cameraController: CameraXController,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.CAMERA,
            ) == PackageManager.PERMISSION_GRANTED,
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { hasCameraPermission = it },
    )

    LaunchedEffect(Unit) {
        if (!hasCameraPermission) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    Scaffold { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "Наведите камеру на лакокрасочное покрытие",
                style = MaterialTheme.typography.titleMedium,
            )

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) {
                if (hasCameraPermission) {
                    CameraPreview(
                        controller = cameraController,
                        modifier = Modifier.fillMaxSize(),
                    )
                    DetectionOverlay(
                        result = uiState.lastResult,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    PermissionPlaceholder(
                        onRequest = { permissionLauncher.launch(Manifest.permission.CAMERA) },
                    )
                }
            }

            StatusPanel(state = uiState)
        }
    }
}

@Composable
private fun PermissionPlaceholder(onRequest: () -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(text = "Для сканирования нужен доступ к камере")
            Button(onClick = onRequest) {
                Text(text = "Разрешить доступ")
            }
        }
    }
}

@Composable
private fun StatusPanel(state: CameraUiState) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(text = state.cameraStatus, style = MaterialTheme.typography.bodyMedium)

        val bridgeText = when {
            state.bridgeMessage != null -> "Обработка: ${state.bridgeMessage}"
            state.nativeBridgeReady -> "Обработка: модель загружена, C++-мост активен"
            else -> "Обработка: подготовка..."
        }
        Text(
            text = bridgeText,
            style = MaterialTheme.typography.bodySmall,
            color = if (state.bridgeMessage != null) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurface
            },
        )

        Text(
            text = "Кадров обработано: ${state.framesAnalyzed}" +
                (state.lastInferenceMs?.let { " · последний: $it мс" } ?: ""),
            style = MaterialTheme.typography.bodySmall,
        )

        Text(
            text = "Дефектов на последнем кадре: ${state.detectionCount}",
            style = MaterialTheme.typography.bodySmall,
        )

        state.lastResult?.detections?.take(MAX_VISIBLE_DETECTIONS)?.forEach { detection ->
            Text(
                text = "• ${ModelLabels.forId(detection.classId)} — " +
                    "${(detection.confidence * 100).toInt()}%",
                style = MaterialTheme.typography.bodySmall,
            )
        }

        state.message?.let { message ->
            Text(
                text = message,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

private const val MAX_VISIBLE_DETECTIONS = 3
