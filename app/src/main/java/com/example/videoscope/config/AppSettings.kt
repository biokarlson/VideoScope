package com.example.videoscope.config

import android.content.Context
import org.json.JSONObject
import kotlin.math.ln
import kotlin.math.roundToInt

/** Настройки в SharedPreferences. */
class AppSettings(context: Context) {
    private val prefs = context.getSharedPreferences("videoscope", Context.MODE_PRIVATE)

    /** Усиление микрофона в ступенях (-2..+2, шаг 0,25). Множитель = 2^ступень. */
    var gainStep: Float
        get() {
            if (prefs.contains("gainStep")) return prefs.getFloat("gainStep", 0f).coerceIn(-6f, 2f)
            // перенос старого значения-множителя (ключ "gain")
            val old = prefs.getFloat("gain", 1f).coerceAtLeast(0.01f)
            val step = (ln(old) / ln(2f)).coerceIn(-6f, 2f)
            return (step * 4f).roundToInt() / 4f
        }
        set(v) {
            val snapped = (v.coerceIn(-6f, 2f) * 4f).roundToInt() / 4f
            prefs.edit().putFloat("gainStep", snapped).apply()
        }

    /** Сила глитча/VHS/шума (0 = чистая картинка). */
    var glitch: Float
        get() = prefs.getFloat("glitch2", 0.35f).coerceIn(0f, 1f)
        set(v) { prefs.edit().putFloat("glitch2", v.coerceIn(0f, 1f)).apply() }

    // Эффекты: выключатель и сила (0..1). Итоговая сила = общая сила (glitch2) x сила эффекта.
    var vhsOn: Boolean
        get() = prefs.getBoolean("vhsOn", true)
        set(v) { prefs.edit().putBoolean("vhsOn", v).apply() }
    var vhsStrength: Float
        get() = prefs.getFloat("vhsStrength", 1f).coerceIn(0f, 1f)
        set(v) { prefs.edit().putFloat("vhsStrength", v.coerceIn(0f, 1f)).apply() }

    var glitchOn: Boolean
        get() = prefs.getBoolean("glitchOn", true)
        set(v) { prefs.edit().putBoolean("glitchOn", v).apply() }
    var glitchStrength: Float
        get() = prefs.getFloat("glitchStrength", 1f).coerceIn(0f, 1f)
        set(v) { prefs.edit().putFloat("glitchStrength", v.coerceIn(0f, 1f)).apply() }

    var noiseOn: Boolean
        get() = prefs.getBoolean("noiseOn", true)
        set(v) { prefs.edit().putBoolean("noiseOn", v).apply() }
    var noiseStrength: Float
        get() = prefs.getFloat("noiseStrength", 1f).coerceIn(0f, 1f)
        set(v) { prefs.edit().putFloat("noiseStrength", v.coerceIn(0f, 1f)).apply() }

    var mode: Int
        get() = prefs.getInt("mode", 0)
        set(v) { prefs.edit().putInt("mode", v).apply() }

    /** 0 = цветная, 1 = чёрно-белая, 2 = монохромная. */
    var palette: Int
        get() = prefs.getInt("palette", 0).coerceIn(0, 2)
        set(v) { prefs.edit().putInt("palette", v.coerceIn(0, 2)).apply() }

    /** Оттенок монохромной палитры, 0..1 (0 и 1 = красный). */
    var monoHue: Float
        get() = prefs.getFloat("monoHue", 0.58f).coerceIn(0f, 1f)
        set(v) { prefs.edit().putFloat("monoHue", v.coerceIn(0f, 1f)).apply() }

    /** Насыщенность монохромной палитры, 0..1. */
    var monoSat: Float
        get() = prefs.getFloat("monoSat", 0.85f).coerceIn(0f, 1f)
        set(v) { prefs.edit().putFloat("monoSat", v.coerceIn(0f, 1f)).apply() }

    /** Разрешена ли пауза по двойному тапу. */
    var pauseEnabled: Boolean
        get() = prefs.getBoolean("pauseEnabled", true)
        set(v) { prefs.edit().putBoolean("pauseEnabled", v).apply() }

    /** OSD: показывать ли индикаторы и сообщения на экране (ошибки показываются всегда). */
    var osdOn: Boolean
        get() = prefs.getBoolean("osdOn", true)
        set(v) { prefs.edit().putBoolean("osdOn", v).apply() }

    /** Защита от случайного выхода: двойное нажатие «Назад». */
    var exitGuard: Boolean
        get() = prefs.getBoolean("exitGuard", true)
        set(v) { prefs.edit().putBoolean("exitGuard", v).apply() }

    /** Режим при старте: -1 = последний использованный, иначе индекс режима. */
    var startMode: Int
        get() = prefs.getInt("startMode", -1).coerceAtLeast(-1)
        set(v) { prefs.edit().putInt("startMode", v.coerceAtLeast(-1)).apply() }

    /** Размер «пикселя»: число строк сцены по высоте (135 / 270 / 540). */
    var pixelRows: Int
        get() = prefs.getInt("pixelRows", 270)
        set(v) { prefs.edit().putInt("pixelRows", v).apply() }

    /** Чёрная рамка вокруг картинки со всех сторон, пикселей экрана (0..300). */
    var border: Int
        get() = prefs.getInt("border", 0).coerceIn(0, 300)
        set(v) { prefs.edit().putInt("border", v.coerceIn(0, 300)).apply() }

    /** Чувствительность детектора удара, 0..1 (0,5 = значения до этапа 5). */
    var beatSensitivity: Float
        get() = prefs.getFloat("beatSensitivity", 0.5f).coerceIn(0f, 1f)
        set(v) { prefs.edit().putFloat("beatSensitivity", v.coerceIn(0f, 1f)).apply() }

    /** Блокировка жестов на экране (остаётся только тап двумя пальцами). */
    var gestureLock: Boolean
        get() = prefs.getBoolean("gestureLock", false)
        set(v) { prefs.edit().putBoolean("gestureLock", v).apply() }

    /** Реакция на звук: 0 = резкая, 1 = плавная. */
    var reaction: Float
        get() = prefs.getFloat("reaction", 0.5f).coerceIn(0f, 1f)
        set(v) { prefs.edit().putFloat("reaction", v.coerceIn(0f, 1f)).apply() }

    /** Множитель скорости перестройки в виде ползунка 0..1 (0,5 = x1, диапазон x0,25..x4). */
    var speedMul: Float
        get() = prefs.getFloat("speedMul", 0.5f).coerceIn(0f, 1f)
        set(v) { prefs.edit().putFloat("speedMul", v.coerceIn(0f, 1f)).apply() }

    /** Источник микрофона: 0 = обычный, 1 = без обработки. */
    var micSource: Int
        get() = prefs.getInt("micSource", 0).coerceIn(0, 1)
        set(v) { prefs.edit().putInt("micSource", v.coerceIn(0, 1)).apply() }

    /** Выбранное устройство ввода: "auto" или название (не входит в пресеты). */
    var micDevice: String
        get() = prefs.getString("micDevice", "auto") ?: "auto"
        set(v) { prefs.edit().putString("micDevice", v).apply() }

    var autoGain: Boolean
        get() = prefs.getBoolean("autoGain", false)
        set(v) { prefs.edit().putBoolean("autoGain", v).apply() }

    /** Потолок искажения Text Glitch, 0..1 (на самом громком искажение не больше этого значения). */
    var textCeiling: Float
        get() = prefs.getFloat("textCeiling", 0.7f).coerceIn(0f, 1f)
        set(v) { prefs.edit().putFloat("textCeiling", v.coerceIn(0f, 1f)).apply() }

    /** Источник искажения (Text Glitch и Photo Glitch): 0 = полная громкость, 1 = середина и высокие, 2 = высокие. */
    var distSource: Int
        get() = prefs.getInt("distSource", 1).coerceIn(0, 2)
        set(v) { prefs.edit().putInt("distSource", v.coerceIn(0, 2)).apply() }

    /** Диапазон реакции, дБ: на столько дБ вокруг центра искажение идёт от 0 до максимума. */
    var distWindow: Float
        get() = prefs.getFloat("distWindow", 12f).coerceIn(6f, 30f)
        set(v) { prefs.edit().putFloat("distWindow", v.coerceIn(6f, 30f)).apply() }

    /** Центры калибровки, дБ: обычный уровень для каждого источника (значения по умолчанию подходят для усиления −3). */
    var calCenterFull: Float
        get() = prefs.getFloat("calCenterFull", -30f).coerceIn(-120f, 10f)
        set(v) { prefs.edit().putFloat("calCenterFull", v.coerceIn(-120f, 10f)).apply() }
    var calCenterMidHigh: Float
        get() = prefs.getFloat("calCenterMidHigh", -35f).coerceIn(-120f, 10f)
        set(v) { prefs.edit().putFloat("calCenterMidHigh", v.coerceIn(-120f, 10f)).apply() }
    var calCenterHigh: Float
        get() = prefs.getFloat("calCenterHigh", -42f).coerceIn(-120f, 10f)
        set(v) { prefs.edit().putFloat("calCenterHigh", v.coerceIn(-120f, 10f)).apply() }

    /** Photo Glitch: автосмена фото по звуку. */
    var photoAuto: Boolean
        get() = prefs.getBoolean("photoAuto", true)
        set(v) { prefs.edit().putBoolean("photoAuto", v).apply() }

    /** Photo Glitch: минимальный интервал смены фото, секунд (0,5..10). */
    var photoInterval: Float
        get() = prefs.getFloat("photoInterval", 0.5f).coerceIn(0.5f, 10f)
        set(v) { prefs.edit().putFloat("photoInterval", v.coerceIn(0.5f, 10f)).apply() }

    /** Photo Glitch: обмен кусков изображения на акцентах. */
    var photoSwap: Boolean
        get() = prefs.getBoolean("photoSwap", true)
        set(v) { prefs.edit().putBoolean("photoSwap", v).apply() }

    /** Photo Glitch: масштаб размера кусков, 0..1 (0,5 = обычный размер). */
    var photoSwapSize: Float
        get() = prefs.getFloat("photoSwapSize", 0.5f).coerceIn(0f, 1f)
        set(v) { prefs.edit().putFloat("photoSwapSize", v.coerceIn(0f, 1f)).apply() }

    /** Photo Glitch: false = заполнить экран (края обрезаются), true = вписать с чёрными полями. */
    var photoFit: Boolean
        get() = prefs.getBoolean("photoFit", false)
        set(v) { prefs.edit().putBoolean("photoFit", v).apply() }

    /** Текст для режима Text Glitch. */
    var glitchText: String
        get() = prefs.getString("glitchText", "ЧИСТЫЕ ВЕЧЕРИНКИ") ?: "ЧИСТЫЕ ВЕЧЕРИНКИ"
        set(v) { prefs.edit().putString("glitchText", v).apply() }

    /** Показывать начальный экран с инструкцией при запуске. */
    var showStartScreen: Boolean
        get() = prefs.getBoolean("showStartScreen", true)
        set(v) { prefs.edit().putBoolean("showStartScreen", v).apply() }

    /** Все настройки, входящие в пресет (без последней вкладки и устройства ввода). */
    fun exportPreset(): JSONObject {
        val o = JSONObject()
        o.put("gainStep", gainStep.toDouble())
        o.put("glitch", glitch.toDouble())
        o.put("vhsOn", vhsOn)
        o.put("vhsStrength", vhsStrength.toDouble())
        o.put("glitchOn", glitchOn)
        o.put("glitchStrength", glitchStrength.toDouble())
        o.put("noiseOn", noiseOn)
        o.put("noiseStrength", noiseStrength.toDouble())
        o.put("palette", palette)
        o.put("monoHue", monoHue.toDouble())
        o.put("monoSat", monoSat.toDouble())
        o.put("mode", mode)
        o.put("startMode", startMode)
        o.put("pixelRows", pixelRows)
        o.put("border", border)
        o.put("speedMul", speedMul.toDouble())
        o.put("beatSensitivity", beatSensitivity.toDouble())
        o.put("reaction", reaction.toDouble())
        o.put("micSource", micSource)
        o.put("autoGain", autoGain)
        o.put("pauseEnabled", pauseEnabled)
        o.put("osdOn", osdOn)
        o.put("exitGuard", exitGuard)
        o.put("gestureLock", gestureLock)
        o.put("showStartScreen", showStartScreen)
        o.put("glitchText", glitchText)
        o.put("textCeiling", textCeiling.toDouble())
        o.put("distSource", distSource)
        o.put("distWindow", distWindow.toDouble())
        o.put("calCenterFull", calCenterFull.toDouble())
        o.put("calCenterMidHigh", calCenterMidHigh.toDouble())
        o.put("calCenterHigh", calCenterHigh.toDouble())
        o.put("photoAuto", photoAuto)
        o.put("photoInterval", photoInterval.toDouble())
        o.put("photoFit", photoFit)
        o.put("photoSwap", photoSwap)
        o.put("photoSwapSize", photoSwapSize.toDouble())
        for (i in 0 until 7) o.put("param" + i, getParam(i).toDouble())
        return o
    }

    /** Применить пресет: отсутствующие в нём значения остаются прежними. */
    fun importPreset(o: JSONObject) {
        gainStep = o.optDouble("gainStep", gainStep.toDouble()).toFloat()
        glitch = o.optDouble("glitch", glitch.toDouble()).toFloat()
        vhsOn = o.optBoolean("vhsOn", vhsOn)
        vhsStrength = o.optDouble("vhsStrength", vhsStrength.toDouble()).toFloat()
        glitchOn = o.optBoolean("glitchOn", glitchOn)
        glitchStrength = o.optDouble("glitchStrength", glitchStrength.toDouble()).toFloat()
        noiseOn = o.optBoolean("noiseOn", noiseOn)
        noiseStrength = o.optDouble("noiseStrength", noiseStrength.toDouble()).toFloat()
        palette = o.optInt("palette", palette)
        monoHue = o.optDouble("monoHue", monoHue.toDouble()).toFloat()
        monoSat = o.optDouble("monoSat", monoSat.toDouble()).toFloat()
        mode = o.optInt("mode", mode)
        startMode = o.optInt("startMode", startMode)
        pixelRows = o.optInt("pixelRows", pixelRows)
        border = o.optInt("border", border)
        speedMul = o.optDouble("speedMul", speedMul.toDouble()).toFloat()
        beatSensitivity = o.optDouble("beatSensitivity", beatSensitivity.toDouble()).toFloat()
        reaction = o.optDouble("reaction", reaction.toDouble()).toFloat()
        micSource = o.optInt("micSource", micSource)
        autoGain = o.optBoolean("autoGain", autoGain)
        pauseEnabled = o.optBoolean("pauseEnabled", pauseEnabled)
        osdOn = o.optBoolean("osdOn", osdOn)
        exitGuard = o.optBoolean("exitGuard", exitGuard)
        gestureLock = o.optBoolean("gestureLock", gestureLock)
        showStartScreen = o.optBoolean("showStartScreen", showStartScreen)
        glitchText = o.optString("glitchText", glitchText)
        textCeiling = o.optDouble("textCeiling", textCeiling.toDouble()).toFloat()
        distSource = o.optInt("distSource", distSource)
        distWindow = o.optDouble("distWindow", distWindow.toDouble()).toFloat()
        calCenterFull = o.optDouble("calCenterFull", calCenterFull.toDouble()).toFloat()
        calCenterMidHigh = o.optDouble("calCenterMidHigh", calCenterMidHigh.toDouble()).toFloat()
        calCenterHigh = o.optDouble("calCenterHigh", calCenterHigh.toDouble()).toFloat()
        photoAuto = o.optBoolean("photoAuto", photoAuto)
        photoInterval = o.optDouble("photoInterval", photoInterval.toDouble()).toFloat()
        photoFit = o.optBoolean("photoFit", photoFit)
        photoSwap = o.optBoolean("photoSwap", photoSwap)
        photoSwapSize = o.optDouble("photoSwapSize", photoSwapSize.toDouble()).toFloat()
        for (i in 0 until 7) setParam(i, o.optDouble("param" + i, getParam(i).toDouble()).toFloat())
    }

    /** Последняя открытая вкладка меню: 0 = Видео, 1 = Аудио, 2 = Интерфейс. */
    var lastTab: Int
        get() = prefs.getInt("lastTab", 0).coerceIn(0, 2)
        set(v) { prefs.edit().putInt("lastTab", v.coerceIn(0, 2)).apply() }

    /** Дополнительный параметр режима (цвет / скорость / ширина), по одному на режим. */
    fun getParam(i: Int): Float = prefs.getFloat("param" + i, 0.5f).coerceIn(0f, 1f)

    fun setParam(i: Int, v: Float) {
        prefs.edit().putFloat("param" + i, v.coerceIn(0f, 1f)).apply()
    }
}
