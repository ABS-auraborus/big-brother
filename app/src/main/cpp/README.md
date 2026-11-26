# C++-слой (frame_bridge)

Этот слой отвечает за приём YUV-кадров из Kotlin, препроцессинг, инференс
ONNX-модели и возврат детекций дефектов ЛКП обратно в Kotlin.

## Что здесь находится

```text
app/src/main/cpp/
├── CMakeLists.txt        # сборка libframe_bridge.so
├── bigbrother.cpp        # JNI-мост, препроцессинг, ONNX Runtime, NMS
└── libs/                 # vendored-зависимости (OpenCV SDK, ONNX Runtime Android)
```

Собирается библиотека: `libframe_bridge.so`.

## Контракт JNI

Kotlin-мост: `app/src/main/java/com/example/bigbrother/data/jni/NativeFrameBridge.kt`.

| JNI-функция | Назначение |
|---|---|
| `Java_..._NativeFrameBridge_nativeInitModel(String modelPath): boolean` | Создаёт `Ort::Session` из файла модели во внутреннем хранилище. |
| `Java_..._NativeFrameBridge_nativeSubmitFrame(...13 args...): int` | Принимает 3 direct ByteBuffer плоскости YUV_420_888, выполняет препроцессинг + инференс + NMS. Возвращает число детекций или `< 0` при ошибке. |
| `Java_..._NativeFrameBridge_nativeCopyLatestDetections(): float[]` | Копирует последнюю детекцию в Kotlin как плоский массив `[x1,y1,x2,y2,score,classId, ...]`. |
| `Java_..._NativeFrameBridge_nativeGetLatestFrameTimestampNs(): long` | Timestamp кадра, к которому относится последний результат. |

Сигнатура `nativeSubmitFrame` (Kotlin):

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

## Правила работы с буферами

1. Для каждого plane C++ получает указатель через `env->GetDirectBufferAddress(...)`.
2. Перед чтением проверяется `GetDirectBufferCapacity` + `rowStride`/`pixelStride`.
3. YUV_420_888 собирается в I420, затем конвертируется в RGB (`cv::cvtColor`).
4. Учитывается `rotationDegrees` (0/90/180/270).
5. **Указатели нельзя хранить после возврата из JNI**: Kotlin закрывает
   `ImageProxy` сразу после синхронного вызова. Для async-обработки нужно копировать данные.
6. Кадр приводится к letterbox 640x640, нормализуется в `[0,1]`, раскладывается
   в NCHW-тензор и подаётся в ONNX Runtime (`images` → `output0`).

## Модель и постобработка

- Модель: `app/src/main/assets/models/best.onnx` (Ultralytics YOLO26n, detect).
- Вход: `[1, 3, 640, 640] float32`.
- Выход: `[1, 12, 8400] float32` = `4 box + 8 class scores` на 8400 anchor.
- Классы: Orange Peel, Paint sagging, Peel Off, bleeding, bubbling, cissing,
  cratering, wrinkle (8 классов; метаданные модели).
- Class-каналы — уже вероятности `[0,1]` (не logits): C++ сравнивает их напрямую
  с порогом confidence `0.50`, затем применяет NMS IoU `0.45`.
  Применять sigmoid повторно **нельзя**: фоновые `0.0` станут `0.5` и пройдут порог.
- Координаты детекций переводятся из letterbox 640x640 обратно в координаты
  **повёрнутого** RGB-кадра и clamp-ятся к границам изображения.

## Коды ошибок `nativeSubmitFrame`

| Код | Значение |
|---:|---|
| `>= 0` | число детекций после NMS |
| `-1` | модель не инициализирована |
| `-2` | некорректные размеры кадра |
| `-3` | недостаточный capacity direct-буфера |
| `-4` | `GetDirectBufferAddress` вернул null |
| `-5` | ошибка сборки I420 |
| `-6` | некорректный output tensor |
| `-7` | некорректная shape выходного тензора |
| `-8` | `Ort::Exception` во время Run |
| `-9` | прочее std::exception |

## Сборка

Сборка подключена в `app/build.gradle.kts` через `externalNativeBuild { cmake { ... } }`.
Нужны Android NDK и CMake (проект проверялся с NDK 28.2.13676358 и CMake 3.22.1).
Файл `gradle.properties` содержит `android.overridePathCheck=true` из-за не-ASCII
символов в пути Windows.
