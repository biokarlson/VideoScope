package com.example.videoscope.audio

/** Неизменяемый «снимок» звука: анализатор кладёт его целиком, рендерер читает. */
class AudioFrame(
    val rms: Float,           // 0..1 общая громкость
    val bass: Float,          // 0..1
    val mid: Float,           // 0..1
    val high: Float,          // 0..1
    val beatCount: Long,      // растёт на 1 при каждом ударе
    val spectrum: FloatArray, // SPECTRUM_SIZE полос, 0..1
    val waveform: FloatArray  // WAVE_SIZE сэмплов, -1..1
) {
    companion object {
        const val SPECTRUM_SIZE = 64
        const val WAVE_SIZE = 512
        val EMPTY = AudioFrame(0f, 0f, 0f, 0f, 0L, FloatArray(SPECTRUM_SIZE), FloatArray(WAVE_SIZE))
    }
}
