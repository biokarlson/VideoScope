package com.example.videoscope.audio

import android.annotation.SuppressLint
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.SystemClock
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Захват микрофона (AudioRecord) в фоновом потоке + анализ:
 * RMS, три полосы (bass/mid/high), 64-полосный спектр, осциллограмма, детектор удара.
 * Результат публикуется как неизменяемый AudioFrame через @Volatile.
 * gain умножает PCM-сэмплы ДО анализа.
 */
class AudioEngine {

    companion object {
        const val SAMPLE_RATE = 44100
        const val FFT_SIZE = 2048
        const val HOP = 512
    }

    @Volatile var gain: Float = 1f

    /** Чувствительность детектора удара, 0..1 (0,5 = прежние пороги). */
    @Volatile var beatSensitivity: Float = 0.5f

    /** Реакция на звук: 0 = резкая, 1 = плавная (0,5 = прежнее сглаживание). */
    @Volatile var reaction: Float = 0.5f

    /** Источник: 0 = обычный (MIC), 1 = без обработки (UNPROCESSED, если поддерживается). */
    @Volatile var source: Int = 0

    /** Предпочтительное устройство ввода (например, USB) или null = автоматически. */
    @Volatile var preferredDevice: AudioDeviceInfo? = null

    /** Автоусиление: медленная подстройка поверх ручного усиления в пределах ±2 ступеней. */
    @Volatile var autoGain: Boolean = false

    /** Текущая поправка автоусиления в ступенях (для индикации). */
    @Volatile var autoStep: Float = 0f

    @Volatile
    var frame: AudioFrame = AudioFrame.EMPTY
        private set

    @Volatile private var running = false
    private var record: AudioRecord? = null
    private var thread: Thread? = null

    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        if (running) return true
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuf <= 0) return false
        val bufSize = max(minBuf, HOP * 8)
        var created: AudioRecord? = null
        if (source == 1 && Build.VERSION.SDK_INT >= 24) {
            created = createRecord(MediaRecorder.AudioSource.UNPROCESSED, bufSize)
        }
        if (created == null) created = createRecord(MediaRecorder.AudioSource.MIC, bufSize)
        val rec = created ?: return false
        val device = preferredDevice
        if (device != null && Build.VERSION.SDK_INT >= 23) {
            rec.setPreferredDevice(device)
        }
        try {
            rec.startRecording()
        } catch (e: IllegalStateException) {
            rec.release()
            return false
        }
        record = rec
        running = true
        thread = Thread({ loop(rec) }, "AudioEngine").also { it.start() }
        return true
    }

    @SuppressLint("MissingPermission")
    private fun createRecord(audioSource: Int, bufSize: Int): AudioRecord? {
        return try {
            val r = AudioRecord(
                audioSource, SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufSize
            )
            if (r.state == AudioRecord.STATE_INITIALIZED) {
                r
            } else {
                r.release()
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    fun stop() {
        running = false
        try {
            thread?.join(500)
        } catch (_: InterruptedException) {
        }
        thread = null
        record?.let {
            try { it.stop() } catch (_: IllegalStateException) {}
            it.release()
        }
        record = null
    }

    private fun toLevel(amp: Float, floorDb: Float, rangeDb: Float): Float =
        ((20f * log10(amp + 1e-9f) - floorDb) / rangeDb).coerceIn(0f, 1f)

    /** Сглаживание: быстрый подъём, медленный спад. Ползунок реакции смещает оба коэффициента. */
    private fun smooth(prev: Float, v: Float, attack: Float, decay: Float): Float {
        val f = (reaction - 0.5f) * 2f // -1 = резкая, +1 = плавная
        val a = (attack * (1f - 0.6f * f)).coerceIn(0.05f, 1f)
        val d = (1f - (1f - decay) * (1f - 0.7f * f)).coerceIn(0f, 0.995f)
        return if (v > prev) prev + (v - prev) * a else prev * d + v * (1f - d)
    }

    private fun loop(rec: AudioRecord) {
        val spec = AudioFrame.SPECTRUM_SIZE
        val pcm = ShortArray(HOP)
        val window = FloatArray(FFT_SIZE)
        val hann = FloatArray(FFT_SIZE) {
            (0.5 - 0.5 * cos(2.0 * PI * it / (FFT_SIZE - 1))).toFloat()
        }
        val re = FloatArray(FFT_SIZE)
        val im = FloatArray(FFT_SIZE)
        val amp = FloatArray(FFT_SIZE / 2)
        val fft = Fft(FFT_SIZE)
        val ampScale = 4f / FFT_SIZE          // амплитуда синуса с окном Ханна
        val binHz = SAMPLE_RATE.toFloat() / FFT_SIZE

        // границы полос спектра (логарифмические, 40 Гц .. 12 кГц)
        val lo = IntArray(spec)
        val hi = IntArray(spec)
        val fMin = 40f
        val fMax = 12000f
        for (k in 0 until spec) {
            val f0 = fMin * (fMax / fMin).pow(k.toFloat() / spec)
            val f1 = fMin * (fMax / fMin).pow((k + 1f) / spec)
            lo[k] = max(1, (f0 / binHz).toInt())
            hi[k] = max(lo[k] + 1, (f1 / binHz).toInt())
        }
        val bassLo = max(1, (20f / binHz).roundToInt())
        val bassHi = (250f / binHz).roundToInt()
        val midHi = (2000f / binHz).roundToInt()
        val highHi = (10000f / binHz).roundToInt()

        val spectrumSmooth = FloatArray(spec)
        var sRms = 0f
        var sBass = 0f
        var sMid = 0f
        var sHigh = 0f
        var beatCount = 0L
        var avgBassEnergy = 0f
        var lastBeatMs = 0L
        var avgRms = 0f

        while (running) {
            // 1. читаем блок PCM
            var read = 0
            while (read < HOP && running) {
                val n = rec.read(pcm, read, HOP - read)
                if (n < 0) { running = false; break }
                read += n
            }
            if (read < HOP) break

            // 2. сдвигаем окно и добавляем новые сэмплы (с учётом gain)
            val g = gain * (if (autoGain) 2f.pow(autoStep) else 1f)
            System.arraycopy(window, HOP, window, 0, FFT_SIZE - HOP)
            var blockPeak = 0f
            for (i in 0 until HOP) {
                val raw = pcm[i] / 32768f * g
                val a = abs(raw)
                if (a > blockPeak) blockPeak = a
                window[FFT_SIZE - HOP + i] = raw.coerceIn(-1f, 1f)
            }

            // 3. RMS по последним 1024 сэмплам
            var sum = 0f
            for (i in FFT_SIZE - 1024 until FFT_SIZE) sum += window[i] * window[i]
            val rms = sqrt(sum / 1024f)

            // автоусиление: медленная подстройка к целевому уровню, в тишине не меняется
            avgRms += (rms - avgRms) * (1f / 170f)
            if (autoGain) {
                if (avgRms > 0.004f) {
                    val err = ln(0.12f / avgRms) / ln(2f)
                    autoStep = (autoStep + err.coerceIn(-1f, 1f) * 0.004f).coerceIn(-2f, 2f)
                }
            } else if (autoStep != 0f) {
                autoStep = 0f
            }

            // 4. осциллограмма со «стабилизацией» по переходу через ноль
            var start = 1024
            for (s in 512..1024) {
                if (window[s] <= 0f && window[s + 1] > 0f) { start = s; break }
            }
            val wave = FloatArray(AudioFrame.WAVE_SIZE)
            for (i in wave.indices) wave[i] = window[start + i * 2]

            // 5. FFT
            for (i in 0 until FFT_SIZE) {
                re[i] = window[i] * hann[i]
                im[i] = 0f
            }
            fft.transform(re, im)
            for (i in amp.indices) amp[i] = sqrt(re[i] * re[i] + im[i] * im[i]) * ampScale

            // 6. полосы
            var bassSum = 0f
            var bassEnergy = 0f
            for (i in bassLo until bassHi) { bassSum += amp[i]; bassEnergy += amp[i] * amp[i] }
            val bassCount = max(1, bassHi - bassLo)
            bassEnergy /= bassCount
            val bassLevel = toLevel(bassSum / bassCount, -80f, 55f)

            var midSum = 0f
            for (i in bassHi until midHi) midSum += amp[i]
            val midLevel = toLevel(midSum / max(1, midHi - bassHi), -80f, 55f)

            var highMax = 0f
            for (i in midHi until highHi) if (amp[i] > highMax) highMax = amp[i]
            val highLevel = toLevel(highMax, -80f, 55f)

            // 7. спектр
            val spectrumOut = FloatArray(spec)
            for (k in 0 until spec) {
                var m = 0f
                for (i in lo[k] until hi[k]) if (amp[i] > m) m = amp[i]
                val v = toLevel(m, -80f, 55f)
                spectrumSmooth[k] = smooth(spectrumSmooth[k], v, 0.6f, 0.88f)
                spectrumOut[k] = spectrumSmooth[k]
            }

            // 8. сглаживание (быстрый подъём, медленный спад)
            sRms = smooth(sRms, toLevel(rms, -60f, 54f), 0.5f, 0.9f)
            sBass = smooth(sBass, bassLevel, 0.7f, 0.88f)
            sMid = smooth(sMid, midLevel, 0.6f, 0.9f)
            sHigh = smooth(sHigh, highLevel, 0.8f, 0.8f)

            // 9. удар: энергия баса против скользящего среднего (~1 с)
            val now = SystemClock.elapsedRealtime()
            val sens = beatSensitivity
            val ratio = (2.5f - 1.6f * sens).coerceAtLeast(1.1f) // 0,5 -> 1,7
            val gate = 0.4f - 0.2f * sens                         // 0,5 -> 0,3
            if (bassEnergy > avgBassEnergy * ratio + 2e-7f &&
                bassLevel > gate && now - lastBeatMs > 160
            ) {
                beatCount++
                lastBeatMs = now
            }
            avgBassEnergy += (bassEnergy - avgBassEnergy) * (1f / 86f)

            frame = AudioFrame(sRms, sBass, sMid, sHigh, beatCount, spectrumOut, wave, blockPeak)
        }
    }
}
