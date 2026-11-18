package com.example.bigbrother.domain.repository

import com.example.bigbrother.domain.model.VideoFrame

interface FrameProcessor {
    val isReady: Boolean
    fun process(frame: VideoFrame)
}
