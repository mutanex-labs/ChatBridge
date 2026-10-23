package dev.bridge.assistant.assistant

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import kotlin.math.ceil
import kotlin.math.floor

class ScreenLasso(context: Context) : View(context) {
    private var screenshot: Bitmap? = null
    private val screenPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(20, 190, 230)
        strokeWidth = 5f * resources.displayMetrics.density
        style = Paint.Style.STROKE
    }
    private val trace = Path()
    private val coords = mutableListOf<Pair<Float, Float>>()
    private var displayRect = RectF()

    fun setScreenshot(image: Bitmap?) {
        screenshot = image
        trace.reset()
        coords.clear()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.rgb(32, 35, 43))
        val source = screenshot ?: return
        val scale = minOf(width.toFloat() / source.width, height.toFloat() / source.height)
        val w = source.width * scale
        val h = source.height * scale
        displayRect = RectF((width - w) / 2f, (height - h) / 2f, (width + w) / 2f, (height + h) / 2f)
        canvas.drawBitmap(source, null, displayRect, screenPaint)
        if (coords.isNotEmpty()) canvas.drawPath(trace, linePaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (screenshot == null) return false
        if (event.actionMasked == MotionEvent.ACTION_DOWN && !displayRect.contains(event.x, event.y)) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                coords.clear()
                trace.reset()
                trace.moveTo(event.x, event.y)
                coords.add(event.x to event.y)
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val x = event.x.coerceIn(displayRect.left, displayRect.right)
                val y = event.y.coerceIn(displayRect.top, displayRect.bottom)
                coords.add(x to y)
                trace.lineTo(x, y)
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP -> {
                trace.close()
                invalidate()
                return true
            }
        }
        return true
    }

    fun selectedBitmap(): Bitmap? {
        val original = screenshot ?: return null
        if (coords.size < 3 || displayRect.width() <= 0f || displayRect.height() <= 0f) return null
        val factorX = original.width / displayRect.width()
        val factorY = original.height / displayRect.height()
        val bitmapPoints = coords.map { ((it.first - displayRect.left) * factorX) to ((it.second - displayRect.top) * factorY) }
        val left = floor(bitmapPoints.minOf { it.first }).toInt().coerceIn(0, original.width - 1)
        val top = floor(bitmapPoints.minOf { it.second }).toInt().coerceIn(0, original.height - 1)
        val right = ceil(bitmapPoints.maxOf { it.first }).toInt().coerceIn(left + 1, original.width)
        val bottom = ceil(bitmapPoints.maxOf { it.second }).toInt().coerceIn(top + 1, original.height)
        if (right - left < 16 || bottom - top < 16) return null
        val path = Path()
        bitmapPoints.forEachIndexed { index, (x, y) ->
            if (index == 0) path.moveTo(x - left, y - top) else path.lineTo(x - left, y - top)
        }
        path.close()
        val output = Bitmap.createBitmap(right - left, bottom - top, Bitmap.Config.ARGB_8888)
        Canvas(output).apply {
            drawColor(Color.WHITE)
            clipPath(path)
            drawBitmap(original, -left.toFloat(), -top.toFloat(), screenPaint)
        }
        return output
    }
}
