package com.example.bigbrother.domain.model

/**
 * Результат обработки одного кадра.
 *
 * [imageWidth]/[imageHeight] — размер уже повёрнутого изображения, в системе
 * координат которого приходят [detections]. Эти размеры нужны UI, чтобы
 * спроектировать прямоугольники на PreviewView (у которого FILL_CENTER
 * обрезает часть картинки).
 */
data class FrameAnalysisResult(
    val detections: List<Detection>,
    val imageWidth: Int,
    val imageHeight: Int,
    val timestampNs: Long,
)
