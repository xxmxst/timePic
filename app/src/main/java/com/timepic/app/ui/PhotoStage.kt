package com.timepic.app.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.timepic.app.imaging.WatermarkRenderer
import com.timepic.app.model.TimeMode
import com.timepic.app.model.WatermarkConfig
import kotlin.math.min

@Composable
fun PhotoStage(
    modifier: Modifier,
    bitmap: Bitmap?,
    config: WatermarkConfig,
    timestamp: Long,
    session: CameraSession,
    front: Boolean,
    flash: Boolean,
    onCameraError: (String) -> Unit,
    onMove: (Float, Float) -> Unit
) {
    BoxWithConstraints(modifier, contentAlignment = Alignment.BottomCenter) {
        val aspect = bitmap?.let { it.width.toFloat() / it.height }
        val frameModifier = if (bitmap == null) Modifier.fillMaxSize() else {
            val frameWidth = minOf(maxWidth, maxHeight * aspect!!)
            Modifier.width(frameWidth).height(frameWidth / aspect)
        }
        Box(frameModifier.clip(RoundedCornerShape(18.dp))
            .background(Color(0xFF080B0F))) {
            if (bitmap != null) {
                Image(bitmap.asImageBitmap(), "待编辑照片", Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
            } else {
                CameraPreview(Modifier.fillMaxSize(), session, front, flash, onCameraError)
            }
            WatermarkLayer(Modifier.fillMaxSize(), config, timestamp, onMove) {
                if (bitmap == null) session.focus(it.x, it.y)
            }
            Text(
                if (bitmap != null) "拖动水印调整位置" else if (config.timeMode == TimeMode.FIXED) "固定时间" else "实时预览",
                modifier = Modifier.align(Alignment.TopStart).padding(12.dp)
                    .background(Color.Black.copy(alpha = .42f), RoundedCornerShape(20.dp))
                    .padding(horizontal = 10.dp, vertical = 5.dp),
                color = Color.White, fontSize = 10.sp
            )
        }
    }
}

@Composable
fun CameraPermissionPanel(
    modifier: Modifier,
    onEnableCamera: () -> Unit,
    onSettings: () -> Unit
) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Column(
            Modifier.padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(Icons.Outlined.CameraAlt, null, Modifier.size(42.dp), tint = Muted)
            Text("开启相机，记录此刻", Modifier.padding(top = 12.dp), fontSize = 15.sp)
            Text("也可以从下方相册选择照片", Modifier.padding(top = 5.dp), fontSize = 11.sp, color = Muted)
            Button(onClick = onEnableCamera, modifier = Modifier.padding(top = 14.dp)) {
                Text("开启相机")
            }
            TextButton(onClick = onSettings) {
                Text("权限设置", fontSize = 11.sp)
            }
        }
    }
}

@Composable
private fun WatermarkLayer(
    modifier: Modifier, config: WatermarkConfig, timestamp: Long,
    onMove: (Float, Float) -> Unit, onTap: (Offset) -> Unit
) {
    var dimensions by remember { mutableStateOf(IntSize.Zero) }
    val latestConfig by rememberUpdatedState(config)
    val latestTimestamp by rememberUpdatedState(timestamp)
    val latestMove by rememberUpdatedState(onMove)
    val latestTap by rememberUpdatedState(onTap)
    var dragging by remember { mutableStateOf(false) }
    Canvas(modifier
        .semantics { contentDescription = "水印预览" }
        .onSizeChanged { dimensions = it }
        .pointerInput(dimensions) {
            detectTapGestures { latestTap(it) }
        }
        .pointerInput(dimensions) {
            detectDragGestures(
                onDragStart = { position ->
                    val bounds = WatermarkRenderer.bounds(dimensions.width, dimensions.height, latestConfig, latestTimestamp)
                    dragging = position.x in bounds.left..(bounds.left + bounds.width) &&
                        position.y in bounds.top..(bounds.top + bounds.height)
                },
                onDragEnd = { dragging = false },
                onDragCancel = { dragging = false }
            ) { change, delta ->
                if (dragging) {
                    change.consume()
                    val current = latestConfig
                    val bounds = WatermarkRenderer.bounds(dimensions.width, dimensions.height, current, latestTimestamp)
                    val margin = min(dimensions.width, dimensions.height) * .03f
                    val freeX = dimensions.width - margin * 2 - bounds.width
                    val freeY = dimensions.height - margin * 2 - bounds.height
                    latestMove(
                        (current.anchorX + if (freeX > 0f) delta.x / freeX else 0f).coerceIn(0f, 1f),
                        (current.anchorY + if (freeY > 0f) delta.y / freeY else 0f).coerceIn(0f, 1f)
                    )
                }
            }
        }) {
        drawIntoCanvas { WatermarkRenderer.draw(it.nativeCanvas, size.width.toInt(), size.height.toInt(), config, timestamp) }
    }
}
