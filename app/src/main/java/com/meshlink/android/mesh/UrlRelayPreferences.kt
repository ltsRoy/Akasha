package com.MeshLink.android.mesh

import android.content.Context

/**
 * Stores opt-in URL relay endpoints for laptop/desktop bridges.
 *
 * The relay is intentionally disabled until a user opens a MeshLink://relay deep
 * link or another settings surface writes a relay URL.
 */
object UrlRelayPreferences {
    private const val PREFS = "MeshLink_url_relays"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_URLS = "urls"

    fun isEnabled(context: Context): Boolean {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_ENABLED, false) && getRelayUrls(context).isNotEmpty()
    }

    fun getRelayUrls(context: Context): List<String> {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return prefs.getStringSet(KEY_URLS, emptySet()).orEmpty()
            .mapNotNull { normalizeUrl(it) }
            .distinct()
            .sorted()
    }

    fun addRelayUrl(context: Context, rawUrl: String): Boolean {
        val url = normalizeUrl(rawUrl) ?: return false
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val urls = prefs.getStringSet(KEY_URLS, emptySet()).orEmpty().toMutableSet()
        urls.add(url)
        prefs.edit()
            .putBoolean(KEY_ENABLED, true)
            .putStringSet(KEY_URLS, urls)
            .apply()
        return true
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ENABLED, enabled)
            .apply()
    }

    private fun normalizeUrl(rawUrl: String): String? {
        val trimmed = rawUrl.trim()
        if (trimmed.isEmpty()) return null
        val withScheme = when {
            trimmed.startsWith("ws://", ignoreCase = true) -> trimmed
            trimmed.startsWith("wss://", ignoreCase = true) -> trimmed
            trimmed.startsWith("http://", ignoreCase = true) -> "ws://" + trimmed.substringAfter("://")
            trimmed.startsWith("https://", ignoreCase = true) -> "wss://" + trimmed.substringAfter("://")
            else -> "ws://$trimmed"
        }
        return withScheme.removeSuffix("/")
    }
}
