package com.timepic.app.imaging

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import com.timepic.app.model.PhotoDraft
import com.timepic.app.model.WatermarkConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlin.coroutines.coroutineContext
import kotlin.math.max

class PhotoStore(private val context: Context) {
    private val draftDirectory get() = File(context.filesDir, "drafts").apply { mkdirs() }

    fun newCaptureFile(): File = File(draftDirectory, "${UUID.randomUUID()}.jpg")

    suspend fun import(uri: Uri): File = withContext(Dispatchers.IO) {
        val file = newCaptureFile()
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                file.outputStream().use { output ->
                    val buffer = ByteArray(32 * 1024)
                    var total = 0L
                    while (true) {
                        coroutineContext.ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        if (total > 100L * 1024 * 1024) throw IOException("图片文件过大，请选择小于 100 MB 的图片")
                        output.write(buffer, 0, count)
                    }
                }
            } ?: throw IOException("无法读取照片，请重新选择")
            file
        } catch (e: Exception) {
            file.delete()
            throw e
        }
    }

    suspend fun preview(file: File): Bitmap = withContext(Dispatchers.IO) { decode(file, 1600) }

    internal fun decode(file: File, maxEdge: Int): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw IOException("无法解码图片，请选择有效的静态照片")
        val sourcePixels = bounds.outWidth.toLong() * bounds.outHeight.toLong()
        if (sourcePixels > 120_000_000L) throw IOException("照片像素过高，请先缩小后重试")
        val memoryPixels = (Runtime.getRuntime().maxMemory() / 4L * 22L / 100L)
            .coerceIn(2_000_000L, 12_000_000L)
        val pixelBudget = if (maxEdge <= 1600) minOf(memoryPixels, 4_000_000L) else memoryPixels
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / sample > maxEdge ||
            (bounds.outWidth.toLong() / sample) * (bounds.outHeight.toLong() / sample) > pixelBudget) sample *= 2
        val bitmap = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inScaled = false
            inMutable = true
        }) ?: throw IOException("图片读取失败")
        val orientation = runCatching {
            ExifInterface(file).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.setScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.setRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.setScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { matrix.setRotate(90f); matrix.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.setRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> { matrix.setRotate(-90f); matrix.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.setRotate(-90f)
        }
        if (matrix.isIdentity) return bitmap
        val rotated = try {
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        } catch (error: Throwable) {
            bitmap.recycle()
            throw error
        }
        if (rotated !== bitmap) bitmap.recycle()
        if (rotated.isMutable) return rotated
        val mutable = rotated.copy(Bitmap.Config.ARGB_8888, true)
        rotated.recycle()
        return mutable
    }

    suspend fun export(draft: PhotoDraft, config: WatermarkConfig): Uri = withContext(Dispatchers.IO) {
        val bitmap = try { decode(File(draft.path), 4096) }
        catch (_: OutOfMemoryError) { decode(File(draft.path), 2048) }
        try {
            WatermarkRenderer.draw(Canvas(bitmap), bitmap.width, bitmap.height, config, draft.timestamp)
            coroutineContext.ensureActive()
            val resolver = context.contentResolver
            val name = "TimePic_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(6)}.jpg"
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.DATE_TAKEN, System.currentTimeMillis())
                if (Build.VERSION.SDK_INT >= 29) {
                    put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/TimePic")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                } else {
                    @Suppress("DEPRECATION")
                    val folder = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "TimePic")
                    if (!folder.exists() && !folder.mkdirs()) throw IOException("无法创建相册目录")
                    @Suppress("DEPRECATION")
                    put(MediaStore.Images.Media.DATA, File(folder, name).path)
                }
            }
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: throw IOException("无法创建照片，请检查可用存储空间")
            try {
                resolver.openOutputStream(uri)?.use {
                    if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it)) throw IOException("图片编码失败")
                } ?: throw IOException("无法写入照片")
                coroutineContext.ensureActive()
                if (Build.VERSION.SDK_INT >= 29) {
                    resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
                }
                uri
            } catch (e: Throwable) {
                runCatching { resolver.delete(uri, null, null) }
                throw e
            }
        } finally { bitmap.recycle() }
    }

    fun validDraft(path: String): Boolean {
        return runCatching {
            val file = File(path).canonicalFile
            file.parentFile == draftDirectory.canonicalFile && file.isFile && file.length() > 0L
        }.getOrDefault(false)
    }

    fun discard(path: String) {
        runCatching { if (validDraft(path)) File(path).canonicalFile.delete() }
    }

    fun cleanOldDrafts(except: String?) {
        runCatching {
            draftDirectory.listFiles()?.filter {
                it.path != except && System.currentTimeMillis() - it.lastModified() > 7 * 24 * 60 * 60 * 1000L
            }?.forEach { it.delete() }
        }
    }
}
