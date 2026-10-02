package com.example.videoscope

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.opengl.GLSurfaceView
import android.os.Build
import android.os.Bundle
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.example.videoscope.audio.AudioEngine
import com.example.videoscope.config.AppSettings
import com.example.videoscope.gesture.GestureController
import com.example.videoscope.gesture.OverlayView
import com.example.videoscope.gl.GLRenderer
import java.util.Locale
import kotlin.math.ln
import kotlin.math.roundToInt

class MainActivity : ComponentActivity(), GestureController.Listener {

    private lateinit var settings: AppSettings
    private lateinit var audio: AudioEngine
    private lateinit var renderer: GLRenderer
    private lateinit var glView: GLSurfaceView
    private lateinit var overlay: OverlayView
    private lateinit var gestures: GestureController

    private var gain = 1f
    private var glitch = 0.35f
    private val params = FloatArray(GLRenderer.MODE_COUNT)
    private var askedPermission = false

    private val micPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startAudio() else showNoMic()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        settings = AppSettings(this)
        gain = settings.gain
        glitch = settings.glitch
        for (i in params.indices) params[i] = settings.getParam(i)
        audio = AudioEngine().also { it.gain = gain }
        renderer = GLRenderer(audio, settings)

        glView = GLSurfaceView(this).apply {
            setEGLContextClientVersion(2)
            setRenderer(renderer)
            renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        }
        overlay = OverlayView(this)
        gestures = GestureController(this, this)

        glView.setOnTouchListener { v, ev ->
            gestures.onTouchEvent(ev, v.width)
            true
        }

        val root = FrameLayout(this)
        root.setBackgroundColor(Color.BLACK)
        val lp = { FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT) }
        root.addView(glView, lp())
        root.addView(overlay, lp())
        setContentView(root)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= 28) {
            val attrs = window.attributes
            attrs.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            window.attributes = attrs
        }
        hideSystemBars()
    }

    override fun onResume() {
        super.onResume()
        hideSystemBars()
        glView.onResume()
        ensureAudio()
    }

    override fun onPause() {
        audio.stop()
        glView.onPause()
        settings.gain = gain
        settings.glitch = glitch
        settings.mode = renderer.mode
        for (i in params.indices) settings.setParam(i, params[i])
        super.onPause()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    private fun hideSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val c = WindowInsetsControllerCompat(window, window.decorView)
        c.hide(WindowInsetsCompat.Type.systemBars())
        c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    // ---------- микрофон ----------

    private fun hasMic() = ContextCompat.checkSelfPermission(
        this, Manifest.permission.RECORD_AUDIO
    ) == PackageManager.PERMISSION_GRANTED

    private fun ensureAudio() {
        if (hasMic()) {
            startAudio()
        } else if (!askedPermission) {
            askedPermission = true
            micPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun startAudio() {
        audio.gain = gain
        if (!audio.start()) overlay.showMessage("Не удалось запустить микрофон", 3000L)
    }

    private fun showNoMic() {
        overlay.showMessage("Нет доступа к микрофону. Разрешите его в настройках приложения", 4000L)
    }

    // ---------- жесты ----------

    override fun onGainScroll(factor: Float) {
        gain = (gain * factor).coerceIn(0.1f, 5.0f)
        audio.gain = gain
        val fraction = ln(gain / 0.1f) / ln(50f)
        overlay.showBar(fraction, String.format(Locale.US, "Gain x%.2f", gain), OverlayView.SIDE_LEFT)
    }

    override fun onParamScroll(delta: Float) {
        val m = renderer.mode
        params[m] = (params[m] + delta).coerceIn(0f, 1f)
        renderer.param = params[m]
        overlay.showBar(
            params[m],
            GLRenderer.PARAM_NAMES[m] + " " + (params[m] * 100).roundToInt() + "%",
            OverlayView.SIDE_RIGHT
        )
    }

    override fun onGlitchScroll(delta: Float) {
        glitch = (glitch + delta).coerceIn(0f, 1f)
        renderer.glitchIntensity = glitch
        overlay.showBar(glitch, "Glitch " + (glitch * 100).roundToInt() + "%", OverlayView.SIDE_CENTER)
    }

    override fun onModeChange(step: Int) {
        val n = GLRenderer.MODE_COUNT
        val m = (renderer.mode + step + n) % n
        renderer.mode = m
        renderer.param = params[m]
        overlay.showMessage(GLRenderer.MODE_NAMES[m] + "  (" + GLRenderer.PARAM_NAMES[m] + ")")
    }

    override fun onTap() {
        if (!hasMic()) {
            micPermission.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        renderer.triggerGlitch()
    }

    override fun onDoubleTap() {
        renderer.paused = !renderer.paused
        overlay.showMessage(if (renderer.paused) "Pause" else "Play")
    }

    override fun onHold(active: Boolean) {
        renderer.holdGlitch = active
    }
}
