package com.example.bigbrother.domain.usecase

import com.example.bigbrother.domain.model.FrameAnalysisResult
import com.example.bigbrother.domain.model.VideoFrame
import com.example.bigbrother.domain.repository.FrameProcessor

/**
 * Граница «камера → обработка кадра».
 *
 * Камера ничего не знает про ONNX, C++ и формат результата: она отдаёт
 * VideoFrame и получает готовый FrameAnalysisResult.
 */
class ForwardFrameToNativeUseCase(
    private val frameProcessor: FrameProcessor,
) {
    operator fun invoke(frame: VideoFrame): FrameAnalysisResult = frameProcessor.process(frame)

    fun statusMessage(): String? = frameProcessor.statusMessage

    val isReady: Boolean get() = frameProcessor.isReady
}
