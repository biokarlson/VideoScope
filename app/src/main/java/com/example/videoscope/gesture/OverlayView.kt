package com.example.videoscope.gesture

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.os.SystemClock
import android.view.View
import kotlin.math.min

/** Полупрозрачный оверлей: сообщения (режим, пауза) и вертикальный индикатор. */
class OverlayView(context: Context) : View(context) {

    companion object {
        const val SIDE_LEFT = 0
        const val SIDE_RIGHT = 1
        const val SIDE_CENTER = 2
    }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val density = resources.displayMetrics.density

    private var message: String? = null
    private var messageUntil = 0L

    private var barFraction = 0f
    private var barLabel = ""
    private var barSide = SIDE_LEFT
    private var barUntil = 0L

    init {
        paint.textAlign = Paint.Align.CENTER
        paint.typeface = Typeface.MONOSPACE
    }

    fun showMessage(text: String, durationMs: Long = 1400L) {
        message = text
        messageUntil = SystemClock.uptimeMillis() + durationMs
        postInvalidateOnAnimation()
    }

    fun showBar(fraction: Float, label: String, side: Int) {
        barFraction = fraction.coerceIn(0f, 1f)
        barLabel = label
        barSide = side
        barUntil = SystemClock.uptimeMillis() + 1200L
        postInvalidateOnAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        val now = SystemClock.uptimeMillis()
        var animating = false

        val msg = message
        if (msg != null) {
            val left = messageUntil - now
            if (left > 0) {
                paint.style = Paint.Style.FILL
                paint.color = Color.WHITE
                paint.alpha = (255 * min(1f, left / 400f)).toInt()
                paint.textSize = 20f * density
                canvas.drawText(msg, width / 2f, height - 48f * density, paint)
                animating = true
            } else {
                message = null
            }
        }

        if (barUntil > now) {
            val a = (255 * min(1f, (barUntil - now) / 400f)).toInt()
            val cx = when (barSide) {
                SIDE_LEFT -> width * 0.1f
                SIDE_RIGHT -> width * 0.9f
                else -> width * 0.5f
            }
            val top = height * 0.2f
            val bottom = height * 0.8f

            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 3f * density
            paint.color = Color.WHITE
            paint.alpha = a / 2
            canvas.drawLine(cx, top, cx, bottom, paint)

            paint.style = Paint.Style.FILL
            paint.alpha = a
            val y = bottom - (bottom - top) * barFraction
            canvas.drawCircle(cx, y, 9f * density, paint)
            paint.textSize = 14f * density
            canvas.drawText(barLabel, cx, top - 12f * density, paint)
            animating = true
        }

        if (animating) postInvalidateOnAnimation()
    }
}
