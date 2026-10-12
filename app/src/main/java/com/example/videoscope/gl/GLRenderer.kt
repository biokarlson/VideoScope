package com.example.videoscope.gl

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import android.os.SystemClock
import com.example.videoscope.audio.AudioEngine
import com.example.videoscope.audio.AudioFrame
import com.example.videoscope.config.AppSettings
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.Random
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.exp
import kotlin.math.floor
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

        /** Индексы режимов: 0 Basic Line, 1 Color Shifter, 2 Trapezoid, 3 Loader, 4 Text Glitch, 5 Photo Glitch, 6 Random (отключён). */
        const val LOADER_MODE = 3
        const val TEXT_MODE = 4
        const val PHOTO_MODE = 5

        val MODE_COUNT: Int = if (RANDOM_ENABLED) 7 else 6
        val MODE_NAMES: Array<String> =
            if (RANDOM_ENABLED) arrayOf("Basic Line", "Color Shifter", "Trapezoid", "Loader", "Text Glitch", "Photo Glitch", "Random")
            else arrayOf("Basic Line", "Color Shifter", "Trapezoid", "Loader", "Text Glitch", "Photo Glitch")
        val PARAM_NAMES: Array<String> =
            if (RANDOM_ENABLED) arrayOf("Color", "Speed", "Width", "Boost", "Row speed", "Ceiling", "Speed")
            else arrayOf("Color", "Speed", "Width", "Boost", "Row speed", "Ceiling")

        const val DEFAULT_TEXT = "ЧИСТЫЕ ВЕЧЕРИНКИ"
        private const val TEXT_W = 2048
        private const val TEXT_H = 256

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
    @Volatile var text: String = settings.glitchText // текст для Text Glitch
    @Volatile var textCeil: Float = settings.textCeiling      // потолок искажения Text Glitch
    @Volatile var distSource: Int = settings.distSource       // 0 полная, 1 середина+высокие, 2 высокие
    @Volatile var distWindow: Float = settings.distWindow     // диапазон реакции, дБ
    @Volatile var centerFull: Float = settings.calCenterFull  // центры калибровки, дБ
    @Volatile var centerMidHigh: Float = settings.calCenterMidHigh
    @Volatile var centerHigh: Float = settings.calCenterHigh
    @Volatile var photoFiles: List<File> = emptyList()        // фото для Photo Glitch
    @Volatile var photosVersion = 0                           // увеличивается при изменении списка фото
    @Volatile var photoAuto: Boolean = settings.photoAuto
    @Volatile var photoMinInterval: Float = settings.photoInterval // секунд
    @Volatile var photoFit: Boolean = settings.photoFit
    @Volatile var photoSwap: Boolean = settings.photoSwap          // обмен кусков на акцентах
    @Volatile var photoSwapSize: Float = settings.photoSwapSize    // масштаб размера кусков, 0..1
    private val nextPhotoRequests = AtomicInteger(0)

    fun requestNextPhoto() {
        nextPhotoRequests.incrementAndGet()
    }
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
    private var textTex = 0
    private var textUploaded: String? = null
    private var dist = 0.08f        // текущее искажение текста, 0..1
    private var photoTex = 0
    private var lastMidOnset = 0L
    private var lastHighOnset = 0L
    private var swapEnv = 0f
    private var swapSeed = 0f
    private var swapCls = 0
    private var swapCount = 0f
    private var photoAspect = 1f
    private var hasPhoto = false
    private var photoIndex = -1
    private var photosSeen = -1
    private var lastNextReq = 0
    private var lastPhotoSwitchMs = 0L
    private val photoLock = Any()
    private var pendingBitmap: Bitmap? = null
    private var pendingIndex = -1
    private var photoLoading = false
    private val photoExecutor = Executors.newSingleThreadExecutor()
    private val photoRandom = Random()
    private var textScroll = 0f     // накопленная прокрутка строк
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

        // текстура с текстом для Text Glitch (содержимое загружается в uploadText)
        GLES20.glGenTextures(1, ids, 0)
        textTex = ids[0]
        GLES20.glActiveTexture(GLES20.GL_TEXTURE2)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textTex)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        textUploaded = null // контекст мог быть потерян: загрузим текст заново

        // текстура фото для Photo Glitch (содержимое загружается в uploadPhoto)
        GLES20.glGenTextures(1, ids, 0)
        photoTex = ids[0]
        GLES20.glActiveTexture(GLES20.GL_TEXTURE3)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, photoTex)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        hasPhoto = false
        photoIndex = -1

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

            // Искажение (Text Glitch и Photo Glitch): только от текущего уровня выбранного источника,
            // без накопления. Центр окна задаёт калибровка (обычный уровень звука), ширина окна в дБ
            // (диапазон реакции): от центра вниз на половину окна искажение 0, вверх на половину окна максимум.
            // Подъём плавнее, спад быстрый (около 9 единиц в секунду).
            val srcDb = when (distSource) {
                0 -> f.dbFull
                1 -> f.dbMidHigh
                else -> f.dbHigh
            }
            val centerDb = when (distSource) {
                0 -> centerFull
                1 -> centerMidHigh
                else -> centerHigh
            }
            val audible = f.dbFull > -70f
            val drive = if (audible) (0.5f + (srcDb - centerDb) / max(1f, distWindow)).coerceIn(0f, 1f) else 0f
            var target = drive
            if (holdGlitch) target = 1f
            if (manualHit) dist = min(1f, dist + 0.35f)
            val distRate = if (target > dist) 6f else 9f
            dist += (target - dist) * (1f - exp(-dt * distRate))

            // Text Glitch: строки медленно дрейфуют всегда, быстрее при искажении, рывок на удар.
            // Правая полоса жестов задаёт коэффициент ускорения сдвига строк (x0,25..x4).
            val sm = ((dist - 0.1f) / 0.8f).coerceIn(0f, 1f)
            val smooth = sm * sm * (3f - 2f * sm)
            val rowCoef = if (mode == TEXT_MODE) 2f.pow((param - 0.5f) * 4f) else 1f
            val scrollRate = (0.02f + 0.10f * smooth + 0.22f * beatEnv) * rowCoef
            textScroll = (textScroll + dt * scrollRate) % 1f
            if (text != textUploaded) uploadText(text)

            // Photo Glitch: обмен кусков на акцентах. Размер зависит от триггера:
            // кик (бас-удар) крупные, середина средние, высокие мелкие. Число кусков от уровня звука.
            val midHit = f.midOnset != lastMidOnset
            val highHit = f.highOnset != lastHighOnset
            lastMidOnset = f.midOnset
            lastHighOnset = f.highOnset
            swapEnv *= exp(-dt * 6f)
            if (mode == PHOTO_MODE && photoSwap && audible) {
                val cls = if (newBeat) 0 else if (midHit) 1 else if (highHit) 2 else -1
                if (cls >= 0) {
                    swapCls = cls
                    swapSeed = (swapSeed + 1f) % 997f
                    swapEnv = 1f
                    swapCount = 1f + floor(drive * 5f)
                }
            }

            // Photo Glitch: загрузка фото и смена по звуку
            if (mode == PHOTO_MODE) updatePhotos(now, drive, audible, newBeat)

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

    /** Нарисовать текст жирным узким шрифтом в атлас и загрузить в текстуру (вызывается из GL-потока). */
    private fun uploadText(s: String) {
        val bmp = Bitmap.createBitmap(TEXT_W, TEXT_H, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.BLACK)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = Color.WHITE
        paint.typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
        paint.textSize = 200f
        val str = if (s.isEmpty()) " " else s
        val w = max(1f, paint.measureText(str))
        val bounds = Rect()
        paint.getTextBounds(str, 0, str.length, bounds)
        val h = max(1f, bounds.height().toFloat())
        val sx = TEXT_W * 0.90f / w          // текст занимает 90% ширины, по бокам зазор для бегущей строки
        val sy = TEXT_H * 0.86f / h
        canvas.translate(TEXT_W * 0.05f - bounds.left * sx, TEXT_H * 0.07f - bounds.top * sy)
        canvas.scale(sx, sy)
        canvas.drawText(str, 0f, 0f, paint)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE2)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textTex)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        bmp.recycle()
        textUploaded = s
    }

    // ---------------- Photo Glitch ----------------

    /**
     * Фото декодируются в фоне (одно следующее заранее), в GL-потоке остаётся только загрузка в текстуру.
     * Смена по звуку: на акценте, но не чаще интервала, который сокращается от 8 с на тихом звуке
     * до минимального интервала (из меню) на громком; в тишине смены нет.
     */
    private fun updatePhotos(now: Long, drive: Float, audible: Boolean, beatHit: Boolean) {
        val files = photoFiles
        if (photosSeen != photosVersion) {
            photosSeen = photosVersion
            hasPhoto = false
            photoIndex = -1
            synchronized(photoLock) {
                pendingBitmap?.recycle()
                pendingBitmap = null
            }
        }
        if (files.isEmpty()) {
            hasPhoto = false
            return
        }

        // заказать декодирование следующего фото в фоне
        var startLoad = false
        var loadIdx = 0
        synchronized(photoLock) {
            if (!photoLoading && pendingBitmap == null) {
                photoLoading = true
                startLoad = true
                var k = if (files.size == 1) 0 else photoRandom.nextInt(files.size)
                if (files.size > 1 && k == photoIndex) k = (k + 1) % files.size
                loadIdx = k
            }
        }
        if (startLoad) {
            val file = files[loadIdx]
            val idx = loadIdx
            photoExecutor.execute {
                val bmp: Bitmap? = try {
                    BitmapFactory.decodeFile(file.path)
                } catch (e: Exception) {
                    null
                }
                synchronized(photoLock) {
                    pendingBitmap = bmp
                    pendingIndex = idx
                    photoLoading = false
                }
            }
        }

        val manual = nextPhotoRequests.get() != lastNextReq
        val loudNorm = drive
        val intervalMs = (8000f + (photoMinInterval * 1000f - 8000f) * loudNorm).toLong()
        val auto = photoAuto && files.size > 1 && audible && beatHit &&
            now - lastPhotoSwitchMs >= intervalMs

        if (!hasPhoto || manual || auto) {
            var bmp: Bitmap? = null
            var idx = -1
            synchronized(photoLock) {
                bmp = pendingBitmap
                idx = pendingIndex
                if (bmp != null) pendingBitmap = null
            }
            val b = bmp
            if (b != null) {
                if (idx in files.indices) uploadPhoto(b, idx) else b.recycle()
                lastPhotoSwitchMs = now
                if (manual) lastNextReq = nextPhotoRequests.get()
            }
        }
    }

    private fun uploadPhoto(bmp: Bitmap, idx: Int) {
        GLES20.glActiveTexture(GLES20.GL_TEXTURE3)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, photoTex)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        photoAspect = bmp.width.toFloat() / max(1, bmp.height)
        bmp.recycle()
        hasPhoto = true
        photoIndex = idx
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
        // текст для Text Glitch на третьем блоке текстур
        GLES20.glActiveTexture(GLES20.GL_TEXTURE2)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textTex)
        GLES20.glUniform1i(sceneProg.uniform("uText"), 2)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        // потолок искажения: у Text Glitch из меню, у Photo Glitch параметр на правой полосе (25..100%)
        val ceil = when (mode) {
            TEXT_MODE -> textCeil
            PHOTO_MODE -> 0.25f + 0.75f * param
            else -> 1f
        }
        GLES20.glUniform1f(sceneProg.uniform("uDist"), min(1f, dist * (1f + 0.12f * beatEnv)) * ceil)
        GLES20.glUniform1f(sceneProg.uniform("uScroll"), textScroll)
        // фото для Photo Glitch на четвёртом блоке текстур
        GLES20.glActiveTexture(GLES20.GL_TEXTURE3)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, photoTex)
        GLES20.glUniform1i(sceneProg.uniform("uPhoto"), 3)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glUniform1f(sceneProg.uniform("uAspect"), width.toFloat() / max(1, height))
        GLES20.glUniform1f(sceneProg.uniform("uPhotoAspect"), photoAspect)
        GLES20.glUniform1f(sceneProg.uniform("uFit"), if (photoFit) 1f else 0f)
        GLES20.glUniform1f(sceneProg.uniform("uHasPhoto"), if (hasPhoto) 1f else 0f)
        GLES20.glUniform1f(sceneProg.uniform("uSwapEnv"), if (photoSwap) swapEnv else 0f)
        GLES20.glUniform1f(sceneProg.uniform("uSwapSeed"), swapSeed)
        GLES20.glUniform1f(sceneProg.uniform("uSwapCls"), swapCls.toFloat())
        GLES20.glUniform1f(sceneProg.uniform("uSwapCount"), swapCount)
        GLES20.glUniform1f(sceneProg.uniform("uSwapScale"), 0.5f + photoSwapSize)
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
