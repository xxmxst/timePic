package com.timepic.app.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.timepic.app.TimePicViewModel
import com.timepic.app.model.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

internal val Ink = Color(0xFF101419)
internal val Panel = Color(0xFF1B222C)
internal val Gold = Color(0xFFF5C76A)
internal val Muted = Color(0xFF9CA8B9)

@Composable
fun TimePicApp(model: TimePicViewModel = viewModel()) {
    MaterialTheme(colorScheme = darkColorScheme(
        primary = Gold, onPrimary = Ink, background = Ink, onBackground = Color.White,
        surface = Panel, onSurface = Color.White, surfaceVariant = Color(0xFF28323F),
        onSurfaceVariant = Muted, secondary = Gold
    )) {
        TimePicContent(model)
    }
}

@Composable
private fun TimePicContent(model: TimePicViewModel) {
    val state by model.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val session = remember { CameraSession() }
    val snackbar = remember { SnackbarHostState() }
    var front by rememberSaveable { mutableStateOf(false) }
    var flash by rememberSaveable { mutableStateOf(false) }
    var dialog by rememberSaveable { mutableStateOf<String?>(null) }
    var confirmClose by remember { mutableStateOf(false) }
    fun hasCamera() = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    var cameraGranted by remember { mutableStateOf(hasCamera()) }
    val cameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        cameraGranted = it
        if (!it) model.notify("相机权限未开启，仍可从相册选择照片")
    }
    val locationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (model.hasLocationPermission()) model.refreshLocation()
        else model.notify("定位权限未开启，可以直接填写地址")
    }
    val storagePermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        if (it) model.savePhoto() else model.notify("保存需要相册写入权限，编辑内容已保留")
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let(model::importPhoto)
    }
    fun requestLocation() {
        if (model.hasLocationPermission()) model.refreshLocation()
        else locationPermission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
    }
    fun save() {
        if (Build.VERSION.SDK_INT <= 28 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        ) storagePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        else model.savePhoto()
    }
    fun closeEditor() {
        if (state.savedUri != null) model.closeDraft() else confirmClose = true
    }
    fun settings() {
        runCatching { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + context.packageName))) }
            .onFailure { model.notify("请在系统设置中开启应用权限") }
    }
    fun openSaved(share: Boolean) {
        val uri = state.savedUri ?: return
        val intent = if (share) Intent(Intent.ACTION_SEND).apply {
            type = "image/jpeg"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } else Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "image/jpeg")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching { context.startActivity(if (share) Intent.createChooser(intent, "分享水印照片") else intent) }
            .onFailure { model.notify("照片已保存，可在系统相册的 TimePic 文件夹查看") }
    }
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                cameraGranted = hasCamera()
                if (model.hasLocationPermission()) model.refreshLocation(automatic = true)
            }
            if (event == Lifecycle.Event.ON_STOP) model.stopLocation()
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer); model.stopLocation() }
    }
    LaunchedEffect(state.ready, state.draft == null) {
        if (state.ready && state.draft == null && model.hasLocationPermission()) model.refreshLocation(automatic = true)
    }
    LaunchedEffect(state.notice) {
        state.notice?.let { message -> snackbar.showSnackbar(message); model.dismissNotice(message) }
    }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(owner) {
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (isActive) { now = System.currentTimeMillis(); delay(1000) }
        }
    }
    val timestamp = state.draft?.timestamp ?: state.config.resolveTime(now)
    BackHandler(state.draft != null || state.busy != null) { if (state.busy == null) closeEditor() }

    val photoStage: @Composable (Modifier) -> Unit = { stageModifier ->
        PhotoStage(
                    modifier = stageModifier.padding(horizontal = 16.dp, vertical = 10.dp),
                    bitmap = state.preview, config = state.config, timestamp = timestamp,
                    cameraGranted = cameraGranted, session = session, front = front, flash = flash,
                    onCameraError = model::notify,
                    onEnableCamera = { cameraPermission.launch(Manifest.permission.CAMERA) },
                    onSettings = ::settings,
                    onMove = { x, y -> model.updateConfig { it.copy(anchorX = x, anchorY = y) } }
                )
    }
    val editorControls: @Composable () -> Unit = {
        Column(Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
                    .padding(top = 12.dp, bottom = 8.dp)) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (state.locating) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = Gold)
                        else Icon(Icons.Outlined.LocationOn, null, Modifier.size(17.dp), tint = Gold)
                        Spacer(Modifier.width(7.dp))
                        Column(Modifier.weight(1f).clickable { dialog = "address" }) {
                            Text(state.config.place.text.ifBlank { "添加拍摄地点" }, maxLines = 1,
                                overflow = TextOverflow.Ellipsis, fontSize = 13.sp)
                            Text(state.locationStatus, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 10.sp, color = Muted)
                        }
                        IconButton(onClick = ::requestLocation, enabled = !state.locating, modifier = Modifier.size(38.dp)) {
                            Icon(Icons.Outlined.MyLocation, "重新定位", tint = Gold, modifier = Modifier.size(20.dp))
                        }
                    }
                    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 9.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly) {
                        EditorTool(Icons.Outlined.Schedule, "时间", state.config.timeMode == TimeMode.FIXED) { dialog = "time" }
                        EditorTool(Icons.Outlined.LocationOn, "地址", state.config.place.source != LocationSource.CURRENT) { dialog = "address" }
                        EditorTool(Icons.Outlined.EditNote, "备注", state.config.note.isNotBlank()) { dialog = "note" }
                        EditorTool(Icons.Outlined.Tune, "大小", false) { dialog = "size" }
                    }
                    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(Template.entries) { template ->
                            TemplateCard(template, state.config.template == template) {
                                model.updateConfig { it.copy(template = template) }
                            }
                        }
                    }
                    if (state.savedUri != null) {
                        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 6.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.CheckCircle, null, Modifier.size(16.dp), tint = Gold)
                            Text("已保存到系统相册", Modifier.weight(1f).padding(start = 6.dp), fontSize = 12.sp, color = Gold)
                            TextButton(onClick = { openSaved(false) }) { Text("查看") }
                            TextButton(onClick = { openSaved(true) }) { Text("分享") }
                        }
                    }
                    if (state.draft == null) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                            Column(Modifier.width(64.dp).clickable(enabled = state.ready) {
                                picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                            }, horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(Icons.Outlined.PhotoLibrary, "从相册选图", Modifier.size(27.dp))
                                Text("相册", fontSize = 11.sp, color = Muted, modifier = Modifier.padding(top = 4.dp))
                            }
                            Box(Modifier.size(74.dp).border(2.dp, if (session.ready) Gold else Muted, CircleShape)
                                .padding(5.dp).background(if (session.ready) Gold else Color(0xFF46505E), CircleShape)
                                .clickable(enabled = session.ready && state.ready) {
                                    model.beginCapture()?.let { file ->
                                        session.takePhoto(context, file, front,
                                            { model.captureFinished(file) }, { model.captureFailed(file) })
                                    }
                                }, contentAlignment = Alignment.Center) {
                                Icon(Icons.Outlined.CameraAlt, "拍照", tint = Ink, modifier = Modifier.size(30.dp))
                            }
                            Column(Modifier.width(64.dp).clickable(enabled = cameraGranted) { front = !front },
                                horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(Icons.Outlined.FlipCameraAndroid, "切换摄像头", Modifier.size(27.dp))
                                Text("翻转", fontSize = 11.sp, color = Muted, modifier = Modifier.padding(top = 4.dp))
                            }
                        }
                    } else {
                        Button(onClick = ::save, enabled = state.savedUri == null,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp).height(50.dp),
                            shape = RoundedCornerShape(16.dp)) {
                            Icon(if (state.savedUri != null) Icons.Outlined.Check else Icons.Outlined.SaveAlt, null, Modifier.size(21.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(if (state.savedUri != null) "已保存 · 修改后可再次保存" else "保存到相册", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
    }

    Scaffold(
        containerColor = Ink,
        snackbarHost = { SnackbarHost(snackbar) },
        contentWindowInsets = WindowInsets.safeDrawing
    ) { insets ->
        Box(Modifier.fillMaxSize().padding(insets)) {
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (state.draft != null) IconButton(onClick = ::closeEditor) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回相机")
                    }
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(if (state.draft == null) "TimePic" else "编辑照片", fontSize = 25.sp, fontWeight = FontWeight.Bold)
                            if (state.draft == null) {
                                Spacer(Modifier.width(7.dp))
                                Box(Modifier.size(6.dp).background(Gold, CircleShape))
                            }
                        }
                        Text(if (state.draft == null) "把时间与地点，留在照片里" else "调整水印，记录你想留下的细节",
                            fontSize = 11.sp, color = Muted)
                    }
                    if (state.draft == null) {
                        IconButton(onClick = { flash = !flash }, enabled = cameraGranted && session.hasFlash) {
                            Icon(if (flash) Icons.Outlined.FlashOn else Icons.Outlined.FlashOff,
                                if (flash) "关闭闪光灯" else "开启闪光灯", tint = if (flash) Gold else Muted)
                        }
                    } else {
                        Text(if (state.draft?.imported == true) "相册照片" else "拍摄照片", color = Gold, fontSize = 12.sp)
                    }
                }

                BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                    if (maxWidth > maxHeight) {
                        Row(Modifier.fillMaxSize()) {
                            photoStage(Modifier.weight(1f).fillMaxHeight())
                            Column(Modifier.width(340.dp).fillMaxHeight().verticalScroll(rememberScrollState())) {
                                editorControls()
                            }
                        }
                    } else {
                        Column(Modifier.fillMaxSize()) {
                            photoStage(Modifier.weight(1f).fillMaxWidth())
                            editorControls()
                        }
                    }
                }
            }
            if (!state.ready || state.busy != null) {
                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .65f))
                    .clickable(indication = null, interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }) {},
                    contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = Gold)
                        Text(state.busy ?: "正在准备…", Modifier.padding(top = 16.dp), color = Color.White)
                    }
                }
            }
        }
    }

    when (dialog) {
        "address" -> AddressDialog(state, onDismiss = { dialog = null; model.clearSearch() },
            onSearch = model::searchAddress, onQueryChanged = model::clearSearch,
            onManual = { model.setAddress(it); dialog = null; model.clearSearch() },
            onSelect = { model.choosePlace(it); dialog = null; model.clearSearch() },
            onLocation = { dialog = null; model.clearSearch(); requestLocation() }, onSettings = ::settings)
        "time" -> TimeDialog(timestamp, state.config.timeMode,
            onDismiss = { dialog = null }, onConfirm = { model.setTime(it); dialog = null })
        "note" -> NoteDialog(state.config.note, onDismiss = { dialog = null },
            onConfirm = { text -> model.updateConfig { it.copy(note = text) }; dialog = null })
        "size" -> SizeDialog(state.config.scale, onDismiss = { dialog = null },
            onScale = { scale -> model.updateConfig { it.copy(scale = scale) } },
            onReset = { model.updateConfig { it.copy(scale = 1f, anchorX = 0f, anchorY = 1f) } })
    }
    if (confirmClose) AlertDialog(
        onDismissRequest = { confirmClose = false },
        title = { Text("放弃这次编辑？") },
        text = { Text("当前照片还没有保存到相册。") },
        confirmButton = { TextButton(onClick = { confirmClose = false; model.closeDraft() }) { Text("放弃编辑") } },
        dismissButton = { TextButton(onClick = { confirmClose = false }) { Text("继续编辑") } }
    )
}

@Composable
private fun EditorTool(icon: ImageVector, label: String, active: Boolean, onClick: () -> Unit) {
    Column(Modifier.width(66.dp).heightIn(min = 48.dp).clickable(onClick = onClick).padding(vertical = 3.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, null, Modifier.size(22.dp), tint = if (active) Gold else Color.White)
        Text(label, fontSize = 11.sp, color = if (active) Gold else Muted, modifier = Modifier.padding(top = 3.dp))
    }
}

@Composable
private fun TemplateCard(template: Template, selected: Boolean, onClick: () -> Unit) {
    Column(Modifier.width(130.dp).height(74.dp)
        .background(if (selected) Color(0xFF39362E) else Color(0xFF252E39), RoundedCornerShape(13.dp))
        .border(1.dp, if (selected) Gold else Color.Transparent, RoundedCornerShape(13.dp))
        .clickable(onClick = onClick).padding(12.dp), verticalArrangement = Arrangement.SpaceBetween) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(template.title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                color = if (selected) Gold else Color.White, modifier = Modifier.weight(1f))
            if (selected) Icon(Icons.Outlined.Check, null, Modifier.size(15.dp), tint = Gold)
        }
        Text(template.description.substringBefore(" ·"), fontSize = 10.sp, color = Muted)
    }
}
