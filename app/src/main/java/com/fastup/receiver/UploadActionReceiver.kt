package com.fastup.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.fastup.FastUpApp

class UploadActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        Log.i(TAG, "UploadActionReceiver received action: $action")
        val uploadManager = FastUpApp.instance.uploadManager

        when (action) {
            ACTION_PAUSE -> {
                uploadManager.forceStopUpload()
            }
            ACTION_RESUME -> {
                uploadManager.startUpload()
            }
        }
    }

    companion object {
        private const val TAG = "UploadActionReceiver"
        const val ACTION_PAUSE = "com.fastup.ACTION_PAUSE_UPLOAD"
        const val ACTION_RESUME = "com.fastup.ACTION_RESUME_UPLOAD"
    }
}
