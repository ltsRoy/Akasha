package com.MeshLink.android.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.MeshLink.android.MainActivity
import com.MeshLink.android.R
import com.MeshLink.android.mesh.BluetoothMeshService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class MeshForegroundService : Service() {

    companion object {
        private const val CHANNEL_ID = "mesh_foreground_service"
        private const val NOTIFICATION_ID = 10001

        const val ACTION_START = "com.MeshLink.android.service.START"
        const val ACTION_STOP = "com.MeshLink.android.service.STOP"
        const val ACTION_QUIT = "com.MeshLink.android.service.QUIT"
        const val ACTION_RESTART_SERVICE = "com.MeshLink.android.service.RESTART_SERVICE"
        const val ACTION_UPDATE_NOTIFICATION = "com.MeshLink.android.service.UPDATE_NOTIFICATION"
        const val ACTION_NOTIFICATION_PERMISSION_GRANTED = "com.MeshLink.android.action.NOTIFICATION_PERMISSION_GRANTED"

        fun scheduleWatchdogAlarm(context: Context) {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
            val intent = Intent(context, ServiceRestarterReceiver::class.java).apply {
                action = ACTION_RESTART_SERVICE
            }
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                20002,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0)
            )
            
            val interval = 5 * 60 * 1000L // 5 minutes
            val triggerAt = System.currentTimeMillis() + interval
            try {
                alarmManager.setInexactRepeating(
                    android.app.AlarmManager.RTC_WAKEUP,
                    triggerAt,
                    interval,
                    pendingIntent
                )
                android.util.Log.d("MeshForegroundService", "Scheduled persistent watchdog alarm (every 5 mins)")
            } catch (e: Exception) {
                android.util.Log.e("MeshForegroundService", "Failed to schedule watchdog alarm: ${e.message}")
            }
        }
        
        fun cancelWatchdogAlarm(context: Context) {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
            val intent = Intent(context, ServiceRestarterReceiver::class.java).apply {
                action = ACTION_RESTART_SERVICE
            }
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                20002,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0)
            )
            try {
                alarmManager.cancel(pendingIntent)
                android.util.Log.d("MeshForegroundService", "Cancelled watchdog alarm")
            } catch (e: Exception) {
                android.util.Log.e("MeshForegroundService", "Failed to cancel watchdog alarm: ${e.message}")
            }
        }

        fun start(context: Context) {
            val intent = Intent(context, MeshForegroundService::class.java).apply { action = ACTION_START }

            // On API >= 26, avoid background-service start restrictions by using startForegroundService
            // only when we can actually post a notification (Android 13+ requires runtime notif permission)
            val bgEnabled = MeshServicePreferences.isBackgroundEnabled(true)
            val hasNotifPerm = hasNotificationPermissionStatic(context)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (bgEnabled && hasNotifPerm) {
                    try {
                        context.startForegroundService(intent)
                    } catch (e: Exception) {
                        // In Android 12+, this might throw ForegroundServiceStartNotAllowedException
                        android.util.Log.e("MeshForegroundService", "Failed to start FGS from background: ${e.message}")
                    }
                } else {
                    // Do not attempt to start a background service from headless context without notif permission
                    // or when background is disabled, to avoid BackgroundServiceStartNotAllowedException.
                    android.util.Log.i(
                        "MeshForegroundService",
                        "Not starting service on API>=26 (bgEnabled=$bgEnabled, hasNotifPerm=$hasNotifPerm)"
                    )
                }
            } else {
                if (bgEnabled) {
                    try {
                        context.startService(intent)
                    } catch (e: Exception) {
                        android.util.Log.e("MeshForegroundService", "Failed to start service: ${e.message}")
                    }
                } else {
                    android.util.Log.i("MeshForegroundService", "Background disabled; not starting service (pre-O)")
                }
            }
        }

        /**
         * Helper to be invoked right after POST_NOTIFICATIONS is granted to try
         * promoting/starting the foreground service immediately without polling.
         */
        fun onNotificationPermissionGranted(context: Context) {
            // If background is enabled and permission now granted, start/promo service
            val hasNotifPerm = hasNotificationPermissionStatic(context)
            if (!MeshServicePreferences.isBackgroundEnabled(true) || !hasNotifPerm) return

            val intent = Intent(context, MeshForegroundService::class.java).apply { action = ACTION_UPDATE_NOTIFICATION }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                // Safe now that we can show a notification
                try {
                    context.startForegroundService(intent)
                } catch (e: Exception) {
                    android.util.Log.e("MeshForegroundService", "Failed to start FGS on perm granted: ${e.message}")
                }
            } else {
                try {
                    context.startService(intent)
                } catch (e: Exception) {
                    android.util.Log.e("MeshForegroundService", "Failed to start service on perm granted: ${e.message}")
                }
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, MeshForegroundService::class.java).apply { action = ACTION_STOP }
            context.startService(intent)
        }

        private fun shouldStartAsForeground(context: Context): Boolean {
            return MeshServicePreferences.isBackgroundEnabled(true) &&
                    hasBluetoothPermissionsStatic(context) &&
                    hasNotificationPermissionStatic(context)
        }

        private fun hasBluetoothPermissionsStatic(ctx: Context): Boolean {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                androidx.core.content.ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.BLUETOOTH_ADVERTISE) == android.content.pm.PackageManager.PERMISSION_GRANTED &&
                androidx.core.content.ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.BLUETOOTH_CONNECT) == android.content.pm.PackageManager.PERMISSION_GRANTED &&
                androidx.core.content.ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.BLUETOOTH_SCAN) == android.content.pm.PackageManager.PERMISSION_GRANTED
            } else {
                val fine = androidx.core.content.ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED
                val coarse = androidx.core.content.ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.ACCESS_COARSE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED
                fine || coarse
            }
        }

        private fun hasNotificationPermissionStatic(ctx: Context): Boolean {
            return if (Build.VERSION.SDK_INT >= 33) {
                androidx.core.content.ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED
            } else true
        }
    }

    private lateinit var notificationManager: NotificationManagerCompat
    private var updateJob: Job? = null
    private val meshService: BluetoothMeshService?
        get() = MeshServiceHolder.meshService
    private val serviceJob = Job()
    private val scope = CoroutineScope(Dispatchers.Default + serviceJob)
    private var isInForeground: Boolean = false
    private var isShuttingDown: Boolean = false
    private var wakeLock: android.os.PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        val powerManager = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        wakeLock = powerManager.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "MeshLink:MeshForegroundServiceWakelock").apply {
            setReferenceCounted(false)
        }
        wakeLock?.acquire() // Acquire immediately on creation to prevent sleep before onStartCommand
        notificationManager = NotificationManagerCompat.from(this)
        createChannel()

        // Ensure mesh service exists in holder (create if needed)
        val existing = MeshServiceHolder.meshService
        if (existing != null) {
            Log.d("MeshForegroundService", "Using existing BluetoothMeshService from holder")
        } else {
            val created = MeshServiceHolder.getOrCreate(applicationContext)
            Log.i("MeshForegroundService", "Created new BluetoothMeshService via holder")
            MeshServiceHolder.attach(created)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (isShuttingDown && intent?.action == ACTION_START) {
            AppShutdownCoordinator.cancelPendingShutdown()
            isShuttingDown = false
        }
        if (isShuttingDown && intent?.action != ACTION_QUIT) {
            return START_NOT_STICKY
        }
        when (intent?.action) {
            ACTION_STOP -> {
                // Stop FGS and mesh cleanly
                cancelWatchdogAlarm(this)
                if (wakeLock?.isHeld == true) wakeLock?.release()
                try { meshService?.stopServices() } catch (_: Exception) { }
                try { MeshServiceHolder.clear() } catch (_: Exception) { }
                try { stopForeground(true) } catch (_: Exception) { }
                notificationManager.cancel(NOTIFICATION_ID)
                isInForeground = false
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_QUIT -> {
                isShuttingDown = true
                cancelWatchdogAlarm(this)
                if (wakeLock?.isHeld == true) wakeLock?.release()
                updateJob?.cancel()
                updateJob = null
                try { stopForeground(true) } catch (_: Exception) { }
                notificationManager.cancel(NOTIFICATION_ID)
                isInForeground = false
                // Fully stop all background activity, stop Tor (without changing setting), then kill the app
                AppShutdownCoordinator.requestFullShutdownAndKill(
                    app = application,
                    mesh = meshService,
                    notificationManager = notificationManager,
                    stopForeground = {
                        try { stopForeground(true) } catch (_: Exception) { }
                        isInForeground = false
                    },
                    stopService = { stopSelf() }
                )
                return START_NOT_STICKY
            }
            ACTION_UPDATE_NOTIFICATION -> {
                // If we became eligible and are not in foreground yet, promote once
                if (MeshServicePreferences.isBackgroundEnabled(true) && hasAllRequiredPermissions() && !isInForeground) {
                    val n = buildNotification(meshService?.getActivePeerCount() ?: 0)
                    startForegroundCompat(n)
                    isInForeground = true
                } else {
                    updateNotification(force = true)
                }
            }
            else -> { /* ACTION_START or null */ }
        }

        // Ensure mesh is running (only after permissions are granted)
        ensureMeshStarted()
        
        if (wakeLock?.isHeld == false) {
            wakeLock?.acquire()
        }

        // Promote exactly once when eligible, otherwise stay background (or stop)
        if (MeshServicePreferences.isBackgroundEnabled(true) && hasAllRequiredPermissions() && !isInForeground) {
            val notification = buildNotification(meshService?.getActivePeerCount() ?: 0)
            startForegroundCompat(notification)
            isInForeground = true
        }

        // Periodically refresh the notification with live network size
        if (updateJob == null) {
            updateJob = scope.launch {
                while (isActive) {
                    // Retry enabling mesh/foreground once permissions become available
                    ensureMeshStarted()
                    val eligible = MeshServicePreferences.isBackgroundEnabled(true) && hasAllRequiredPermissions()
                    if (eligible) {
                        // Only update the notification; do not re-call startForeground()
                        updateNotification(force = false)
                    } else {
                        // If disabled or perms missing, ensure we are not in foreground and clear notif
                        if (isInForeground) {
                            try { stopForeground(false) } catch (_: Exception) { }
                            isInForeground = false
                        }
                        notificationManager.cancel(NOTIFICATION_ID)
                    }
                    delay(5000)
                }
            }
        }

        if (!isShuttingDown && MeshServicePreferences.isBackgroundEnabled(true)) {
            scheduleWatchdogAlarm(this)
        }

        return START_STICKY
    }

    private fun ensureMeshStarted() {
        if (isShuttingDown) return
        if (!hasBluetoothPermissions()) return
        try {
            android.util.Log.d("MeshForegroundService", "Ensuring mesh service is started")
            val service = MeshServiceHolder.getOrCreate(applicationContext)
            service.startServices()
        } catch (e: Exception) {
            android.util.Log.e("MeshForegroundService", "Failed to start mesh service: ${e.message}")
        }
    }

    private fun updateNotification(force: Boolean) {
        if (isShuttingDown) {
            notificationManager.cancel(NOTIFICATION_ID)
            return
        }
        val count = meshService?.getActivePeerCount() ?: 0
        val notification = buildNotification(count)
        if (MeshServicePreferences.isBackgroundEnabled(true) && hasAllRequiredPermissions()) {
            notificationManager.notify(NOTIFICATION_ID, notification)
        } else if (force) {
            // If disabled and forced, make sure to remove any prior foreground state
            try { stopForeground(false) } catch (_: Exception) { }
            notificationManager.cancel(NOTIFICATION_ID)
            isInForeground = false
        }
    }

    private fun hasAllRequiredPermissions(): Boolean {
        // For starting FGS with connectedDevice|dataSync, we need:
        // - Foreground service permissions (declared in manifest)
        // - One of the device-related permissions (we request BL perms at runtime)
        // - On Android 13+, POST_NOTIFICATIONS to actually show notification
        return hasBluetoothPermissions() && hasNotificationPermission()
    }

    private fun hasBluetoothPermissions(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.BLUETOOTH_ADVERTISE) == android.content.pm.PackageManager.PERMISSION_GRANTED &&
            androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.BLUETOOTH_CONNECT) == android.content.pm.PackageManager.PERMISSION_GRANTED &&
            androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.BLUETOOTH_SCAN) == android.content.pm.PackageManager.PERMISSION_GRANTED
        } else {
            // Prior to S, scanning requires location permissions
            val fine = androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED
            val coarse = androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_COARSE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED
            fine || coarse
        }
    }

    private fun hasNotificationPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= 33) {
            androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED
        } else true
    }

    private fun buildNotification(activePeers: Int): Notification {
        val openIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0)
        )

        // Action: Quit MeshLink
        val quitIntent = Intent(this, MeshForegroundService::class.java).apply { action = ACTION_QUIT }
        val quitPendingIntent = PendingIntent.getService(
            this, 1, quitIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0)
        )

        val title = getString(R.string.app_name)
        val content = getString(R.string.mesh_service_notification_content, activePeers)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(content)
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(pendingIntent)
            // Add an action button that appears when notification is expanded
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                getString(R.string.notification_action_quit_MeshLink),
                quitPendingIntent
            )
            .build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.mesh_service_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.mesh_service_channel_desc)
                setShowBadge(false)
            }
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }
    }

    private fun hasLocationPermission(): Boolean {
        val fine = androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED
        val coarse = androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_COARSE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED
        return fine || coarse
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= 34) {
            val type = if (hasLocationPermission()) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            } else {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            }
            try {
                startForeground(NOTIFICATION_ID, notification, type)
            } catch (e: SecurityException) {
                // Fallback for cases where "While In Use" permission exists but background start is restricted
                if (type and ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION != 0) {
                     android.util.Log.w("MeshForegroundService", "Failed to start with LOCATION type, falling back to CONNECTED_DEVICE: ${e.message}")
                     startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
                } else {
                    throw e
                }
            }
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    override fun onDestroy() {
        updateJob?.cancel()
        updateJob = null
        if (wakeLock?.isHeld == true) wakeLock?.release()
        // Cancel the service coroutine scope to prevent leaks
        try { serviceJob.cancel() } catch (_: Exception) { }
        // Best-effort ensure we are not marked foreground
        if (isInForeground) {
            try { stopForeground(true) } catch (_: Exception) { }
            isInForeground = false
        }
        
        // Aggressively attempt to restart unless explicitly shut down
        if (!isShuttingDown && MeshServicePreferences.isBackgroundEnabled(true)) {
            android.util.Log.d("MeshForegroundService", "Service destroyed unexpectedly. Broadcasting restart intent.")
            val restartIntent = Intent(this, ServiceRestarterReceiver::class.java).apply {
                action = ACTION_RESTART_SERVICE
            }
            sendBroadcast(restartIntent)
        }
        
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
