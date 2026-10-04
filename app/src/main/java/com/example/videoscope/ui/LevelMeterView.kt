package com.example.videoscope.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.SystemClock
import android.view.View
import kotlin.math.log10
import kotlin.math.min

/**
 * Индикатор уровня входного сигнала: полоса громкости (зелёная, жёлтая, красная зоны),
 * маркер удержания пика и красный квадрат, который загорается при клиппинге.
 */
class LevelMeterView(context: Context) : View(context) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val density = resources.displayMetrics.density

    private var level = 0f
    private var peakHold = 0f
    private var peakHoldTime = 0L
    private var clipUntil = 0L

    /** rms: 0..1 (уже в логарифмической шкале), peak: линейный пик блока после усиления. */
    fun update(rms: Float, peak: Float) {
        val now = SystemClock.uptimeMillis()
        level = rms.coerceIn(0f, 1f)
        val peakLevel = ((20f * log10(peak + 1e-9f) + 60f) / 60f).coerceIn(0f, 1f)
        if (peakLevel >= peakHold || now - peakHoldTime > 800L) {
            peakHold = peakLevel
            peakHoldTime = now
        }
        if (peak >= 0.98f) clipUntil = now + 1200L
        invalidate()
    }

    private fun fill(canvas: Canvas, from: Float, to: Float, h: Float, color: Int) {
        if (to <= from) return
        paint.style = Paint.Style.FILL
        paint.color = color
        canvas.drawRect(from, 0f, to, h, paint)
    }

    override fun onDraw(canvas: Canvas) {
        val h = height.toFloat()
        val clipSize = h
        val barW = width - clipSize - 6f * density
        if (barW <= 0f) return

        // фон полосы
        paint.style = Paint.Style.FILL
        paint.color = Color.argb(70, 255, 255, 255)
        canvas.drawRoundRect(0f, 0f, barW, h, 4f * density, 4f * density, paint)

        // уровень: зелёный до 70%, жёлтый до 90%, красный выше
        val fillTo = barW * level
        fill(canvas, 0f, min(fillTo, barW * 0.7f), h, Color.rgb(64, 199, 74))
        fill(canvas, barW * 0.7f, min(fillTo, barW * 0.9f), h, Color.rgb(240, 200, 40))
        fill(canvas, barW * 0.9f, fillTo, h, Color.rgb(224, 71, 24))

        // маркер удержания пика
        val px = barW * peakHold
        paint.color = Color.WHITE
        canvas.drawRect(px - 1.5f * density, 0f, px + 1.5f * density, h, paint)

        // клиппинг
        val clipping = SystemClock.uptimeMillis() < clipUntil
        paint.color = if (clipping) Color.rgb(255, 40, 40) else Color.argb(90, 255, 40, 40)
        val cx = barW + 6f * density
        canvas.drawRoundRect(cx, 0f, cx + clipSize, h, 4f * density, 4f * density, paint)
    }
}
