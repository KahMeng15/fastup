package com.fastup.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.fastup.service.UsbTetherService

class CaptureReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Log.i(TAG, "CaptureReceiver onReceive: action=${intent.action}")
        val service = UsbTetherService.instance
        if (service != null) {
            service.triggerCapture()
        } else {
            Log.w(TAG, "UsbTetherService is not running")
        }
    }

    companion object {
        private const val TAG = "CaptureReceiver"
    }
}
