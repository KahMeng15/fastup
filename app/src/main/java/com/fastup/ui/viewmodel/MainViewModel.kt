package com.fastup.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fastup.FastUpApp
import com.fastup.model.*
import com.fastup.usb.UsbCameraManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
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
    val folderGDriveSync: StateFlow<FolderSyncInfo?> = uploadManager.folderGDriveSync
    val serverConnectionState: StateFlow<ServerConnectionState> = uploadManager.serverConnectionState

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

    private val _isSyncingCuration = MutableStateFlow(false)
    val isSyncingCuration: StateFlow<Boolean> = _isSyncingCuration.asStateFlow()

    // Burst Drawer State
    private val _selectedBurstGroupId = MutableStateFlow<String?>(null)
    val selectedBurstGroupId: StateFlow<String?> = _selectedBurstGroupId.asStateFlow()

    private val _burstAlternates = MutableStateFlow<List<com.fastup.model.ServerStagingPhoto>>(emptyList())
    val burstAlternates: StateFlow<List<com.fastup.model.ServerStagingPhoto>> = _burstAlternates.asStateFlow()

    private val _isLoadingBurstAlternates = MutableStateFlow(false)
    val isLoadingBurstAlternates: StateFlow<Boolean> = _isLoadingBurstAlternates.asStateFlow()

    fun setCurationViewMode(mode: String) {
        _curationViewMode.value = mode
    }

    fun syncCuration(onDone: (() -> Unit)? = null) {
        if (_isSyncingCuration.value) {
            onDone?.invoke()
            return
        }
        _isSyncingCuration.value = true
        uploadManager.syncUploadedFilesWithServer {
            _isSyncingCuration.value = false
            onDone?.invoke()
        }
    }

    fun openBurstDrawer(photo: com.fastup.model.PhotoItem) {
        val groupId = photo.burstGroupId
        _selectedBurstGroupId.value = groupId ?: photo.id
        _isLoadingBurstAlternates.value = true

        viewModelScope.launch {
            val mediaId = photo.serverMediaId
            if (mediaId != null) {
                val alternates = uploadManager.apiClient.fetchAlternates(mediaId)
                if (alternates.isNotEmpty()) {
                    _burstAlternates.value = alternates
                    _isLoadingBurstAlternates.value = false
                    return@launch
                }
            }

            // Fallback: match from local photos in the same burst group
            val localGroup = photos.value.filter {
                (groupId != null && it.burstGroupId == groupId) || it.id == photo.id
            }.map { p ->
                com.fastup.model.ServerStagingPhoto(
                    id = p.serverMediaId ?: p.id,
                    fileName = p.fileName,
                    folderPath = uploadManager.currentSessionId,
                    burstGroupId = p.burstGroupId,
                    isHero = p.isHero,
                    burstCount = p.burstCount,
                    qualityScore = p.qualityScore,
                    faceCount = null
                )
            }
            _burstAlternates.value = localGroup
            _isLoadingBurstAlternates.value = false
        }
    }

    fun closeBurstDrawer() {
        _selectedBurstGroupId.value = null
        _burstAlternates.value = emptyList()
    }

    fun setHeroPhoto(mediaId: String, onDone: ((Boolean) -> Unit)? = null) {
        viewModelScope.launch {
            val res = uploadManager.apiClient.setHeroPhoto(mediaId)
            if (res.isSuccess) {
                // Update burst alternates list locally
                _burstAlternates.update { list ->
                    list.map { it.copy(isHero = it.id == mediaId) }
                }
                // Refresh local photos & server sync
                syncCuration()
                onDone?.invoke(true)
            } else {
                onDone?.invoke(false)
            }
        }
    }

    fun togglePhotoVisibility(mediaId: String, currentIsHero: Boolean, onDone: ((Boolean) -> Unit)? = null) {
        viewModelScope.launch {
            val targetState = !currentIsHero
            val res = uploadManager.apiClient.togglePhotoVisibility(mediaId, targetState)
            if (res.isSuccess) {
                _burstAlternates.update { list ->
                    list.map { if (it.id == mediaId) it.copy(isHero = targetState) else it }
                }
                syncCuration()
                onDone?.invoke(true)
            } else {
                onDone?.invoke(false)
            }
        }
    }

    init {
        checkServerConnection()
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
    val scopes: StateFlow<List<String>> = uploadManager.scopesFlow

    var sessionId: String
        get() = uploadManager.currentSessionId
        set(value) {
            uploadManager.currentSessionId = value
        }

    fun isFolderWritable(folderPath: String): Boolean {
        return uploadManager.isFolderWritable(folderPath)
    }

    private val _serverFolders = MutableStateFlow<List<String>>(emptyList())
    val serverFolders: StateFlow<List<String>> = _serverFolders.asStateFlow()

    private val _isLoadingFolders = MutableStateFlow(false)
    val isLoadingFolders: StateFlow<Boolean> = _isLoadingFolders.asStateFlow()

    fun loadServerFolders(onDone: ((Result<List<String>>) -> Unit)? = null) {
        viewModelScope.launch {
            _isLoadingFolders.value = true
            val res = uploadManager.apiClient.fetchStagingFoldersResult()
            res.onSuccess { result ->
                _serverFolders.value = result.folders
                if (result.scopes.isNotEmpty()) {
                    uploadManager.updateScopes(result.scopes)
                }
            }
            _isLoadingFolders.value = false
            onDone?.invoke(res.map { it.folders })
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

    private val _liveViewerMode = MutableStateFlow(uploadManager.liveViewerMode)
    val liveViewerMode: StateFlow<com.fastup.model.LiveViewerMode> = _liveViewerMode.asStateFlow()

    fun setLiveViewerMode(mode: com.fastup.model.LiveViewerMode) {
        uploadManager.liveViewerMode = mode
        _liveViewerMode.value = mode
    }

    private val _liveViewFrame = MutableStateFlow<ByteArray?>(null)
    val liveViewFrame: StateFlow<ByteArray?> = _liveViewFrame.asStateFlow()

    private val _liveViewBitmap = MutableStateFlow<android.graphics.Bitmap?>(null)
    val liveViewBitmap: StateFlow<android.graphics.Bitmap?> = _liveViewBitmap.asStateFlow()

    private val _isLiveViewStreaming = MutableStateFlow(false)
    val isLiveViewStreaming: StateFlow<Boolean> = _isLiveViewStreaming.asStateFlow()

    fun startLiveView() {
        usbCameraManager?.startLiveView()
    }

    fun stopLiveView() {
        usbCameraManager?.stopLiveView()
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
            }.onFailure {
                _serverAvailableStorage.value = "--"
            }
        }
    }

    fun checkServerConnection() {
        uploadManager.checkServerConnection { success ->
            if (success) {
                checkServerStorage()
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
        viewModelScope.launch {
            manager.liveViewFrame.collect { frame ->
                _liveViewFrame.value = frame
            }
        }
        viewModelScope.launch {
            manager.liveViewBitmap.collect { bitmap ->
                _liveViewBitmap.value = bitmap
            }
        }
        viewModelScope.launch {
            manager.isLiveViewStreaming.collect { isStreaming ->
                _isLiveViewStreaming.value = isStreaming
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

    fun refreshGDriveSync(folderPath: String = sessionId) {
        uploadManager.checkFolderGDriveSync(folderPath)
    }

    fun fetchGoogleAccounts(onResult: (Result<List<GoogleAccount>>) -> Unit) {
        viewModelScope.launch {
            val res = uploadManager.apiClient.fetchGoogleAccounts()
            withContext(Dispatchers.Main) {
                onResult(res)
            }
        }
    }

    fun fetchGoogleDriveFolders(
        accountId: String,
        parentId: String? = null,
        onResult: (Result<List<GDriveFolder>>) -> Unit
    ) {
        viewModelScope.launch {
            val res = uploadManager.apiClient.fetchGoogleDriveFolders(accountId, parentId)
            withContext(Dispatchers.Main) {
                onResult(res)
            }
        }
    }

    fun initializeGDriveSync(
        accountId: String,
        folderName: String? = null,
        existingFolderId: String? = null,
        onResult: (Result<FolderGDriveSync>) -> Unit
    ) {
        viewModelScope.launch {
            val res = uploadManager.apiClient.initializeGDriveSync(
                accountId = accountId,
                folderPath = sessionId,
                folderName = folderName,
                existingFolderId = existingFolderId
            )
            res.onSuccess {
                uploadManager.checkFolderGDriveSync(sessionId)
            }
            withContext(Dispatchers.Main) {
                onResult(res)
            }
        }
    }

    fun fetchFolderShareLinks(onResult: (DualShareLinks) -> Unit) {
        viewModelScope.launch {
            var hellomyphotosLink: String? = null
            var gdriveLink: String? = null
            var isSynced = false
            var gdriveStatus = "NOT_INITIALIZED"

            // 1. Fetch Hellomyphotos folder share link
            val shareRes = uploadManager.apiClient.createShareLink(sessionId)
            shareRes.onSuccess {
                hellomyphotosLink = it.shareUrl
            }

            // 2. Fetch Google Drive folder sync info
            val gdriveRes = uploadManager.apiClient.fetchFolderGDriveSync(sessionId)
            gdriveRes.onSuccess { info ->
                if (info.synced && info.sync != null) {
                    isSynced = true
                    gdriveLink = info.sync.gdriveLink.ifBlank { "https://drive.google.com/drive/folders/${info.sync.gdriveFolderId}" }
                    gdriveStatus = if (info.sync.syncStatus == "PAUSED") "PAUSED" else if (info.sync.syncStatus == "SYNCING") "SYNCING" else "READY"
                } else {
                    isSynced = false
                    gdriveStatus = "NOT_INITIALIZED"
                }
            }

            val result = DualShareLinks(
                title = "Folder: $sessionId",
                hellomyphotosUrl = hellomyphotosLink,
                gdriveUrl = gdriveLink,
                isGDriveSynced = isSynced,
                gdriveStatus = gdriveStatus,
                isLoading = false
            )
            withContext(Dispatchers.Main) {
                onResult(result)
            }
        }
    }

    fun fetchPhotoShareLinks(photo: PhotoItem, onResult: (DualShareLinks) -> Unit) {
        viewModelScope.launch {
            var hellomyphotosLink: String? = null
            var gdriveLink: String? = null
            var isSynced = false
            var gdriveStatus = "NOT_INITIALIZED"

            // Check if folder itself has GDrive initialized
            val folderGdriveRes = uploadManager.apiClient.fetchFolderGDriveSync(sessionId)
            val folderIsSynced = folderGdriveRes.getOrNull()?.synced == true

            // Resolve server media id if not already known
            var mediaId = photo.serverMediaId
            if (mediaId == null) {
                val serverPhotos = uploadManager.apiClient.fetchSessionPhotos(sessionId, view = "all")
                val matched = serverPhotos.find {
                    it.fileName.equals(photo.fileName, ignoreCase = true) ||
                            it.fileName.equals(photo.fileName.replace(Regex("\\.arw$", RegexOption.IGNORE_CASE), ".jpg"), ignoreCase = true)
                }
                if (matched != null) {
                    mediaId = matched.id
                }
            }

            if (mediaId != null) {
                // 1. Create or fetch single photo share link on Hellomyphotos
                val shareRes = uploadManager.apiClient.createMediaShareLink(sessionId, mediaId)
                shareRes.onSuccess {
                    hellomyphotosLink = it.shareUrl
                }

                // 2. Check Google Drive sync for this specific photo
                if (folderIsSynced) {
                    val mediaGdriveRes = uploadManager.apiClient.fetchMediaGDriveLink(mediaId)
                    mediaGdriveRes.onSuccess { mLink ->
                        if (mLink.synced && !mLink.link.isNullOrBlank()) {
                            isSynced = true
                            gdriveLink = mLink.link
                            gdriveStatus = "READY"
                        } else {
                            isSynced = false
                            gdriveStatus = "SYNCING" // Folder is connected, photo is being processed / queued
                        }
                    }
                } else {
                    isSynced = false
                    gdriveStatus = "NOT_INITIALIZED"
                }
            } else {
                // Photo hasn't been uploaded to server yet
                gdriveStatus = if (folderIsSynced) "NOT_UPLOADED" else "NOT_INITIALIZED"
            }

            val result = DualShareLinks(
                title = photo.fileName,
                hellomyphotosUrl = hellomyphotosLink,
                gdriveUrl = gdriveLink,
                isGDriveSynced = isSynced,
                gdriveStatus = gdriveStatus,
                isLoading = false,
                errorMessage = if (mediaId == null) "Photo not uploaded to server yet" else null
            )
            withContext(Dispatchers.Main) {
                onResult(result)
            }
        }
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
