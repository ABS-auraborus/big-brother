package com.example.bigbrother.domain.repository

import com.example.bigbrother.domain.model.FrameAnalysisResult
import com.example.bigbrother.domain.model.VideoFrame

/**
 * Место, куда камера отдаёт каждый кадр.
 *
 * Реализация синхронно обрабатывает буферы и возвращает результат,
 * чтобы вызывающий мог сразу обновить UI. [statusMessage] описывает причину,
 * по которой обработка пока недоступна (null = всё готово).
 */
interface FrameProcessor {
    val isReady: Boolean
    val statusMessage: String?
    fun process(frame: VideoFrame): FrameAnalysisResult
}
