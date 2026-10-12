package com.example.videoscope.audio

/** Неизменяемый «снимок» звука: анализатор кладёт его целиком, рендерер читает. */
class AudioFrame(
    val rms: Float,           // 0..1 общая громкость
    val bass: Float,          // 0..1
    val mid: Float,           // 0..1
    val high: Float,          // 0..1
    val beatCount: Long,      // растёт на 1 при каждом ударе
    val spectrum: FloatArray, // SPECTRUM_SIZE полос, 0..1
    val waveform: FloatArray, // WAVE_SIZE сэмплов, -1..1
    val peak: Float = 0f,     // пик блока после усиления (>= 0,98 = клиппинг)
    val dbFull: Float = -100f,    // полная громкость, дБ (RMS относительно полной шкалы)
    val dbMidHigh: Float = -100f, // энергия 250 Гц..10 кГц (середина и высокие), дБ
    val dbHigh: Float = -100f,    // энергия 2..10 кГц (высокие), дБ
    val midOnset: Long = 0L,      // счётчик акцентов середины (клэп, синтезатор)
    val highOnset: Long = 0L      // счётчик акцентов высоких (хэт)
) {
    companion object {
        const val SPECTRUM_SIZE = 64
        const val WAVE_SIZE = 512
        val EMPTY = AudioFrame(0f, 0f, 0f, 0f, 0L, FloatArray(SPECTRUM_SIZE), FloatArray(WAVE_SIZE))
    }
}
