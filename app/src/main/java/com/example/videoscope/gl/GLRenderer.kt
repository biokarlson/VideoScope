package com.example.videoscope.gl

import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.os.SystemClock
import com.example.videoscope.audio.AudioEngine
import com.example.videoscope.audio.AudioFrame
import com.example.videoscope.config.AppSettings
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.atomic.AtomicInteger
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Конвейер в стиле аналогового видеосинтезатора:
 *  Pass 1: scene.frag рисует плоские цветные поля в маленький FBO (270 строк, NEAREST)
 *          — звук приходит текстурой 512x2 (осциллограмма + спектр)
 *  Pass 2: post.frag растягивает его на экран и добавляет VHS/глитч/шум (uGlitch)
 */
class GLRenderer(
    private val audio: AudioEngine,
    settings: AppSettings
) : GLSurfaceView.Renderer {

    companion object {
        /**
         * Режим Random временно отключён. Код Random (в том числе ветка в шейдере сцены)
         * остаётся на месте, чтобы включить режим обратно, поставьте true.
         */
        const val RANDOM_ENABLED = false

        /** Индексы режимов: 0 Basic Line, 1 Color Shifter, 2 Trapezoid, 3 Loader, 4 Random (отключён). */
        const val LOADER_MODE = 3

        val MODE_COUNT: Int = if (RANDOM_ENABLED) 5 else 4
        val MODE_NAMES: Array<String> =
            if (RANDOM_ENABLED) arrayOf("Basic Line", "Color Shifter", "Trapezoid", "Loader", "Random")
            else arrayOf("Basic Line", "Color Shifter", "Trapezoid", "Loader")
        val PARAM_NAMES: Array<String> =
            if (RANDOM_ENABLED) arrayOf("Color", "Speed", "Width", "Boost", "Speed")
            else arrayOf("Color", "Speed", "Width", "Boost")

        /** Сохранённый режим вне диапазона (например, отключённый Random) заменяется первым режимом. */
        fun safeMode(m: Int): Int = if (m in 0 until MODE_COUNT) m else 0
        const val PALETTE_COUNT = 3
        val PALETTE_NAMES = arrayOf("Color", "B&W", "Mono")
        private const val AUDIO_W = 512
    }

    // --- управляется из UI-потока ---
    @Volatile var mode: Int = safeMode(settings.mode)
    @Volatile var param: Float = settings.getParam(safeMode(settings.mode))
    @Volatile var palette: Int = settings.palette
    @Volatile var speedSlider: Float = settings.speedMul // множитель скорости перестройки, ползунок 0..1 (0,5 = x1)
    @Volatile var sceneRows: Int = settings.pixelRows // строк сцены по высоте (размер «пикселя»)
    @Volatile var borderPx: Int = settings.border     // чёрная рамка со всех сторон, пикселей экрана
    @Volatile var monoHue: Float = settings.monoHue
    @Volatile var monoSat: Float = settings.monoSat
    @Volatile var glitchIntensity: Float = settings.glitch // общая сила эффектов (жест в центре)
    @Volatile var vhsOn: Boolean = settings.vhsOn
    @Volatile var vhsStrength: Float = settings.vhsStrength
    @Volatile var glitchOn: Boolean = settings.glitchOn
    @Volatile var glitchStrength: Float = settings.glitchStrength
    @Volatile var noiseOn: Boolean = settings.noiseOn
    @Volatile var noiseStrength: Float = settings.noiseStrength
    @Volatile var paused: Boolean = false
    @Volatile var holdGlitch: Boolean = false
    private val manualTriggers = AtomicInteger(0)

    fun triggerGlitch() {
        manualTriggers.incrementAndGet()
    }

    // --- состояние GL-потока ---
    private lateinit var sceneProg: ShaderProgram
    private lateinit var postProg: ShaderProgram
    private var scene: Framebuffer? = null
    private var audioTex = 0
    private var stripesTex = 0
    private var loader: LoaderStripes? = null
    private var stripesBuf: ByteBuffer = ByteBuffer.allocateDirect(1)
    private var sceneT = 0f // время сцены с учётом множителя скорости перестройки
    private var lastLoaderBeatMs = 0L
    private val audioBuf: ByteBuffer = ByteBuffer.allocateDirect(AUDIO_W * 2)
    private var width = 1
    private var height = 1

    private var lastTimeMs = 0L
    private var t = 0f
    private var beatEnv = 0f
    private var seed = 0f
    private var lastBeatCount = 0L
    private var lastManual = 0
    private var lastFrame: AudioFrame = AudioFrame.EMPTY

    private val quad: FloatBuffer = ByteBuffer.allocateDirect(8 * 4)
        .order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
            put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f))
            position(0)
        }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        sceneProg = ShaderProgram(Shaders.QUAD_VERT, Shaders.SCENE_FRAG)
        postProg = ShaderProgram(Shaders.QUAD_VERT, Shaders.POST_FRAG)
        scene = null // контекст мог быть потерян — пересоздадим в onSurfaceChanged

        // текстура с аудио: 512x2 LUMINANCE (строка 0 = волна, строка 1 = спектр)
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        audioTex = ids[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, audioTex)
        GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 1)
        GLES20.glTexImage2D(
            GLES20.GL_TEXTURE_2D, 0, GLES20.GL_LUMINANCE, AUDIO_W, 2, 0,
            GLES20.GL_LUMINANCE, GLES20.GL_UNSIGNED_BYTE, null
        )
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

        // текстура цветов строк для Loader: 1 x rows LUMINANCE, память выделяется в rebuildScene()
        GLES20.glGenTextures(1, ids, 0)
        stripesTex = ids[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, stripesTex)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

        lastTimeMs = SystemClock.uptimeMillis()
    }

    override fun onSurfaceChanged(gl: GL10?, w: Int, h: Int) {
        width = max(1, w)
        height = max(1, h)
        rebuildScene()
    }

    private fun rebuildScene() {
        scene?.release()
        val rows = sceneRows.coerceIn(60, 1080)
        val sw = max(2, (rows * width.toFloat() / height).toInt())
        val fb = Framebuffer(sw, rows, nearest = true)
        fb.clear()
        scene = fb

        // Loader: состояние полос и текстура по числу строк сцены
        loader = LoaderStripes(rows)
        stripesBuf = ByteBuffer.allocateDirect(rows)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, stripesTex)
        GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 1)
        GLES20.glTexImage2D(
            GLES20.GL_TEXTURE_2D, 0, GLES20.GL_LUMINANCE, 1, rows, 0,
            GLES20.GL_LUMINANCE, GLES20.GL_UNSIGNED_BYTE, null
        )
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
    }

    override fun onDrawFrame(gl: GL10?) {
        var fb = scene ?: return
        if (fb.height != sceneRows.coerceIn(60, 1080)) {
            rebuildScene()
            fb = scene ?: return
        }
        val now = SystemClock.uptimeMillis()
        val dt = ((now - lastTimeMs) / 1000f).coerceIn(0f, 0.1f)
        lastTimeMs = now

        if (!paused) {
            val f = audio.frame
            lastFrame = f
            t += dt
            sceneT += dt * 2f.pow((speedSlider - 0.5f) * 4f)

            beatEnv *= exp(-dt * 7f)
            val newBeat = f.beatCount != lastBeatCount
            if (newBeat) {
                lastBeatCount = f.beatCount
                beatEnv = 1f
            }
            val m = manualTriggers.get()
            val manualHit = m != lastManual
            if (manualHit) {
                lastManual = m
                beatEnv = 1f
            }
            if (holdGlitch) beatEnv = 1f
            seed = ((f.beatCount + lastManual) % 997L).toFloat()

            uploadAudio(f)

            // Loader: акцент не чаще раза в 250 мс; сильный акцент (мощный бас) меняет пару цветов
            val ld = loader
            if (mode == LOADER_MODE && ld != null) {
                val hit = (newBeat || manualHit) && (now - lastLoaderBeatMs >= 250L)
                if (hit) lastLoaderBeatMs = now
                ld.update(dt, f, hit, hit && newBeat && f.bass > 0.55f, param, now)
                uploadStripes(ld)
            }
            renderScene(fb, f)
        }
        renderPost(fb)
    }

    /** Кодирование: байт = 0.5 + значение (волна ±0.5 -> весь диапазон), шейдер умножает на 2. */
    private fun uploadAudio(f: AudioFrame) {
        for (i in 0 until AUDIO_W) {
            val w = (0.5f + f.waveform[i]).coerceIn(0f, 1f)
            audioBuf.put(i, (w * 255f).toInt().toByte())
            val s = f.spectrum[i / 8].coerceIn(0f, 1f)
            audioBuf.put(AUDIO_W + i, (s * 255f).toInt().toByte())
        }
        audioBuf.position(0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, audioTex)
        GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 1)
        GLES20.glTexSubImage2D(
            GLES20.GL_TEXTURE_2D, 0, 0, 0, AUDIO_W, 2,
            GLES20.GL_LUMINANCE, GLES20.GL_UNSIGNED_BYTE, audioBuf
        )
    }

    private fun uploadStripes(ld: LoaderStripes) {
        stripesBuf.position(0)
        stripesBuf.put(ld.buf)
        stripesBuf.position(0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, stripesTex)
        GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 1)
        GLES20.glTexSubImage2D(
            GLES20.GL_TEXTURE_2D, 0, 0, 0, 1, ld.rows,
            GLES20.GL_LUMINANCE, GLES20.GL_UNSIGNED_BYTE, stripesBuf
        )
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
    }

    // ---------------- Pass 1 ----------------

    private fun renderScene(fb: Framebuffer, f: AudioFrame) {
        fb.bind()
        sceneProg.use()
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, audioTex)
        GLES20.glUniform1i(sceneProg.uniform("uAudio"), 0)
        // текстура цветов строк для Loader на втором блоке текстур
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, stripesTex)
        GLES20.glUniform1i(sceneProg.uniform("uStripes"), 1)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glUniform1f(sceneProg.uniform("uTime"), sceneT % 1000f)
        GLES20.glUniform1f(sceneProg.uniform("uMode"), mode.toFloat())
        GLES20.glUniform1f(sceneProg.uniform("uParam"), param)
        GLES20.glUniform1f(sceneProg.uniform("uBeat"), beatEnv)
        GLES20.glUniform1f(sceneProg.uniform("uLevel"), f.rms)
        GLES20.glUniform1f(sceneProg.uniform("uBass"), f.bass)
        GLES20.glUniform1f(sceneProg.uniform("uMid"), f.mid)
        GLES20.glUniform1f(sceneProg.uniform("uHigh"), f.high)
        GLES20.glUniform1f(sceneProg.uniform("uSeed"), seed)
        GLES20.glUniform1f(sceneProg.uniform("uPalette"), palette.toFloat())
        GLES20.glUniform1f(sceneProg.uniform("uMonoHue"), monoHue)
        GLES20.glUniform1f(sceneProg.uniform("uMonoSat"), monoSat)
        drawQuad(sceneProg)
    }

    // ---------------- Pass 2 ----------------

    private fun renderPost(src: Framebuffer) {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        // рамка: картинка рисуется в уменьшенный прямоугольник, края заливаются чёрным
        val maxB = (0.4f * min(width, height)).toInt()
        val b = borderPx.coerceIn(0, 300).coerceAtMost(maxB)
        val innerW = width - 2 * b
        val innerH = height - 2 * b
        if (b > 0) {
            GLES20.glViewport(0, 0, width, height)
            GLES20.glClearColor(0f, 0f, 0f, 1f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        }
        GLES20.glViewport(b, b, innerW, innerH)
        postProg.use()
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, src.texture)
        GLES20.glUniform1i(postProg.uniform("uTex"), 0)
        val f = lastFrame
        val overall = if (holdGlitch) 1f else glitchIntensity
        GLES20.glUniform1f(postProg.uniform("uTime"), t % 1000f)
        GLES20.glUniform2f(postProg.uniform("uRes"), innerW.toFloat(), innerH.toFloat())
        GLES20.glUniform1f(postProg.uniform("uLevel"), f.rms)
        GLES20.glUniform1f(postProg.uniform("uBass"), f.bass)
        GLES20.glUniform1f(postProg.uniform("uMid"), f.mid)
        GLES20.glUniform1f(postProg.uniform("uHigh"), f.high)
        GLES20.glUniform1f(postProg.uniform("uBeat"), beatEnv)
        GLES20.glUniform1f(postProg.uniform("uVhs"), if (vhsOn) overall * vhsStrength else 0f)
        GLES20.glUniform1f(postProg.uniform("uGlitch"), if (glitchOn) overall * glitchStrength else 0f)
        GLES20.glUniform1f(postProg.uniform("uNoise"), if (noiseOn) overall * noiseStrength else 0f)
        GLES20.glUniform1f(postProg.uniform("uPalette"), palette.toFloat())
        GLES20.glUniform1f(postProg.uniform("uMonoHue"), monoHue)
        drawQuad(postProg)
    }

    private fun drawQuad(prog: ShaderProgram) {
        val a = prog.attrib("aPos")
        quad.position(0)
        GLES20.glVertexAttribPointer(a, 2, GLES20.GL_FLOAT, false, 0, quad)
        GLES20.glEnableVertexAttribArray(a)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(a)
    }
}
