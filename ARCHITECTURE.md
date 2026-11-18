# Архитектура проекта (этап: камера → C++ buffer pointer)

На текущем этапе в проекте есть **только Kotlin-код камеры** и мост передачи
видеобуфера в будущий C++-слой. C++-код и ИИ-модели **не написаны**, для них
созданы пустые директории с подробными контрактами.

## Поток данных

```text
CameraX PreviewView
        │
        │ ImageAnalysis (YUV_420_888)
        ▼
ImageProxy → VideoFrame(planes: List<direct ByteBuffer>, strides, rotation, timestamp)
        │
        ▼
ForwardFrameToNativeUseCase
        │
        ▼
NativeFrameBridge (Kotlin/JNI)
        │
        │ ByteBuffer → GetDirectBufferAddress → uint8_t* (в C++)
        ▼
[C++-слой пока не существует]
```

Главное: Kotlin **не копирует пиксели**. Он передаёт в JNI сами direct-буферы
и параметры их раскладки (`rowStride`, `pixelStride`). На C++-стороне из
`ByteBuffer` получаются сырые указатели.

`ImageProxy` закрывается сразу после синхронного вызова JNI, поэтому C++ обязан
использовать данные в течение вызова или скопировать их себе.

---

## Полное дерево

```text
app/src/main/
├── java/com/example/bigbrother/
│   ├── MainActivity.kt
│   ├── di/
│   │   ├── AppContainer.kt
│   │   └── ViewModelFactory.kt
│   ├── domain/
│   │   ├── model/VideoFrame.kt
│   │   ├── repository/FrameProcessor.kt
│   │   └── usecase/ForwardFrameToNativeUseCase.kt
│   ├── data/
│   │   ├── camera/CameraXController.kt
│   │   ├── jni/NativeFrameBridge.kt
│   │   └── ai/README.md
│   └── presentation/
│       ├── camera/
│       │   ├── CameraScreen.kt
│       │   ├── CameraUiState.kt
│       │   ├── CameraViewModel.kt
│       │   └── components/CameraPreview.kt
│       └── theme/AppTheme.kt
├── cpp/README.md
├── jniLibs/README.md
└── assets/models/README.md
```

---

## Что в каждой папке

### `java/com/example/bigbrother/`

| Путь | Назначение |
|---|---|
| `MainActivity.kt` | Тонкая Activity. Создаёт `AppContainer`, получает `CameraViewModel`, создаёт `CameraXController`, связывает их и показывает `CameraScreen`. В `onDestroy()` освобождает камеру. |
| `di/AppContainer.kt` | Ручной DI. Создаёт `NativeFrameBridge`, `ForwardFrameToNativeUseCase`, фабрику ViewModel и `CameraXController` с колбэками. |
| `di/ViewModelFactory.kt` | Универсальная фабрика `ViewModelProvider.Factory`, чтобы не подключать Hilt. |
| `domain/model/VideoFrame.kt` | Чистая модель одного кадра: список plane-буферов, размер, поворот, формат, timestamp. |
| `domain/repository/FrameProcessor.kt` | Контракт «куда отдавать кадр». Пока реализуется только JNI-мостом. Позже — ИИ-обработчиком. |
| `domain/usecase/ForwardFrameToNativeUseCase.kt` | Тонкий use case, который передаёт кадр в `FrameProcessor`. Граница между камерой и обработкой. |
| `data/camera/CameraXController.kt` | Вся CameraX-логика: preview, `ImageAnalysis`, `STRATEGY_KEEP_ONLY_LATEST`, преобразование `ImageProxy → VideoFrame`, вызов `onFrame`, `image.close()`, `release()`. |
| `data/jni/NativeFrameBridge.kt` | Kotlin-стона моста в C++. Загружает `libframe_bridge.so`, объявляет `external nativeSubmitFrame(...)`. Если библиотеки нет, `isReady = false` и кадры не отправляются — приложение не падает. |
| `data/ai/README.md` | Зарезервированная директория под будущий Kotlin-код ИИ. Сейчас нет ни одного файла с ИИ-логикой. |
| `presentation/camera/CameraScreen.kt` | Экран: запрос разрешения, preview камеры, статус подключения C++ и счётчик переданных кадров. |
| `presentation/camera/CameraUiState.kt` | Состояние UI: статус камеры, готовность моста, число кадров, сообщение об ошибке. |
| `presentation/camera/CameraViewModel.kt` | Принимает кадры, вызывает use case, обновляет счётчик. Никакой обработки изображения. |
| `presentation/camera/components/CameraPreview.kt` | `AndroidView` с `PreviewView`. Единственный мост Android View → Compose. |
| `presentation/theme/AppTheme.kt` | Минимальная Material3-тема. |

### `app/src/main/cpp/`

Пустая директория с `README.md`. Здесь будет:

```text
CMakeLists.txt        // сборка libframe_bridge.so
frame_bridge.cpp      // реализация JNI-функции
frame_bridge.h        // объявления
```

Контракт JNI и правила работы с `GetDirectBufferAddress` подробно описаны в
`app/src/main/cpp/README.md`.

### `app/src/main/jniLibs/`

Пустая директория с `README.md`. Сюда позже кладутся готовые `.so` по ABI:
`arm64-v8a`, `armeabi-v7a`, `x86`, `x86_64`.

### `app/src/main/assets/models/`

Пустая директория с `README.md`. Здесь будут лежать файлы моделей
(`.tflite`, `.onnx` и т.д.) и метаданные версии модели.

---

## JNI-контракт

Kotlin загружает библиотеку `frame_bridge` и вызывает:

```kotlin
private external fun nativeSubmitFrame(
    plane0: ByteBuffer, rowStride0: Int, pixelStride0: Int,
    plane1: ByteBuffer, rowStride1: Int, pixelStride1: Int,
    plane2: ByteBuffer, rowStride2: Int, pixelStride2: Int,
    width: Int, height: Int,
    rotationDegrees: Int,
    timestampNs: Long,
): Int
```

Ожидаемое имя C++-функции:

```text
Java_com_example_bigbrother_data_jni_NativeFrameBridge_nativeSubmitFrame
```