# Готовые нативные библиотеки

Сюда кладутся уже собранные `.so`, если C++ собирается вне проекта:

```text
app/src/main/jniLibs/
├── arm64-v8a/libframe_bridge.so
├── armeabi-v7a/libframe_bridge.so
├── x86/libframe_bridge.so
└── x86_64/libframe_bridge.so
```

Имя библиотеки должно совпадать с `System.loadLibrary("frame_bridge")` в
`data/jni/NativeFrameBridge.kt`.

Сейчас папка пустая: C++-код и бинарники намеренно не включены.
Исходники будущего C++-слоя описаны в `app/src/main/cpp/README.md`.
