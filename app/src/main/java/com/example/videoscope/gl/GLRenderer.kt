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
        const val MODE_COUNT = 4
        val MODE_NAMES = arrayOf("Basic Line", "Color Shifter", "Trapezoid", "Random")
        val PARAM_NAMES = arrayOf("Color", "Speed", "Width", "Speed")
        private const val SCENE_ROWS = 270
        private const val AUDIO_W = 512
    }

    // --- управляется из UI-потока ---
    @Volatile var mode: Int = settings.mode.coerceIn(0, MODE_COUNT - 1)
    @Volatile var param: Float = settings.getParam(settings.mode.coerceIn(0, MODE_COUNT - 1))
    @Volatile var glitchIntensity: Float = settings.glitch
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

        lastTimeMs = SystemClock.uptimeMillis()
    }

    override fun onSurfaceChanged(gl: GL10?, w: Int, h: Int) {
        width = max(1, w)
        height = max(1, h)
        scene?.release()
        val sw = max(2, (SCENE_ROWS * width.toFloat() / height).toInt())
        val fb = Framebuffer(sw, SCENE_ROWS, nearest = true)
        fb.clear()
        scene = fb
    }

    override fun onDrawFrame(gl: GL10?) {
        val fb = scene ?: return
        val now = SystemClock.uptimeMillis()
        val dt = ((now - lastTimeMs) / 1000f).coerceIn(0f, 0.1f)
        lastTimeMs = now

        if (!paused) {
            val f = audio.frame
            lastFrame = f
            t += dt

            beatEnv *= exp(-dt * 7f)
            if (f.beatCount != lastBeatCount) {
                lastBeatCount = f.beatCount
                beatEnv = 1f
            }
            val m = manualTriggers.get()
            if (m != lastManual) {
                lastManual = m
                beatEnv = 1f
            }
            if (holdGlitch) beatEnv = 1f
            seed = ((f.beatCount + lastManual) % 997L).toFloat()

            uploadAudio(f)
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

    // ---------------- Pass 1 ----------------

    private fun renderScene(fb: Framebuffer, f: AudioFrame) {
        fb.bind()
        sceneProg.use()
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, audioTex)
        GLES20.glUniform1i(sceneProg.uniform("uAudio"), 0)
        GLES20.glUniform1f(sceneProg.uniform("uTime"), t % 1000f)
        GLES20.glUniform1f(sceneProg.uniform("uMode"), mode.toFloat())
        GLES20.glUniform1f(sceneProg.uniform("uParam"), param)
        GLES20.glUniform1f(sceneProg.uniform("uBeat"), beatEnv)
        GLES20.glUniform1f(sceneProg.uniform("uLevel"), f.rms)
        GLES20.glUniform1f(sceneProg.uniform("uBass"), f.bass)
        GLES20.glUniform1f(sceneProg.uniform("uMid"), f.mid)
        GLES20.glUniform1f(sceneProg.uniform("uHigh"), f.high)
        GLES20.glUniform1f(sceneProg.uniform("uSeed"), seed)
        drawQuad(sceneProg)
    }

    // ---------------- Pass 2 ----------------

    private fun renderPost(src: Framebuffer) {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        GLES20.glViewport(0, 0, width, height)
        postProg.use()
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, src.texture)
        GLES20.glUniform1i(postProg.uniform("uTex"), 0)
        val f = lastFrame
        val glitch = if (holdGlitch) 1f else glitchIntensity
        GLES20.glUniform1f(postProg.uniform("uTime"), t % 1000f)
        GLES20.glUniform2f(postProg.uniform("uRes"), width.toFloat(), height.toFloat())
        GLES20.glUniform1f(postProg.uniform("uLevel"), f.rms)
        GLES20.glUniform1f(postProg.uniform("uBass"), f.bass)
        GLES20.glUniform1f(postProg.uniform("uMid"), f.mid)
        GLES20.glUniform1f(postProg.uniform("uHigh"), f.high)
        GLES20.glUniform1f(postProg.uniform("uBeat"), beatEnv)
        GLES20.glUniform1f(postProg.uniform("uGlitch"), glitch)
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
