package com.timepic.app.ui

import android.content.Context
import android.view.View
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.io.File

class CameraSession {
    internal var focusAction: ((Float, Float) -> Unit)? = null
    fun focus(x: Float, y: Float) { focusAction?.invoke(x, y) }
    internal var capture: ImageCapture? = null
    var ready by mutableStateOf(false)
        internal set
    var hasFlash by mutableStateOf(false)
        internal set

    fun takePhoto(context: Context, file: File, front: Boolean, onSuccess: () -> Unit, onError: () -> Unit) {
        val imageCapture = capture ?: run { onError(); return }
        val metadata = ImageCapture.Metadata().apply { isReversedHorizontal = front }
        val options = ImageCapture.OutputFileOptions.Builder(file).setMetadata(metadata).build()
        imageCapture.takePicture(options, ContextCompat.getMainExecutor(context),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) { onSuccess() }
                override fun onError(exception: ImageCaptureException) { onError() }
            })
    }
}

@Composable
fun CameraPreview(
    modifier: Modifier, session: CameraSession, front: Boolean, flash: Boolean,
    onError: (String) -> Unit
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current
    val previewView = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FIT_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }
    val latestError by rememberUpdatedState(onError)
    DisposableEffect(previewView, lifecycle, front) {
        var disposed = false
        var provider: ProcessCameraProvider? = null
        var preview: Preview? = null
        var capture: ImageCapture? = null
        var camera: Camera? = null
        var boundWidth = 0
        var boundHeight = 0
        val future = ProcessCameraProvider.getInstance(context)
        session.ready = false
        fun bind() {
            val cameraProvider = provider ?: return
            val viewport = previewView.viewPort ?: return
            if (disposed || previewView.width == 0 || previewView.height == 0) return
            if (camera != null && boundWidth == previewView.width && boundHeight == previewView.height) return
            val selector = if (front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
            try {
                if (!cameraProvider.hasCamera(selector)) {
                    latestError(if (front) "此设备没有前置摄像头" else "此设备没有后置摄像头")
                    return
                }
                preview?.let { cameraProvider.unbind(it) }
                capture?.let { cameraProvider.unbind(it) }
                val newPreview = Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }
                val newCapture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .setTargetRotation(previewView.display.rotation)
                    .setFlashMode(if (flash) ImageCapture.FLASH_MODE_ON else ImageCapture.FLASH_MODE_OFF)
                    .build()
                camera = cameraProvider.bindToLifecycle(lifecycle, selector,
                    UseCaseGroup.Builder().addUseCase(newPreview).addUseCase(newCapture).setViewPort(viewport).build())
                preview = newPreview
                capture = newCapture
                session.focusAction = { x, y ->
                    camera?.cameraControl?.startFocusAndMetering(
                        FocusMeteringAction.Builder(previewView.meteringPointFactory.createPoint(x, y))
                            .setAutoCancelDuration(3, java.util.concurrent.TimeUnit.SECONDS).build())
                }
                session.capture = newCapture
                session.hasFlash = camera?.cameraInfo?.hasFlashUnit() == true
                session.ready = true
                boundWidth = previewView.width
                boundHeight = previewView.height
            } catch (_: Exception) {
                session.ready = false
                latestError("相机暂时无法打开，请检查权限或重新进入")
            }
        }
        val listener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> bind() }
        previewView.addOnLayoutChangeListener(listener)
        future.addListener({
            if (!disposed) {
                try { provider = future.get(); bind() }
                catch (_: Exception) { latestError("相机初始化失败，请重试") }
            }
        }, ContextCompat.getMainExecutor(context))
        previewView.setOnTouchListener { view, event ->
            if (event.action == android.view.MotionEvent.ACTION_UP) {
                camera?.cameraControl?.startFocusAndMetering(
                    FocusMeteringAction.Builder(previewView.meteringPointFactory.createPoint(event.x, event.y))
                        .setAutoCancelDuration(3, java.util.concurrent.TimeUnit.SECONDS).build())
                view.performClick()
            }
            true
        }
        onDispose {
            disposed = true
            previewView.removeOnLayoutChangeListener(listener)
            previewView.setOnTouchListener(null)
            preview?.let { provider?.unbind(it) }
            capture?.let { provider?.unbind(it) }
            session.focusAction = null
            session.capture = null
            session.ready = false
        }
    }
    LaunchedEffect(flash, session.ready) {
        session.capture?.flashMode = if (flash && session.hasFlash) ImageCapture.FLASH_MODE_ON else ImageCapture.FLASH_MODE_OFF
    }
    AndroidView(factory = { previewView }, modifier = modifier)
}
