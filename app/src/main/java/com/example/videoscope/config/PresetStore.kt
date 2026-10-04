package com.example.videoscope.config

import android.content.Context
import org.json.JSONObject

/** Пресеты настроек: имя -> JSON, хранятся в отдельном SharedPreferences. */
class PresetStore(context: Context) {
    private val prefs = context.getSharedPreferences("videoscope_presets", Context.MODE_PRIVATE)

    fun names(): List<String> = prefs.all.keys.sorted()

    fun save(name: String, json: JSONObject) {
        prefs.edit().putString(name, json.toString()).apply()
    }

    fun load(name: String): JSONObject? {
        val s = prefs.getString(name, null) ?: return null
        return try {
            JSONObject(s)
        } catch (e: Exception) {
            null
        }
    }

    fun delete(name: String) {
        prefs.edit().remove(name).apply()
    }
}
