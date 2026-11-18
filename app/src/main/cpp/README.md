# C++-слой (пока пусто)

Здесь будет лежать **весь C++-код обработки видеокадра**. Сейчас в проекте нет
ни одного `.cpp`/`.h` файла и нет `CMakeLists.txt` — это сделано специально:
на текущем этапе реализуется только Kotlin-часть камеры.

## Что появится в этой папке позже

```text
app/src/main/cpp/
├── CMakeLists.txt              // описание сборки libframe_bridge.so
├── frame_bridge.cpp            // реализация JNI-функции
├── frame_bridge.h              // объявления внутренних C++-функций
└── (дополнительные .cpp/.h)   // алгоритмы работы с YUV-буферами
```

## Контракт с Kotlin

Kotlin-мост находится в:

```text
app/src/main/java/com/example/bigbrother/data/jni/NativeFrameBridge.kt
```

Он загружает библиотеку:

```kotlin
System.loadLibrary("frame_bridge")
```

и ожидает JNI-функцию с именем:

```text
Java_com_example_bigbrother_data_jni_NativeFrameBridge_nativeSubmitFrame
```

Сигнатура Kotlin-объявления:

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

C++ должен:

1. Получить `jbyte*`/`uint8_t*` для каждого plane через `env->GetDirectBufferAddress(planeN)`.
2. Работать с YUV_420_888 по `rowStride` и `pixelStride`.
3. Учесть `rotationDegrees`.
4. **Не хранить указатели после возврата из функции**: сразу после вызова
   Kotlin закрывает `ImageProxy`, и direct-буферы становятся недействительными.
   Если нужна асинхронная обработка — C++ обязан скопировать данные себе.
5. Вернуть `Int` (например, 0 = успех; конкретные коды — по договорённости).

## Как подключить сборку

Когда C++-код появится:

1. Установить NDK и CMake в Android SDK.
2. Создать `app/src/main/cpp/CMakeLists.txt`.
3. Добавить в `app/build.gradle.kts`:

```kotlin
android {
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
        }
    }
}
```

Альтернатива — положить готовые `.so` в `app/src/main/jniLibs/<abi>/`.

Пока библиотеки нет, `NativeFrameBridge.isReady == false`, приложение работает
без падения и просто не отправляет кадры в C++.
