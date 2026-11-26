package com.example.bigbrother.data.ai

/**
 * Имена классов из метаданных best.onnx (Ultralytics YOLO26n).
 *
 * Порядок индексов совпадает с порядком классов на выходе модели:
 * classId = channelIndex - 4.
 */
object ModelLabels {

    private val LABELS = listOf(
        "Апельсиновая корка (Orange Peel)",
        "Потёки / провисание (Paint sagging)",
        "Отслоение (Peel Off)",
        "Проступ / проявление (bleeding)",
        "Пузыри (bubbling)",
        "Сбегание / «рыбьи глаза» (cissing)",
        "Кратеры (cratering)",
        "Морщины (wrinkle)",
    )

    fun forId(classId: Int): String =
        LABELS.getOrNull(classId) ?: "Неизвестный класс #$classId"

    val count: Int get() = LABELS.size
}
