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

class ScreenLasso(context: Context) : View(context) {
    private var screenshot: Bitmap? = null
    private val screenPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(20, 190, 230)
        strokeWidth = 5f * resources.displayMetrics.density
        style = Paint.Style.STROKE
    }
    private val trace = Path()
    private val coords = mutableListOf<LassoPoint>()
    private var displayRect = RectF()
    private var drawing = false
    private var completed = false

    fun setScreenshot(image: Bitmap?) {
        screenshot = image
        clearSelection()
        updateDisplayRect()
        invalidate()
    }

    private fun clearSelection() {
        trace.reset()
        coords.clear()
        drawing = false
        completed = false
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        updateDisplayRect()
        clearSelection()
    }

    private fun updateDisplayRect() {
        val source = screenshot
        if (source == null || width <= 0 || height <= 0 || source.width <= 0 || source.height <= 0) {
            displayRect.setEmpty()
            return
        }
        val scale = minOf(width.toFloat() / source.width, height.toFloat() / source.height)
        val w = source.width * scale
        val h = source.height * scale
        displayRect.set((width - w) / 2f, (height - h) / 2f, (width + w) / 2f, (height + h) / 2f)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.rgb(32, 35, 43))
        val source = screenshot ?: return
        if (displayRect.isEmpty) updateDisplayRect()
        if (!displayRect.isEmpty) canvas.drawBitmap(source, null, displayRect, screenPaint)
        if (coords.isNotEmpty()) canvas.drawPath(trace, linePaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (screenshot == null || displayRect.isEmpty) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (!displayRect.contains(event.x, event.y)) return false
                clearSelection()
                drawing = true
                coords.add(LassoPoint(event.x, event.y))
                trace.moveTo(event.x, event.y)
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!drawing) return false
                for (index in 0 until event.historySize) addPoint(event.getHistoricalX(index), event.getHistoricalY(index))
                addPoint(event.x, event.y)
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (!drawing) return false
                addPoint(event.x, event.y)
                drawing = false
                completed = coords.size >= 3
                if (completed) trace.close() else clearSelection()
                invalidate()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                clearSelection()
                invalidate()
                return true
            }
        }
        return drawing
    }

    private fun addPoint(x: Float, y: Float) {
        val clampedX = x.coerceIn(displayRect.left, displayRect.right)
        val clampedY = y.coerceIn(displayRect.top, displayRect.bottom)
        coords.add(LassoPoint(clampedX, clampedY))
        trace.lineTo(clampedX, clampedY)
    }

    fun selectedBitmap(): Bitmap? {
        val original = screenshot ?: return null
        if (!completed || drawing) return null
        val selection = LassoGeometry.resolve(width, height, original.width, original.height, coords) ?: return null
        val crop = selection.crop
        val path = Path().apply {
            selection.points.forEachIndexed { index, point ->
                val x = point.x - crop.left
                val y = point.y - crop.top
                if (index == 0) moveTo(x, y) else lineTo(x, y)
            }
            close()
        }
        return Bitmap.createBitmap(crop.width, crop.height, Bitmap.Config.ARGB_8888).also { output ->
            Canvas(output).apply {
                drawColor(Color.WHITE)
                clipPath(path)
                drawBitmap(original, -crop.left.toFloat(), -crop.top.toFloat(), screenPaint)
            }
        }
    }
}
