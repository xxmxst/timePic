package com.timepic.app

import com.timepic.app.model.*
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class WatermarkLogicTest {
    @Test fun fixedTimeDoesNotAdvanceAcrossPhotos() {
        val fixed = Instant.parse("2024-02-29T12:34:56Z").toEpochMilli()
        val config = WatermarkConfig(timeMode = TimeMode.FIXED, fixedTimeMillis = fixed)
        assertEquals(fixed, config.resolveTime(fixed + 60_000))
        assertEquals(fixed, config.resolveTime(fixed + 86_400_000))
    }
    @Test fun liveModeUsesTheCaptureOrImportInstant() {
        val config = WatermarkConfig(timeMode = TimeMode.LIVE, fixedTimeMillis = 123)
        assertEquals(1000L, config.resolveTime(1000L))
        assertEquals(9000L, config.resolveTime(9000L))
        val draft = PhotoDraft("source", config.resolveTime(1000), true)
        assertEquals(1000L, draft.timestamp)
    }
    @Test fun manualAddressDiscardsUnrelatedCoordinates() {
        val config = WatermarkConfig(place = Place("旧地址", 31.2, 121.5, LocationSource.CURRENT))
        val manual = config.withManualAddress("  北京市朝阳区  ")
        assertEquals("北京市朝阳区", manual.place.text)
        assertEquals(LocationSource.MANUAL, manual.place.source)
        assertNull(manual.place.latitude)
        assertNull(manual.place.longitude)
    }
    @Test fun anchorsClampAtAllImageEdges() {
        val bottomRight = watermarkBounds(1200f, 800f, 400f, 200f, 24f, 2f, 3f)
        assertEquals(776f, bottomRight.left, .01f)
        assertEquals(576f, bottomRight.top, .01f)
        val topLeft = watermarkBounds(1200f, 800f, 400f, 200f, 24f, -1f, -1f)
        assertEquals(24f, topLeft.left, .01f)
        assertEquals(24f, topLeft.top, .01f)
    }
    @Test fun relativePlacementScalesWithExportResolution() {
        val small = watermarkBounds(300f, 400f, 90f, 60f, 9f, .3f, .8f)
        val large = watermarkBounds(3000f, 4000f, 900f, 600f, 90f, .3f, .8f)
        assertEquals(small.left * 10, large.left, .01f)
        assertEquals(small.top * 10, large.top, .01f)
    }
    @Test fun leapDayAndMidnightFormatInLocalZone() {
        val instant = Instant.parse("2024-02-28T16:00:01Z").toEpochMilli()
        assertEquals("2024-02-29 00:00:01", formatTime(instant, "yyyy-MM-dd HH:mm:ss", ZoneId.of("Asia/Shanghai")))
    }
}
