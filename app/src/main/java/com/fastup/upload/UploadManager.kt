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

    private val _scopes = MutableStateFlow<List<String>>(
        prefs.getStringSet(PREF_SCOPES, emptySet())?.toList() ?: emptyList()
    )
    val scopesFlow: StateFlow<List<String>> = _scopes.asStateFlow()

    var scopes: List<String>
        get() = _scopes.value
        set(value) {
            _scopes.value = value
            prefs.edit().putStringSet(PREF_SCOPES, value.toSet()).apply()
        }

    fun updateScopes(newScopes: List<String>) {
        val clean = newScopes.map { it.trim().trim('/') }.filter { it.isNotEmpty() }
        scopes = clean
    }

    private val _networkSimulation = MutableStateFlow(
        com.fastup.model.NetworkSimulationConfig(
            enabled = prefs.getBoolean(PREF_NET_SIM_ENABLED, false),
            profile = prefs.getString(PREF_NET_SIM_PROFILE, "OFF") ?: "OFF",
            latencyMs = prefs.getLong(PREF_NET_SIM_LATENCY_MS, 0L),
            dropRatePercent = prefs.getInt(PREF_NET_SIM_DROP_RATE, 0),
            throttleKbps = prefs.getLong(PREF_NET_SIM_THROTTLE_KBPS, 0L)
        )
    )
    val networkSimulationFlow: StateFlow<com.fastup.model.NetworkSimulationConfig> = _networkSimulation.asStateFlow()

    fun updateNetworkSimulation(config: com.fastup.model.NetworkSimulationConfig) {
        _networkSimulation.value = config
        apiClient.networkSimulation = config
        prefs.edit()
            .putBoolean(PREF_NET_SIM_ENABLED, config.enabled)
            .putString(PREF_NET_SIM_PROFILE, config.profile)
            .putLong(PREF_NET_SIM_LATENCY_MS, config.latencyMs)
            .putInt(PREF_NET_SIM_DROP_RATE, config.dropRatePercent)
            .putLong(PREF_NET_SIM_THROTTLE_KBPS, config.throttleKbps)
            .apply()
    }

    fun isFolderWritable(folderPath: String): Boolean {
        val clean = folderPath.trim().trim('/')
        // Root is NEVER writable (users cannot write directly to root parent folder)
        if (clean.isEmpty()) return false

        val currentScopes = scopes
        // If no scopes set or unrestricted (contains * or /), any non-empty subfolder is writable
        if (currentScopes.isEmpty() || currentScopes.any { it == "*" || it == "" || it == "/" }) {
            return true
        }

        return currentScopes.any { scope ->
            val s = scope.trim().trim('/')
            if (s.isEmpty() || s == "*") true
            else clean.equals(s, ignoreCase = true) || clean.startsWith("$s/", ignoreCase = true)
        }
    }

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
            checkFolderGDriveSync(value)
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

    private val _folderGDriveSync = MutableStateFlow<com.fastup.model.FolderSyncInfo?>(null)
    val folderGDriveSync: StateFlow<com.fastup.model.FolderSyncInfo?> = _folderGDriveSync.asStateFlow()

    private val _serverConnectionState = MutableStateFlow(ServerConnectionState.DISCONNECTED)
    val serverConnectionState: StateFlow<ServerConnectionState> = _serverConnectionState.asStateFlow()

    private val _localFolders = MutableStateFlow<List<LocalFolderInfo>>(emptyList())
    val localFolders: StateFlow<List<LocalFolderInfo>> = _localFolders.asStateFlow()

    // Internal Upload Queue
    private val pendingQueue = ConcurrentLinkedQueue<String>() // photo IDs
    private val retryAttempts = java.util.concurrent.ConcurrentHashMap<String, Int>()
    private var autoRetryJob: Job? = null

    init {
        apiClient.networkSimulation = _networkSimulation.value
        scanExistingCaptures()
        refreshLocalFolders()
        syncUploadedFilesWithServer()
        checkFolderGDriveSync()
        checkServerConnection()
        startWorker()
    }

    fun checkFolderGDriveSync(folderPath: String = currentSessionId, onResult: ((com.fastup.model.FolderSyncInfo?) -> Unit)? = null) {
        scope.launch {
            val res = apiClient.fetchFolderGDriveSync(folderPath)
            val info = res.getOrNull()
            _folderGDriveSync.value = info
            withContext(Dispatchers.Main) {
                onResult?.invoke(info)
            }
        }
    }

    suspend fun pingServer(targetUrl: String? = null): Result<Boolean> {
        return apiClient.pingServer(targetUrl)
    }

    fun checkServerConnection(onResult: ((Boolean) -> Unit)? = null) {
        scope.launch {
            _serverConnectionState.value = ServerConnectionState.CONNECTING
            val res = apiClient.checkConnection()
            res.onSuccess {
                _serverConnectionState.value = ServerConnectionState.CONNECTED
                com.fastup.util.IssueAlertNotifier.clearAlert(context, "server_disconnected")
                com.fastup.util.IssueAlertNotifier.clearAlert(context, "server_auth_expired")
                onResult?.invoke(true)
            }.onFailure { err ->
                val msg = err.message ?: ""
                if (msg.contains("AUTH_EXPIRED") || msg.contains("401") || msg.contains("403")) {
                    _serverConnectionState.value = ServerConnectionState.ERROR
                    com.fastup.util.IssueAlertNotifier.notifyIssue(
                        context = context,
                        issueKey = "server_auth_expired",
                        title = "FastUp Session Expired",
                        message = "Photographer token expired (HTTP 401). Tap to re-pair via OTP.",
                        isError = true
                    )
                } else {
                    _serverConnectionState.value = ServerConnectionState.DISCONNECTED
                    com.fastup.util.IssueAlertNotifier.notifyIssue(
                        context = context,
                        issueKey = "server_disconnected",
                        title = "FastUp Server Offline",
                        message = "Cannot reach server at $serverUrl. Check Wi-Fi & IP.",
                        isError = true
                    )
                }
                onResult?.invoke(false)
            }
        }
    }

    private var debounceSyncJob: Job? = null
    private var lastSyncTime = 0L

    fun syncUploadedFilesWithServer(onComplete: (() -> Unit)? = null) {
        val now = System.currentTimeMillis()
        // Throttle server photo syncs to at most once every 5 seconds to preserve socket bandwidth during active uploads
        if (now - lastSyncTime < 5000L && onComplete == null) {
            return
        }
        lastSyncTime = now
        scope.launch {
            try {
                val serverPhotos = apiClient.fetchSessionPhotos(currentSessionId, view = "all")
                if (serverPhotos.isNotEmpty()) {
                    val editor = uploadedPrefs.edit()
                    // Build lookup map by filename and filename without extension
                    val curationByFileName = mutableMapOf<String, com.fastup.model.ServerStagingPhoto>()
                    val curationByBaseName = mutableMapOf<String, com.fastup.model.ServerStagingPhoto>()

                    serverPhotos.forEach { sp ->
                        val fileName = sp.fileName
                        editor.putBoolean(fileName, true)
                        val base = fileName.substringBeforeLast(".")
                        editor.putBoolean(base, true)
                        curationByFileName[fileName] = sp
                        curationByBaseName[base] = sp
                    }
                    editor.apply()

                    _photos.update { list ->
                        list.map { photo ->
                            val isCompleted = uploadedPrefs.getBoolean(photo.id, false) ||
                                (photo.rawFile != null && uploadedPrefs.getBoolean(photo.rawFile.name, false)) ||
                                (photo.jpegFile != null && uploadedPrefs.getBoolean(photo.jpegFile.name, false)) ||
                                uploadedPrefs.getBoolean(photo.fileName, false)

                            val baseName = photo.fileName.substringBeforeLast(".")
                            val serverCuration = curationByFileName[photo.fileName]
                                ?: (photo.jpegFile?.name?.let { curationByFileName[it] })
                                ?: (photo.rawFile?.name?.let { curationByFileName[it] })
                                ?: curationByBaseName[baseName]

                            val newBurstGroupId = serverCuration?.burstGroupId ?: photo.burstGroupId
                            val newIsHero = serverCuration?.isHero ?: photo.isHero
                            val newBurstCount = serverCuration?.burstCount ?: photo.burstCount
                            val newQualityScore = serverCuration?.qualityScore ?: photo.qualityScore
                            val newServerMediaId = serverCuration?.id ?: photo.serverMediaId

                            val newStatus = if (isCompleted && photo.status != UploadStatus.COMPLETED) {
                                UploadStatus.COMPLETED
                            } else {
                                photo.status
                            }

                            photo.copy(
                                status = newStatus,
                                uploadProgress = if (newStatus == UploadStatus.COMPLETED) 1f else photo.uploadProgress,
                                errorMessage = if (newStatus == UploadStatus.COMPLETED) null else photo.errorMessage,
                                burstGroupId = newBurstGroupId,
                                isHero = newIsHero,
                                burstCount = newBurstCount,
                                qualityScore = newQualityScore,
                                serverMediaId = newServerMediaId
                            )
                        }
                    }
                    Log.i(TAG, "Synced ${serverPhotos.size} uploaded files and curation metadata from server for session $currentSessionId")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed syncing uploaded files with server: ${e.message}")
            } finally {
                onComplete?.invoke()
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
            retryAttempts.remove(item.id)
            enqueue(item.id, force = true)
        }
    }

    /**
     * Called whenever any upload completes successfully.
     * Since the network pipeline is demonstrably functional, any other pending or stalled photos
     * in the queue are accelerated and re-enqueued if not already queued.
     */
    private fun scheduleAutoRetryPending() {
        autoRetryJob?.cancel()
        autoRetryJob = scope.launch {
            kotlinx.coroutines.delay(1000)
            if (_isUploadPaused.value) return@launch
            val pendingPhotos = _photos.value.filter { it.status == UploadStatus.PENDING }
            var readdedCount = 0
            for (photo in pendingPhotos) {
                if (!pendingQueue.contains(photo.id)) {
                    pendingQueue.add(photo.id)
                    readdedCount++
                }
            }
            if (readdedCount > 0) {
                Log.i(TAG, "Healthy upload detected: automatically re-enqueued $readdedCount pending photo(s) to worker queue.")
            }
        }
    }

    /**
     * Force re-enqueues all photos currently in PENDING state.
     * Clears and resets pendingQueue to guarantee no orphaned/stuck items.
     * Resumes worker if paused.
     */
    fun forceUploadPending(): Int {
        if (_isUploadPaused.value) {
            _isUploadPaused.value = false
            prefs.edit().putBoolean(PREF_UPLOAD_PAUSED, false).apply()
        }
        val pendingPhotos = _photos.value.filter { it.status == UploadStatus.PENDING }
        Log.i(TAG, "Force re-enqueuing ${pendingPhotos.size} pending photo(s)...")
        pendingQueue.clear()
        for (item in pendingPhotos) {
            retryAttempts.remove(item.id)
            pendingQueue.add(item.id)
        }
        triggerNext()
        return pendingPhotos.size
    }

    fun retryAll(includeCompleted: Boolean = false): Int {
        if (_isUploadPaused.value) {
            _isUploadPaused.value = false
            prefs.edit().putBoolean(PREF_UPLOAD_PAUSED, false).apply()
        }
        val targetPhotos = if (includeCompleted) {
            _photos.value
        } else {
            _photos.value.filter { it.status != UploadStatus.COMPLETED }
        }
        Log.i(TAG, "Retrying/enqueuing ${targetPhotos.size} uploads (includeCompleted=$includeCompleted)...")
        for (item in targetPhotos) {
            retryAttempts.remove(item.id)
            enqueue(item.id, force = true)
        }
        triggerNext()
        return targetPhotos.size
    }

    fun retryPhoto(id: String) {
        if (_isUploadPaused.value) {
            _isUploadPaused.value = false
            prefs.edit().putBoolean(PREF_UPLOAD_PAUSED, false).apply()
        }
        retryAttempts.remove(id)
        enqueue(id, force = true)
    }

    private fun canUploadNow(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return true
        val activeNetwork = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(activeNetwork) ?: return false

        val hasInternet = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        if (!hasInternet) return false

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
                    Log.w(TAG, "Cannot upload now (offline or Wi-Fi only restriction: $wifiOnly)")
                    _serverConnectionState.value = ServerConnectionState.DISCONNECTED
                    pendingQueue.add(nextId)
                    _isUploading.value = false
                    _currentSpeedBps.value = 0L
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
        var lastUploadResponseJson: String? = null
        val maxAttemptsPerFile = 3

        // Calculate target server folder: if file was stored in a subfolder of tether_captures,
        // use its relative directory structure; otherwise default to currentSessionId.
        val baseDir = File(context.getExternalFilesDir(null), "tether_captures")
        val targetSession = try {
            val parentDir = currentPhoto.localFile.parentFile
            if (parentDir != null && baseDir.exists() && parentDir.absolutePath.startsWith(baseDir.absolutePath)) {
                val rel = parentDir.absolutePath.removePrefix(baseDir.absolutePath).trim('/')
                if (rel.isNotEmpty()) rel else currentSessionId
            } else {
                currentSessionId
            }
        } catch (_: Exception) {
            currentSessionId
        }

        for (fileToUpload in filesToUpload) {
            var fileSuccess = false
            var fileAttempt = 0
            var fileError: String? = null

            while (fileAttempt < maxAttemptsPerFile && !fileSuccess) {
                if (_isUploadPaused.value) {
                    Log.i(TAG, "Upload paused by user, halting file transfer")
                    return
                }

                fileAttempt++
                var lastTime = System.currentTimeMillis()
                var lastBytes = 0L

                val result = apiClient.uploadPhoto(
                    file = fileToUpload,
                    sessionId = targetSession
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
                                    uploadSpeedBps = speedBps,
                                    errorMessage = if (fileAttempt > 1) "Retrying ($fileAttempt/$maxAttemptsPerFile)..." else null
                                )
                                else it
                            }
                        }
                    }
                }

                if (result.isSuccess) {
                    fileSuccess = true
                    totalUploadedBytes += fileToUpload.length()
                    lastUploadResponseJson = result.getOrNull()
                } else {
                    fileError = result.exceptionOrNull()?.message ?: "Upload failed"
                    Log.w(TAG, "Upload attempt $fileAttempt failed for ${fileToUpload.name}: $fileError")

                    // Check if permanent error (e.g. 401 Unauthorized, 403 Forbidden / Scope disallowed)
                    val isPermanent = fileError.contains("401") || fileError.contains("403") ||
                            fileError.contains("Forbidden", ignoreCase = true) ||
                            fileError.contains("Unauthorized", ignoreCase = true) ||
                            fileError.contains("Outside allowed scope", ignoreCase = true) ||
                            fileError.contains("session is disabled", ignoreCase = true)

                    if (isPermanent) {
                        Log.e(TAG, "Permanent upload failure, aborting retries: $fileError")
                        break
                    }

                    if (fileAttempt < maxAttemptsPerFile) {
                        val backoffMs = (1000L * (1 shl (fileAttempt - 1))) + kotlin.random.Random.nextLong(200, 600)
                        Log.i(TAG, "Transient upload failure, retrying in ${backoffMs}ms (attempt ${fileAttempt + 1}/$maxAttemptsPerFile)...")
                        _photos.update { list ->
                            list.map {
                                if (it.id == photoId) it.copy(
                                    errorMessage = "Retrying in ${(backoffMs + 500) / 1000}s (attempt ${fileAttempt + 1}/$maxAttemptsPerFile)..."
                                )
                                else it
                            }
                        }
                        kotlinx.coroutines.delay(backoffMs)
                    }
                }
            }

            if (!fileSuccess) {
                uploadFailed = true
                failureReason = fileError ?: "Upload failed after $fileAttempt attempts"
                break
            }
        }

        if (uploadFailed) {
            _serverConnectionState.value = ServerConnectionState.ERROR
            val attemptsSoFar = (retryAttempts[photoId] ?: 0) + 1
            retryAttempts[photoId] = attemptsSoFar

            com.fastup.util.IssueAlertNotifier.notifyIssue(
                context = context,
                issueKey = "photo_upload_failed_$photoId",
                title = "Upload Failed: ${currentPhoto.fileName}",
                message = failureReason ?: "Upload failed after retries. Check server connectivity.",
                isError = true
            )

            // Check if permanent error (auth / scope)
            val isPermanent = failureReason?.let {
                it.contains("401") || it.contains("403") ||
                it.contains("Forbidden", ignoreCase = true) ||
                it.contains("Unauthorized", ignoreCase = true) ||
                it.contains("Outside allowed scope", ignoreCase = true) ||
                it.contains("session is disabled", ignoreCase = true)
            } ?: false

            if (isPermanent) {
                Log.e(TAG, "Permanent upload failure for ${currentPhoto.fileName}: $failureReason")
                _photos.update { list ->
                    list.map {
                        if (it.id == photoId) it.copy(
                            status = UploadStatus.FAILED,
                            errorMessage = failureReason ?: "Permanent upload error",
                            uploadSpeedBps = 0L
                        )
                        else it
                    }
                }
            } else {
                // Smart auto-recovery: do NOT give up! Mark as PENDING with backoff so the pipeline
                // can continue uploading other images and automatically retry this one.
                val backoffSeconds = minOf(300L, 5L * (1L shl minOf(attemptsSoFar - 1, 5))) // 5s, 10s, 20s, 40s, 80s, 160s, max 300s
                Log.w(TAG, "Transient upload failure for ${currentPhoto.fileName} (cycle $attemptsSoFar). Auto-recovering in ${backoffSeconds}s: $failureReason")

                _photos.update { list ->
                    list.map {
                        if (it.id == photoId) it.copy(
                            status = UploadStatus.PENDING,
                            errorMessage = "Will retry in ${backoffSeconds}s (fail #${attemptsSoFar})",
                            uploadSpeedBps = 0L
                        )
                        else it
                    }
                }

                // Schedule re-enqueue after backoff delay
                scope.launch {
                    kotlinx.coroutines.delay(backoffSeconds * 1000L)
                    if (!_isUploadPaused.value) {
                        val current = _photos.value.find { it.id == photoId }
                        if (current != null && current.status != UploadStatus.COMPLETED) {
                            Log.i(TAG, "Auto-requeuing delayed photo ${current.fileName} (cycle $attemptsSoFar)")
                            if (!pendingQueue.contains(photoId)) {
                                pendingQueue.add(photoId)
                            }
                        }
                    }
                }
            }
        } else {
            // Upload successful: reset failure count for this photo
            retryAttempts.remove(photoId)
            _serverConnectionState.value = ServerConnectionState.CONNECTED
            Log.i(TAG, "Successfully uploaded ${currentPhoto.fileName} (${filesToUpload.size} file(s))")

            // SMART RESILIENCE: If other photos had previously failed/delayed, proof that this upload
            // succeeded means the network connection is functional again!
            // Automatically wake up and re-enqueue any pending photos with backoff messages.
            scheduleAutoRetryPending()

            val editor = uploadedPrefs.edit()
            editor.putBoolean(photoId, true)
            filesToUpload.forEach { f ->
                editor.putBoolean(f.name, true)
                editor.putBoolean(f.nameWithoutExtension, true)
            }
            editor.apply()

            var uploadedMediaId: String? = null
            try {
                if (!lastUploadResponseJson.isNullOrBlank()) {
                    val jObj = org.json.JSONObject(lastUploadResponseJson)
                    val fArr = jObj.optJSONArray("files")
                    if (fArr != null && fArr.length() > 0) {
                        uploadedMediaId = fArr.getJSONObject(0).optString("id").ifBlank { null }
                    }
                }
            } catch (_: Exception) {}

            _photos.update { list ->
                list.map {
                    if (it.id == photoId) it.copy(
                        status = UploadStatus.COMPLETED,
                        uploadProgress = 1f,
                        uploadSpeedBps = 0L,
                        errorMessage = null,
                        serverMediaId = uploadedMediaId ?: it.serverMediaId
                    )
                    else it
                }
            }

            // Debounce server curation metadata sync so rapid back-to-back uploads don't spam the network
            debounceSyncJob?.cancel()
            debounceSyncJob = scope.launch {
                kotlinx.coroutines.delay(4000)
                syncUploadedFilesWithServer()
                checkFolderGDriveSync()
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

        val isLocalhost = result.serverUrl.contains("://localhost", ignoreCase = true) || result.serverUrl.contains("://127.0.0.1")
        if (result.serverUrl.isNotBlank() && !isLocalhost) {
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

        val initialScopes = result.scopes.ifEmpty {
            if (result.folderPath.isNotBlank()) listOf(result.folderPath.trim().trim('/')) else emptyList()
        }
        updateScopes(initialScopes)
    }

    fun disconnectPhotographer() {
        _photographerName.value = null
        prefs.edit().remove(PREF_PHOTOGRAPHER_NAME).apply()
        scopes = emptyList()
        prefs.edit().remove(PREF_SCOPES).apply()
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
        private const val PREF_SCOPES = "pref_scopes"
        private const val PREF_NET_SIM_ENABLED = "pref_net_sim_enabled"
        private const val PREF_NET_SIM_PROFILE = "pref_net_sim_profile"
        private const val PREF_NET_SIM_LATENCY_MS = "pref_net_sim_latency_ms"
        private const val PREF_NET_SIM_DROP_RATE = "pref_net_sim_drop_rate"
        private const val PREF_NET_SIM_THROTTLE_KBPS = "pref_net_sim_throttle_kbps"
    }
}
