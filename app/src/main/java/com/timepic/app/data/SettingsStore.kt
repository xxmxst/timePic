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
    private fun JSONObject.optFiniteDouble(key: String, fallback: Double? = null): Double? {
        val value = opt(key)
        val number = when (value) {
            is Number -> value.toDouble()
            is String -> value.toDoubleOrNull()
            else -> null
        }
        return number?.takeIf { it.isFinite() } ?: fallback
    }

    fun encode(config: WatermarkConfig): String = JSONObject().apply {
        put("schemaVersion", 1)
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
        val template = when (obj.optString("template")) {
            Template.CHECK_IN.name -> Template.CHECK_IN
            Template.PROJECT.name -> Template.PROJECT
            else -> Template.SIMPLE
        }
        val timeMode = if (obj.optString("timeMode") == TimeMode.FIXED.name) TimeMode.FIXED else TimeMode.LIVE
        val source = when (obj.optString("source")) {
            LocationSource.SEARCH.name -> LocationSource.SEARCH
            LocationSource.MANUAL.name -> LocationSource.MANUAL
            else -> LocationSource.CURRENT
        }
        val fixedTime = when (val value = obj.opt("fixedTime")) {
            is Number -> value.toLong()
            is String -> value.toLongOrNull() ?: 0L
            else -> 0L
        }
        val latitude = obj.optFiniteDouble("lat")?.takeIf { it in -90.0..90.0 }
        val longitude = obj.optFiniteDouble("lon")?.takeIf { it in -180.0..180.0 }
        val scale = obj.optFiniteDouble("scale", 1.0)!!.toFloat().coerceIn(.75f, 1.4f)
        val anchorX = obj.optFiniteDouble("x", 0.0)!!.toFloat().coerceIn(0f, 1f)
        val anchorY = obj.optFiniteDouble("y", 1.0)!!.toFloat().coerceIn(0f, 1f)
        WatermarkConfig(
            template = template,
            timeMode = timeMode,
            fixedTimeMillis = fixedTime,
            place = Place(
                obj.optString("address").take(160),
                latitude,
                longitude,
                source
            ),
            note = obj.optString("note").take(100),
            scale = scale,
            anchorX = anchorX,
            anchorY = anchorY
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
