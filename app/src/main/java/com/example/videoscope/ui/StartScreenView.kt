package com.example.videoscope.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View

/**
 * Начальный экран с краткой инструкцией: название сверху, зоны свайпов, подсказка про меню, версия снизу.
 * Закрывается касанием или автоматически; первое касание только закрывает экран.
 */
class StartScreenView(context: Context, private val version: String) : View(context) {

    private val density = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val autoHide = Runnable { hide() }
    private var hiding = false

    init {
        isClickable = true
        visibility = GONE
    }

    fun isShowing(): Boolean = visibility == VISIBLE && !hiding

    fun show() {
        hiding = false
        removeCallbacks(autoHide)
        animate().cancel()
        alpha = 1f
        visibility = VISIBLE
        postDelayed(autoHide, 6000L)
        invalidate()
    }

    fun hide() {
        if (visibility != VISIBLE || hiding) return
        hiding = true
        removeCallbacks(autoHide)
        animate().alpha(0f).setDuration(300L).withEndAction {
            visibility = GONE
            hiding = false
        }.start()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) hide()
        return true
    }

    private fun sp(v: Float): Float = v * resources.displayMetrics.scaledDensity

    /** Подгон размера текста под ширину зоны. */
    private fun fit(text: String, size: Float, maxWidth: Float) {
        paint.textSize = size
        while (paint.measureText(text) > maxWidth && paint.textSize > sp(9f)) {
            paint.textSize = paint.textSize - 1f
        }
    }

    private fun arrowUpDown(canvas: Canvas, cx: Float, cy: Float, size: Float) {
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 3f * density
        canvas.drawLine(cx, cy - size, cx, cy + size, paint)
        paint.style = Paint.Style.FILL
        path.reset()
        path.moveTo(cx, cy - size - 6f * density)
        path.lineTo(cx - 8f * density, cy - size + 6f * density)
        path.lineTo(cx + 8f * density, cy - size + 6f * density)
        path.close()
        canvas.drawPath(path, paint)
        path.reset()
        path.moveTo(cx, cy + size + 6f * density)
        path.lineTo(cx - 8f * density, cy + size - 6f * density)
        path.lineTo(cx + 8f * density, cy + size - 6f * density)
        path.close()
        canvas.drawPath(path, paint)
    }

    private fun arrowLeftRight(canvas: Canvas, cx: Float, cy: Float, size: Float) {
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 3f * density
        canvas.drawLine(cx - size, cy, cx + size, cy, paint)
        paint.style = Paint.Style.FILL
        path.reset()
        path.moveTo(cx - size - 6f * density, cy)
        path.lineTo(cx - size + 6f * density, cy - 8f * density)
        path.lineTo(cx - size + 6f * density, cy + 8f * density)
        path.close()
        canvas.drawPath(path, paint)
        path.reset()
        path.moveTo(cx + size + 6f * density, cy)
        path.lineTo(cx + size - 6f * density, cy - 8f * density)
        path.lineTo(cx + size - 6f * density, cy + 8f * density)
        path.close()
        canvas.drawPath(path, paint)
    }

    private fun zone(
        canvas: Canvas, left: Float, right: Float, fill: Int,
        title: String, subtitle: String?, withHorizontal: Boolean
    ) {
        val h = height.toFloat()
        val w = right - left
        val cx = (left + right) / 2f
        paint.style = Paint.Style.FILL
        paint.color = fill
        canvas.drawRect(left, h * 0.22f, right, h * 0.80f, paint)

        paint.color = Color.WHITE
        paint.textAlign = Paint.Align.CENTER
        paint.typeface = Typeface.DEFAULT_BOLD
        fit(title, sp(16f), w * 0.92f)
        canvas.drawText(title, cx, h * 0.22f + sp(26f), paint)

        paint.typeface = Typeface.DEFAULT
        if (subtitle != null) {
            fit(subtitle, sp(12f), w * 0.92f)
            paint.color = Color.argb(210, 255, 255, 255)
            canvas.drawText(subtitle, cx, h * 0.22f + sp(46f), paint)
        }
        paint.color = Color.WHITE
        arrowUpDown(canvas, cx, h * 0.51f, h * 0.10f)
        if (withHorizontal) {
            arrowLeftRight(canvas, cx, h * 0.69f, w * 0.18f)
            paint.style = Paint.Style.FILL
            paint.typeface = Typeface.DEFAULT
            fit("режим", sp(12f), w * 0.9f)
            paint.color = Color.argb(210, 255, 255, 255)
            canvas.drawText("режим", cx, h * 0.69f + sp(26f), paint)
        }
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val inset = 32f * density

        paint.style = Paint.Style.FILL
        paint.color = Color.argb(175, 0, 0, 0)
        canvas.drawRect(0f, 0f, w, h, paint)

        // зоны жестов (границы совпадают с зонами в GestureController)
        zone(canvas, inset, w * 0.2f, Color.argb(70, 51, 133, 255), "Громкость", "усиление микрофона", false)
        zone(canvas, w * 0.2f, w * 0.8f, Color.argb(45, 133, 104, 255), "Эффекты", "общая сила эффектов", true)
        zone(canvas, w * 0.8f, w - inset, Color.argb(70, 64, 199, 74), "Доп. настройка", "параметр режима", false)

        // название сверху
        paint.style = Paint.Style.FILL
        paint.color = Color.WHITE
        paint.textAlign = Paint.Align.CENTER
        paint.typeface = Typeface.DEFAULT_BOLD
        paint.textSize = sp(28f)
        canvas.drawText("VideoScope", w / 2f, h * 0.14f, paint)

        // подсказка про меню
        paint.typeface = Typeface.DEFAULT
        fit("Тап двумя пальцами: меню", sp(18f), w * 0.8f)
        paint.color = Color.WHITE
        canvas.drawText("Тап двумя пальцами: меню", w / 2f, h * 0.90f, paint)

        // версия снизу
        if (version.isNotEmpty()) {
            paint.textSize = sp(13f)
            paint.color = Color.argb(180, 255, 255, 255)
            canvas.drawText("v" + version, w / 2f, h - 14f * density, paint)
        }
    }
}
