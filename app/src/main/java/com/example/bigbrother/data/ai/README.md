# Kotlin-слой, связанный с ИИ

Здесь лежит Kotlin-код, который подготавливает модель и её метаданные для
нативного инференса. Сам инференс выполняется в C++ (`app/src/main/cpp/bigbrother.cpp`).

```text
data/ai/
├── AssetModelInstaller.kt   # копирует .onnx из assets в filesDir/models
└── ModelLabels.kt           # имена 8 классов из метаданных best.onnx
```

## AssetModelInstaller

`ONNX Runtime` в C++ открывает модель по пути в файловой системе. Android assets
таким путём не являются, поэтому при первом кадре `NativeFrameBridge` вызывает
`AssetModelInstaller.install("models/best.onnx", "best.onnx")`, который копирует
файл в `context.filesDir/models/best.onnx`. При последующих запусках копирование
пропускается, если размер файла совпадает с размером asset.

## ModelLabels

Список из 8 классов дефектов ЛКП в том порядке, в котором они закодированы в
выходном тензоре модели (каналы 4..11). Используется в UI для подписей рамок и
списка детекций. При замене модели с другим набором классов обновите этот файл.

## Откуда берутся результаты

Kotlin не запускает модель через ONNX Runtime Java API. Поток такой:

```text
VideoFrame → NativeFrameBridge.process() → JNI nativeSubmitFrame(...)
           ← float[] детекций ← nativeCopyLatestDetections()
           → FrameAnalysisResult → CameraUiState → UI
```

Домен и UI не зависят от ONNX: они работают с `Detection` и `FrameAnalysisResult`.
