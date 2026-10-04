package com.example.videoscope.gl

import com.example.videoscope.audio.AudioFrame
import java.util.Random
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Режим «Loader»: горизонтальные полосы на весь экран, ползущие снизу вверх.
 *
 * Хранит цвет каждой строки сцены (строка 0 = низ). Цвета логические:
 * 0, 1 = первая пара, 2, 3 = вторая пара, 4 = чёрный (редкий акцент).
 * Шейдер превращает логический цвет в цвет текущей палитры.
 *
 * Скорость: минимум около 1 полосы в 2 секунды, всегда ненулевой (даже в тишине);
 * громкость и удары разгоняют её до максимума, который задаётся параметром режима.
 * Толщина новой полосы выбирается по частотному составу в момент её появления:
 * в тишине и при одном тоне все полосы одного размера.
 */
class LoaderStripes(val rows: Int) {

    companion object {
        const val MIN_STRIPES_PER_SEC = 0.5f
        const val BLACK = 4
    }

    /** Цвета строк для текстуры (строка 0 = низ экрана). */
    val buf = ByteArray(rows)

    private val rnd = Random()
    private var lastFrame: AudioFrame = AudioFrame.EMPTY
    private var acc = 0f
    private var jump = 0
    private var curColor = 0
    private var curRemaining = 0
    private var pair = 0
    private var parity = 0
    private var holdThick = 8f
    private var avgThick = 8f
    private var speed = MIN_STRIPES_PER_SEC
    private var boost = 0f
    private var lastSwitchMs = 0L
    private var mixed = false
    private var silent = true

    init {
        shift(rows) // начальное заполнение экрана
    }

    /**
     * beat: акцент (скачок и краткое ускорение); strong: сильный акцент (смена пары цветов);
     * boostParam: 0..1, максимальная скорость от x1 до x20 минимальной.
     */
    fun update(dt: Float, f: AudioFrame, beat: Boolean, strong: Boolean, boostParam: Float, nowMs: Long) {
        lastFrame = f
        silent = f.rms < 0.1f

        val k = 1f + boostParam.coerceIn(0f, 1f) * 19f
        val maxSpeed = MIN_STRIPES_PER_SEC * k

        if (beat) {
            boost = 1f
            jump += 3 + rnd.nextInt(4)
            if (strong && nowMs - lastSwitchMs >= 2000L) {
                pair = 1 - pair
                lastSwitchMs = nowMs
            }
        }
        boost *= exp(-dt * 4f)

        val level = if (silent) 0f else f.rms.coerceIn(0f, 1f).pow(1.5f)
        val mix = (level + 0.5f * boost).coerceIn(0f, 1f)
        val target = MIN_STRIPES_PER_SEC + (maxSpeed - MIN_STRIPES_PER_SEC) * mix
        val a = 1f - exp(-dt * (if (target > speed) 20f else 3f))
        speed += (target - speed) * a

        // полос в секунду -> строк в секунду (по средней толщине)
        acc += speed * avgThick * dt
        var n = acc.toInt()
        acc -= n
        n += jump
        jump = 0
        if (n > 0) shift(n)
    }

    /** Сдвиг содержимого вверх на n строк, снизу дорисовываются новые полосы. */
    private fun shift(n: Int) {
        val c = min(n, rows)
        if (c < rows) System.arraycopy(buf, 0, buf, c, rows - c)
        for (i in c - 1 downTo 0) buf[i] = nextRowColor().toByte()
    }

    private fun nextRowColor(): Int {
        if (curRemaining <= 0) {
            val t = pickThickness(lastFrame)
            curRemaining = t
            avgThick += (t - avgThick) * 0.2f
            val busy = mixed && !silent
            if (busy && rnd.nextFloat() < 0.03f) {
                curColor = BLACK
            } else {
                // изредка цвет повторяется: получаются широкие сплошные участки
                if (!(busy && rnd.nextFloat() < 0.15f)) parity = 1 - parity
                curColor = pair * 2 + parity
            }
        }
        curRemaining--
        return curColor
    }

    /** Толщина новой полосы в строках по текущему звучанию. */
    private fun pickThickness(f: AudioFrame): Int {
        if (silent) return holdThick.roundToInt().coerceAtLeast(1)
        val b = f.bass
        val m = f.mid
        val h = f.high
        val sum = b + m + h
        if (sum < 0.05f) return holdThick.roundToInt().coerceAtLeast(1)

        val pb = b / sum
        val pm = m / sum
        val ph = h / sum
        if (max(pb, max(pm, ph)) > 0.6f) {
            // один тон или явно преобладающая полоса частот: все полосы одного размера
            mixed = false
            val t = when {
                pb >= pm && pb >= ph -> 24
                pm >= ph -> 8
                else -> 2
            }
            holdThick = t.toFloat()
            return t
        }
        // смешанное звучание: басы дают широкие, середина средние, высокие тонкие
        mixed = true
        val r = rnd.nextFloat()
        val t = when {
            r < pb -> 14 + rnd.nextInt(23)
            r < pb + pm -> 5 + rnd.nextInt(6)
            else -> 1 + rnd.nextInt(3)
        }
        holdThick += (t - holdThick) * 0.1f
        return t
    }
}
