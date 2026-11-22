package com.fastup.upload

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import com.fastup.model.PhotoItem
import com.fastup.model.ServerConnectionState
import com.fastup.model.ShareSessionInfo
import com.fastup.model.UploadFormat
import com.fastup.model.UploadStatus
import com.fastup.util.ArwParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue

data class LocalFolderInfo(
    val id: String,
    val displayName: String,
    val fileCount: Int,
    val totalSizeBytes: Long,
    val path: String
)

class UploadManager(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var workerJob: Job? = null

    private val prefs = context.getSharedPreferences("fastup_settings", Context.MODE_PRIVATE)
    private val uploadedPrefs = context.getSharedPreferences("fastup_uploaded_photos", Context.MODE_PRIVATE)

    // Preferences & Settings State
    var serverUrl: String = prefs.getString(PREF_SERVER_URL, "http://10.0.0.43:8000") ?: "http://10.0.0.43:8000"
        set(value) {
            field = value
            apiClient.baseUrl = value
            prefs.edit().putString(PREF_SERVER_URL, value).apply()
            checkServerConnection()
        }

    var apiKey: String = prefs.getString(PREF_API_KEY, "fastup_secret_key") ?: "fastup_secret_key"
        set(value) {
            field = value
            apiClient.apiKey = value
            prefs.edit().putString(PREF_API_KEY, value).apply()
        }

    private val _photographerName = MutableStateFlow<String?>(prefs.getString(PREF_PHOTOGRAPHER_NAME, null))
    val photographerName: StateFlow<String?> = _photographerName.asStateFlow()

    var apiClient = ApiClient(serverUrl, apiKey)
        private set

    private val _currentSessionId = MutableStateFlow(prefs.getString(PREF_CURRENT_FOLDER, "weddings") ?: "weddings")
    val currentSessionIdFlow: StateFlow<String> = _currentSessionId.asStateFlow()

    var currentSessionId: String
        get() = _currentSessionId.value
        set(value) {
            _currentSessionId.value = value
            prefs.edit().putString(PREF_CURRENT_FOLDER, value).apply()
            syncUploadedFilesWithServer()
        }

    var cameraCaptureFormat: com.fastup.model.CameraCaptureFormat = try {
        com.fastup.model.CameraCaptureFormat.valueOf(
            prefs.getString(PREF_CAMERA_CAPTURE_FORMAT, com.fastup.model.CameraCaptureFormat.JPEG_ONLY.name)
                ?: com.fastup.model.CameraCaptureFormat.JPEG_ONLY.name
        )
    } catch (_: Exception) {
        com.fastup.model.CameraCaptureFormat.JPEG_ONLY
    }
        set(value) {
            field = value
            prefs.edit().putString(PREF_CAMERA_CAPTURE_FORMAT, value.name).apply()
        }

    var uploadFormat: UploadFormat = try {
        UploadFormat.valueOf(prefs.getString(PREF_UPLOAD_FORMAT, UploadFormat.RAW_AND_JPEG.name) ?: UploadFormat.RAW_AND_JPEG.name)
    } catch (_: Exception) {
        UploadFormat.RAW_AND_JPEG
    }
        set(value) {
            field = value
            prefs.edit().putString(PREF_UPLOAD_FORMAT, value.name).apply()
        }

    var autoUpload: Boolean = prefs.getBoolean(PREF_AUTO_UPLOAD, true)
        set(value) {
            field = value
            prefs.edit().putBoolean(PREF_AUTO_UPLOAD, value).apply()
        }

    var wifiOnly: Boolean = prefs.getBoolean(PREF_WIFI_ONLY, false)
        set(value) {
            field = value
            prefs.edit().putBoolean(PREF_WIFI_ONLY, value).apply()
        }

    var liveViewerMode: com.fastup.model.LiveViewerMode = try {
        com.fastup.model.LiveViewerMode.valueOf(
            prefs.getString(PREF_LIVE_VIEWER_MODE, com.fastup.model.LiveViewerMode.INSTANT_CAPTURE.name)
                ?: com.fastup.model.LiveViewerMode.INSTANT_CAPTURE.name
        )
    } catch (_: Exception) {
        com.fastup.model.LiveViewerMode.INSTANT_CAPTURE
    }
        set(value) {
            field = value
            prefs.edit().putString(PREF_LIVE_VIEWER_MODE, value.name).apply()
        }

    private val _isUploadPaused = MutableStateFlow(prefs.getBoolean(PREF_UPLOAD_PAUSED, false))
    val isUploadPaused: StateFlow<Boolean> = _isUploadPaused.asStateFlow()

    // State Flows for UI
    private val _photos = MutableStateFlow<List<PhotoItem>>(emptyList())
    val photos: StateFlow<List<PhotoItem>> = _photos.asStateFlow()

    private val _currentSpeedBps = MutableStateFlow(0L)
    val currentSpeedBps: StateFlow<Long> = _currentSpeedBps.asStateFlow()

    private val _isUploading = MutableStateFlow(false)
    val isUploading: StateFlow<Boolean> = _isUploading.asStateFlow()

    private val _shareSession = MutableStateFlow<ShareSessionInfo?>(null)
    val shareSession: StateFlow<ShareSessionInfo?> = _shareSession.asStateFlow()

    private val _serverConnectionState = MutableStateFlow(ServerConnectionState.DISCONNECTED)
    val serverConnectionState: StateFlow<ServerConnectionState> = _serverConnectionState.asStateFlow()

    private val _localFolders = MutableStateFlow<List<LocalFolderInfo>>(emptyList())
    val localFolders: StateFlow<List<LocalFolderInfo>> = _localFolders.asStateFlow()

    // Internal Upload Queue
    private val pendingQueue = ConcurrentLinkedQueue<String>() // photo IDs

    init {
        scanExistingCaptures()
        refreshLocalFolders()
        syncUploadedFilesWithServer()
        checkServerConnection()
        startWorker()
    }

    fun checkServerConnection(onResult: ((Boolean) -> Unit)? = null) {
        scope.launch {
            _serverConnectionState.value = ServerConnectionState.CONNECTING
            val res = apiClient.checkConnection()
            res.onSuccess {
                _serverConnectionState.value = ServerConnectionState.CONNECTED
                onResult?.invoke(true)
            }.onFailure {
                _serverConnectionState.value = ServerConnectionState.DISCONNECTED
                onResult?.invoke(false)
            }
        }
    }

    fun syncUploadedFilesWithServer() {
        scope.launch {
            try {
                val serverFiles = apiClient.fetchSessionUploadedFiles(currentSessionId)
                if (serverFiles.isNotEmpty()) {
                    val editor = uploadedPrefs.edit()
                    serverFiles.forEach { fileName ->
                        editor.putBoolean(fileName, true)
                        val base = fileName.substringBeforeLast(".")
                        editor.putBoolean(base, true)
                    }
                    editor.apply()

                    _photos.update { list ->
                        list.map { photo ->
                            val isCompleted = uploadedPrefs.getBoolean(photo.id, false) ||
                                (photo.rawFile != null && uploadedPrefs.getBoolean(photo.rawFile.name, false)) ||
                                (photo.jpegFile != null && uploadedPrefs.getBoolean(photo.jpegFile.name, false)) ||
                                uploadedPrefs.getBoolean(photo.fileName, false)
                            if (isCompleted && photo.status != UploadStatus.COMPLETED) {
                                photo.copy(status = UploadStatus.COMPLETED, uploadProgress = 1f, errorMessage = null)
                            } else {
                                photo
                            }
                        }
                    }
                    Log.i(TAG, "Synced ${serverFiles.size} uploaded files from server for session $currentSessionId")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed syncing uploaded files with server: ${e.message}")
            }
        }
    }

    fun scanExistingCaptures() {
        try {
            val baseDir = File(context.getExternalFilesDir(null), "tether_captures")
            if (!baseDir.exists()) return

            val files = mutableListOf<File>()
            fun scan(dir: File) {
                dir.listFiles()?.forEach { f ->
                    if (f.isDirectory) {
                        scan(f)
                    } else if (f.isFile && (f.name.endsWith(".arw", true) || f.name.endsWith(".jpg", true) || f.name.endsWith(".jpeg", true))) {
                        files.add(f)
                    }
                }
            }
            scan(baseDir)
            files.sortByDescending { it.lastModified() }

            val currentIds = _photos.value.map { it.id }.toSet()
            val previewCacheDir = File(context.cacheDir, "previews")

            val grouped = files.groupBy { it.nameWithoutExtension }
            val newItems = grouped.filter { (base, _) -> base !in currentIds }.map { (base, groupFiles) ->
                val raw = groupFiles.find { it.name.endsWith(".arw", ignoreCase = true) }
                val jpeg = groupFiles.find { it.name.endsWith(".jpg", ignoreCase = true) || it.name.endsWith(".jpeg", ignoreCase = true) }
                val primaryFile = raw ?: jpeg ?: groupFiles.first()

                val thumbFile = File(previewCacheDir, "${base}_thumb.jpg")
                val previewFile = jpeg ?: if (thumbFile.exists() && thumbFile.length() > 0) thumbFile else null

                val totalSize = (raw?.length() ?: 0L) + (jpeg?.length() ?: 0L).let { if (raw == null) primaryFile.length() else it }

                val isUploaded = uploadedPrefs.getBoolean(base, false) ||
                        groupFiles.any { uploadedPrefs.getBoolean(it.name, false) }

                PhotoItem(
                    id = base,
                    fileName = primaryFile.name,
                    localFile = primaryFile,
                    rawFile = raw,
                    jpegFile = jpeg,
                    previewFile = previewFile,
                    fileSize = totalSize,
                    timestamp = primaryFile.lastModified(),
                    status = if (isUploaded) UploadStatus.COMPLETED else UploadStatus.PENDING,
                    uploadProgress = if (isUploaded) 1f else 0f,
                    errorMessage = null
                )
            }
            if (newItems.isNotEmpty()) {
                _photos.update { current ->
                    current + newItems
                }
            }
            refreshLocalFolders()
        } catch (e: Exception) {
            Log.e(TAG, "Error scanning existing captures", e)
        }
    }

    fun addPhoto(photo: PhotoItem) {
        val baseName = photo.id
        var itemToEnqueue = photo

        _photos.update { current ->
            val existing = current.find { it.id == baseName || it.fileName.substringBeforeLast(".") == baseName }
            if (existing != null) {
                val raw = existing.rawFile ?: photo.rawFile
                val jpeg = existing.jpegFile ?: photo.jpegFile
                val primary = raw ?: jpeg ?: photo.localFile
                val preview = jpeg ?: existing.previewFile ?: photo.previewFile
                val totalSize = (raw?.length() ?: 0L) + (jpeg?.length() ?: 0L).let { if (raw == null) primary.length() else it }

                val merged = existing.copy(
                    id = baseName,
                    fileName = primary.name,
                    localFile = primary,
                    rawFile = raw,
                    jpegFile = jpeg,
                    previewFile = preview,
                    fileSize = totalSize,
                    exif = if (existing.exif.shutterSpeed != null) existing.exif else photo.exif
                )
                itemToEnqueue = merged
                listOf(merged) + current.filter { it.id != existing.id && it.id != baseName }
            } else {
                listOf(photo) + current.filter { it.id != baseName }
            }
        }
        refreshLocalFolders()
        if (autoUpload) {
            enqueue(itemToEnqueue.id)
        }
    }

    fun enqueue(photoId: String, force: Boolean = false) {
        val photo = _photos.value.find { it.id == photoId } ?: return

        // If already completed and not forced, don't re-upload
        if (photo.status == UploadStatus.COMPLETED && !force) {
            Log.d(TAG, "Photo ${photo.fileName} is already completed. Skipping enqueue.")
            return
        }

        // Verify format eligibility
        val shouldUpload = when (uploadFormat) {
            UploadFormat.RAW_AND_JPEG -> true
            UploadFormat.JPEG_ONLY -> photo.jpegFile != null || photo.isJpeg || photo.previewFile != null || photo.isRaw
            UploadFormat.RAW_ONLY -> photo.rawFile != null || photo.isRaw
        }

        if (!shouldUpload) {
            Log.d(TAG, "Skipping upload for ${photo.fileName} due to format setting: $uploadFormat")
            return
        }

        Log.d(TAG, "Enqueuing ${photo.fileName} (id=$photoId) for upload")
        _photos.update { list ->
            list.map {
                if (it.id == photoId) it.copy(status = UploadStatus.PENDING, errorMessage = null)
                else it
            }
        }

        if (!pendingQueue.contains(photoId)) {
            pendingQueue.add(photoId)
        }
        triggerNext()
    }

    fun retryFailed() {
        if (_isUploadPaused.value) {
            _isUploadPaused.value = false
            prefs.edit().putBoolean(PREF_UPLOAD_PAUSED, false).apply()
        }
        val failed = _photos.value.filter { it.status == UploadStatus.FAILED }
        Log.i(TAG, "Retrying ${failed.size} failed uploads...")
        for (item in failed) {
            enqueue(item.id, force = true)
        }
    }

    fun retryPhoto(id: String) {
        if (_isUploadPaused.value) {
            _isUploadPaused.value = false
            prefs.edit().putBoolean(PREF_UPLOAD_PAUSED, false).apply()
        }
        enqueue(id, force = true)
    }

    private fun canUploadNow(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return true
        val activeNetwork = cm.activeNetwork ?: return true
        val caps = cm.getNetworkCapabilities(activeNetwork) ?: return true

        if (wifiOnly) {
            val isWifi = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
            return isWifi
        }
        return true
    }

    private fun startWorker() {
        workerJob?.cancel()
        workerJob = scope.launch {
            while (true) {
                if (_isUploadPaused.value) {
                    _isUploading.value = false
                    _currentSpeedBps.value = 0L
                    kotlinx.coroutines.delay(1000)
                    continue
                }

                val nextId = pendingQueue.poll()
                if (nextId == null) {
                    _isUploading.value = false
                    _currentSpeedBps.value = 0L
                    kotlinx.coroutines.delay(500)
                    continue
                }

                if (!canUploadNow()) {
                    Log.w(TAG, "Cannot upload now (network constraints / Wi-Fi only: $wifiOnly)")
                    pendingQueue.add(nextId)
                    _isUploading.value = false
                    kotlinx.coroutines.delay(2000)
                    continue
                }

                uploadSingle(nextId)
            }
        }
    }

    fun forceStopUpload() {
        Log.i(TAG, "Force stopping uploads...")
        _isUploadPaused.value = true
        prefs.edit().putBoolean(PREF_UPLOAD_PAUSED, true).apply()

        // Cancel any in-flight HTTP upload call immediately
        apiClient.cancelCurrentUpload()

        // Reset any UPLOADING photos back to PENDING so they can resume later
        _photos.update { list ->
            list.map {
                if (it.status == UploadStatus.UPLOADING) it.copy(status = UploadStatus.PENDING, uploadSpeedBps = 0L)
                else it
            }
        }
        _isUploading.value = false
        _currentSpeedBps.value = 0L
    }

    fun startUpload() {
        Log.i(TAG, "Starting / resuming uploads...")
        _isUploadPaused.value = false
        prefs.edit().putBoolean(PREF_UPLOAD_PAUSED, false).apply()

        // Re-enqueue all pending items if they aren't already queued
        val pendingPhotos = _photos.value.filter { it.status == UploadStatus.PENDING }
        for (item in pendingPhotos) {
            if (!pendingQueue.contains(item.id)) {
                pendingQueue.add(item.id)
            }
        }
        triggerNext()
    }

    private suspend fun uploadSingle(photoId: String) {
        val photo = _photos.value.find { it.id == photoId } ?: return
        _isUploading.value = true

        _photos.update { list ->
            list.map {
                if (it.id == photoId) it.copy(status = UploadStatus.UPLOADING, uploadProgress = 0f)
                else it
            }
        }

        var currentPhoto = photo
        // If JPEG_ONLY format and we have a RAW file without a preview or camera JPEG, extract high-res preview on demand
        if (uploadFormat == UploadFormat.JPEG_ONLY && currentPhoto.jpegFile == null && currentPhoto.previewFile == null && currentPhoto.isRaw) {
            val previewDir = File(context.cacheDir, "previews").apply { mkdirs() }
            val extracted = ArwParser.extractPreview(currentPhoto.localFile, previewDir)
            if (extracted != null && extracted.exists() && extracted.length() > 0) {
                currentPhoto = currentPhoto.copy(previewFile = extracted)
                _photos.update { list ->
                    list.map { if (it.id == photoId) currentPhoto else it }
                }
            }
        }

        // Determine which files to upload based on uploadFormat
        val filesToUpload: List<File> = when (uploadFormat) {
            UploadFormat.RAW_ONLY -> listOfNotNull(currentPhoto.rawFile ?: currentPhoto.localFile)
            UploadFormat.JPEG_ONLY -> listOfNotNull(currentPhoto.jpegFile ?: currentPhoto.previewFile ?: currentPhoto.displayFile)
            UploadFormat.RAW_AND_JPEG -> {
                val list = mutableListOf<File>()
                currentPhoto.rawFile?.let { list.add(it) }
                currentPhoto.jpegFile?.let { list.add(it) }
                if (list.isEmpty()) list.add(currentPhoto.localFile)
                list
            }
        }

        val totalBatchBytes = filesToUpload.sumOf { it.length() }.coerceAtLeast(1L)
        var totalUploadedBytes = 0L
        var uploadFailed = false
        var failureReason: String? = null

        for (fileToUpload in filesToUpload) {
            var lastTime = System.currentTimeMillis()
            var lastBytes = 0L

            val result = apiClient.uploadPhoto(
                file = fileToUpload,
                sessionId = currentSessionId
            ) { bytesWritten, _ ->
                val now = System.currentTimeMillis()
                val dt = (now - lastTime).coerceAtLeast(1)
                val dBytes = bytesWritten - lastBytes

                if (dt >= 400 || (totalUploadedBytes + bytesWritten) >= totalBatchBytes) {
                    val speedBps = (dBytes * 1000) / dt
                    lastTime = now
                    lastBytes = bytesWritten

                    _currentSpeedBps.value = speedBps
                    val overallProgress = ((totalUploadedBytes + bytesWritten).toFloat() / totalBatchBytes).coerceIn(0f, 1f)

                    _photos.update { list ->
                        list.map {
                            if (it.id == photoId) it.copy(
                                uploadProgress = overallProgress,
                                uploadSpeedBps = speedBps
                            )
                            else it
                        }
                    }
                }
            }

            if (!result.isSuccess) {
                uploadFailed = true
                failureReason = result.exceptionOrNull()?.message ?: "Upload failed"
                break
            } else {
                totalUploadedBytes += fileToUpload.length()
            }
        }

        if (uploadFailed) {
            _serverConnectionState.value = ServerConnectionState.ERROR
            Log.e(TAG, "Failed uploading ${currentPhoto.fileName}: $failureReason")
            _photos.update { list ->
                list.map {
                    if (it.id == photoId) it.copy(
                        status = UploadStatus.FAILED,
                        errorMessage = failureReason ?: "Upload failed",
                        uploadSpeedBps = 0L
                    )
                    else it
                }
            }
        } else {
            _serverConnectionState.value = ServerConnectionState.CONNECTED
            Log.i(TAG, "Successfully uploaded ${currentPhoto.fileName} (${filesToUpload.size} file(s))")
            val editor = uploadedPrefs.edit()
            editor.putBoolean(photoId, true)
            filesToUpload.forEach { f ->
                editor.putBoolean(f.name, true)
                editor.putBoolean(f.nameWithoutExtension, true)
            }
            editor.apply()

            _photos.update { list ->
                list.map {
                    if (it.id == photoId) it.copy(
                        status = UploadStatus.COMPLETED,
                        uploadProgress = 1f,
                        uploadSpeedBps = 0L,
                        errorMessage = null
                    )
                    else it
                }
            }
        }
    }

    fun refreshLocalFolders() {
        try {
            val baseDir = File(context.getExternalFilesDir(null), "tether_captures")
            if (!baseDir.exists()) {
                _localFolders.value = emptyList()
                return
            }

            fun isPhoto(f: File) = f.isFile && (f.name.endsWith(".arw", true) || f.name.endsWith(".jpg", true) || f.name.endsWith(".jpeg", true))

            val allFiles = mutableListOf<File>()
            fun scanAll(dir: File) {
                dir.listFiles()?.forEach { f ->
                    if (f.isDirectory) scanAll(f)
                    else if (isPhoto(f)) allFiles.add(f)
                }
            }
            scanAll(baseDir)

            val folderList = mutableListOf<LocalFolderInfo>()

            // 1. All captures option
            if (allFiles.isNotEmpty()) {
                folderList.add(
                    LocalFolderInfo(
                        id = "__all__",
                        displayName = "All Captures (${allFiles.size} photos)",
                        fileCount = allFiles.size,
                        totalSizeBytes = allFiles.sumOf { it.length() },
                        path = baseDir.absolutePath
                    )
                )
            }

            // 2. Root files directly in tether_captures
            val rootFiles = baseDir.listFiles()?.filter { isPhoto(it) } ?: emptyList()
            if (rootFiles.isNotEmpty()) {
                folderList.add(
                    LocalFolderInfo(
                        id = "__root__",
                        displayName = "Root Folder (tether_captures)",
                        fileCount = rootFiles.size,
                        totalSizeBytes = rootFiles.sumOf { it.length() },
                        path = baseDir.absolutePath
                    )
                )
            }

            // 3. Subdirectories
            val subdirs = baseDir.listFiles()?.filter { it.isDirectory } ?: emptyList()
            for (subdir in subdirs) {
                val subFiles = mutableListOf<File>()
                fun scanSub(d: File) {
                    d.listFiles()?.forEach { f ->
                        if (f.isDirectory) scanSub(f)
                        else if (isPhoto(f)) subFiles.add(f)
                    }
                }
                scanSub(subdir)
                if (subFiles.isNotEmpty()) {
                    folderList.add(
                        LocalFolderInfo(
                            id = subdir.name,
                            displayName = "${subdir.name} (${subFiles.size} photos)",
                            fileCount = subFiles.size,
                            totalSizeBytes = subFiles.sumOf { it.length() },
                            path = subdir.absolutePath
                        )
                    )
                }
            }

            _localFolders.value = folderList
        } catch (e: Exception) {
            Log.e(TAG, "Error refreshing local folders", e)
        }
    }

    fun clearLocalStorage(folderId: String): Boolean {
        try {
            val baseDir = File(context.getExternalFilesDir(null), "tether_captures")
            val previewCacheDir = File(context.cacheDir, "previews")
            if (!baseDir.exists()) return true

            when (folderId) {
                "__all__" -> {
                    baseDir.deleteRecursively()
                    baseDir.mkdirs()
                    previewCacheDir.deleteRecursively()
                    previewCacheDir.mkdirs()
                    _photos.value = emptyList()
                    pendingQueue.clear()
                    _isUploading.value = false
                    _currentSpeedBps.value = 0L
                }
                "__root__" -> {
                    val rootFiles = baseDir.listFiles()?.filter { it.isFile } ?: emptyList()
                    val deletedNames = mutableSetOf<String>()
                    for (file in rootFiles) {
                        deletedNames.add(file.name)
                        File(previewCacheDir, "${file.nameWithoutExtension}_thumb.jpg").delete()
                        file.delete()
                    }
                    _photos.update { list ->
                        list.filter { it.fileName !in deletedNames }
                    }
                    pendingQueue.removeIf { it in deletedNames }
                }
                else -> {
                    val targetDir = File(baseDir, folderId)
                    if (targetDir.exists()) {
                        val subFiles = mutableListOf<File>()
                        fun findFiles(d: File) {
                            d.listFiles()?.forEach { f ->
                                if (f.isDirectory) findFiles(f)
                                else subFiles.add(f)
                            }
                        }
                        findFiles(targetDir)
                        val deletedNames = subFiles.map { it.name }.toSet()
                        for (file in subFiles) {
                            File(previewCacheDir, "${file.nameWithoutExtension}_thumb.jpg").delete()
                        }
                        targetDir.deleteRecursively()
                        _photos.update { list ->
                            list.filter { it.fileName !in deletedNames }
                        }
                        pendingQueue.removeIf { it in deletedNames }
                    }
                }
            }
            refreshLocalFolders()
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Error clearing local storage for $folderId", e)
            return false
        }
    }

    fun triggerNext() {
        // Worker polls automatically
    }

    fun generateShareLink(onResult: (Result<ShareSessionInfo>) -> Unit) {
        scope.launch {
            val res = apiClient.createShareLink(currentSessionId)
            res.onSuccess { info ->
                _shareSession.value = info
            }
            withContext(Dispatchers.Main) {
                onResult(res)
            }
        }
    }

    fun clearShareSession() {
        _shareSession.value = null
    }

    fun applyClaimedOtp(result: ClaimOtpResult) {
        apiKey = result.token
        _photographerName.value = result.photographerName
        prefs.edit().putString(PREF_PHOTOGRAPHER_NAME, result.photographerName).apply()

        if (result.serverUrl.isNotBlank()) {
            serverUrl = result.serverUrl
        }
        if (result.folderPath.isNotBlank()) {
            currentSessionId = result.folderPath
        }
        uploadFormat = try {
            when (result.uploadFormat) {
                "JPEG_ONLY" -> UploadFormat.JPEG_ONLY
                "RAW_ONLY" -> UploadFormat.RAW_ONLY
                else -> UploadFormat.RAW_AND_JPEG
            }
        } catch (_: Exception) {
            UploadFormat.JPEG_ONLY
        }
        autoUpload = result.autoUpload
        wifiOnly = result.wifiOnly
    }

    fun disconnectPhotographer() {
        _photographerName.value = null
        prefs.edit().remove(PREF_PHOTOGRAPHER_NAME).apply()
    }

    companion object {
        private const val TAG = "UploadManager"
        private const val PREF_SERVER_URL = "pref_server_url"
        private const val PREF_API_KEY = "pref_api_key"
        private const val PREF_CURRENT_FOLDER = "pref_current_folder"
        private const val PREF_UPLOAD_FORMAT = "pref_upload_format"
        private const val PREF_CAMERA_CAPTURE_FORMAT = "pref_camera_capture_format"
        private const val PREF_AUTO_UPLOAD = "pref_auto_upload"
        private const val PREF_WIFI_ONLY = "pref_wifi_only"
        private const val PREF_UPLOAD_PAUSED = "pref_upload_paused"
        private const val PREF_PHOTOGRAPHER_NAME = "pref_photographer_name"
        private const val PREF_LIVE_VIEWER_MODE = "pref_live_viewer_mode"
    }
}
