package com.example.videoscope.config

import android.content.Context

/** Настройки в SharedPreferences. */
class AppSettings(context: Context) {
    private val prefs = context.getSharedPreferences("videoscope", Context.MODE_PRIVATE)

    var gain: Float
        get() = prefs.getFloat("gain", 1.0f).coerceIn(0.1f, 5.0f)
        set(v) { prefs.edit().putFloat("gain", v.coerceIn(0.1f, 5.0f)).apply() }

    /** Сила глитча/VHS/шума (0 = чистая картинка). */
    var glitch: Float
        get() = prefs.getFloat("glitch2", 0.35f).coerceIn(0f, 1f)
        set(v) { prefs.edit().putFloat("glitch2", v.coerceIn(0f, 1f)).apply() }

    var mode: Int
        get() = prefs.getInt("mode", 0)
        set(v) { prefs.edit().putInt("mode", v).apply() }

    /** Дополнительный параметр режима (цвет / скорость / ширина), по одному на режим. */
    fun getParam(i: Int): Float = prefs.getFloat("param" + i, 0.5f).coerceIn(0f, 1f)

    fun setParam(i: Int, v: Float) {
        prefs.edit().putFloat("param" + i, v.coerceIn(0f, 1f)).apply()
    }
}
