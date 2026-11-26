# Архитектура проекта (этап: камера → C++/ONNX → UI)

Проект показывает камеру, анализирует видеокадры в C++ с ONNX-моделью и
отображает обнаруженные дефекты ЛКП поверх preview.

## Поток данных

```text
CameraX PreviewView (UI preview)
        │
        │ ImageAnalysis (YUV_420_888, ~4 fps throttling)
        ▼
CameraXController.analyze()
  ImageProxy → VideoFrame(3 direct ByteBuffer, strides, rotation, timestamp)
        │
        ▼
CameraViewModel.onFrame()  (analysisExecutor thread)
        │
        ▼
ForwardFrameToNativeUseCase
        │
        ▼
NativeFrameBridge.process()
  ├─ ensureInitialized(): AssetModelInstaller копирует best.onnx в filesDir
  ├─ nativeInitModel(path)
  ├─ nativeSubmitFrame(y,u,v, strides, size, rotation, ts)  ──JNI──► C++
  │                                                                  │
  │                              YUV/I420 → RGB → rotate → letterbox 640x640
  │                              NCHW float32 → ONNX Runtime Run
  │                              output [1,12,8400] → sigmoid → threshold 0.50 → NMS 0.45
  │                              boxes → coordinates in rotated image space
  │                                                                  │
  ├─ nativeCopyLatestDetections() ◄──────────── flat float[] ────────┘
  └─ FrameAnalysisResult(detections, imageWidth, imageHeight, timestamp)
        │
        ▼
CameraViewModel обновляет CameraUiState (StateFlow)
        │
        ▼
CameraScreen: CameraPreview + DetectionOverlay + StatusPanel
```

Ключевые свойства:

- **Без копирования пикселей в Kotlin**: в JNI уходят те же direct ByteBuffer,
  указатели берутся через `GetDirectBufferAddress`.
- **Синхронный JNI-вызов**: C++ должен использовать/скопировать данные до
  возврата; `ImageProxy` закрывается в `finally` сразу после `onFrame`.
- **Throttling**: `CameraXController` отправляет не каждый кадр, а раз в ~250 мс,
  чтобы не забивать CPU инференсом.
- **Безопасная деградация**: если модель/библиотека недоступны, мост возвращает
  пустой `FrameAnalysisResult`, UI показывает причину в статусе.

## Полное дерево

```text
app/src/main/
├── java/com/example/bigbrother/
│   ├── MainActivity.kt
│   ├── di/
│   │   ├── AppContainer.kt
│   │   └── ViewModelFactory.kt
│   ├── domain/
│   │   ├── model/
│   │   │   ├── Detection.kt
│   │   │   ├── FrameAnalysisResult.kt
│   │   │   └── VideoFrame.kt
│   │   ├── repository/FrameProcessor.kt
│   │   └── usecase/ForwardFrameToNativeUseCase.kt
│   ├── data/
│   │   ├── ai/
│   │   │   ├── AssetModelInstaller.kt
│   │   │   ├── ModelLabels.kt
│   │   │   └── README.md
│   │   ├── camera/CameraXController.kt
│   │   └── jni/NativeFrameBridge.kt
│   └── presentation/
│       ├── camera/
│       │   ├── CameraScreen.kt
│       │   ├── CameraUiState.kt
│       │   ├── CameraViewModel.kt
│       │   └── components/
│       │       ├── CameraPreview.kt
│       │       └── DetectionOverlay.kt
│       └── theme/AppTheme.kt
├── cpp/
│   ├── CMakeLists.txt
│   ├── bigbrother.cpp
│   ├── README.md
│   └── libs/                      # vendored OpenCV + ONNX Runtime
├── assets/models/
│   ├── best.onnx
│   └── README.md
└── jniLibs/
    └── README.md
```

## Что в каждой папке

| Путь | Назначение |
|---|---|
| `MainActivity.kt` | Тонкая Activity: создаёт `AppContainer`, `CameraViewModel`, `CameraXController`, показывает `CameraScreen`, освобождает камеру в `onDestroy()`. |
| `di/AppContainer.kt` | Ручной DI: создаёт `NativeFrameBridge(context)`, `ForwardFrameToNativeUseCase`, фабрику VM и `CameraXController`. |
| `di/ViewModelFactory.kt` | Минимальная фабрика `ViewModelProvider.Factory`. |
| `domain/model/VideoFrame.kt` | Кадр камеры: 3 plane ByteBuffer + strides + размер + rotation + timestamp. |
| `domain/model/Detection.kt` | Одна детекция: `x1,y1,x2,y2,confidence,classId` в координатах повёрнутого кадра. |
| `domain/model/FrameAnalysisResult.kt` | Результат кадра: список детекций + размер повёрнутого изображения + timestamp. |
| `domain/repository/FrameProcessor.kt` | Контракт обработчика кадра: `isReady`, `statusMessage`, `process()`. |
| `domain/usecase/ForwardFrameToNativeUseCase.kt` | Граница камера→обработка: проксирует кадр и статус в `FrameProcessor`. |
| `data/camera/CameraXController.kt` | CameraX Preview + ImageAnalysis, throttling, `ImageProxy→VideoFrame`, `image.close()`. |
| `data/jni/NativeFrameBridge.kt` | JNI-мост: загружает `frame_bridge`, инициализирует ONNX-модель, вызывает native-функции, парсит `float[]` детекций. |
| `data/ai/AssetModelInstaller.kt` | Копирует `best.onnx` из assets в `filesDir/models` для ONNX Runtime. |
| `data/ai/ModelLabels.kt` | Читаемые имена 8 классов дефектов. |
| `presentation/camera/CameraScreen.kt` | Compose-экран: permission, preview, overlay, статус-панель. |
| `presentation/camera/CameraUiState.kt` | UI state: статус камеры/моста, счётчик кадров, время инференса, последний результат. |
| `presentation/camera/CameraViewModel.kt` | Вызывает use case, меряет время, публикует `StateFlow`. |
| `presentation/camera/components/CameraPreview.kt` | `AndroidView` с `PreviewView`. |
| `presentation/camera/components/DetectionOverlay.kt` | Canvas-оверлей: переводит box-координаты модели в координаты PreviewView с учётом `FILL_CENTER`. |
| `presentation/theme/AppTheme.kt` | Material3 тема. |
| `cpp/bigbrother.cpp` | YUV→RGB→letterbox→NCHW→ONNX Runtime→NMS→JNI output. |
| `cpp/CMakeLists.txt` | Сборка `libframe_bridge.so`, линковка OpenCV + ONNX Runtime. |
| `assets/models/best.onnx` | Модель YOLO26n detect (8 классов дефектов ЛКП). |
| `jniLibs/README.md` | Запасная директория для prebuilt `.so`, если native собирается вне Gradle. |

## JNI-контракт

```kotlin
private external fun nativeInitModel(modelPath: String): Boolean

private external fun nativeSubmitFrame(
    plane0: ByteBuffer, rowStride0: Int, pixelStride0: Int,
    plane1: ByteBuffer, rowStride1: Int, pixelStride1: Int,
    plane2: ByteBuffer, rowStride2: Int, pixelStride2: Int,
    width: Int, height: Int,
    rotationDegrees: Int,
    timestampNs: Long,
): Int

private external fun nativeCopyLatestDetections(): FloatArray?
private external fun nativeGetLatestFrameTimestampNs(): Long
```

Возврат `nativeSubmitFrame`: `>=0` — число детекций, `<0` — код ошибки.
Формат `FloatArray`: каждые 6 значений = `x1, y1, x2, y2, score, classId`,
координаты в пикселях **повёрнутого** кадра.

## Проверка

Верифицировано в этой среде:

- `:app:assembleDebug` — `BUILD SUCCESSFUL` (NDK 28.2.13676358, CMake 3.22.1).
- `llvm-nm` показывает экспорт 4 JNI-функций из `libframe_bridge.so`.
- `app-debug.apk` содержит `assets/models/best.onnx` и `libframe_bridge.so`/`libonnxruntime.so` для ABI.
- Python `onnxruntime` smoke-check: вход `[1,3,640,640]`, выход `[1,12,8400]`, CPU forward на нулевом тензоре ~89 мс на хосте (на устройстве будет другое время).

Не верифицировано: реальная работа на физическом Android-устройстве/эмуляторе в
этой сессии — ADB-устройств не обнаружено.
