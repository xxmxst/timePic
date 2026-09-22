package com.timepic.app.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.timepic.app.model.*
import kotlinx.coroutines.flow.first
import org.json.JSONObject

private val Context.watermarkSettings by preferencesDataStore("watermark_settings")

object ConfigCodec {
    fun encode(config: WatermarkConfig): String = JSONObject().apply {
        put("template", config.template.name)
        put("timeMode", config.timeMode.name)
        put("fixedTime", config.fixedTimeMillis)
        put("address", config.place.text)
        put("source", config.place.source.name)
        config.place.latitude?.let { put("lat", it) }
        config.place.longitude?.let { put("lon", it) }
        put("note", config.note)
        put("scale", config.scale.toDouble())
        put("x", config.anchorX.toDouble())
        put("y", config.anchorY.toDouble())
    }.toString()

    fun decode(json: String?): WatermarkConfig = runCatching {
        if (json == null) return WatermarkConfig()
        val obj = JSONObject(json)
        WatermarkConfig(
            template = Template.valueOf(obj.optString("template", "SIMPLE")),
            timeMode = TimeMode.valueOf(obj.optString("timeMode", "LIVE")),
            fixedTimeMillis = obj.optLong("fixedTime"),
            place = Place(
                obj.optString("address").take(160),
                if (obj.has("lat")) obj.getDouble("lat") else null,
                if (obj.has("lon")) obj.getDouble("lon") else null,
                LocationSource.valueOf(obj.optString("source", "CURRENT"))
            ),
            note = obj.optString("note").take(100),
            scale = obj.optDouble("scale", 1.0).toFloat().coerceIn(.75f, 1.4f),
            anchorX = obj.optDouble("x", 0.0).toFloat().coerceIn(0f, 1f),
            anchorY = obj.optDouble("y", 1.0).toFloat().coerceIn(0f, 1f)
        )
    }.getOrDefault(WatermarkConfig())
}

class SettingsStore(private val context: Context) {
    private val key = stringPreferencesKey("config")
    suspend fun read(): WatermarkConfig = ConfigCodec.decode(context.watermarkSettings.data.first()[key])
    suspend fun write(config: WatermarkConfig) {
        context.watermarkSettings.edit { it[key] = ConfigCodec.encode(config) }
    }
}
