package com.example.bigbrother.data.jni

import com.example.bigbrother.domain.model.VideoFrame
import com.example.bigbrother.domain.repository.FrameProcessor
import java.nio.ByteBuffer
class NativeFrameBridge : FrameProcessor {

    private val nativeAvailable: Boolean = try {
        System.loadLibrary(LIBRARY_NAME)
        true
    } catch (e: LinkageError) {
        false
    }

    override val isReady: Boolean get() = nativeAvailable

    override fun process(frame: VideoFrame) {
        if (!nativeAvailable) return

        val planes = frame.planes
        if (planes.size < YUV_PLANE_COUNT) return
        if (planes.take(YUV_PLANE_COUNT).any { !it.buffer.isDirect }) return

        nativeSubmitFrame(
            planes[0].buffer, planes[0].rowStride, planes[0].pixelStride,
            planes[1].buffer, planes[1].rowStride, planes[1].pixelStride,
            planes[2].buffer, planes[2].rowStride, planes[2].pixelStride,
            frame.width,
            frame.height,
            frame.rotationDegrees,
            frame.timestampNs,
        )
    }

    /**
     * На C++ каждый ByteBuffer преобразуется в указатель через GetDirectBufferAddress.
     * Вызов синхронный: C++ обязан использовать данные до возврата из функции,
     * потому что после этого CameraX закрывает ImageProxy и буферы становятся
     * недействительными.
     */
    private external fun nativeSubmitFrame(
        plane0: ByteBuffer,
        rowStride0: Int,
        pixelStride0: Int,
        plane1: ByteBuffer,
        rowStride1: Int,
        pixelStride1: Int,
        plane2: ByteBuffer,
        rowStride2: Int,
        pixelStride2: Int,
        width: Int,
        height: Int,
        rotationDegrees: Int,
        timestampNs: Long,
    ): Int

    private companion object {
        const val LIBRARY_NAME = "frame_bridge"
        const val YUV_PLANE_COUNT = 3
    }
}
