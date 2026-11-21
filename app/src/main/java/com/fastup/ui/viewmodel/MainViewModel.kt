package com.fastup.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fastup.FastUpApp
import com.fastup.model.CameraConnectionState
import com.fastup.model.PhotoItem
import com.fastup.model.ShareSessionInfo
import com.fastup.model.UploadFormat
import com.fastup.model.UploadStatus
import com.fastup.usb.UsbCameraManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class DashboardStats(
    val totalPhotos: Int = 0,
    val pendingCount: Int = 0,
    val completedCount: Int = 0,
    val failedCount: Int = 0,
    val overallProgress: Float = 0f,
    val speedFormatted: String = "0 kB/s"
)

class MainViewModel : ViewModel() {

    private val uploadManager = FastUpApp.instance.uploadManager
    private var usbCameraManager: UsbCameraManager? = null

    val photos: StateFlow<List<PhotoItem>> = uploadManager.photos
    val isUploading: StateFlow<Boolean> = uploadManager.isUploading
    val shareSession: StateFlow<ShareSessionInfo?> = uploadManager.shareSession

    private val _cameraState = MutableStateFlow(CameraConnectionState.DISCONNECTED)
    val cameraState: StateFlow<CameraConnectionState> = _cameraState.asStateFlow()

    private val _cameraModel = MutableStateFlow("Sony ILCE-7M3")
    val cameraModel: StateFlow<String> = _cameraModel.asStateFlow()

    private val _deviceTransferStats = MutableStateFlow(com.fastup.usb.DeviceTransferStats())
    val deviceTransferStats: StateFlow<com.fastup.usb.DeviceTransferStats> = _deviceTransferStats.asStateFlow()

    // Configuration Settings
    var serverUrl: String
        get() = uploadManager.serverUrl
        set(value) {
            uploadManager.serverUrl = value
        }

    var apiKey: String
        get() = uploadManager.apiKey
        set(value) {
            uploadManager.apiKey = value
        }

    val currentSessionId: StateFlow<String> = uploadManager.currentSessionIdFlow

    var sessionId: String
        get() = uploadManager.currentSessionId
        set(value) {
            uploadManager.currentSessionId = value
        }

    private val _serverFolders = MutableStateFlow<List<String>>(emptyList())
    val serverFolders: StateFlow<List<String>> = _serverFolders.asStateFlow()

    private val _isLoadingFolders = MutableStateFlow(false)
    val isLoadingFolders: StateFlow<Boolean> = _isLoadingFolders.asStateFlow()

    fun loadServerFolders(onDone: ((Result<List<String>>) -> Unit)? = null) {
        viewModelScope.launch {
            _isLoadingFolders.value = true
            val res = uploadManager.apiClient.fetchStagingFolders()
            res.onSuccess { list ->
                _serverFolders.value = list
            }
            _isLoadingFolders.value = false
            onDone?.invoke(res)
        }
    }

    fun createServerFolder(folderPath: String, onDone: (Result<String>) -> Unit) {
        viewModelScope.launch {
            val res = uploadManager.apiClient.createStagingFolder(folderPath)
            res.onSuccess { createdPath ->
                if (!_serverFolders.value.contains(createdPath)) {
                    _serverFolders.value = listOf(createdPath) + _serverFolders.value
                }
                uploadManager.currentSessionId = createdPath
            }
            onDone(res)
        }
    }

    fun selectServerFolder(folderPath: String) {
        uploadManager.currentSessionId = folderPath
    }

    var uploadFormat: UploadFormat
        get() = uploadManager.uploadFormat
        set(value) {
            uploadManager.uploadFormat = value
        }

    var autoUpload: Boolean
        get() = uploadManager.autoUpload
        set(value) {
            uploadManager.autoUpload = value
        }

    var wifiOnly: Boolean
        get() = uploadManager.wifiOnly
        set(value) {
            uploadManager.wifiOnly = value
        }

    val dashboardStats: StateFlow<DashboardStats> = uploadManager.photos.map { list ->
        val total = list.size
        val pending = list.count { it.status == UploadStatus.PENDING || it.status == UploadStatus.UPLOADING }
        val completed = list.count { it.status == UploadStatus.COMPLETED }
        val failed = list.count { it.status == UploadStatus.FAILED }
        val progress = if (total > 0) completed.toFloat() / total else 0f

        val speedBps = uploadManager.currentSpeedBps.value
        val speedStr = formatSpeed(speedBps)

        DashboardStats(
            totalPhotos = total,
            pendingCount = pending,
            completedCount = completed,
            failedCount = failed,
            overallProgress = progress,
            speedFormatted = speedStr
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), DashboardStats())

    fun attachCameraManager(manager: UsbCameraManager) {
        usbCameraManager = manager
        viewModelScope.launch {
            manager.connectionState.collect { state ->
                _cameraState.value = state
            }
        }
        viewModelScope.launch {
            manager.cameraModel.collect { model ->
                _cameraModel.value = model
            }
        }
        viewModelScope.launch {
            manager.deviceTransferStats.collect { stats ->
                _deviceTransferStats.value = stats
            }
        }
    }

    fun reconnectCamera() {
        usbCameraManager?.scanForCamera()
    }

    fun retryFailed() {
        uploadManager.retryFailed()
    }

    fun retryPhoto(id: String) {
        uploadManager.retryPhoto(id)
    }

    fun generateShareLink(onResult: (Result<ShareSessionInfo>) -> Unit) {
        uploadManager.generateShareLink(onResult)
    }

    fun clearShareSession() {
        uploadManager.clearShareSession()
    }

    fun cleanupCurrentSession(onDone: (Boolean) -> Unit) {
        viewModelScope.launch {
            val res = uploadManager.apiClient.cleanupSession(sessionId)
            onDone(res.isSuccess)
        }
    }

    private fun formatSpeed(bytesPerSec: Long): String {
        return when {
            bytesPerSec >= 1024 * 1024 -> {
                String.format("%.1f MB/s", bytesPerSec.toDouble() / (1024 * 1024))
            }
            bytesPerSec >= 1024 -> {
                String.format("%.0f kB/s", bytesPerSec.toDouble() / 1024)
            }
            bytesPerSec > 0 -> {
                "$bytesPerSec B/s"
            }
            else -> "0 kB/s"
        }
    }
}
