# Модель дефектов ЛКП

## Текущая модель

```text
app/src/main/assets/models/best.onnx
```

- Формат: ONNX, Ultralytics YOLO26n (`task=detect`, `head=Detect`).
- Размер: ~9.4 MB.
- Метаданные из модели: `date=2026-11-24`, `version=8.4.216`, `imgsz=[640,640]`,
  `stride=32`, `end2end=False`.
- Вход: `images`, `[1, 3, 640, 640] float32` (RGB, NCHW, значения нормированы на `[0,1]`).
- Выход: `output0`, `[1, 12, 8400] float32` = `4 координаты + 8 классов` на anchor.
- Поскольку `end2end=False`, модель отдаёт **сырые** box-значения в координатах
  letterbox-изображения 640x640 и class logits. Препроцессинг, sigmoid, порог
  confidence и NMS делает C++ (`app/src/main/cpp/bigbrother.cpp`).
- При первом запуске Kotlin копирует файл в `filesDir/models/best.onnx`, откуда
  его открывает ONNX Runtime в C++ (assets напрямую недоступны нативному коду).

## Классы (порядок каналов 4..11)

| classId | Класс | Русское название в UI |
|---:|---|---|
| 0 | Orange Peel | Апельсиновая корка |
| 1 | Paint sagging | Потёки / провисание |
| 2 | Peel Off | Отслоение |
| 3 | bleeding | Проступ / проявление |
| 4 | bubbling | Пузыри |
| 5 | cissing | Сбегание / «рыбьи глаза» |
| 6 | cratering | Кратеры |
| 7 | wrinkle | Морщины |

Имена классов также продублированы в Kotlin:
`app/src/main/java/com/example/bigbrother/data/ai/ModelLabels.kt`.

## Как заменить модель

1. Положите новый `.onnx` в эту папку.
2. Если изменились размер входа, имя входа/выхода или число классов — обновите
   константы и имена в `bigbrother.cpp` (`kTargetSize`, `inputNames`,
   `outputNames`) и список в `ModelLabels.kt`.
3. Измените `MODEL_FILE_NAME` в `NativeFrameBridge.kt`, если имя файла отличается
   от `best.onnx`.
4. `AssetModelInstaller` копирует файл заново, если размер отличается.
