package com.timepic.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.timepic.app.data.ConfigCodec
import com.timepic.app.model.Template
import com.timepic.app.model.TimeMode
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SettingsCodecTest {
    @Test fun invalidStoredEnumFallsBackWithoutDroppingOtherFields() {
        val config = ConfigCodec.decode("""{
            "template":"UNKNOWN",
            "timeMode":"UNKNOWN",
            "fixedTime":"1700000000000",
            "address":"测试地址",
            "source":"UNKNOWN",
            "note":"测试备注",
            "scale":1.2,
            "x":0.25,
            "y":0.75
        }""")
        assertEquals(Template.SIMPLE, config.template)
        assertEquals(TimeMode.LIVE, config.timeMode)
        assertEquals(1700000000000L, config.fixedTimeMillis)
        assertEquals("测试地址", config.place.text)
        assertEquals("测试备注", config.note)
        assertEquals(.25f, config.anchorX, .001f)
        assertEquals(.75f, config.anchorY, .001f)
    }

    @Test fun malformedNumbersOnlyResetAffectedFields() {
        val config = ConfigCodec.decode("""{
            "address":"保留地址",
            "note":"保留备注",
            "lat":"bad",
            "lon":200,
            "scale":"bad",
            "x":"bad",
            "y":"NaN"
        }""")
        assertEquals("保留地址", config.place.text)
        assertEquals("保留备注", config.note)
        assertEquals(null, config.place.latitude)
        assertEquals(null, config.place.longitude)
        assertEquals(1f, config.scale, .001f)
        assertEquals(0f, config.anchorX, .001f)
        assertEquals(1f, config.anchorY, .001f)
    }
}
