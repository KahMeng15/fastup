package com.fastup.util

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import com.fastup.FastUpApp
import com.fastup.MainActivity
import java.util.concurrent.ConcurrentHashMap

object IssueAlertNotifier {
    private const val TAG = "IssueAlertNotifier"
    private const val NOTIFICATION_ID_BASE = 5000
    private val lastAlertTimestamps = ConcurrentHashMap<String, Long>()
    private const val MIN_NOTIFICATION_INTERVAL_MS = 60_000L // Don't spam same alert within 60s

    fun notifyIssue(
        context: Context,
        issueKey: String,
        title: String,
        message: String,
        isError: Boolean = true
    ) {
        val now = System.currentTimeMillis()
        val lastTime = lastAlertTimestamps[issueKey] ?: 0L
        if (now - lastTime < MIN_NOTIFICATION_INTERVAL_MS) {
            return
        }
        lastAlertTimestamps[issueKey] = now

        try {
            val intent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            val pendingIntent = PendingIntent.getActivity(
                context,
                issueKey.hashCode(),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
            )

            val builder = NotificationCompat.Builder(context, FastUpApp.CHANNEL_ALERTS)
                .setSmallIcon(android.R.drawable.stat_notify_error)
                .setContentTitle(title)
                .setContentText(message)
                .setStyle(NotificationCompat.BigTextStyle().bigText(message))
                .setPriority(if (isError) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT)
                .setCategory(NotificationCompat.CATEGORY_ERROR)
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)

            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            val notifId = NOTIFICATION_ID_BASE + (issueKey.hashCode() and 0x7FFF)
            nm?.notify(notifId, builder.build())
            Log.i(TAG, "Posted push notification alert: [$title] - $message")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to post alert notification: ${e.message}")
        }
    }

    fun clearAlert(context: Context, issueKey: String) {
        lastAlertTimestamps.remove(issueKey)
        try {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            val notifId = NOTIFICATION_ID_BASE + (issueKey.hashCode() and 0x7FFF)
            nm?.cancel(notifId)
        } catch (_: Exception) {}
    }
}
