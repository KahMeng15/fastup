package com.fastup.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.fastup.FastUpApp
import com.fastup.MainActivity
import com.fastup.R
import com.fastup.model.CameraConnectionState
import com.fastup.model.PhotoItem
import com.fastup.model.UploadStatus
import com.fastup.usb.UsbCameraManager
import com.fastup.util.ArwParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID

class UsbTetherService : Service() {

    private val binder = LocalBinder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var usbCameraManager: UsbCameraManager
    private var wakeLock: PowerManager.WakeLock? = null

    inner class LocalBinder : Binder() {
        fun getService(): UsbTetherService = this@UsbTetherService
        fun getCameraManager(): UsbCameraManager = usbCameraManager
    }

    fun getCameraManager(): UsbCameraManager = usbCameraManager

    fun triggerCapture() {
        usbCameraManager.triggerCapture()
    }

    override fun onCreate() {
        super.onCreate()
        instance = this

        // 1. Immediately enter foreground to satisfy Android 8+ foreground service rules
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val serviceType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC or ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            } else {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            }
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                buildNotification("Camera: Ready"),
                serviceType
            )
        } else {
            startForeground(NOTIFICATION_ID, buildNotification("Camera: Ready"))
        }

        // Acquire partial wake lock to keep USB PTP transfer and polling alive when screen turns off
        acquireWakeLockIfNeeded()

        // 2. Initialize camera manager and scan for connected camera
        usbCameraManager = UsbCameraManager(this) { capturedFile ->
            processCapturedFile(capturedFile)
        }
        usbCameraManager.start()
        observeCameraState()
    }

    private fun observeCameraState() {
        usbCameraManager.connectionState.onEach { state ->
            val text = when (state) {
                CameraConnectionState.CONNECTED -> {
                    acquireWakeLockIfNeeded()
                    "Camera Connected (Ready to shoot)"
                }
                CameraConnectionState.CONNECTING -> {
                    acquireWakeLockIfNeeded()
                    "Connecting to camera..."
                }
                CameraConnectionState.CAPTURING -> {
                    acquireWakeLockIfNeeded()
                    "Receiving photo from camera..."
                }
                CameraConnectionState.DISCONNECTED -> {
                    // Only release wake lock if no uploads are active
                    if (!FastUpApp.instance.uploadManager.isUploading.value) {
                        releaseWakeLock()
                    }
                    "No camera connected (Waiting for USB)"
                }
                CameraConnectionState.ERROR -> {
                    if (!FastUpApp.instance.uploadManager.isUploading.value) {
                        releaseWakeLock()
                    }
                    "USB Connection Error"
                }
            }
            updateNotification(text)
        }.launchIn(scope)

        // Also track upload manager activity to keep CPU awake during long event uploads
        FastUpApp.instance.uploadManager.isUploading.onEach { uploading ->
            if (uploading) {
                acquireWakeLockIfNeeded()
            } else if (usbCameraManager.connectionState.value == CameraConnectionState.DISCONNECTED) {
                releaseWakeLock()
            }
        }.launchIn(scope)
    }

    private fun acquireWakeLockIfNeeded() {
        try {
            if (wakeLock == null) {
                val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
                wakeLock = powerManager?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "FastUp:UsbTetherWakeLock")?.apply {
                    setReferenceCounted(false)
                }
            }
            if (wakeLock?.isHeld == false) {
                wakeLock?.acquire(6 * 60 * 60 * 1000L) // 6 hours safety timeout
                Log.d(TAG, "Acquired partial WakeLock")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to acquire WakeLock: ${e.message}")
        }
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
                Log.d(TAG, "Released partial WakeLock")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing WakeLock: ${e.message}")
        }
    }

    private fun processCapturedFile(file: File) {
        scope.launch {
            val isRaw = file.name.endsWith(".arw", ignoreCase = true)
            val isJpeg = file.name.endsWith(".jpg", ignoreCase = true) || file.name.endsWith(".jpeg", ignoreCase = true)
            val thumbDir = File(cacheDir, "previews").apply { mkdirs() }
            val preview = if (isRaw) {
                ArwParser.extractPreview(file, thumbDir)
            } else {
                file
            }
            val exif = ArwParser.extractExif(file)

            val baseName = file.nameWithoutExtension

            val photoItem = PhotoItem(
                id = baseName,
                fileName = file.name,
                localFile = file,
                rawFile = if (isRaw) file else null,
                jpegFile = if (isJpeg) file else null,
                previewFile = preview,
                fileSize = file.length(),
                status = UploadStatus.PENDING,
                exif = exif
            )

            FastUpApp.instance.uploadManager.addPhoto(photoItem)
        }
    }

    private fun buildNotification(contentText: String): Notification {
        val launchIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, FastUpApp.CHANNEL_USB_TETHER)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle("FastUp Tethering Active")
            .setContentText(contentText)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification(text: String) {
        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
        notificationManager.notify(NOTIFICATION_ID, buildNotification(text))
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        instance = null
        usbCameraManager.stop()
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing wakeLock: ${e.message}")
        }
        super.onDestroy()
    }

    companion object {
        private const val TAG = "UsbTetherService"
        const val NOTIFICATION_ID = 1001
        var instance: UsbTetherService? = null
            private set
    }
}
