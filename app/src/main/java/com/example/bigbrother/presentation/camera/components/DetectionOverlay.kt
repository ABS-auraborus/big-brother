package com.example.bigbrother.presentation.camera.components

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.bigbrother.domain.model.FrameAnalysisResult

/**
 * Рисует рамки детекций поверх камеры.
 *
 * C++ отдаёт координаты в системе повёрнутого кадра, а PreviewView использует
 * FILL_CENTER: картинка масштабируется так, чтобы занять весь View, и часть
 * картинки обрезается. Поэтому здесь считаем тот же масштаб и смещение:
 *
 *   scale   = max(viewW / imageW, viewH / imageH)
 *   offsetX = (viewW - imageW * scale) / 2
 *   offsetY = (viewH - imageH * scale) / 2
 */
@Composable
fun DetectionOverlay(
    result: FrameAnalysisResult?,
    modifier: Modifier = Modifier,
) {
    val imageWidth = result?.imageWidth ?: 0
    val imageHeight = result?.imageHeight ?: 0
    if (result == null || imageWidth <= 0 || imageHeight <= 0) return

    val textMeasurer = rememberTextMeasurer()

    Canvas(modifier = modifier) {
        val scale = maxOf(size.width / imageWidth, size.height / imageHeight)
        val offsetX = (size.width - imageWidth * scale) / 2f
        val offsetY = (size.height - imageHeight * scale) / 2f

        result.detections.forEachIndexed { index, detection ->
            val color = OVERLAY_COLORS[index % OVERLAY_COLORS.size]

            val left = detection.x1 * scale + offsetX
            val top = detection.y1 * scale + offsetY
            val width = detection.width * scale
            val height = detection.height * scale

            drawRect(
                color = color,
                topLeft = Offset(left, top),
                size = Size(width, height),
                style = Stroke(width = BORDER_WIDTH.dp.toPx()),
            )

            val label = "${detection.classId}: ${(detection.confidence * 100).toInt()}%"
            val labelStyle = TextStyle(color = Color.White, fontSize = LABEL_FONT_SIZE.sp)
            val measured = textMeasurer.measure(label, style = labelStyle)

            val labelTop = if (top - measured.size.height - LABEL_GAP.dp.toPx() >= 0f) {
                top - measured.size.height - LABEL_GAP.dp.toPx()
            } else {
                top + LABEL_GAP.dp.toPx()
            }

            drawRect(
                color = color.copy(alpha = 0.85f),
                topLeft = Offset(left, labelTop),
                size = Size(
                    measured.size.width + LABEL_PADDING.dp.toPx() * 2,
                    measured.size.height.toFloat() + LABEL_PADDING.dp.toPx(),
                ),
            )
            drawText(
                textLayoutResult = measured,
                color = Color.White,
                topLeft = Offset(
                    left + LABEL_PADDING.dp.toPx(),
                    labelTop + LABEL_PADDING.dp.toPx() / 2,
                ),
            )
        }
    }
}

private val OVERLAY_COLORS = listOf(
    Color(0xFFFF5252),
    Color(0xFFFFB300),
    Color(0xFF00E5FF),
    Color(0xFF76FF03),
    Color(0xFFE040FB),
    Color(0xFFFF7043),
    Color(0xFF40C4FF),
    Color(0xFFFFD54F),
)

private const val BORDER_WIDTH = 2f
private const val LABEL_FONT_SIZE = 12f
private const val LABEL_GAP = 4f
private const val LABEL_PADDING = 3f
