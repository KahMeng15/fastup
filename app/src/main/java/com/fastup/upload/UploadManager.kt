package com.fastup.upload

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import com.fastup.model.PhotoItem
import com.fastup.model.ShareSessionInfo
import com.fastup.model.UploadFormat
import com.fastup.model.UploadStatus
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

class UploadManager(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var workerJob: Job? = null

    // Preferences & Settings State
    var serverUrl: String = "http://10.0.0.43:8000"
        set(value) {
            field = value
            apiClient.baseUrl = value
        }

    var apiKey: String = "fastup_secret_key"
        set(value) {
            field = value
            apiClient.apiKey = value
        }

    var apiClient = ApiClient(serverUrl, apiKey)
        private set

    private val _currentSessionId = MutableStateFlow("weddings/staging")
    val currentSessionIdFlow: StateFlow<String> = _currentSessionId.asStateFlow()

    var currentSessionId: String
        get() = _currentSessionId.value
        set(value) {
            _currentSessionId.value = value
        }

    var uploadFormat: UploadFormat = UploadFormat.RAW_AND_JPEG
    var autoUpload: Boolean = true
    var wifiOnly: Boolean = false

    // State Flows for UI
    private val _photos = MutableStateFlow<List<PhotoItem>>(emptyList())
    val photos: StateFlow<List<PhotoItem>> = _photos.asStateFlow()

    private val _currentSpeedBps = MutableStateFlow(0L)
    val currentSpeedBps: StateFlow<Long> = _currentSpeedBps.asStateFlow()

    private val _isUploading = MutableStateFlow(false)
    val isUploading: StateFlow<Boolean> = _isUploading.asStateFlow()

    private val _shareSession = MutableStateFlow<ShareSessionInfo?>(null)
    val shareSession: StateFlow<ShareSessionInfo?> = _shareSession.asStateFlow()

    // Internal Upload Queue
    private val pendingQueue = ConcurrentLinkedQueue<String>() // photo IDs

    init {
        startWorker()
    }

    fun addPhoto(photo: PhotoItem) {
        _photos.update { current ->
            listOf(photo) + current.filter { it.id != photo.id }
        }
        if (autoUpload) {
            enqueue(photo.id)
        }
    }

    fun enqueue(photoId: String) {
        val photo = _photos.value.find { it.id == photoId } ?: return

        // Verify format eligibility
        val shouldUpload = when (uploadFormat) {
            UploadFormat.RAW_AND_JPEG -> true
            UploadFormat.JPEG_ONLY -> photo.isJpeg
            UploadFormat.RAW_ONLY -> photo.isRaw
        }

        if (!shouldUpload) {
            Log.d(TAG, "Skipping upload for ${photo.fileName} due to format setting: $uploadFormat")
            return
        }

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
        val failed = _photos.value.filter { it.status == UploadStatus.FAILED }
        for (item in failed) {
            enqueue(item.id)
        }
    }

    fun retryPhoto(id: String) {
        enqueue(id)
    }

    private fun canUploadNow(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
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

    private suspend fun uploadSingle(photoId: String) {
        val photo = _photos.value.find { it.id == photoId } ?: return
        _isUploading.value = true

        _photos.update { list ->
            list.map {
                if (it.id == photoId) it.copy(status = UploadStatus.UPLOADING, uploadProgress = 0f)
                else it
            }
        }

        // Determine which file to upload
        val fileToUpload: File = when (uploadFormat) {
            UploadFormat.RAW_ONLY -> photo.rawFile ?: photo.localFile
            UploadFormat.JPEG_ONLY -> photo.jpegFile ?: photo.displayFile
            UploadFormat.RAW_AND_JPEG -> photo.localFile
        }

        var lastTime = System.currentTimeMillis()
        var lastBytes = 0L

        val result = apiClient.uploadPhoto(
            file = fileToUpload,
            sessionId = currentSessionId
        ) { bytesWritten, totalBytes ->
            val now = System.currentTimeMillis()
            val dt = (now - lastTime).coerceAtLeast(1)
            val dBytes = bytesWritten - lastBytes

            if (dt >= 400 || bytesWritten == totalBytes) {
                val speedBps = (dBytes * 1000) / dt
                lastTime = now
                lastBytes = bytesWritten

                _currentSpeedBps.value = speedBps
                val progress = if (totalBytes > 0) bytesWritten.toFloat() / totalBytes else 0f

                _photos.update { list ->
                    list.map {
                        if (it.id == photoId) it.copy(
                            uploadProgress = progress,
                            uploadSpeedBps = speedBps
                        )
                        else it
                    }
                }
            }
        }

        result.fold(
            onSuccess = {
                Log.i(TAG, "Successfully uploaded ${photo.fileName}")
                _photos.update { list ->
                    list.map {
                        if (it.id == photoId) it.copy(
                            status = UploadStatus.COMPLETED,
                            uploadProgress = 1f,
                            uploadSpeedBps = 0L
                        )
                        else it
                    }
                }
            },
            onFailure = { err ->
                Log.e(TAG, "Failed uploading ${photo.fileName}: ${err.message}")
                _photos.update { list ->
                    list.map {
                        if (it.id == photoId) it.copy(
                            status = UploadStatus.FAILED,
                            errorMessage = err.message ?: "Upload failed",
                            uploadSpeedBps = 0L
                        )
                        else it
                    }
                }
            }
        )
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

    companion object {
        private const val TAG = "UploadManager"
    }
}
