package com.example.bigbrother.data.jni

import android.content.Context
import com.example.bigbrother.data.ai.AssetModelInstaller
import com.example.bigbrother.domain.model.Detection
import com.example.bigbrother.domain.model.FrameAnalysisResult
import com.example.bigbrother.domain.model.VideoFrame
import com.example.bigbrother.domain.repository.FrameProcessor
import java.nio.ByteBuffer

/**
 * Kotlin-стона моста в C++ / ONNX Runtime.
 *
 * Жизненный цикл:
 *  1. System.loadLibrary("frame_bridge") — загружает libframe_bridge.so.
 *  2. ensureInitialized() — один раз копирует best.onnx из assets в filesDir
 *     и вызывает C++ nativeInitModel(path).
 *  3. process(frame) — синхронно передаёт direct-буферы в nativeSubmitFrame,
 *     затем забирает результат через nativeCopyLatestDetections.
 *
 * Если библиотеки или модели нет, мост не падает: он возвращает пустой результат
 * с корректной геометрией, а причину можно узнать через [statusMessage].
 */
class NativeFrameBridge(context: Context) : FrameProcessor {

    private val appContext = context.applicationContext
    private val modelInstaller = AssetModelInstaller(appContext)

    private val nativeAvailable: Boolean = try {
        System.loadLibrary(LIBRARY_NAME)
        true
    } catch (e: LinkageError) {
        false
    }

    @Volatile
    private var modelInitialized = false

    @Volatile
    private var lastErrorCode = 0

    @Volatile
    private var loadErrorMessage: String? = null

    override val isReady: Boolean get() = nativeAvailable && modelInitialized

    /** Текущее состояние для статус-панели (null = всё хорошо). */
    override val statusMessage: String? get() = when {
        !nativeAvailable -> "Не загружена библиотека lib$LIBRARY_NAME.so"
        !modelInitialized -> loadErrorMessage ?: "Модель ещё не загружена"
        lastErrorCode != 0 -> errorDescription(lastErrorCode)
        else -> null
    }

    /**
     * Ленивая инициализация модели: вызывается из потока анализа камеры,
     * поэтому не блокирует главный поток при первом кадре.
     */
    @Synchronized
    fun ensureInitialized(): Boolean {
        if (!nativeAvailable) return false
        if (modelInitialized) return true

        return try {
            val modelFile = modelInstaller.install(MODEL_ASSET_PATH, MODEL_FILE_NAME)
            val ok = nativeInitModel(modelFile.absolutePath)
            if (ok) {
                modelInitialized = true
                loadErrorMessage = null
                true
            } else {
                loadErrorMessage = "ONNX Runtime не смог открыть модель ${modelFile.name}"
                false
            }
        } catch (t: Throwable) {
            loadErrorMessage = t.message ?: "Ошибка загрузки модели"
            false
        }
    }

    override fun process(frame: VideoFrame): FrameAnalysisResult {
        val (imageWidth, imageHeight) = frame.rotatedSize()

        if (!ensureInitialized()) return emptyResult(frame, imageWidth, imageHeight)

        val planes = frame.planes
        if (planes.size < YUV_PLANE_COUNT ||
            planes.take(YUV_PLANE_COUNT).any { !it.buffer.isDirect }
        ) {
            lastErrorCode = ERROR_BAD_INPUT
            return emptyResult(frame, imageWidth, imageHeight)
        }

        val count = nativeSubmitFrame(
            planes[0].buffer, planes[0].rowStride, planes[0].pixelStride,
            planes[1].buffer, planes[1].rowStride, planes[1].pixelStride,
            planes[2].buffer, planes[2].rowStride, planes[2].pixelStride,
            frame.width,
            frame.height,
            frame.rotationDegrees,
            frame.timestampNs,
        )

        if (count < 0) {
            lastErrorCode = count
            return emptyResult(frame, imageWidth, imageHeight)
        }
        lastErrorCode = 0

        val packed = nativeCopyLatestDetections() ?: return emptyResult(frame, imageWidth, imageHeight)
        val timestampNs = nativeGetLatestFrameTimestampNs().takeIf { it != 0L } ?: frame.timestampNs

        return FrameAnalysisResult(
            detections = parseDetections(packed),
            imageWidth = imageWidth,
            imageHeight = imageHeight,
            timestampNs = timestampNs,
        )
    }

    private fun parseDetections(packed: FloatArray): List<Detection> {
        if (packed.isEmpty() || packed.size % DETECTION_FLOATS != 0) return emptyList()
        return packed.asList().chunked(DETECTION_FLOATS).mapNotNull { values ->
            if (values.size != DETECTION_FLOATS) return@mapNotNull null
            Detection(
                x1 = values[0],
                y1 = values[1],
                x2 = values[2],
                y2 = values[3],
                confidence = values[4],
                classId = values[5].toInt(),
            )
        }
    }

    private fun emptyResult(frame: VideoFrame, width: Int, height: Int) = FrameAnalysisResult(
        detections = emptyList(),
        imageWidth = width,
        imageHeight = height,
        timestampNs = frame.timestampNs,
    )

    private fun VideoFrame.rotatedSize(): Pair<Int, Int> {
        val angle = ((rotationDegrees % 360) + 360) % 360
        return if (angle == 90 || angle == 270) height to width else width to height
    }

    private fun errorDescription(code: Int): String = when (code) {
        ERROR_NO_MODEL -> "Модель не инициализирована"
        ERROR_BAD_DIMENSIONS -> "Некорректный размер кадра"
        ERROR_BAD_CAPACITY -> "Недостаточный размер буфера кадра"
        ERROR_BAD_POINTER -> "Не удалось получить указатель на буфер"
        ERROR_BAD_I420 -> "Ошибка преобразования YUV → I420"
        ERROR_BAD_OUTPUT -> "Неожиданный формат выхода модели"
        ERROR_BAD_SHAPE -> "Некорректная форма выходного тензора"
        ERROR_INFERENCE -> "Ошибка инференса ONNX Runtime"
        ERROR_NATIVE -> "Внутренняя ошибка C++"
        ERROR_BAD_INPUT -> "Кадр не содержит три direct-буфера YUV"
        else -> "Неизвестная ошибка C++: $code"
    }

    /**
     * Синхронный вызов: C++ получает указатели через GetDirectBufferAddress
     * и обязан использовать данные до возврата, т.к. CameraX сразу закрывает
     * ImageProxy и буферы становятся недействительными.
     */
    private external fun nativeInitModel(modelPath: String): Boolean

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

    private external fun nativeCopyLatestDetections(): FloatArray?

    private external fun nativeGetLatestFrameTimestampNs(): Long

    private companion object {
        const val LIBRARY_NAME = "frame_bridge"
        const val MODEL_ASSET_PATH = "models/best.onnx"
        const val MODEL_FILE_NAME = "best.onnx"
        const val YUV_PLANE_COUNT = 3
        const val DETECTION_FLOATS = 6

        const val ERROR_NO_MODEL = -1
        const val ERROR_BAD_DIMENSIONS = -2
        const val ERROR_BAD_CAPACITY = -3
        const val ERROR_BAD_POINTER = -4
        const val ERROR_BAD_I420 = -5
        const val ERROR_BAD_OUTPUT = -6
        const val ERROR_BAD_SHAPE = -7
        const val ERROR_INFERENCE = -8
        const val ERROR_NATIVE = -9
        const val ERROR_BAD_INPUT = -10
    }
}
