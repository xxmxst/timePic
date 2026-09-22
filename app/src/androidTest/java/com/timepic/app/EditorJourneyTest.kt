package com.timepic.app

import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.timepic.app.model.WatermarkConfig
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class EditorJourneyTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun manualAddressFixedTimeImportEditSaveAndRestoreCamera() {
        lateinit var model: TimePicViewModel
        compose.runOnUiThread { model = ViewModelProvider(compose.activity)[TimePicViewModel::class.java] }
        compose.waitUntil(10_000) { model.state.value.ready }
        compose.runOnUiThread { model.stopLocation(); model.updateConfig { WatermarkConfig() } }
        compose.onNodeWithText("地址", useUnmergedTree = true).performClick()
        compose.onNode(hasSetTextAction()).performTextReplacement("上海市浦东新区测试园区")
        compose.onNodeWithText("使用此地址").performClick()
        compose.onNodeWithText("时间", useUnmergedTree = true).performClick()
        compose.onNodeWithText("使用固定时间").performClick()
        val fixedTime = model.state.value.config.fixedTimeMillis
        compose.onNodeWithText("工程记录", useUnmergedTree = true).performClick()
        compose.onNodeWithText("备注", useUnmergedTree = true).performClick()
        compose.onNode(hasSetTextAction()).performTextReplacement("现场检查已完成")
        compose.onNodeWithText("完成").performClick()
        val source = File(compose.activity.cacheDir, "journey-source.jpg")
        val bitmap = Bitmap.createBitmap(1200, 1600, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(67, 92, 122)) }
        source.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) }
        bitmap.recycle()
        var saved: Uri? = null
        try {
            compose.runOnUiThread { model.importPhoto(Uri.fromFile(source)) }
            compose.waitUntil(10_000) { model.state.value.draft != null && model.state.value.busy == null }
            assertEquals(fixedTime, model.state.value.draft!!.timestamp)
            compose.onNodeWithText("编辑照片").assertIsDisplayed()
            compose.activityRule.scenario.recreate()
            compose.runOnUiThread { model = ViewModelProvider(compose.activity)[TimePicViewModel::class.java] }
            compose.waitUntil(10_000) { model.state.value.draft != null && model.state.value.busy == null }
            assertEquals(fixedTime, model.state.value.draft!!.timestamp)
            compose.onNodeWithContentDescription("水印预览").performTouchInput {
                swipe(start = androidx.compose.ui.geometry.Offset(width * .4f, height * .85f),
                    end = androidx.compose.ui.geometry.Offset(width * .5f, height * .5f))
            }
            compose.runOnUiThread {
                compose.activity.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            }
            compose.waitUntil(10_000) {
                compose.activity.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
            }
            compose.onNodeWithText("编辑照片").assertIsDisplayed()
            compose.runOnUiThread {
                compose.activity.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            }
            compose.waitUntil(10_000) {
                compose.activity.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT
            }
            compose.onNodeWithText("保存到相册").performClick()
            compose.waitUntil(20_000) { model.state.value.savedUri != null }
            saved = model.state.value.savedUri
            compose.onNodeWithText("已保存到系统相册").assertIsDisplayed()
            assertEquals("上海市浦东新区测试园区", model.state.value.config.place.text)
            compose.onNodeWithContentDescription("返回相机").performClick()
            compose.waitUntil(5000) { model.state.value.draft == null }
            compose.onNodeWithContentDescription("从相册选图").assertExists()
        } finally {
            saved?.let { compose.activity.contentResolver.delete(it, null, null) }
            source.delete()
            compose.runOnUiThread {
                model.closeDraft()
                model.updateConfig { WatermarkConfig() }
                compose.activity.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            }
        }
    }
}
