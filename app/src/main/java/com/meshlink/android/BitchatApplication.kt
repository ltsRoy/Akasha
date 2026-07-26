package com.MeshLink.android

import android.app.Application
import com.MeshLink.android.nostr.RelayDirectory
import com.MeshLink.android.ui.theme.ThemePreferenceManager
import com.MeshLink.android.net.ArtiTorManager
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit
import com.MeshLink.android.web.WebServerManager

/**
 * Main application class for MeshLink Android
 */
class MeshLinkApplication : Application() {

    private var webServer: WebServerManager? = null

    override fun onCreate() {
        super.onCreate()

        // Initialize Tor first so any early network goes over Tor
        try {
            val torProvider = ArtiTorManager.getInstance()
            torProvider.init(this)
        } catch (_: Exception){}

        // Initialize relay directory (loads assets/nostr_relays.csv)
        RelayDirectory.initialize(this)

        // Initialize LocationNotesManager dependencies early so sheet subscriptions can start immediately
        try { com.MeshLink.android.nostr.LocationNotesInitializer.initialize(this) } catch (_: Exception) { }

        // Initialize favorites persistence early so MessageRouter/NostrTransport can use it on startup
        try {
            com.MeshLink.android.favorites.FavoritesPersistenceService.initialize(this)
        } catch (_: Exception) { }

        // Warm up Nostr identity to ensure npub is available for favorite notifications
        try {
            com.MeshLink.android.nostr.NostrIdentityBridge.getCurrentNostrIdentity(this)
        } catch (_: Exception) { }

        // Initialize theme preference
        ThemePreferenceManager.init(this)

        // Initialize debug preference manager (persists debug toggles)
        try { com.MeshLink.android.ui.debug.DebugPreferenceManager.init(this) } catch (_: Exception) { }

        // Initialize Geohash Registries for persistence
        try {
            com.MeshLink.android.nostr.GeohashAliasRegistry.initialize(this)
            com.MeshLink.android.nostr.GeohashConversationRegistry.initialize(this)
        } catch (_: Exception) { }

        // Initialize mesh service preferences
        try { com.MeshLink.android.service.MeshServicePreferences.init(this) } catch (_: Exception) { }

        // Proactively start the foreground service to keep mesh alive
        try { com.MeshLink.android.service.MeshForegroundService.start(this) } catch (_: Exception) { }

        // Start the embedded web server for the laptop connection portal
        try {
            webServer = WebServerManager(this, 8080)
            webServer?.start(fi.iki.elonen.NanoHTTPD.SOCKET_READ_TIMEOUT, false)
            android.util.Log.i("MeshLinkApplication", "Web server started on port 8080")
        } catch (e: Exception) {
            android.util.Log.e("MeshLinkApplication", "Failed to start web server", e)
        }

        // Schedule keep-alive worker to run periodically (every 15 mins is the minimum)
        scheduleKeepAliveWorker()
    }

    private fun scheduleKeepAliveWorker() {
        val constraints = Constraints.Builder()
            // We don't require network, just want it to run periodically
            .build()
            
        val keepAliveRequest = PeriodicWorkRequestBuilder<com.MeshLink.android.service.KeepAliveWorker>(
            15, TimeUnit.MINUTES
        )
        .setConstraints(constraints)
        .build()
        
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "MeshForegroundServiceKeepAlive",
            ExistingPeriodicWorkPolicy.UPDATE, // or KEEP
            keepAliveRequest
        )
    }
}
