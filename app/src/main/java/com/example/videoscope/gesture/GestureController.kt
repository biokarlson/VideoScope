package com.example.videoscope.gesture

import android.content.Context
import android.view.GestureDetector
import android.view.MotionEvent
import kotlin.math.abs
import kotlin.math.exp

/**
 * Зоны экрана:
 *  - левая полоса (20% ширины, с отступом от края под жест «Назад»): вертикальный свайп = gain
 *  - правая полоса (20%): вертикальный свайп = параметр режима (цвет / скорость / ширина)
 *  - центр: свайп влево/вправо = режим, вертикальный свайп = сила глитча
 *  - тап = ручной глитч, двойной тап = пауза, долгое нажатие = максимальный глитч
 */
class GestureController(context: Context, private val listener: Listener) {

    interface Listener {
        fun onGainScroll(factor: Float)
        fun onParamScroll(delta: Float)
        fun onGlitchScroll(delta: Float)
        fun onModeChange(step: Int)
        fun onTap()
        fun onDoubleTap()
        fun onHold(active: Boolean)
    }

    private enum class Zone { GAIN, PARAM, CENTER }

    private val density = context.resources.displayMetrics.density
    private var viewWidth = 1
    private var zone = Zone.CENTER
    private var holding = false
    private var centerVertical: Boolean? = null

    private val detector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {

        override fun onDown(e: MotionEvent): Boolean {
            val inset = 32f * density
            val x = e.x
            zone = when {
                x >= inset && x <= viewWidth * 0.2f -> Zone.GAIN
                x >= viewWidth * 0.8f && x <= viewWidth - inset -> Zone.PARAM
                else -> Zone.CENTER
            }
            centerVertical = null
            return true
        }

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            listener.onTap()
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            listener.onDoubleTap()
            return true
        }

        override fun onLongPress(e: MotionEvent) {
            holding = true
            listener.onHold(true)
        }

        // distanceY > 0, когда палец движется ВВЕРХ
        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
            when (zone) {
                Zone.GAIN -> listener.onGainScroll(exp(distanceY * 0.004f))
                Zone.PARAM -> listener.onParamScroll(distanceY * 0.003f)
                Zone.CENTER -> {
                    if (centerVertical == null) {
                        val tx = abs(e2.x - (e1?.x ?: e2.x))
                        val ty = abs(e2.y - (e1?.y ?: e2.y))
                        centerVertical = ty > tx
                    }
                    if (centerVertical == true) listener.onGlitchScroll(distanceY * 0.003f)
                }
            }
            return true
        }

        override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
            if (zone != Zone.CENTER || centerVertical == true) return false
            val ax = abs(velocityX)
            val ay = abs(velocityY)
            if (ax > 700f && ax > ay * 1.5f) {
                listener.onModeChange(if (velocityX < 0) 1 else -1)
                return true
            }
            return false
        }
    })

    fun onTouchEvent(ev: MotionEvent, width: Int): Boolean {
        viewWidth = if (width < 1) 1 else width
        val handled = detector.onTouchEvent(ev)
        val action = ev.actionMasked
        if ((action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) && holding) {
            holding = false
            listener.onHold(false)
        }
        return handled
    }
}
