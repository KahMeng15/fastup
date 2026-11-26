package com.fastup

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.fastup.upload.UploadManager

class FastUpApp : Application() {

    lateinit var uploadManager: UploadManager
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannels()
        uploadManager = UploadManager(this)
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val usbChannel = NotificationChannel(
                CHANNEL_USB_TETHER,
                getString(R.string.channel_usb_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.channel_usb_desc)
                setShowBadge(false)
            }

            val uploadChannel = NotificationChannel(
                CHANNEL_UPLOAD,
                "FastUp Uploads",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows upload progress and transfer speed"
                setShowBadge(false)
            }

            val alertsChannel = NotificationChannel(
                CHANNEL_ALERTS,
                "FastUp Alerts & Issues",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notifies when camera disconnects, uploads fail, or server/storage issues occur"
                setShowBadge(true)
                enableVibration(true)
            }

            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager?.createNotificationChannel(usbChannel)
            notificationManager?.createNotificationChannel(uploadChannel)
            notificationManager?.createNotificationChannel(alertsChannel)
        }
    }

    companion object {
        const val CHANNEL_USB_TETHER = "fastup_usb_tether"
        const val CHANNEL_UPLOAD = "fastup_upload"
        const val CHANNEL_ALERTS = "fastup_alerts"

        lateinit var instance: FastUpApp
            private set
    }
}
