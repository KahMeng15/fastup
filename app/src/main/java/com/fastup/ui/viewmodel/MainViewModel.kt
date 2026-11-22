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

data class StorageFacts(
    val localUsedFormatted: String = "0 MB",
    val localFreeFormatted: String = "0 GB",
    val uploadedSizeFormatted: String = "0 MB",
    val serverAvailableFormatted: String = "--"
)

class MainViewModel : ViewModel() {

    private val uploadManager = FastUpApp.instance.uploadManager
    private var usbCameraManager: UsbCameraManager? = null

    val photos: StateFlow<List<PhotoItem>> = uploadManager.photos
    val isUploading: StateFlow<Boolean> = uploadManager.isUploading
    val isUploadPaused: StateFlow<Boolean> = uploadManager.isUploadPaused
    val shareSession: StateFlow<ShareSessionInfo?> = uploadManager.shareSession

    private val _cameraState = MutableStateFlow(CameraConnectionState.DISCONNECTED)
    val cameraState: StateFlow<CameraConnectionState> = _cameraState.asStateFlow()

    private val _cameraModel = MutableStateFlow("Camera")
    val cameraModel: StateFlow<String> = _cameraModel.asStateFlow()

    private val _deviceTransferStats = MutableStateFlow(com.fastup.usb.DeviceTransferStats())
    val deviceTransferStats: StateFlow<com.fastup.usb.DeviceTransferStats> = _deviceTransferStats.asStateFlow()

    private val _serverAvailableStorage = MutableStateFlow("--")
    val serverAvailableStorage: StateFlow<String> = _serverAvailableStorage.asStateFlow()

    private val _curationViewMode = MutableStateFlow("curated")
    val curationViewMode: StateFlow<String> = _curationViewMode.asStateFlow()

    fun setCurationViewMode(mode: String) {
        _curationViewMode.value = mode
    }

    init {
        checkServerStorage()
        uploadManager.refreshLocalFolders()
    }

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

    val photographerName: StateFlow<String?> = uploadManager.photographerName

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

    var cameraCaptureFormat: com.fastup.model.CameraCaptureFormat
        get() = uploadManager.cameraCaptureFormat
        set(value) {
            uploadManager.cameraCaptureFormat = value
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

    val storageFacts: StateFlow<StorageFacts> = kotlinx.coroutines.flow.combine(
        uploadManager.localFolders,
        uploadManager.photos,
        _serverAvailableStorage
    ) { localFolders, photoList, serverAvail ->
        val localUsed = localFolders.sumOf { it.totalSizeBytes }
        val freeSpace = try {
            FastUpApp.instance.getExternalFilesDir(null)?.usableSpace ?: 0L
        } catch (_: Exception) {
            0L
        }
        val uploadedSize = photoList.filter { it.status == UploadStatus.COMPLETED }.sumOf { it.fileSize }

        StorageFacts(
            localUsedFormatted = formatStorageBytes(localUsed),
            localFreeFormatted = formatStorageBytes(freeSpace),
            uploadedSizeFormatted = formatStorageBytes(uploadedSize),
            serverAvailableFormatted = serverAvail
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), StorageFacts())

    fun checkServerStorage() {
        viewModelScope.launch {
            val res = uploadManager.apiClient.fetchServerStorageAvailable()
            res.onSuccess { avail ->
                _serverAvailableStorage.value = avail
            }
        }
    }

    private fun formatStorageBytes(bytes: Long): String {
        return when {
            bytes >= 1024L * 1024L * 1024L -> String.format(java.util.Locale.US, "%.0f GB", bytes.toDouble() / (1024L * 1024L * 1024L))
            bytes >= 1024L * 1024L -> String.format(java.util.Locale.US, "%.0f MB", bytes.toDouble() / (1024L * 1024L))
            bytes >= 1024L -> String.format(java.util.Locale.US, "%.0f kB", bytes.toDouble() / 1024L)
            bytes > 0L -> "$bytes B"
            else -> "0 MB"
        }
    }

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

    fun forceStopUpload() {
        uploadManager.forceStopUpload()
    }

    fun startUpload() {
        uploadManager.startUpload()
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

    val localFolders: StateFlow<List<com.fastup.upload.LocalFolderInfo>> = uploadManager.localFolders

    fun refreshLocalFolders() {
        uploadManager.refreshLocalFolders()
    }

    fun clearLocalStorage(folderId: String, onDone: (Boolean) -> Unit) {
        viewModelScope.launch {
            val success = uploadManager.clearLocalStorage(folderId)
            onDone(success)
        }
    }

    fun pairWithOtp(targetServerUrl: String, otpCode: String, onDone: (Result<String>) -> Unit) {
        viewModelScope.launch {
            val res = uploadManager.apiClient.claimOtp(targetServerUrl, otpCode)
            res.onSuccess { claimResult ->
                uploadManager.applyClaimedOtp(claimResult)
                onDone(Result.success(claimResult.photographerName))
            }.onFailure { err ->
                onDone(Result.failure(err))
            }
        }
    }

    fun disconnectPhotographer() {
        uploadManager.disconnectPhotographer()
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
