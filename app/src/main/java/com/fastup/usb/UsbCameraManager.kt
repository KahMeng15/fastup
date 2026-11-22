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
    private var loopJob: Job? = null

    private var currentDevice: UsbDevice? = null
    private var driver: SonyPtpDriver? = null
    private var connectJob: Job? = null

    private val _connectionState = MutableStateFlow(CameraConnectionState.DISCONNECTED)
    val connectionState: StateFlow<CameraConnectionState> = _connectionState.asStateFlow()

    private val _cameraModel = MutableStateFlow("Camera")
    val cameraModel: StateFlow<String> = _cameraModel.asStateFlow()

    private val _deviceTransferStats = MutableStateFlow(DeviceTransferStats())
    val deviceTransferStats: StateFlow<DeviceTransferStats> = _deviceTransferStats.asStateFlow()

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
                Log.w(TAG, "First openSession attempt failed, retrying after reset...")
                delay(400)
                pDriver.clearHalt()
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

    private fun startMonitoringLoop() {
        loopJob?.cancel()
        loopJob = scope.launch {
            Log.i(TAG, "Starting PTP event loop on Interrupt endpoint...")
            while (isActive && _connectionState.value != CameraConnectionState.DISCONNECTED && _connectionState.value != CameraConnectionState.ERROR) {
                val d = driver ?: break

                // Read incoming PTP event from camera interrupt endpoint (blocking up to 500ms)
                val event = d.pollEvent(timeoutMs = 500)
                if (event != null) {
                    val eventCode = event.first
                    val handle = event.second
                    Log.i(TAG, "PTP Event received: 0x${eventCode.toString(16)}, handle=${handle?.let { "0x" + it.toString(16) } ?: "null"}")

                    // Check for Sony object added events (0xC201)
                    val isPhotoEvent = eventCode == SonyPtpConstants.PTP_EVENT_OBJECT_ADDED ||
                            eventCode == SonyPtpConstants.SONY_EVENT_OBJECT_READY ||
                            eventCode == 0xC101 || eventCode == 0xC201

                    if (isPhotoEvent) {
                        val targetHandle = if (handle != null && handle != 0 && handle != -1) {
                            handle
                        } else {
                            SonyPtpConstants.PHOTO_OBJECT_HANDLE
                        }
                        handleNewObject(targetHandle)
                    }
                }

                delay(50)
            }
        }
    }

    private suspend fun handleNewObject(initialHandle: Int) {
        transferMutex.withLock {
            val d = driver ?: return@withLock
            _connectionState.value = CameraConnectionState.CAPTURING

            var currentHandle = initialHandle
            var downloadedInBatch = 0
            var firstWasJpeg = false
            var firstWasRaw = false

            try {
                while (downloadedInBatch < 2) {
                    // Short stabilization delay before requesting next object from camera buffer
                    delay(150)

                    // For the 2nd file in a batch, verify with camera that an object is actually in memory
                    // to prevent calling getObjectInfo on an empty RAM buffer (which crashes Sony firmware)
                    if (downloadedInBatch == 1) {
                        val hasSecond = d.hasObjectInMemory()
                        Log.i(TAG, "Checking for 2nd file in RAM (RAW+JPEG check): hasObjectInMemory=$hasSecond")
                        if (!hasSecond) {
                            Log.i(TAG, "No 2nd file in camera RAM. Shutter batch complete.")
                            break
                        }
                    }

                    val info = d.getObjectInfo(currentHandle)
                    if (info == null) {
                        Log.d(TAG, "No object ready at handle 0x${currentHandle.toString(16)}")
                        break
                    }

                    val fileName = info.first.takeIf { it.isNotBlank() } ?: "DSC_${System.currentTimeMillis()}.JPG"
                    val expectedSize = info.second
                    val isJpeg = fileName.endsWith(".jpg", ignoreCase = true) || fileName.endsWith(".jpeg", ignoreCase = true)
                    val isRaw = fileName.endsWith(".arw", ignoreCase = true)

                    if (downloadedInBatch == 0) {
                        firstWasJpeg = isJpeg
                        firstWasRaw = isRaw
                    }

                    _deviceTransferStats.update {
                        it.copy(
                            totalCaptured = it.totalCaptured + 1,
                            pendingTransfer = it.pendingTransfer + 1,
                            currentFileName = fileName
                        )
                    }
                    Log.i(TAG, "Initiating download [batch item ${downloadedInBatch + 1}] handle 0x${currentHandle.toString(16)} -> $fileName (size: $expectedSize bytes)")

                    val sessionFolder = com.fastup.FastUpApp.instance.uploadManager.currentSessionId.trim().ifEmpty { "weddings" }
                    val storageDir = File(context.getExternalFilesDir(null), "tether_captures/$sessionFolder").apply { mkdirs() }
                    val targetFile = File(storageDir, fileName)

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
                        downloadedInBatch++
                        Log.i(TAG, "Photo downloaded successfully ($downloadedInBatch in batch): ${targetFile.absolutePath} (${targetFile.length()} bytes)")
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
                        break
                    }

                    // If we have already downloaded both a RAW and a JPEG for this shot, the batch is 100% complete!
                    if ((firstWasJpeg && isRaw) || (firstWasRaw && isJpeg) || downloadedInBatch >= 2) {
                        Log.i(TAG, "Both RAW and JPEG downloaded for this shot. Shutter batch complete.")
                        break
                    }

                    // For the 2nd file, target PHOTO_OBJECT_HANDLE
                    currentHandle = SonyPtpConstants.PHOTO_OBJECT_HANDLE
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error downloading captured objects", e)
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
                com.fastup.FastUpApp.instance.uploadManager.refreshLocalFolders()
                _connectionState.value = CameraConnectionState.CONNECTED
            }
        }
    }

    fun disconnect() {
        loopJob?.cancel()
        driver?.closeSession()
        driver = null
        currentDevice = null
        _connectionState.value = CameraConnectionState.DISCONNECTED
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
    }
}
