package com.MeshLink.android.india

import android.annotation.SuppressLint
import android.content.Context
import android.location.GnssStatus
import android.location.LocationManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Tracks how many NavIC (IRNSS) satellites this device can see, alongside the other constellations.
 *
 * NavIC is India's own regional navigation system, run by ISRO. It covers India and about 1,500 km
 * beyond, and — unlike GPS — it isn't operated by a foreign government, so it keeps working for
 * India regardless of anyone else's policy decisions. Most phones sold in India in recent years
 * carry NavIC-capable GNSS chipsets.
 *
 * For Akasha this matters because the whole point is not depending on infrastructure someone else
 * controls: the messaging layer needs no ISP or tower, and the positioning layer can lean on an
 * Indian constellation.
 *
 * Read-only — this observes the GNSS status the platform already reports and never requests
 * additional location data of its own.
 */
class NavicMonitor(private val context: Context) {

    data class GnssSnapshot(
        /** NavIC/IRNSS satellites currently tracked. */
        val navicVisible: Int = 0,
        /** NavIC/IRNSS satellites actually contributing to the position fix. */
        val navicInFix: Int = 0,
        /** All satellites tracked, across every constellation. */
        val totalVisible: Int = 0,
        /** All satellites used in the fix. */
        val totalInFix: Int = 0,
        /** Strongest NavIC signal-to-noise ratio seen, in dB-Hz. Rough signal-quality indicator. */
        val navicTopCn0: Float = 0f,
        /** Whether the chipset reports NavIC capability at all. */
        val navicSupported: Boolean = false,
    ) {
        /** True when Indian satellites are actively contributing to where we think we are. */
        val navicContributing: Boolean get() = navicInFix > 0
    }

    private val _status = MutableStateFlow(GnssSnapshot())
    val status: StateFlow<GnssSnapshot> = _status

    private val locationManager: LocationManager? =
        context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager

    private var callback: GnssStatus.Callback? = null

    private val handler = Handler(Looper.getMainLooper())

    /**
     * Begin observing GNSS status. Safe to call repeatedly; requires location permission, and
     * silently does nothing without it.
     */
    @SuppressLint("MissingPermission")
    fun start() {
        if (callback != null) return
        val lm = locationManager ?: return

        val cb = object : GnssStatus.Callback() {
            override fun onSatelliteStatusChanged(status: GnssStatus) {
                var navicVisible = 0
                var navicInFix = 0
                var totalInFix = 0
                var navicTopCn0 = 0f

                for (i in 0 until status.satelliteCount) {
                    val usedInFix = status.usedInFix(i)
                    if (usedInFix) totalInFix++

                    if (status.getConstellationType(i) == GnssStatus.CONSTELLATION_IRNSS) {
                        navicVisible++
                        if (usedInFix) navicInFix++
                        val cn0 = status.getCn0DbHz(i)
                        if (cn0 > navicTopCn0) navicTopCn0 = cn0
                    }
                }

                _status.value = GnssSnapshot(
                    navicVisible = navicVisible,
                    navicInFix = navicInFix,
                    totalVisible = status.satelliteCount,
                    totalInFix = totalInFix,
                    navicTopCn0 = navicTopCn0,
                    // Seeing any IRNSS satellite proves the chipset tracks the constellation.
                    navicSupported = navicVisible > 0 || _status.value.navicSupported,
                )
            }
        }

        val registered = runCatching {
            lm.registerGnssStatusCallback(cb, handler)
        }.getOrElse {
            Log.w(TAG, "Could not register GNSS status callback: ${it.message}")
            false
        }

        if (registered) {
            callback = cb
            Log.i(TAG, "NavIC monitor started")
        }
    }

    fun stop() {
        val cb = callback ?: return
        runCatching { locationManager?.unregisterGnssStatusCallback(cb) }
        callback = null
    }

    companion object {
        private const val TAG = "NavicMonitor"
    }
}
