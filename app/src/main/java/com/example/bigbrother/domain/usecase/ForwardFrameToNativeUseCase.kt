package com.example.bigbrother.domain.usecase

import com.example.bigbrother.domain.model.VideoFrame
import com.example.bigbrother.domain.repository.FrameProcessor

class ForwardFrameToNativeUseCase(
    private val frameProcessor: FrameProcessor,
) {
    operator fun invoke(frame: VideoFrame) {
        frameProcessor.process(frame)
    }
}
