package com.MeshLink.android.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class ServiceRestarterReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Log.i("ServiceRestarter", "ServiceRestarterReceiver received: ${intent.action}")
        
        // Only attempt to start if the background service setting is enabled
        if (MeshServicePreferences.isBackgroundEnabled(true)) {
            Log.d("ServiceRestarter", "Attempting to restart MeshForegroundService...")
            try {
                MeshForegroundService.start(context.applicationContext)
            } catch (e: Exception) {
                Log.e("ServiceRestarter", "Failed to restart MeshForegroundService: ${e.message}")
            }
        } else {
            Log.d("ServiceRestarter", "Background disabled, skipping restart.")
        }
    }
}
