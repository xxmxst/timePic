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
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
import com.timepic.app.TimePicState
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
    var showMore by rememberSaveable { mutableStateOf(false) }
    var pendingCapture by rememberSaveable { mutableStateOf(false) }
    var confirmClose by remember { mutableStateOf(false) }
    var confirmReplace by rememberSaveable { mutableStateOf(false) }
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

    fun beginCaptureNow() {
        model.beginCapture()?.let { file ->
            session.takePhoto(context, file, front,
                { model.captureFinished(file) }, { model.captureFailed(file) })
        }
    }

    val storagePermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        if (it) {
            if (pendingCapture) {
                pendingCapture = false
                beginCaptureNow()
            } else model.savePhoto()
        } else {
            pendingCapture = false
            model.notify("保存需要相册写入权限，编辑内容已保留")
        }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let(model::importPhoto)
    }
    fun choosePhoto() {
        if (state.draft != null && state.savedUri == null) confirmReplace = true
        else picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
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
                    modifier = stageModifier,
                    bitmap = state.preview, config = state.config, timestamp = timestamp,
                    session = session, front = front, flash = flash,
                    onCameraError = model::notify,
                    onMove = { x, y -> model.updateConfig { it.copy(anchorX = x, anchorY = y) } }
                )
    }
    val permissionPanel: @Composable (Modifier) -> Unit = { panelModifier ->
        CameraPermissionPanel(
            modifier = panelModifier,
            onEnableCamera = { cameraPermission.launch(Manifest.permission.CAMERA) },
            onSettings = ::settings
        )
    }
    val topControls: @Composable () -> Unit = {
        Row(
            Modifier.fillMaxWidth().height(56.dp).background(Ink.copy(alpha = .82f))
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (state.draft != null) IconButton(onClick = ::closeEditor) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回相机")
            }
            Column(Modifier.weight(1f).padding(start = 8.dp)) {
                Text(if (state.draft == null) "TimePic" else "编辑照片", fontSize = 18.sp,
                    fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (state.draft != null) Text(
                    if (state.draft?.imported == true) "相册照片" else "拍摄照片",
                    color = Muted, fontSize = 10.sp
                )
            }
            IconButton(onClick = ::choosePhoto, enabled = state.ready && state.busy == null) {
                Icon(Icons.Outlined.PhotoLibrary, "从相册选图")
            }
            if (state.draft == null) IconButton(
                onClick = { flash = !flash },
                enabled = cameraGranted && session.ready && session.hasFlash && state.busy == null
            ) {
                Icon(if (flash) Icons.Outlined.FlashOn else Icons.Outlined.FlashOff,
                    if (flash) "关闭闪光灯" else "开启闪光灯", tint = if (flash) Gold else Muted)
            }
            IconButton(onClick = { showMore = true }, enabled = state.ready && state.busy == null) {
                Icon(Icons.Outlined.MoreHoriz, "更多设置")
            }
        }
    }
    val editorControls: @Composable () -> Unit = {
        Column(
            Modifier.fillMaxWidth()
                .background(Panel.copy(alpha = .94f), RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp))
                .padding(horizontal = 12.dp, vertical = 6.dp)
        ) {
            if (state.draft == null) {
                Row(
                    Modifier.fillMaxWidth().height(88.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    QuickAction(Icons.Outlined.EditNote, "备注", "编辑备注",
                        state.ready && state.busy == null) { showMore = false; dialog = "note" }
                    Box(
                        Modifier.size(74.dp).border(2.dp, if (session.ready) Gold else Muted, CircleShape)
                            .semantics { contentDescription = "拍照" }
                            .clickable(enabled = session.ready && state.ready && state.busy == null, role = Role.Button) {
                                if (Build.VERSION.SDK_INT <= 28 &&
                                    ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
                                ) {
                                    pendingCapture = true
                                    storagePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                                } else beginCaptureNow()
                            }.padding(5.dp).background(if (session.ready) Gold else Color(0xFF46505E), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Outlined.CameraAlt, null, tint = Ink, modifier = Modifier.size(30.dp))
                    }
                    QuickAction(Icons.Outlined.MyLocation, if (state.locating) "定位中" else "定位", "获取当前位置",
                        state.ready && !state.locating && state.busy == null, ::requestLocation)
                }
            } else if (state.savedUri != null) {
                Text("已保存到系统相册", color = Gold, fontSize = 12.sp, modifier = Modifier.align(Alignment.CenterHorizontally))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    TextButton(onClick = { openSaved(false) }) { Text("查看") }
                    TextButton(onClick = { openSaved(true) }) { Text("分享") }
                    TextButton(onClick = ::closeEditor) { Text("继续拍摄") }
                }
            } else {
                Button(
                    onClick = ::save,
                    enabled = state.ready && state.busy == null,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Icon(Icons.Outlined.SaveAlt, null, Modifier.size(21.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("保存到相册", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }

    Scaffold(
        containerColor = Ink,
        snackbarHost = { SnackbarHost(snackbar, Modifier.padding(bottom = 104.dp)) },
        contentWindowInsets = WindowInsets.safeDrawing
    ) { insets ->
        Box(Modifier.fillMaxSize().padding(insets)) {
            TimePicWorkspace(
                modifier = Modifier.fillMaxSize(),
                showPermission = !cameraGranted && state.draft == null,
                preview = photoStage,
                permissionPanel = permissionPanel,
                toolbar = topControls,
                actions = editorControls
            )
            if (!state.ready || state.busy != null) {
                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .65f))
                    .clickable(indication = null, interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }) {},
                    contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = Gold)
                        Text(state.busy ?: "正在准备…", Modifier.padding(top = 16.dp), color = Color.White)
                        if (state.cancelable) {
                            TextButton(onClick = model::cancelPhotoTask, modifier = Modifier.padding(top = 4.dp)) {
                                Text("取消", color = Gold)
                            }
                        }
                    }
                }
            }
        }
    }

    if (showMore) MoreSettingsSheet(
        state = state,
        front = front,
        onDismiss = { showMore = false },
        onEdit = { key -> showMore = false; dialog = key },
        onTemplate = { template -> model.updateConfig { it.copy(template = template) }; showMore = false },
        onFlip = { front = !front },
        onLocation = { showMore = false; requestLocation() },
        onSettings = { showMore = false; settings() }
    )
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
    if (confirmReplace) AlertDialog(
        onDismissRequest = { confirmReplace = false },
        title = { Text("更换照片？") },
        text = { Text("选择新照片后将替换当前未保存的编辑，取消选图则保留当前照片。") },
        confirmButton = { TextButton(onClick = {
            confirmReplace = false
            picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }) { Text("选择新照片") } },
        dismissButton = { TextButton(onClick = { confirmReplace = false }) { Text("继续编辑") } }
    )
    if (confirmClose) AlertDialog(
        onDismissRequest = { confirmClose = false },
        title = { Text("放弃这次编辑？") },
        text = { Text("当前照片还没有保存到相册。") },
        confirmButton = { TextButton(onClick = { confirmClose = false; model.closeDraft() }) { Text("放弃编辑") } },
        dismissButton = { TextButton(onClick = { confirmClose = false }) { Text("继续编辑") } }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MoreSettingsSheet(
    state: TimePicState,
    front: Boolean,
    onDismiss: () -> Unit,
    onEdit: (String) -> Unit,
    onTemplate: (Template) -> Unit,
    onFlip: () -> Unit,
    onLocation: () -> Unit,
    onSettings: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().navigationBarsPadding().verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, bottom = 18.dp)
        ) {
            Text("更多设置", fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Text("调整水印内容和样式", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp, bottom = 10.dp))
            MoreSettingRow(Icons.Outlined.Schedule, "时间",
                if (state.config.timeMode == TimeMode.FIXED) "固定时间" else "使用实时") { onEdit("time") }
            MoreSettingRow(Icons.Outlined.LocationOn, "地址",
                state.config.place.text.ifBlank { "未设置地址" }) { onEdit("address") }
            MoreSettingRow(Icons.Outlined.FlipCameraAndroid, "翻转摄像头",
                if (front) "当前使用前置摄像头" else "当前使用后置摄像头") { onFlip() }
            MoreSettingRow(Icons.Outlined.Tune, "大小与位置",
                "${(state.config.scale * 100).toInt()}% · 可在预览中拖动") { onEdit("size") }
            MoreSettingRow(Icons.Outlined.MyLocation, "当前位置",
                if (state.locating) "正在获取位置…" else state.locationStatus) { onLocation() }
            Text("水印模板", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 14.dp, bottom = 8.dp))
            Template.entries.forEach { template ->
                FilterChip(
                    selected = state.config.template == template,
                    onClick = { onTemplate(template) },
                    label = { Text("${template.title} · ${template.description.substringBefore(" ·")}") },
                    leadingIcon = if (state.config.template == template) {
                        { Icon(Icons.Outlined.Check, null, Modifier.size(16.dp)) }
                    } else null,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)
                )
            }
            TextButton(onClick = onSettings, modifier = Modifier.padding(top = 8.dp)) {
                Text("打开应用权限设置")
            }
        }
    }
}

@Composable
private fun MoreSettingRow(icon: ImageVector, title: String, summary: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable(onClick = onClick).padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, Modifier.size(22.dp), tint = Gold)
        Column(Modifier.weight(1f).padding(start = 14.dp)) {
            Text(title, fontSize = 14.sp)
            Text(summary, color = Muted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Icon(Icons.Outlined.ChevronRight, null, Modifier.size(18.dp), tint = Muted)
    }
}

/** Keep foreground controls outside the preview's gesture area, in every permission state. */
@Composable
internal fun TimePicWorkspace(
    modifier: Modifier = Modifier,
    showPermission: Boolean,
    preview: @Composable (Modifier) -> Unit,
    permissionPanel: @Composable (Modifier) -> Unit,
    toolbar: @Composable () -> Unit,
    actions: @Composable () -> Unit
) {
    val density = LocalDensity.current
    var toolbarHeight by remember { mutableStateOf(56.dp) }
    var actionsHeight by remember { mutableStateOf(100.dp) }
    BoxWithConstraints(modifier.testTag("workspace")) {
        val landscape = maxWidth > maxHeight
        val railWidth = if (landscape) minOf(300.dp, maxWidth * .38f) else 0.dp
        val contentModifier = Modifier.fillMaxSize()
            .padding(top = toolbarHeight, bottom = if (landscape) 0.dp else actionsHeight, end = railWidth)
        if (showPermission) permissionPanel(contentModifier) else preview(contentModifier)
        Box(Modifier.align(Alignment.TopCenter).fillMaxWidth().testTag("toolbar")
            .onSizeChanged { toolbarHeight = with(density) { it.height.toDp() } }) {
            toolbar()
        }
        if (landscape) {
            Box(Modifier.align(Alignment.CenterEnd).width(railWidth).fillMaxHeight()
                .padding(top = toolbarHeight).background(Panel.copy(alpha = .92f)),
                contentAlignment = Alignment.Center) {
                Box(Modifier.fillMaxWidth().testTag("main-actions")) { actions() }
            }
        } else {
            Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().testTag("main-actions")
                .onSizeChanged { actionsHeight = with(density) { it.height.toDp() } }) {
                actions()
            }
        }
    }
}

@Composable
private fun QuickAction(icon: ImageVector, label: String, description: String, enabled: Boolean, onClick: () -> Unit) {
    Column(Modifier.width(64.dp).heightIn(min = 48.dp)
        .semantics { contentDescription = description }
        .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
        .padding(vertical = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, null, Modifier.size(24.dp), tint = if (enabled) Color.White else Muted)
        Text(label, fontSize = 11.sp, color = Muted, modifier = Modifier.padding(top = 4.dp))
    }
}
