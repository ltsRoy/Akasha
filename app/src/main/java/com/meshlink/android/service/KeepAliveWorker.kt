package com.MeshLink.android.service

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

class KeepAliveWorker(
    private val context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        Log.d("KeepAliveWorker", "Periodic keep-alive check running...")
        
        if (MeshServicePreferences.isBackgroundEnabled(true)) {
            try {
                Log.d("KeepAliveWorker", "Ensuring MeshForegroundService is started.")
                MeshForegroundService.start(context.applicationContext)
            } catch (e: Exception) {
                Log.e("KeepAliveWorker", "Failed to start MeshForegroundService: ${e.message}")
            }
        }
        
        return Result.success()
    }
}
