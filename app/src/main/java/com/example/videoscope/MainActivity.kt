package com.example.videoscope

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.opengl.GLSurfaceView
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.example.videoscope.audio.AudioEngine
import com.example.videoscope.config.AppSettings
import com.example.videoscope.config.PresetStore
import com.example.videoscope.gesture.GestureController
import com.example.videoscope.gesture.OverlayView
import com.example.videoscope.gl.GLRenderer
import com.example.videoscope.ui.MenuPanel
import com.example.videoscope.ui.StartScreenView
import java.util.Locale
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt

class MainActivity : ComponentActivity(), GestureController.Listener {

    private lateinit var settings: AppSettings
    private lateinit var audio: AudioEngine
    private lateinit var renderer: GLRenderer
    private lateinit var glView: GLSurfaceView
    private lateinit var overlay: OverlayView
    private lateinit var gestures: GestureController
    private lateinit var menu: MenuPanel
    private lateinit var presets: PresetStore
    private lateinit var startScreen: StartScreenView

    private var gainStep = 0f
    private var gainRaw = 0f
    private var glitch = 0.35f
    private val params = FloatArray(GLRenderer.MODE_COUNT)
    private var askedPermission = false
    private var menuOpen = false
    private var pauseEnabled = true
    private var osdOn = true
    private var exitGuard = true
    private var gestureLock = false
    private var lastBackMs = 0L

    private val backCallback = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
            if (startScreen.isShowing()) {
                startScreen.hide()
                return
            }
            if (menuOpen) {
                closeMenu()
                return
            }
            if (!exitGuard) {
                finish()
                return
            }
            val now = SystemClock.uptimeMillis()
            if (now - lastBackMs < 2000L) {
                finish()
            } else {
                lastBackMs = now
                overlay.showMessage("Нажмите ещё раз «Назад» для выхода", 2000L, true)
            }
        }
    }

    private val menuHost = object : MenuPanel.Host {
        override fun getPalette(): Int = renderer.palette
        override fun setPalette(p: Int) {
            renderer.palette = p
        }
        override fun getMonoHue(): Float = renderer.monoHue
        override fun setMonoHue(h: Float) {
            renderer.monoHue = h
        }
        override fun getMonoSat(): Float = renderer.monoSat
        override fun setMonoSat(s: Float) {
            renderer.monoSat = s
        }
        override fun getEffectOn(index: Int): Boolean = when (index) {
            0 -> renderer.vhsOn
            1 -> renderer.glitchOn
            else -> renderer.noiseOn
        }
        override fun setEffectOn(index: Int, on: Boolean) {
            when (index) {
                0 -> renderer.vhsOn = on
                1 -> renderer.glitchOn = on
                else -> renderer.noiseOn = on
            }
        }
        override fun getEffectStrength(index: Int): Float = when (index) {
            0 -> renderer.vhsStrength
            1 -> renderer.glitchStrength
            else -> renderer.noiseStrength
        }
        override fun setEffectStrength(index: Int, v: Float) {
            when (index) {
                0 -> renderer.vhsStrength = v
                1 -> renderer.glitchStrength = v
                else -> renderer.noiseStrength = v
            }
        }
        override fun getOverall(): Float = glitch
        override fun setOverall(v: Float) {
            glitch = v.coerceIn(0f, 1f)
            renderer.glitchIntensity = glitch
        }
        override fun getGainStep(): Float = gainStep
        override fun setGainStep(v: Float) {
            gainStep = v.coerceIn(-2f, 2f)
            gainRaw = gainStep
            audio.gain = gainMul()
        }
        override fun getLevel(): Float = audio.frame.rms
        override fun getPeak(): Float = audio.frame.peak
        override fun isOsdOn(): Boolean = osdOn
        override fun setOsdOn(v: Boolean) {
            osdOn = v
            overlay.osdEnabled = v
        }
        override fun isExitGuard(): Boolean = exitGuard
        override fun setExitGuard(v: Boolean) {
            exitGuard = v
        }
        override fun getStartMode(): Int = settings.startMode
        override fun setStartMode(i: Int) {
            settings.startMode = i
        }
        override fun getCurrentMode(): Int = renderer.mode
        override fun getModeParam(i: Int): Float = params[i.coerceIn(0, params.size - 1)]
        override fun setModeParam(i: Int, v: Float) {
            val m = i.coerceIn(0, params.size - 1)
            params[m] = v.coerceIn(0f, 1f)
            if (renderer.mode == m) renderer.param = params[m]
        }
        override fun getPixelRows(): Int = renderer.sceneRows
        override fun setPixelRows(rows: Int) {
            renderer.sceneRows = rows
        }
        override fun getBorder(): Int = renderer.borderPx
        override fun setBorder(px: Int) {
            renderer.borderPx = px.coerceIn(0, 300)
        }
        override fun getBeatSensitivity(): Float = audio.beatSensitivity
        override fun setBeatSensitivity(v: Float) {
            audio.beatSensitivity = v.coerceIn(0f, 1f)
        }
        override fun isGestureLock(): Boolean = gestureLock
        override fun setGestureLock(v: Boolean) {
            gestureLock = v
        }
        override fun resetTab(index: Int) {
            when (index) {
                0 -> resetVideo()
                1 -> resetAudio()
                else -> resetInterface()
            }
            saveSettings()
        }
        override fun resetAll() {
            resetVideo()
            resetAudio()
            resetInterface()
            renderer.mode = 0
            renderer.param = params[0]
            saveSettings()
        }
        override fun getSpeedMul(): Float = renderer.speedSlider
        override fun setSpeedMul(v: Float) {
            renderer.speedSlider = v.coerceIn(0f, 1f)
        }
        override fun getReaction(): Float = audio.reaction
        override fun setReaction(v: Float) {
            audio.reaction = v.coerceIn(0f, 1f)
        }
        override fun getMicSource(): Int = audio.source
        override fun setMicSource(i: Int) {
            audio.source = i
            restartAudio()
        }
        override fun isUnprocessedSupported(): Boolean = unprocessedSupported()
        override fun getInputDeviceNames(): List<String> = inputDevices().map { deviceLabel(it) }
        override fun getMicDevice(): String = settings.micDevice
        override fun setMicDevice(name: String) {
            settings.micDevice = name
            audio.preferredDevice = findDevice(name)
            restartAudio()
        }
        override fun isAutoGain(): Boolean = audio.autoGain
        override fun setAutoGain(v: Boolean) {
            audio.autoGain = v
        }
        override fun getAutoStep(): Float = audio.autoStep
        override fun isShowStartScreen(): Boolean = settings.showStartScreen
        override fun setShowStartScreen(v: Boolean) {
            settings.showStartScreen = v
        }
        override fun showStartScreenNow() {
            closeMenu()
            startScreen.show()
        }
        override fun getPresetNames(): List<String> = presets.names()
        override fun savePreset(name: String) {
            saveSettings()
            presets.save(name, settings.exportPreset())
        }
        override fun loadPreset(name: String) {
            val json = presets.load(name)
            if (json != null) {
                settings.importPreset(json)
                loadLiveFromSettings()
            }
        }
        override fun deletePreset(name: String) {
            presets.delete(name)
        }
        override fun isPauseEnabled(): Boolean = pauseEnabled
        override fun setPauseEnabled(v: Boolean) {
            pauseEnabled = v
            if (!v) renderer.paused = false
        }
        override fun getLastTab(): Int = settings.lastTab
        override fun setLastTab(i: Int) {
            settings.lastTab = i
        }
        override fun onClose() {
            closeMenu()
        }
    }

    private val micPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startAudio() else showNoMic()
        }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    /** Множитель усиления: 2 в степени ступени (-2..+2 даёт x0,25..x4). */
    private fun gainMul(): Float = 2f.pow(gainStep)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        settings = AppSettings(this)
        gainStep = settings.gainStep
        gainRaw = gainStep
        glitch = settings.glitch
        pauseEnabled = settings.pauseEnabled
        osdOn = settings.osdOn
        exitGuard = settings.exitGuard
        gestureLock = settings.gestureLock
        // режим при старте: фиксированный режим заменяет последний использованный;
        // если сохранённый режим сейчас отключён (Random), берётся безопасный вариант
        if (settings.startMode >= GLRenderer.MODE_COUNT) settings.startMode = -1
        if (settings.startMode >= 0) settings.mode = settings.startMode
        settings.mode = GLRenderer.safeMode(settings.mode)
        for (i in params.indices) params[i] = settings.getParam(i)
        presets = PresetStore(this)
        audio = AudioEngine().also {
            it.gain = gainMul()
            it.beatSensitivity = settings.beatSensitivity
            it.reaction = settings.reaction
            it.source = settings.micSource
            it.autoGain = settings.autoGain
        }
        audio.preferredDevice = findDevice(settings.micDevice)
        renderer = GLRenderer(audio, settings)

        glView = GLSurfaceView(this).apply {
            setEGLContextClientVersion(2)
            setRenderer(renderer)
            renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        }
        overlay = OverlayView(this)
        overlay.osdEnabled = osdOn
        gestures = GestureController(this, this)

        glView.setOnTouchListener { v, ev ->
            gestures.onTouchEvent(ev, v.width, !menuOpen && !gestureLock)
            true
        }

        val match = ViewGroup.LayoutParams.MATCH_PARENT

        val root = FrameLayout(this)
        root.setBackgroundColor(Color.BLACK)
        root.addView(glView, FrameLayout.LayoutParams(match, match))
        root.addView(overlay, FrameLayout.LayoutParams(match, match))

        startScreen = StartScreenView(this, appVersion())
        root.addView(startScreen, FrameLayout.LayoutParams(match, match))

        // слой интерфейса: отступы под вырез/системные панели
        val uiLayer = FrameLayout(this)
        root.addView(uiLayer, FrameLayout.LayoutParams(match, match))

        menu = MenuPanel(this, menuHost)
        menu.visibility = View.GONE
        val panelWidth = (resources.displayMetrics.widthPixels * 0.5f).toInt().coerceAtLeast(dp(320))
        val menuLp = FrameLayout.LayoutParams(panelWidth, match, Gravity.END)
        menuLp.setMargins(0, dp(8), dp(8), dp(8))
        uiLayer.addView(menu, menuLp)

        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val c = insets.getInsets(
                WindowInsetsCompat.Type.displayCutout() or WindowInsetsCompat.Type.systemBars()
            )
            uiLayer.setPadding(c.left, c.top, c.right, c.bottom)
            insets
        }

        setContentView(root)
        onBackPressedDispatcher.addCallback(this, backCallback)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= 28) {
            val attrs = window.attributes
            attrs.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            window.attributes = attrs
        }
        hideSystemBars()
        if (settings.showStartScreen) startScreen.show()
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
        saveSettings()
        super.onPause()
    }

    private fun saveSettings() {
        settings.gainStep = gainStep
        settings.glitch = glitch
        settings.pauseEnabled = pauseEnabled
        settings.osdOn = osdOn
        settings.exitGuard = exitGuard
        settings.gestureLock = gestureLock
        settings.vhsOn = renderer.vhsOn
        settings.vhsStrength = renderer.vhsStrength
        settings.glitchOn = renderer.glitchOn
        settings.glitchStrength = renderer.glitchStrength
        settings.noiseOn = renderer.noiseOn
        settings.noiseStrength = renderer.noiseStrength
        settings.mode = renderer.mode
        settings.palette = renderer.palette
        settings.monoHue = renderer.monoHue
        settings.monoSat = renderer.monoSat
        settings.pixelRows = renderer.sceneRows
        settings.border = renderer.borderPx
        settings.beatSensitivity = audio.beatSensitivity
        settings.reaction = audio.reaction
        settings.speedMul = renderer.speedSlider
        settings.micSource = audio.source
        settings.autoGain = audio.autoGain
        for (i in params.indices) settings.setParam(i, params[i])
    }

    /** Заново прочитать все настройки из хранилища (после загрузки пресета). */
    private fun loadLiveFromSettings() {
        gainStep = settings.gainStep
        gainRaw = gainStep
        audio.gain = gainMul()
        glitch = settings.glitch
        renderer.glitchIntensity = glitch
        pauseEnabled = settings.pauseEnabled
        if (!pauseEnabled) renderer.paused = false
        osdOn = settings.osdOn
        overlay.osdEnabled = osdOn
        exitGuard = settings.exitGuard
        gestureLock = settings.gestureLock

        renderer.vhsOn = settings.vhsOn
        renderer.vhsStrength = settings.vhsStrength
        renderer.glitchOn = settings.glitchOn
        renderer.glitchStrength = settings.glitchStrength
        renderer.noiseOn = settings.noiseOn
        renderer.noiseStrength = settings.noiseStrength
        renderer.palette = settings.palette
        renderer.monoHue = settings.monoHue
        renderer.monoSat = settings.monoSat
        renderer.sceneRows = settings.pixelRows
        renderer.borderPx = settings.border
        renderer.speedSlider = settings.speedMul

        for (i in params.indices) params[i] = settings.getParam(i)
        renderer.mode = GLRenderer.safeMode(settings.mode)
        renderer.param = params[renderer.mode]

        audio.beatSensitivity = settings.beatSensitivity
        audio.reaction = settings.reaction
        audio.autoGain = settings.autoGain
        audio.source = settings.micSource
        restartAudio()
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

    // ---------- меню ----------

    private fun openMenu() {
        menuOpen = true
        menu.refresh()
        menu.visibility = View.VISIBLE
    }

    private fun closeMenu() {
        menuOpen = false
        menu.visibility = View.GONE
    }

    // ---------- сброс настроек по вкладкам ----------

    private fun resetVideo() {
        renderer.palette = 0
        renderer.monoHue = 0.58f
        renderer.monoSat = 0.85f
        glitch = 0.35f
        renderer.glitchIntensity = glitch
        renderer.vhsOn = true
        renderer.vhsStrength = 1f
        renderer.glitchOn = true
        renderer.glitchStrength = 1f
        renderer.noiseOn = true
        renderer.noiseStrength = 1f
        settings.startMode = -1
        for (i in params.indices) params[i] = 0.5f
        renderer.param = params[renderer.mode]
        renderer.sceneRows = 270
        renderer.borderPx = 0
        renderer.speedSlider = 0.5f
    }

    private fun resetAudio() {
        gainStep = 0f
        gainRaw = 0f
        audio.gain = gainMul()
        audio.beatSensitivity = 0.5f
        audio.reaction = 0.5f
        audio.autoGain = false
        audio.source = 0
        settings.micDevice = "auto"
        audio.preferredDevice = null
        restartAudio()
    }

    private fun resetInterface() {
        pauseEnabled = true
        renderer.paused = false
        osdOn = true
        overlay.osdEnabled = true
        exitGuard = true
        gestureLock = false
        settings.showStartScreen = true
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

    /** Перезапуск захвата (смена источника или устройства). */
    private fun restartAudio() {
        audio.stop()
        if (hasMic()) startAudio()
    }

    private fun unprocessedSupported(): Boolean {
        if (Build.VERSION.SDK_INT < 24) return false
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        return am.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true"
    }

    /** Подключённые внешние устройства ввода: USB, проводная и Bluetooth-гарнитура. */
    private fun inputDevices(): List<AudioDeviceInfo> {
        if (Build.VERSION.SDK_INT < 23) return emptyList()
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val result = ArrayList<AudioDeviceInfo>()
        for (d in am.getDevices(AudioManager.GET_DEVICES_INPUTS)) {
            val type = d.type
            if (type == AudioDeviceInfo.TYPE_USB_DEVICE ||
                type == AudioDeviceInfo.TYPE_USB_HEADSET ||
                type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
            ) {
                result.add(d)
            }
        }
        return result
    }

    private fun deviceLabel(d: AudioDeviceInfo): String {
        val kind = when (d.type) {
            AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET -> "USB"
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth"
            else -> "гарнитура"
        }
        return d.productName.toString() + " (" + kind + ")"
    }

    private fun findDevice(name: String): AudioDeviceInfo? {
        if (name == "auto") return null
        return inputDevices().firstOrNull { deviceLabel(it) == name }
    }

    private fun appVersion(): String {
        return try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: ""
        } catch (e: Exception) {
            ""
        }
    }

    private fun startAudio() {
        audio.gain = gainMul()
        if (!audio.start()) overlay.showMessage("Не удалось запустить микрофон", 3000L, true)
    }

    private fun showNoMic() {
        overlay.showMessage("Нет доступа к микрофону. Разрешите его в настройках приложения", 4000L, true)
    }

    // ---------- жесты ----------

    override fun onGainScroll(factor: Float) {
        // свайп накапливается плавно, значение привязывается к шагу 0,25
        gainRaw = (gainRaw + ln(factor) / ln(2f)).coerceIn(-2f, 2f)
        gainStep = (gainRaw * 4f).roundToInt() / 4f
        audio.gain = gainMul()
        overlay.showBar(
            (gainStep + 2f) / 4f,
            String.format(Locale.US, "Gain %+.2f (x%.2f)", gainStep, gainMul()),
            OverlayView.SIDE_LEFT
        )
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
        overlay.showBar(glitch, "Effects " + (glitch * 100).roundToInt() + "%", OverlayView.SIDE_CENTER)
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
        if (!pauseEnabled) return
        renderer.paused = !renderer.paused
        overlay.showMessage(if (renderer.paused) "Pause" else "Play")
    }

    override fun onHold(active: Boolean) {
        renderer.holdGlitch = active
    }

    override fun onMenuToggle() {
        if (menuOpen) closeMenu() else openMenu()
    }
}
