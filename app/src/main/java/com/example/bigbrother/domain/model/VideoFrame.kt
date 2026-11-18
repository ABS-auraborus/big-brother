package com.example.bigbrother.domain.model

import java.nio.ByteBuffer

data class VideoFrame(
    val planes: List<Plane>,
    val width: Int,
    val height: Int,
    val rotationDegrees: Int,
    val format: Int,
    val timestampNs: Long,
) {
    data class Plane(
        val buffer: ByteBuffer,
        val rowStride: Int,
        val pixelStride: Int,
    )

    init {
        require(planes.isNotEmpty()) { "VideoFrame must contain at least one plane" }
        require(width > 0 && height > 0) { "Frame size must be positive" }
    }
}
