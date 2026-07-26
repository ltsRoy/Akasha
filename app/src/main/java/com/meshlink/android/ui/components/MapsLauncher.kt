package com.MeshLink.android.ui.components

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import com.MeshLink.android.model.MeshLinkMessage

/**
 * Opens coordinates in a maps app.
 *
 * Shared by the facility cards and shared-location messages so there is one definition of "open this
 * point", rather than each screen inventing its own intent and drifting.
 */
object MapsLauncher {

    private const val TAG = "MapsLauncher"

    /**
     * Show [latitude]/[longitude] on a map, labelled [label].
     *
     * A `geo:` intent is tried first: any installed maps app handles it and it works with no network,
     * which matters in an app used when there is none. The web URL is only a fallback for devices with
     * no maps app at all — a browser is worse than a dead tap, but not much.
     */
    fun open(context: Context, latitude: Double, longitude: Double, label: String? = null) {
        // Built piece by piece so the label is properly percent-encoded.
        val encodedLabel = label?.takeIf { it.isNotBlank() }?.let { Uri.encode(it) }
        val geoUri = buildString {
            append("geo:")
            append(latitude)
            append(',')
            append(longitude)
            append("?q=")
            append(latitude)
            append(',')
            append(longitude)
            if (encodedLabel != null) {
                append('(')
                append(encodedLabel)
                append(')')
            }
        }

        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(geoUri)))
            return
        } catch (e: ActivityNotFoundException) {
            Log.d(TAG, "No geo: handler installed, falling back to web maps")
        } catch (e: Exception) {
            Log.w(TAG, "geo: intent failed: ${e.message}")
        }

        try {
            context.startActivity(
                Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse("https://www.google.com/maps/search/?api=1&query=$latitude,$longitude"),
                )
            )
        } catch (e: Exception) {
            Log.w(TAG, "Could not open maps for $latitude,$longitude: ${e.message}")
        }
    }

    /**
     * Coordinates carried by a shared-location message, or null if it isn't one.
     *
     * Prefers the structured [MeshLinkMessage.latitude]/[MeshLinkMessage.longitude] fields, falling
     * back to parsing the message text. The fallback is needed because those fields are only populated
     * for *received* messages — the local echo of a location you send yourself is plain text, so
     * without it your own shared pin would be the one thing you couldn't tap.
     */
    fun coordinatesOf(message: MeshLinkMessage): Pair<Double, Double>? {
        message.latitude?.let { lat ->
            message.longitude?.let { lon -> return lat to lon }
        }
        return parseCoordinates(message.content)
    }

    /** Pull "lat, lng" out of a shared-location message body. */
    fun parseCoordinates(content: String): Pair<Double, Double>? {
        if (!content.contains("📍")) return null
        return try {
            val body = content.substringAfter(':', missingDelimiterValue = "")
            val parts = body.split(',')
            if (parts.size < 2) return null
            val lat = parts[0].trim().toDoubleOrNull() ?: return null
            val lon = parts[1].trim().takeWhile { it.isDigit() || it == '.' || it == '-' }
                .toDoubleOrNull() ?: return null
            // Reject anything outside the valid range rather than opening a map of nowhere.
            if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return null
            lat to lon
        } catch (e: Exception) {
            null
        }
    }
}
