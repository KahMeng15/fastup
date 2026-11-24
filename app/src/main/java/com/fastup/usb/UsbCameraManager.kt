@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.fastup.usb

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Build
import android.util.Log
import com.fastup.model.CameraConnectionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

data class DeviceTransferStats(
    val totalCaptured: Int = 0,
    val pendingTransfer: Int = 0,
    val completedTransfer: Int = 0,
    val failedTransfer: Int = 0,
    val currentFileName: String? = null,
    val speedBps: Long = 0L,
    val speedFormatted: String = "0 kB/s",
    val overallProgress: Float = 0f
)

class UsbCameraManager(
    private val context: Context,
    private val onPhotoCaptured: (File) -> Unit
) {
    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var eventJob: Job? = null
    private var downloadJob: Job? = null
    private var watchdogJob: Job? = null
    private var photoChannel = Channel<Int>(Channel.UNLIMITED)

    private var currentDevice: UsbDevice? = null
    private var driver: SonyPtpDriver? = null
    private var connectJob: Job? = null

    private val _connectionState = MutableStateFlow(CameraConnectionState.DISCONNECTED)
    val connectionState: StateFlow<CameraConnectionState> = _connectionState.asStateFlow()

    private val _cameraModel = MutableStateFlow("Camera")
    val cameraModel: StateFlow<String> = _cameraModel.asStateFlow()

    private val _deviceTransferStats = MutableStateFlow(DeviceTransferStats())
    val deviceTransferStats: StateFlow<DeviceTransferStats> = _deviceTransferStats.asStateFlow()

    private val _liveViewFrame = MutableStateFlow<ByteArray?>(null)
    val liveViewFrame: StateFlow<ByteArray?> = _liveViewFrame.asStateFlow()

    private val _liveViewBitmap = MutableStateFlow<android.graphics.Bitmap?>(null)
    val liveViewBitmap: StateFlow<android.graphics.Bitmap?> = _liveViewBitmap.asStateFlow()

    private val _isLiveViewStreaming = MutableStateFlow(false)
    val isLiveViewStreaming: StateFlow<Boolean> = _isLiveViewStreaming.asStateFlow()

    private var liveViewJob: Job? = null
    @Volatile private var isLiveViewPausedForCapture = false
    @Volatile private var lastCaptureTimeMs = 0L
    private val pendingCaptureCount = java.util.concurrent.atomic.AtomicInteger(0)
    private var quietPeriodJob: Job? = null

    private fun scheduleLiveViewResume(delayMs: Long = 2500L) {
        quietPeriodJob?.cancel()
        quietPeriodJob = scope.launch {
            delay(delayMs)
            val elapsed = System.currentTimeMillis() - lastCaptureTimeMs
            if (elapsed >= (delayMs - 200) &&
                pendingCaptureCount.get() == 0 &&
                photoChannel.isEmpty &&
                !transferMutex.isLocked &&
                _connectionState.value == CameraConnectionState.CONNECTED
            ) {
                isLiveViewPausedForCapture = false
                Log.i(TAG, "Camera SD write idle; Live View & background queries resumed cleanly.")
            }
        }
    }

    fun startLiveView() {
        if (_isLiveViewStreaming.value || liveViewJob?.isActive == true) return
        _isLiveViewStreaming.value = true
        liveViewJob = scope.launch(Dispatchers.IO) {
            Log.i(TAG, "Starting Live View streaming loop...")
            while (isActive && _isLiveViewStreaming.value && _connectionState.value == CameraConnectionState.CONNECTED) {
                // If a photo transfer or capture is ongoing, yield and do not send Live View requests
                if (isLiveViewPausedForCapture || transferMutex.isLocked || !photoChannel.isEmpty || _connectionState.value == CameraConnectionState.CAPTURING) {
                    delay(150)
                    continue
                }

                val frame = transferMutex.withLock {
                    if (isLiveViewPausedForCapture || !photoChannel.isEmpty || _connectionState.value == CameraConnectionState.CAPTURING) {
                        return@withLock null
                    }
                    val d = driver ?: return@withLock null
                    try {
                        val f = d.getLiveViewFrame()
                        if (f == null && (d.lastLiveViewResponseCode == 0x200F || d.lastLiveViewResponseCode == 0x2019)) {
                            // Camera busy / access denied: shutter or exposure is active on camera
                            Log.d(TAG, "Live View: camera busy/access denied (0x${d.lastLiveViewResponseCode.toString(16)}). Shutter active, yielding...")
                            delay(500)
                        }
                        f
                    } catch (e: Exception) {
                        Log.d(TAG, "Live view frame fetch failed: ${e.message}")
                        null
                    }
                }

                if (frame != null && frame.isNotEmpty()) {
                    _liveViewFrame.value = frame
                    val bmp = try {
                        android.graphics.BitmapFactory.decodeByteArray(frame, 0, frame.size)
                    } catch (_: Exception) {
                        null
                    }
                    if (bmp != null) {
                        _liveViewBitmap.value = bmp
                    }
                    Log.d(TAG, "Live view frame updated: ${frame.size} bytes")
                } else {
                    Log.d(TAG, "Live view frame was null, lastResp=0x${driver?.lastLiveViewResponseCode?.toString(16)}")
                }

                // Smooth ~12 FPS (80ms interval) for USB 2.0 stability on Sony PTP
                delay(80)
            }
            _isLiveViewStreaming.value = false
            Log.i(TAG, "Live View streaming loop ended")
        }
    }

    fun stopLiveView() {
        _isLiveViewStreaming.value = false
        liveViewJob?.cancel()
        liveViewJob = null
        _liveViewFrame.value = null
        _liveViewBitmap.value = null
    }

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_USB_PERMISSION -> {
                    synchronized(this) {
                        val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                        } else {
                            @Suppress("DEPRECATION")
                            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                        }
                        if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                            Log.i(TAG, "USB Permission granted for device: ${device?.deviceName}")
                            device?.let { connectDevice(it) }
                        } else {
                            Log.w(TAG, "USB Permission denied for device: $device")
                            _connectionState.value = CameraConnectionState.ERROR
                        }
                    }
                }
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    }
                    Log.i(TAG, "USB device attached broadcast received: ${device?.deviceName}")
                    device?.let { checkAndConnect(it) }
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    }
                    Log.i(TAG, "USB device detached: ${device?.deviceName}")
                    disconnect()

                    // Production safeguard: Auto-reconnect poll after unexpected disconnect/reboot
                    scope.launch {
                        for (attempt in 1..10) {
                            delay(1000)
                            if (_connectionState.value == CameraConnectionState.CONNECTED) break
                            val devices = usbManager.deviceList
                            val cam = devices.values.find { isSonyCamera(it) }
                            if (cam != null) {
                                Log.i(TAG, "Auto-recovery: found Sony camera after detach on attempt $attempt, connecting...")
                                checkAndConnect(cam)
                                break
                            }
                        }
                    }
                }
                ACTION_TRIGGER_CAPTURE -> {
                    // Triggered via: adb shell am broadcast -a com.fastup.TRIGGER_CAPTURE -n com.fastup/.usb.CaptureReceiver
                    // (or directly since it's NOT_EXPORTED, use: adb shell am broadcast -a com.fastup.TRIGGER_CAPTURE com.fastup)
                    Log.i(TAG, "ADB capture trigger received")
                    scope.launch {
                        transferMutex.withLock {
                            val d = driver
                            if (d != null) {
                                val ok = d.triggerCapture()
                                Log.i(TAG, "ADB triggered capture result: $ok")
                            } else {
                                Log.w(TAG, "ADB trigger: no camera connected")
                            }
                        }
                    }
                }
            }
        }
    }

    fun triggerCapture() {
        scope.launch {
            // Pause live view immediately so no PTP traffic hits the camera
            // while it processes the shutter command and begins buffering the image.
            isLiveViewPausedForCapture = true
            quietPeriodJob?.cancel()
            try {
                transferMutex.withLock {
                    val d = driver
                    if (d != null) {
                        Log.i(TAG, "Triggering camera capture via Sony SDIO Capture...")
                        val ok = d.triggerCapture()
                        Log.i(TAG, "triggerCapture result: $ok")
                    } else {
                        Log.w(TAG, "Cannot trigger capture: no camera connected")
                        // No shot fired — safe to resume immediately
                        isLiveViewPausedForCapture = pendingCaptureCount.get() > 0 || !photoChannel.isEmpty
                    }
                }
                // The 0xC201 "Object Ready" event will arrive shortly and increment
                // pendingCaptureCount via the eventJob. We do NOT set isLiveViewPausedForCapture=false
                // here — that is exclusively managed by scheduleLiveViewResume() so the
                // quiet window always coordinates with the actual download completion.
            } catch (e: Exception) {
                Log.e(TAG, "triggerCapture error", e)
                if (pendingCaptureCount.get() <= 0 && photoChannel.isEmpty) {
                    isLiveViewPausedForCapture = false
                }
            }
        }
    }

    private val transferMutex = Mutex()

    fun start() {
        val filter = IntentFilter().apply {
            addAction(ACTION_USB_PERMISSION)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            addAction(ACTION_TRIGGER_CAPTURE)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(usbReceiver, filter)
        }
        scanForCamera()
    }

    fun stop() {
        try {
            context.unregisterReceiver(usbReceiver)
        } catch (e: Exception) {
            // Already unregistered
        }
        disconnect()
    }

    fun scanForCamera() {
        val deviceList = usbManager.deviceList
        Log.i(TAG, "Scanning for camera. Detected ${deviceList.size} USB devices.")
        for (device in deviceList.values) {
            Log.d(TAG, "Checking device: ${device.deviceName}, VID: 0x${device.vendorId.toString(16)}, PID: 0x${device.productId.toString(16)}")
            if (isSonyCamera(device)) {
                Log.i(TAG, "Sony Camera matched: ${device.deviceName}")
                checkAndConnect(device)
                return
            }
        }
        Log.d(TAG, "No Sony camera currently detected among connected USB devices")
    }

    private fun isSonyCamera(device: UsbDevice): Boolean {
        if (device.vendorId == SonyPtpConstants.SONY_VENDOR_ID) {
            return true
        }
        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            if (iface.interfaceClass == SonyPtpConstants.USB_CLASS_STILL_IMAGE) {
                return true
            }
        }
        return false
    }

    fun checkAndConnect(device: UsbDevice) {
        if (!isSonyCamera(device)) return
        if (_connectionState.value == CameraConnectionState.CONNECTED ||
            _connectionState.value == CameraConnectionState.CONNECTING
        ) {
            Log.d(TAG, "Already connected or connecting to camera, skipping duplicate request")
            return
        }
        currentDevice = device

        if (usbManager.hasPermission(device)) {
            Log.i(TAG, "Already have permission for ${device.deviceName}, connecting directly...")
            connectDevice(device)
        } else {
            Log.i(TAG, "Requesting USB permission for ${device.deviceName}...")
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
            val intent = Intent(ACTION_USB_PERMISSION).apply {
                setPackage(context.packageName)
            }
            val permissionIntent = PendingIntent.getBroadcast(
                context,
                0,
                intent,
                flags
            )
            usbManager.requestPermission(device, permissionIntent)
        }
    }

    private fun connectDevice(device: UsbDevice) {
        connectJob?.cancel()
        connectJob = scope.launch {
            _connectionState.value = CameraConnectionState.CONNECTING

            // Find PTP interface and endpoints
            var ptpInterface: UsbInterface? = null
            var bulkIn: UsbEndpoint? = null
            var bulkOut: UsbEndpoint? = null
            var interruptIn: UsbEndpoint? = null

            for (i in 0 until device.interfaceCount) {
                val iface = device.getInterface(i)
                if (iface.interfaceClass == SonyPtpConstants.USB_CLASS_STILL_IMAGE ||
                    device.vendorId == SonyPtpConstants.SONY_VENDOR_ID
                ) {
                    for (e in 0 until iface.endpointCount) {
                        val ep = iface.getEndpoint(e)
                        if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                            if (ep.direction == UsbConstants.USB_DIR_IN) bulkIn = ep
                            else bulkOut = ep
                        } else if (ep.type == UsbConstants.USB_ENDPOINT_XFER_INT && ep.direction == UsbConstants.USB_DIR_IN) {
                            interruptIn = ep
                        }
                    }
                    if (bulkIn != null && bulkOut != null) {
                        ptpInterface = iface
                        break
                    }
                }
            }

            if (ptpInterface == null || bulkIn == null || bulkOut == null) {
                Log.e(TAG, "Could not find compatible PTP bulk endpoints on device")
                _connectionState.value = CameraConnectionState.ERROR
                return@launch
            }

            val conn = usbManager.openDevice(device)
            if (conn == null || !conn.claimInterface(ptpInterface, true)) {
                Log.e(TAG, "Failed opening device or claiming USB interface")
                _connectionState.value = CameraConnectionState.ERROR
                return@launch
            }

            val pDriver = SonyPtpDriver(conn, ptpInterface, bulkIn, bulkOut, interruptIn)
            
            // Attempt opening session (with 1 retry if camera endpoint was busy)
            var opened = pDriver.openSession()
            if (!opened) {
                Log.w(TAG, "First openSession attempt failed, retrying after delay...")
                delay(500)
                opened = pDriver.openSession()
            }

            if (opened) {
                driver = pDriver
                _connectionState.value = CameraConnectionState.CONNECTED
                _cameraModel.value = device.productName ?: "Camera"
                Log.i(TAG, "Successfully connected to camera! Ready for tethered shooting.")

                startMonitoringLoop()
            } else {
                Log.e(TAG, "Failed to initialize Sony PTP session")
                conn.releaseInterface(ptpInterface)
                conn.close()
                _connectionState.value = CameraConnectionState.ERROR
            }
        }
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private fun startMonitoringLoop() {
        eventJob?.cancel()
        downloadJob?.cancel()
        photoChannel = Channel(Channel.UNLIMITED)

        // Coroutine 1: Continuous PTP Interrupt Endpoint Poller (never blocked by file downloads)
        eventJob = scope.launch {
            Log.i(TAG, "Starting PTP event loop on Interrupt endpoint...")
            while (isActive && _connectionState.value != CameraConnectionState.DISCONNECTED && _connectionState.value != CameraConnectionState.ERROR) {
                val d = driver ?: break

                // Poll incoming PTP event from camera interrupt endpoint (blocking up to 500ms).
                // We ALWAYS poll — this is a READ; stopping it means we miss 0xC201 photo events.
                val event = d.pollEvent(timeoutMs = 500)
                if (event != null) {
                    val eventCode = event.first
                    val handle = event.second
                    val param2 = event.third
                    Log.i(TAG, "PTP Event received: 0x${eventCode.toString(16)}, handle=${handle?.let { "0x" + it.toString(16) } ?: "null"}, param2=${param2?.let { "0x" + it.toString(16) } ?: "null"}")

                    val isPhotoEvent = eventCode == SonyPtpConstants.PTP_EVENT_OBJECT_ADDED ||
                            eventCode == SonyPtpConstants.SONY_EVENT_OBJECT_READY ||
                            eventCode == 0xC101 || eventCode == 0xC201

                    if (isPhotoEvent) {
                        isLiveViewPausedForCapture = true
                        quietPeriodJob?.cancel() // cancel pending resumption if a new photo event arrived
                        pendingCaptureCount.incrementAndGet()
                        val targetHandle = if (handle != null && handle != 0 && handle != -1) {
                            handle
                        } else {
                            SonyPtpConstants.PHOTO_OBJECT_HANDLE
                        }
                        photoChannel.trySend(targetHandle)
                    } else if (eventCode == 0xC202) {
                        // 0xC202 is Sony SDIE_ObjectRemoved indicating the camera RAM has retired the previous object.
                        // Do NOT send to photoChannel! A second spurious drain request will crash the camera while it writes to SD.
                        Log.i(TAG, "Sony Camera confirmed object retired from RAM (0xC202)")
                        // Trigger adaptive recovery: give 2000ms after RAM retirement for SD write to complete
                        lastCaptureTimeMs = System.currentTimeMillis()
                        if (pendingCaptureCount.get() <= 0 && photoChannel.isEmpty) {
                            scheduleLiveViewResume(2000L)
                        }
                    }
                }
            }
        }

        // Coroutine 2: Dedicated Photo Download Worker (processes queued captures one by one)
        downloadJob = scope.launch {
            Log.i(TAG, "Starting photo download worker...")
            for (targetHandle in photoChannel) {
                if (!isActive || _connectionState.value == CameraConnectionState.DISCONNECTED) break
                downloadCapturedPhoto(targetHandle)
            }
        }
    }

    private suspend fun downloadCapturedPhoto(handle: Int) {
        transferMutex.withLock {
            val d = driver ?: return@withLock
            val currentHandle = handle

            try {
                // Brief settling delay before querying object info: 60ms
                delay(60)

                val info = d.getObjectInfo(currentHandle, retries = 2)
                if (info == null) {
                    Log.w(TAG, "No object info found for handle 0x${currentHandle.toString(16)}")
                    return@withLock
                }

                val fileName = info.first.takeIf { it.isNotBlank() } ?: "DSC_${System.currentTimeMillis()}.JPG"
                val expectedSize = info.second
                val isRaw = fileName.endsWith(".arw", ignoreCase = true)

                val cameraFormat = com.fastup.FastUpApp.instance.uploadManager.cameraCaptureFormat
                // In JPEG_ONLY mode, companion RAW files must still be drained from camera RAM so the buffer
                // doesn't block, but can be discarded locally to avoid wasting phone storage & upload bandwidth.
                val shouldDiscard = isRaw && cameraFormat == com.fastup.model.CameraCaptureFormat.JPEG_ONLY

                _connectionState.value = CameraConnectionState.CAPTURING
                _deviceTransferStats.update {
                    it.copy(
                        totalCaptured = if (!shouldDiscard) it.totalCaptured + 1 else it.totalCaptured,
                        pendingTransfer = if (!shouldDiscard) it.pendingTransfer + 1 else it.pendingTransfer,
                        currentFileName = fileName
                    )
                }
                Log.i(TAG, "Downloading photo handle 0x${currentHandle.toString(16)} -> $fileName (size: $expectedSize bytes, discardRaw=$shouldDiscard)")

                val sessionFolder = com.fastup.FastUpApp.instance.uploadManager.currentSessionId.trim().ifEmpty { "weddings" }
                val storageDir = File(context.getExternalFilesDir(null), "tether_captures/$sessionFolder").apply { mkdirs() }
                val targetFile = if (shouldDiscard) {
                    File(context.cacheDir, "temp_discard_${System.currentTimeMillis()}.arw")
                } else {
                    File(storageDir, fileName)
                }

                var lastSpeedUpdate = System.currentTimeMillis()
                var lastBytesTransferred = 0L

                val success = d.downloadObject(currentHandle, targetFile, expectedSize) { bytesRead, totalBytes ->
                    val now = System.currentTimeMillis()
                    val dt = now - lastSpeedUpdate
                    if (dt >= 200) {
                        val speed = if (dt > 0) ((bytesRead - lastBytesTransferred) * 1000L) / dt else 0L
                        lastSpeedUpdate = now
                        lastBytesTransferred = bytesRead
                        val prog = if (totalBytes > 0) bytesRead.toFloat() / totalBytes else 0f
                        _deviceTransferStats.update {
                            it.copy(
                                speedBps = speed,
                                speedFormatted = formatSpeed(speed),
                                overallProgress = if (it.totalCaptured > 0) (it.completedTransfer.toFloat() + prog) / it.totalCaptured else prog
                            )
                        }
                    }
                }

                if (success && targetFile.exists() && targetFile.length() > 0) {
                    if (shouldDiscard) {
                        Log.i(TAG, "Companion RAW drained (${targetFile.length()} bytes) and discarded to satisfy JPEG_ONLY mode.")
                        targetFile.delete()
                    } else {
                        Log.i(TAG, "Photo downloaded successfully: ${targetFile.absolutePath} (${targetFile.length()} bytes)")
                        _deviceTransferStats.update {
                            val newCompleted = it.completedTransfer + 1
                            val total = it.totalCaptured
                            val prog = if (total > 0) newCompleted.toFloat() / total else 1f
                            it.copy(
                                pendingTransfer = maxOf(0, it.pendingTransfer - 1),
                                completedTransfer = newCompleted,
                                currentFileName = null,
                                speedBps = 0L,
                                speedFormatted = "0 kB/s",
                                overallProgress = prog
                            )
                        }
                        withContext(Dispatchers.Main) {
                            onPhotoCaptured(targetFile)
                        }
                    }
                } else {
                    Log.w(TAG, "Photo download for handle 0x${currentHandle.toString(16)} returned false or empty file")
                    _deviceTransferStats.update {
                        it.copy(
                            pendingTransfer = maxOf(0, it.pendingTransfer - 1),
                            failedTransfer = it.failedTransfer + 1,
                            currentFileName = null,
                            speedBps = 0L,
                            speedFormatted = "0 kB/s"
                        )
                    }
                    if (targetFile.exists() && targetFile.length() == 0L) {
                        targetFile.delete()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error downloading captured object 0x${handle.toString(16)}", e)
                _deviceTransferStats.update {
                    it.copy(
                        pendingTransfer = maxOf(0, it.pendingTransfer - 1),
                        failedTransfer = it.failedTransfer + 1,
                        currentFileName = null,
                        speedBps = 0L,
                        speedFormatted = "0 kB/s"
                    )
                }
            } finally {
                lastCaptureTimeMs = System.currentTimeMillis()
                val remaining = pendingCaptureCount.decrementAndGet()
                com.fastup.FastUpApp.instance.uploadManager.refreshLocalFolders()
                if (_connectionState.value == CameraConnectionState.CAPTURING) {
                    _connectionState.value = CameraConnectionState.CONNECTED
                }
                // Adaptive quiet window:
                // If there are more captures queued in a burst, keep live view paused.
                // When queue is drained, wait 2500ms safety window after the last download completes.
                if (remaining <= 0 && photoChannel.isEmpty) {
                    scheduleLiveViewResume(2500L)
                }
            }
        }
    }

    fun disconnect() {
        stopLiveView()
        _connectionState.value = CameraConnectionState.DISCONNECTED
        eventJob?.cancel()
        eventJob = null
        downloadJob?.cancel()
        downloadJob = null
        try {
            driver?.release()
        } catch (e: Exception) {
            // Ignore close errors during disconnect
        }
        driver = null
        currentDevice = null
        Log.i(TAG, "Camera disconnected")
    }

    private fun formatSpeed(bytesPerSec: Long): String {
        return when {
            bytesPerSec >= 1024 * 1024 -> String.format(java.util.Locale.US, "%.1f MB/s", bytesPerSec.toDouble() / (1024 * 1024))
            bytesPerSec >= 1024 -> String.format(java.util.Locale.US, "%.0f kB/s", bytesPerSec.toDouble() / 1024)
            bytesPerSec > 0 -> "$bytesPerSec B/s"
            else -> "0 kB/s"
        }
    }

    companion object {
        private const val TAG = "UsbCameraManager"
        private const val ACTION_USB_PERMISSION = "com.fastup.USB_PERMISSION"
        const val ACTION_TRIGGER_CAPTURE = "com.fastup.TRIGGER_CAPTURE"
    }
}
