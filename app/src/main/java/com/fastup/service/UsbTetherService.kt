package com.fastup.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
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

    inner class LocalBinder : Binder() {
        fun getService(): UsbTetherService = this@UsbTetherService
        fun getCameraManager(): UsbCameraManager = usbCameraManager
    }

    fun getCameraManager(): UsbCameraManager = usbCameraManager

    override fun onCreate() {
        super.onCreate()

        // 1. Immediately enter foreground to satisfy Android 8+ foreground service rules
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                buildNotification("Sony A7 III: Ready"),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIFICATION_ID, buildNotification("Sony A7 III: Ready"))
        }

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
                CameraConnectionState.CONNECTED -> "Sony A7 III Connected (Ready to shoot)"
                CameraConnectionState.CONNECTING -> "Connecting to Sony A7 III..."
                CameraConnectionState.CAPTURING -> "Receiving photo from camera..."
                CameraConnectionState.DISCONNECTED -> "No camera connected (Waiting for USB)"
                CameraConnectionState.ERROR -> "USB Connection Error"
            }
            updateNotification(text)
        }.launchIn(scope)
    }

    private fun processCapturedFile(file: File) {
        scope.launch {
            val thumbDir = File(cacheDir, "previews").apply { mkdirs() }
            val preview = if (file.name.endsWith(".arw", ignoreCase = true)) {
                ArwParser.extractPreview(file, thumbDir)
            } else {
                file
            }
            val exif = ArwParser.extractExif(file)

            val photoItem = PhotoItem(
                id = UUID.randomUUID().toString(),
                fileName = file.name,
                localFile = file,
                rawFile = if (file.name.endsWith(".arw", ignoreCase = true)) file else null,
                jpegFile = if (file.name.endsWith(".jpg", ignoreCase = true)) file else null,
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
        usbCameraManager.stop()
        super.onDestroy()
    }

    companion object {
        const val NOTIFICATION_ID = 1001
    }
}
