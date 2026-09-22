package com.timepic.app.imaging

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import com.timepic.app.model.*
import kotlin.math.min

/**
 * All text is laid out in a 1000-unit image coordinate system. Preview and JPEG
 * export use this exact renderer, including line breaks, padding and anchors.
 */
object WatermarkRenderer {
    private val gold = Color.rgb(245, 199, 106)
    private data class Line(val layout: StaticLayout, val gap: Float)
    private data class Block(
        val lines: List<Line>, val width: Float, val height: Float,
        val padding: Float, val factor: Float, val bounds: WatermarkBounds
    )

    private fun build(width: Int, height: Int, config: WatermarkConfig, timestamp: Long): Block {
        val documentHeight = 1000f * height / width.coerceAtLeast(1)
        val unit = min(1f, documentHeight / 1000f) * config.scale
        val padding = 25f * unit
        val blockWidth = min(750f * unit, 936f)
        val textWidth = (blockWidth - padding * 2).toInt().coerceAtLeast(1)
        val lines = mutableListOf<Line>()
        fun line(text: String, size: Float, bold: Boolean = false, color: Int = Color.WHITE, gap: Float = 10f) {
            if (text.isBlank()) return
            val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                textSize = size * unit
                this.color = color
                typeface = if (bold) Typeface.create("sans-serif", Typeface.BOLD) else Typeface.create("sans-serif", Typeface.NORMAL)
                if (config.template == Template.SIMPLE) setShadowLayer(3f * unit, 0f, 2f * unit, Color.BLACK)
            }
            val layout = StaticLayout.Builder.obtain(text, 0, text.length, paint, textWidth)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL).setIncludePad(false)
                .setLineSpacing(3f * unit, 1f).build()
            lines += Line(layout, gap * unit)
        }
        val address = config.place.text
        when (config.template) {
            Template.SIMPLE -> {
                line(formatTime(timestamp, "HH:mm:ss"), 92f, true)
                line(formatTime(timestamp, "yyyy.MM.dd  EEEE"), 32f)
                line(address, 32f, gap = 7f)
                line(config.note, 28f, color = Color.rgb(229, 232, 237), gap = 0f)
            }
            Template.CHECK_IN -> {
                line("日常打卡  /  TIMEPIC", 25f, true, gold, 16f)
                line(formatTime(timestamp, "HH:mm:ss"), 80f, true)
                line(formatTime(timestamp, "yyyy.MM.dd  EEEE"), 31f, gap = 18f)
                line(address, 33f)
                line(config.note, 29f, color = Color.rgb(225, 229, 235), gap = 0f)
            }
            Template.PROJECT -> {
                line("工程记录", 40f, true, gold, 20f)
                line("时间  " + formatTime(timestamp, "yyyy-MM-dd HH:mm:ss"), 32f)
                line(if (address.isBlank()) "" else "地点  $address", 32f)
                line(if (config.note.isBlank()) "" else "备注  ${config.note}", 30f, gap = 0f)
            }
        }
        val blockHeight = padding * 2 + lines.sumOf { (it.layout.height + it.gap).toDouble() }.toFloat()
        val margin = 30f * min(1f, documentHeight / 1000f)
        val factor = min(1f, min((1000f - margin * 2) / blockWidth, (documentHeight - margin * 2) / blockHeight))
        val bounds = watermarkBounds(1000f, documentHeight, blockWidth * factor,
            blockHeight * factor, margin, config.anchorX, config.anchorY)
        return Block(lines, blockWidth, blockHeight, padding, factor, bounds)
    }

    fun bounds(width: Int, height: Int, config: WatermarkConfig, timestamp: Long): WatermarkBounds {
        val block = build(width, height, config, timestamp)
        val ratio = width / 1000f
        return with(block.bounds) { WatermarkBounds(left * ratio, top * ratio, this.width * ratio, this.height * ratio) }
    }

    fun draw(canvas: Canvas, width: Int, height: Int, config: WatermarkConfig, timestamp: Long) {
        if (width <= 0 || height <= 0) return
        val block = build(width, height, config, timestamp)
        canvas.save()
        canvas.scale(width / 1000f, width / 1000f)
        canvas.translate(block.bounds.left, block.bounds.top)
        canvas.scale(block.factor, block.factor)
        if (config.template != Template.SIMPLE) {
            val background = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = if (config.template == Template.CHECK_IN) Color.argb(192, 20, 26, 34) else Color.argb(212, 17, 24, 31)
            }
            canvas.drawRoundRect(RectF(0f, 0f, block.width, block.height), 20f, 20f, background)
            if (config.template == Template.PROJECT) {
                background.color = gold
                canvas.drawRoundRect(RectF(0f, 20f, 7f, block.height - 20f), 3f, 3f, background)
            }
        }
        canvas.translate(block.padding, block.padding)
        block.lines.forEach {
            it.layout.draw(canvas)
            canvas.translate(0f, it.layout.height + it.gap)
        }
        canvas.restore()
    }
}
