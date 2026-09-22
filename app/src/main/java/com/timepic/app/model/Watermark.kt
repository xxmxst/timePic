package com.timepic.app.model

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.max

enum class Template(val title: String, val description: String) {
    SIMPLE("简洁时间", "纯净文字 · 留住此刻"),
    CHECK_IN("日常打卡", "温暖卡片 · 记录日常"),
    PROJECT("工程记录", "清晰分行 · 现场记录")
}
enum class TimeMode { LIVE, FIXED }
enum class LocationSource { CURRENT, SEARCH, MANUAL }

data class Place(
    val text: String = "",
    val latitude: Double? = null,
    val longitude: Double? = null,
    val source: LocationSource = LocationSource.CURRENT
)

data class WatermarkConfig(
    val template: Template = Template.SIMPLE,
    val timeMode: TimeMode = TimeMode.LIVE,
    val fixedTimeMillis: Long = 0L,
    val place: Place = Place(),
    val note: String = "",
    val scale: Float = 1f,
    val anchorX: Float = 0f,
    val anchorY: Float = 1f
) {
    fun resolveTime(nowMillis: Long): Long =
        if (timeMode == TimeMode.FIXED) fixedTimeMillis else nowMillis

    fun withManualAddress(text: String): WatermarkConfig =
        copy(place = Place(text.trim().take(160), source = LocationSource.MANUAL))
}

data class PhotoDraft(val path: String, val timestamp: Long, val imported: Boolean)
data class WatermarkBounds(val left: Float, val top: Float, val width: Float, val height: Float)

fun watermarkBounds(
    imageWidth: Float, imageHeight: Float,
    blockWidth: Float, blockHeight: Float,
    margin: Float, x: Float, y: Float
): WatermarkBounds {
    val freeX = max(0f, imageWidth - margin * 2 - blockWidth)
    val freeY = max(0f, imageHeight - margin * 2 - blockHeight)
    return WatermarkBounds(
        margin + freeX * x.coerceIn(0f, 1f),
        margin + freeY * y.coerceIn(0f, 1f),
        blockWidth, blockHeight
    )
}

fun formatTime(millis: Long, pattern: String, zone: ZoneId = ZoneId.systemDefault()): String =
    DateTimeFormatter.ofPattern(pattern, Locale.SIMPLIFIED_CHINESE)
        .format(Instant.ofEpochMilli(millis).atZone(zone))
