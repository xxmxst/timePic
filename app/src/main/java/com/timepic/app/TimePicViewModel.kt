package com.timepic.app

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.timepic.app.data.*
import com.timepic.app.imaging.PhotoStore
import com.timepic.app.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File

data class TimePicState(
    val ready: Boolean = false,
    val config: WatermarkConfig = WatermarkConfig(),
    val draft: PhotoDraft? = null,
    val preview: Bitmap? = null,
    val busy: String? = null,
    val locating: Boolean = false,
    val locationStatus: String = "获取当前位置，或填写地址",
    val searchBusy: Boolean = false,
    val searchResults: List<Place> = emptyList(),
    val searchMessage: String? = null,
    val savedUri: Uri? = null,
    val notice: String? = null
)

class TimePicViewModel(application: Application, private val savedState: SavedStateHandle) : AndroidViewModel(application) {
    private val settings = SettingsStore(application)
    private val location = SystemLocationRepository(application)
    private val photos = PhotoStore(application)
    private val mutableState = MutableStateFlow(TimePicState())
    val state = mutableState.asStateFlow()
    private var locationJob: Job? = null
    private var searchJob: Job? = null
    private var persistJob: Job? = null
    private var captureTimestamp = 0L
    private var locationGeneration = 0
    private var searchGeneration = 0

    init {
        viewModelScope.launch {
            var config = savedState.get<String>("config")?.let(ConfigCodec::decode)
                ?: runCatching { settings.read() }.getOrDefault(WatermarkConfig())
            val path = savedState.get<String>("draftPath")
            val draft = path?.takeIf { photos.validDraft(it) }?.let {
                PhotoDraft(it, savedState["draftTime"] ?: System.currentTimeMillis(), savedState["imported"] ?: false)
            }
            if (draft == null && config.place.source == LocationSource.CURRENT) config = config.copy(place = Place())
            mutableState.update { it.copy(config = config, busy = if (draft != null) "恢复编辑中的照片…" else null) }
            if (draft != null) {
                try {
                    val bitmap = photos.preview(File(draft.path))
                    mutableState.update { it.copy(draft = draft, preview = bitmap) }
                } catch (_: Exception) { notify("上次的照片无法恢复，请重新选择") }
                catch (_: OutOfMemoryError) { notify("照片过大，请重新选择") }
            }
            withContext(Dispatchers.IO) { photos.cleanOldDrafts(draft?.path) }
            mutableState.update { it.copy(ready = true, busy = null) }
        }
    }

    fun notify(text: String) { mutableState.update { it.copy(notice = text) } }
    fun dismissNotice(text: String) { mutableState.update { if (it.notice == text) it.copy(notice = null) else it } }

    fun updateConfig(transform: (WatermarkConfig) -> WatermarkConfig) {
        if (state.value.busy != null) return
        val config = transform(state.value.config)
        mutableState.update { it.copy(config = config, savedUri = null) }
        savedState["config"] = ConfigCodec.encode(config)
        persistJob?.cancel()
        persistJob = viewModelScope.launch {
            delay(250)
            try { settings.write(config) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { notify("设置暂时无法保存，本次编辑仍可继续") }
        }
    }

    fun setTime(fixedMillis: Long?) {
        updateConfig { it.copy(timeMode = if (fixedMillis == null) TimeMode.LIVE else TimeMode.FIXED,
            fixedTimeMillis = fixedMillis ?: it.fixedTimeMillis) }
        val timestamp = fixedMillis ?: System.currentTimeMillis()
        mutableState.update { it.copy(draft = it.draft?.copy(timestamp = timestamp), savedUri = null) }
        if (state.value.draft != null) savedState["draftTime"] = timestamp
    }

    fun setAddress(text: String) {
        stopLocation()
        updateConfig { it.withManualAddress(text) }
        mutableState.update { it.copy(locationStatus = if (text.isBlank()) "未设置地址" else "使用手动填写的地址") }
    }

    fun choosePlace(place: Place) {
        stopLocation()
        updateConfig { it.copy(place = place) }
        mutableState.update { it.copy(locationStatus = "已选择地点，可继续编辑地址") }
    }

    fun hasLocationPermission() = location.hasPermission()

    fun refreshLocation(automatic: Boolean = false) {
        if (!state.value.ready || state.value.busy != null) return
        if (automatic && (state.value.draft != null || state.value.config.place.source != LocationSource.CURRENT)) return
        stopLocation()
        val generation = locationGeneration
        mutableState.update { it.copy(locating = true, locationStatus = "正在获取系统位置…") }
        locationJob = viewModelScope.launch {
            try {
                val result = location.current()
                if (generation == locationGeneration) {
                    updateConfig { it.copy(place = result.place) }
                    mutableState.update { it.copy(locationStatus = result.message) }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (e: Exception) {
                if (generation == locationGeneration) mutableState.update {
                    it.copy(locationStatus = (e as? LocationFailure)?.message ?: "定位失败，可重试或手动填写地址")
                }
            } finally {
                if (generation == locationGeneration) mutableState.update { it.copy(locating = false) }
            }
        }
    }

    fun stopLocation() {
        locationGeneration++
        locationJob?.cancel()
        mutableState.update {
            it.copy(locating = false, locationStatus = if (it.locating) "定位已暂停，可重新获取" else it.locationStatus)
        }
    }

    fun searchAddress(text: String) {
        searchGeneration++
        val generation = searchGeneration
        searchJob?.cancel()
        if (text.isBlank()) { clearSearch(); return }
        mutableState.update { it.copy(searchBusy = true, searchResults = emptyList(), searchMessage = null) }
        searchJob = viewModelScope.launch {
            try {
                val result = location.search(text.trim())
                if (generation == searchGeneration) mutableState.update { it.copy(searchResults = result,
                    searchMessage = if (result.isEmpty()) "没有找到地点，可直接使用输入的地址" else null) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (e: Exception) {
                if (generation == searchGeneration) mutableState.update { it.copy(searchMessage = (e as? LocationFailure)?.message
                    ?: "系统地址搜索暂不可用，可直接使用输入的地址") }
            } finally {
                if (generation == searchGeneration) mutableState.update { it.copy(searchBusy = false) }
            }
        }
    }

    fun clearSearch() {
        searchGeneration++
        searchJob?.cancel()
        mutableState.update { it.copy(searchBusy = false, searchResults = emptyList(), searchMessage = null) }
    }

    fun beginCapture(): File? {
        if (!state.value.ready || state.value.busy != null) return null
        stopLocation()
        captureTimestamp = state.value.config.resolveTime(System.currentTimeMillis())
        return try {
            photos.newCaptureFile().also { mutableState.update { it.copy(busy = "正在拍摄…", savedUri = null) } }
        } catch (_: Exception) { notify("无法创建照片，请检查存储空间"); null }
    }

    fun captureFailed(file: File?) {
        file?.let { photos.discard(it.path) }
        mutableState.update { it.copy(busy = null) }
        notify("拍摄失败，请重试")
    }

    fun captureFinished(file: File) {
        viewModelScope.launch { openDraft(file, captureTimestamp, false) }
    }

    fun importPhoto(uri: Uri) {
        if (state.value.busy != null) return
        stopLocation()
        val timestamp = state.value.config.resolveTime(System.currentTimeMillis())
        mutableState.update { it.copy(busy = "正在读取照片…", savedUri = null) }
        viewModelScope.launch {
            try { openDraft(photos.import(uri), timestamp, true) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                mutableState.update { it.copy(busy = null) }
                notify("照片无法读取，请选择有效的静态图片")
            }
        }
    }

    private suspend fun openDraft(file: File, timestamp: Long, imported: Boolean) {
        try {
            val bitmap = photos.preview(file)
            val draft = PhotoDraft(file.path, timestamp, imported)
            state.value.draft?.let { photos.discard(it.path) }
            savedState["draftPath"] = file.path
            savedState["draftTime"] = timestamp
            savedState["imported"] = imported
            mutableState.update { it.copy(draft = draft, preview = bitmap, busy = null, savedUri = null) }
        } catch (cancelled: CancellationException) {
            photos.discard(file.path)
            throw cancelled
        } catch (_: Exception) {
            photos.discard(file.path)
            mutableState.update { it.copy(busy = null) }
            notify("图片无法解码，请重新拍摄或选择照片")
        } catch (_: OutOfMemoryError) {
            photos.discard(file.path)
            mutableState.update { it.copy(busy = null) }
            notify("照片过大，内存不足，请选择较小的图片")
        }
    }

    fun savePhoto() {
        val snapshot = state.value
        val draft = snapshot.draft ?: return
        if (snapshot.busy != null) return
        mutableState.update { it.copy(busy = "正在生成并保存水印照片…") }
        viewModelScope.launch {
            try {
                val uri = photos.export(draft, snapshot.config)
                mutableState.update { it.copy(savedUri = uri) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: OutOfMemoryError) { notify("内存不足，照片仍保留在编辑页，请重试") }
            catch (_: Exception) { notify("保存失败，请检查相册权限和可用空间后重试") }
            finally { mutableState.update { it.copy(busy = null) } }
        }
    }

    fun closeDraft() {
        if (state.value.busy != null) return
        state.value.draft?.let { photos.discard(it.path) }
        savedState.remove<String>("draftPath")
        savedState.remove<Long>("draftTime")
        savedState.remove<Boolean>("imported")
        mutableState.update { it.copy(draft = null, preview = null, savedUri = null) }
    }

    override fun onCleared() { location.close(); super.onCleared() }
}
