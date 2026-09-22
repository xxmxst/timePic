package com.timepic.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.timepic.app.imaging.PhotoStore
import com.timepic.app.imaging.WatermarkRenderer
import com.timepic.app.model.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ImagePipelineTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val store = PhotoStore(context)

    @Test fun allExifRotationsAndMirrorsAreNormalized() {
        val expected = listOf(
            intArrayOf(Color.RED, Color.GREEN, Color.BLUE, Color.YELLOW),
            intArrayOf(Color.GREEN, Color.RED, Color.YELLOW, Color.BLUE),
            intArrayOf(Color.YELLOW, Color.BLUE, Color.GREEN, Color.RED),
            intArrayOf(Color.BLUE, Color.YELLOW, Color.RED, Color.GREEN),
            intArrayOf(Color.RED, Color.BLUE, Color.GREEN, Color.YELLOW),
            intArrayOf(Color.BLUE, Color.RED, Color.YELLOW, Color.GREEN),
            intArrayOf(Color.YELLOW, Color.GREEN, Color.BLUE, Color.RED),
            intArrayOf(Color.GREEN, Color.YELLOW, Color.RED, Color.BLUE)
        )
        for (orientation in 1..8) {
            val file = fixture("orientation-$orientation.jpg")
            try {
                ExifInterface(file).apply {
                    setAttribute(ExifInterface.TAG_ORIENTATION, orientation.toString())
                    saveAttributes()
                }
                val bitmap = store.decode(file, 1600)
                try {
                    assertEquals(if (orientation >= 5) 240 else 400, bitmap.width)
                    assertEquals(if (orientation >= 5) 400 else 240, bitmap.height)
                    val pixels = intArrayOf(
                        bitmap.getPixel(bitmap.width / 4, bitmap.height / 4),
                        bitmap.getPixel(bitmap.width * 3 / 4, bitmap.height / 4),
                        bitmap.getPixel(bitmap.width / 4, bitmap.height * 3 / 4),
                        bitmap.getPixel(bitmap.width * 3 / 4, bitmap.height * 3 / 4)
                    )
                    expected[orientation - 1].forEachIndexed { i, color ->
                        assertTrue("orientation=$orientation corner=$i",
                            kotlin.math.abs(Color.red(color) - Color.red(pixels[i])) < 12 &&
                            kotlin.math.abs(Color.green(color) - Color.green(pixels[i])) < 12 &&
                            kotlin.math.abs(Color.blue(color) - Color.blue(pixels[i])) < 12)
                    }
                } finally { bitmap.recycle() }
            } finally { file.delete() }
        }
    }

    @Test fun templatesRemainWithinBothPortraitAndLandscapeImages() {
        for (template in Template.entries) {
            for ((w, h) in listOf(900 to 1200, 1200 to 900, 1600 to 400)) {
                val config = WatermarkConfig(template = template, scale = 1.4f,
                    place = Place("北京市朝阳区望京街道某某园区研发中心一号楼".repeat(5)),
                    note = "现场检查记录，设备运行正常。".repeat(5), anchorX = 1f, anchorY = 1f)
                val bounds = WatermarkRenderer.bounds(w, h, config, 1_700_000_000_000)
                assertTrue(bounds.left >= 0 && bounds.top >= 0)
                assertTrue(bounds.left + bounds.width <= w + 1)
                assertTrue(bounds.top + bounds.height <= h + 1)
                val scaled = WatermarkRenderer.bounds(w * 2, h * 2, config, 1_700_000_000_000)
                assertEquals(bounds.left * 2, scaled.left, 1f)
                assertEquals(bounds.top * 2, scaled.top, 1f)
                val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                try {
                    bitmap.eraseColor(Color.rgb(60, 75, 100))
                    WatermarkRenderer.draw(Canvas(bitmap), w, h, config, 1_700_000_000_000)
                    assertEquals(Color.rgb(60, 75, 100), bitmap.getPixel(0, 0))
                } finally { bitmap.recycle() }
            }
        }
    }

    @Test fun importExportLeavesSourceUntouchedAndProducesReadableJpeg() = runBlocking {
        val source = fixture("original.jpg")
        val originalBytes = source.readBytes()
        var imported: File? = null
        var saved: Uri? = null
        try {
            imported = store.import(Uri.fromFile(source))
            saved = store.export(PhotoDraft(imported.path, 1_700_000_000_000, true),
                WatermarkConfig(template = Template.PROJECT, place = Place("测试地点"), note = "导出验证"))
            assertArrayEquals(originalBytes, source.readBytes())
            val output = context.contentResolver.openInputStream(saved)!!.use { android.graphics.BitmapFactory.decodeStream(it) }
            assertNotNull(output)
            assertEquals(400, output.width)
            assertEquals(240, output.height)
            // Watermark is actually burned into the lower portion of the JPEG.
            var changed = 0
            val original = store.decode(source, 1600)
            for (y in output.height / 2 until output.height step 4) {
                for (x in 0 until output.width step 4) {
                    if (kotlin.math.abs(Color.red(output.getPixel(x, y)) - Color.red(original.getPixel(x, y))) > 30) changed++
                }
            }
            assertTrue("export contains a visible watermark", changed > 20)
            original.recycle()
            output.recycle()
        } finally {
            saved?.let { context.contentResolver.delete(it, null, null) }
            imported?.let { store.discard(it.path) }
            source.delete()
        }
    }

    private fun fixture(name: String): File {
        val bitmap = Bitmap.createBitmap(400, 240, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint()
        listOf(Color.RED, Color.GREEN, Color.BLUE, Color.YELLOW).forEachIndexed { index, color ->
            paint.color = color
            val left = (index % 2) * 200f
            val top = (index / 2) * 120f
            canvas.drawRect(left, top, left + 200, top + 120, paint)
        }
        val file = File(context.cacheDir, name)
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 100, it) }
        bitmap.recycle()
        return file
    }
}
