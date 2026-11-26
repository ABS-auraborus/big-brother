package com.example.bigbrother.domain.model

/**
 * Одна детекция дефекта ЛКП.
 *
 * Координаты заданы в пикселях **повёрнутого** кадра (после учёта
 * rotationDegrees), начало — левый верхний угол. C++ упаковывает каждую
 * детекцию в 6 float-значений: x1, y1, x2, y2, score, classId.
 */
data class Detection(
    val x1: Float,
    val y1: Float,
    val x2: Float,
    val y2: Float,
    val confidence: Float,
    val classId: Int,
) {
    val width: Float get() = x2 - x1
    val height: Float get() = y2 - y1
}
