package dev.bridge.assistant.assistant

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.min

data class LassoPoint(val x: Float, val y: Float)
data class LassoRect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
}
data class LassoSelection(val crop: LassoRect, val points: List<LassoPoint>)

/** Converts view-space lasso points into source-bitmap coordinates. */
object LassoGeometry {
    private const val MIN_CROP_EDGE = 16
    private const val MIN_POLYGON_AREA = 256.0

    fun resolve(
        viewWidth: Int,
        viewHeight: Int,
        bitmapWidth: Int,
        bitmapHeight: Int,
        points: List<LassoPoint>
    ): LassoSelection? {
        if (viewWidth <= 0 || viewHeight <= 0 || bitmapWidth <= 0 || bitmapHeight <= 0) return null
        if (points.size < 3) return null

        val scale = min(viewWidth.toFloat() / bitmapWidth, viewHeight.toFloat() / bitmapHeight)
        if (!scale.isFinite() || scale <= 0f) return null
        val displayedWidth = bitmapWidth * scale
        val displayedHeight = bitmapHeight * scale
        val offsetX = (viewWidth - displayedWidth) / 2f
        val offsetY = (viewHeight - displayedHeight) / 2f
        val mapped = points.map { point ->
            LassoPoint(
                ((point.x - offsetX) / scale).coerceIn(0f, bitmapWidth.toFloat()),
                ((point.y - offsetY) / scale).coerceIn(0f, bitmapHeight.toFloat())
            )
        }
        if (mapped.distinct().size < 3 || polygonArea(mapped) < MIN_POLYGON_AREA) return null

        val left = floor(mapped.minOf { it.x }).toInt().coerceIn(0, bitmapWidth - 1)
        val top = floor(mapped.minOf { it.y }).toInt().coerceIn(0, bitmapHeight - 1)
        val right = ceil(mapped.maxOf { it.x }).toInt().coerceIn(left + 1, bitmapWidth)
        val bottom = ceil(mapped.maxOf { it.y }).toInt().coerceIn(top + 1, bitmapHeight)
        val crop = LassoRect(left, top, right, bottom)
        if (crop.width < MIN_CROP_EDGE || crop.height < MIN_CROP_EDGE) return null
        return LassoSelection(crop, mapped)
    }

    private fun polygonArea(points: List<LassoPoint>): Double {
        var twiceArea = 0.0
        for (index in points.indices) {
            val current = points[index]
            val next = points[(index + 1) % points.size]
            twiceArea += current.x.toDouble() * next.y - next.x.toDouble() * current.y
        }
        return kotlin.math.abs(twiceArea) / 2.0
    }
}
