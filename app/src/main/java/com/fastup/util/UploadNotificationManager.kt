package com.fastup.util

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import com.fastup.FastUpApp
import com.fastup.MainActivity
import com.fastup.model.PhotoItem
import com.fastup.model.UploadStatus
import com.fastup.receiver.UploadActionReceiver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Manages the live lockscreen / ongoing activity notification for FastUp uploads.
 *
 * Modeled after iOS Live Activities:
 * - Visible on Lockscreen & Always-On Display (VISIBILITY_PUBLIC)
 * - Real-time progress bar (e.g. 42 / 120 uploaded • 35%)
 * - Current file being transferred and live transfer speed (e.g. "DSC0102.JPG • 24.5 MB/s")
 * - Lockscreen action buttons (Pause / Resume)
 * - Automatically completes with a brief summary or cleans up when idle.
 */
class UploadNotificationManager(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private var observerJob: Job? = null
    private var dismissJob: Job? = null
    private var lastWasUploadingOrPausedWithPending = false

    fun start() {
        if (observerJob != null) return
        val uploadManager = FastUpApp.instance.uploadManager

        observerJob = scope.launch {
            combine(
                uploadManager.photos,
                uploadManager.isUploading,
                uploadManager.isUploadPaused,
                uploadManager.currentSpeedBps
            ) { photos, isUploading, isPaused, speedBps ->
                UploadNotificationState(
                    photos = photos,
                    isUploading = isUploading,
                    isPaused = isPaused,
                    speedBps = speedBps
                )
            }.distinctUntilChanged().collect { state ->
                render(state)
            }
        }
    }

    fun stop() {
        observerJob?.cancel()
        observerJob = null
        cancelNotification()
    }

    private fun render(state: UploadNotificationState) {
        val total = state.photos.size
        val uploaded = state.photos.count { it.status == UploadStatus.COMPLETED }
        val pending = state.photos.count { it.status == UploadStatus.PENDING }
        val active = state.photos.find { it.status == UploadStatus.UPLOADING }

        val hasWork = (state.isUploading || (state.isPaused && pending > 0) || active != null)

        if (hasWork) {
            dismissJob?.cancel()
            dismissJob = null
            lastWasUploadingOrPausedWithPending = true
            postLiveActivityNotification(state, uploaded, total, pending, active)
        } else if (lastWasUploadingOrPausedWithPending && total > 0 && pending == 0 && !state.isPaused) {
            // All uploads just completed! Show success notification and auto-dismiss after 6 seconds.
            lastWasUploadingOrPausedWithPending = false
            postCompletedNotification(total)
            dismissJob?.cancel()
            dismissJob = scope.launch {
                delay(6000L)
                cancelNotification()
            }
        } else {
            // Idle state and nothing pending
            if (!lastWasUploadingOrPausedWithPending) {
                cancelNotification()
            }
        }
    }

    private fun postLiveActivityNotification(
        state: UploadNotificationState,
        uploadedCount: Int,
        totalCount: Int,
        pendingCount: Int,
        activePhoto: PhotoItem?
    ) {
        val launchIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val contentPendingIntent = PendingIntent.getActivity(
            context,
            0,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Title: e.g. "FastUp • Uploading (42/120)" or "FastUp • Paused (42/120)"
        val statusLabel = if (state.isPaused) "Paused" else "Uploading"
        val title = "FastUp • $statusLabel ($uploadedCount / $totalCount)"

        // Body: e.g. "DSC0124.JPG • 18.4 MB/s" or "32 items queued"
        val speedStr = formatSpeed(state.speedBps)
        val contentText = when {
            state.isPaused -> "$pendingCount items paused • Tap resume to continue"
            activePhoto != null && state.speedBps > 0 -> "${activePhoto.fileName} • $speedStr"
            activePhoto != null -> "Transferring ${activePhoto.fileName}..."
            pendingCount > 0 -> "$pendingCount items waiting in queue"
            else -> "Processing..."
        }

        val builder = NotificationCompat.Builder(context, FastUpApp.CHANNEL_UPLOAD)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle(title)
            .setContentText(contentText)
            .setSubText("FastUp Live")
            .setContentIntent(contentPendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC) // Shows on Lockscreen / AOD
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)

        // Add determinate progress bar
        if (totalCount > 0) {
            builder.setProgress(totalCount, uploadedCount, false)
        } else {
            builder.setProgress(0, 0, true)
        }

        // Action Buttons (Pause / Resume)
        if (state.isPaused) {
            val resumeIntent = Intent(context, UploadActionReceiver::class.java).apply {
                action = UploadActionReceiver.ACTION_RESUME
            }
            val resumePendingIntent = PendingIntent.getBroadcast(
                context,
                101,
                resumeIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(
                android.R.drawable.ic_media_play,
                "Resume",
                resumePendingIntent
            )
        } else {
            val pauseIntent = Intent(context, UploadActionReceiver::class.java).apply {
                action = UploadActionReceiver.ACTION_PAUSE
            }
            val pausePendingIntent = PendingIntent.getBroadcast(
                context,
                102,
                pauseIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(
                android.R.drawable.ic_media_pause,
                "Pause",
                pausePendingIntent
            )
        }

        try {
            notificationManager.notify(NOTIFICATION_ID_LIVE_UPLOAD, builder.build())
        } catch (e: Exception) {
            Log.w(TAG, "Failed to post live activity notification: ${e.message}")
        }
    }

    private fun postCompletedNotification(totalUploaded: Int) {
        val launchIntent = Intent(context, MainActivity::class.java)
        val contentPendingIntent = PendingIntent.getActivity(
            context,
            0,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(context, FastUpApp.CHANNEL_UPLOAD)
            .setSmallIcon(android.R.drawable.stat_sys_upload_done)
            .setContentTitle("Upload Complete")
            .setContentText("All $totalUploaded photos uploaded to server")
            .setSubText("FastUp Live")
            .setContentIntent(contentPendingIntent)
            .setOngoing(false)
            .setAutoCancel(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_LOW)

        try {
            notificationManager.notify(NOTIFICATION_ID_LIVE_UPLOAD, builder.build())
        } catch (e: Exception) {
            Log.w(TAG, "Failed to post complete notification: ${e.message}")
        }
    }

    private fun cancelNotification() {
        try {
            notificationManager.cancel(NOTIFICATION_ID_LIVE_UPLOAD)
        } catch (_: Exception) {}
    }

    private fun formatSpeed(bytesPerSec: Long): String {
        return when {
            bytesPerSec >= 1024 * 1024 -> String.format(Locale.US, "%.1f MB/s", bytesPerSec / (1024.0 * 1024.0))
            bytesPerSec >= 1024 -> String.format(Locale.US, "%.0f KB/s", bytesPerSec / 1024.0)
            bytesPerSec > 0 -> "$bytesPerSec B/s"
            else -> ""
        }
    }

    private data class UploadNotificationState(
        val photos: List<PhotoItem>,
        val isUploading: Boolean,
        val isPaused: Boolean,
        val speedBps: Long
    )

    companion object {
        private const val TAG = "UploadNotificationMgr"
        const val NOTIFICATION_ID_LIVE_UPLOAD = 2002
    }
}
