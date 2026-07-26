package com.MeshLink.android.security

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * How strictly this device treats traffic from the mesh.
 *
 * The trade-off is real and unavoidable: the strong guarantees only hold if we refuse anything we
 * can't verify, but refusing everything unverifiable also cuts off older clients that predate the
 * approved-device list. So it's a user choice, defaulting to the interoperable option.
 */
enum class MeshMode {
    /**
     * Accept unsigned/unknown senders but mark them clearly in the UI. Keeps the network wide and
     * keeps older clients reachable — the right default when the point is reaching people.
     */
    Lenient,

    /**
     * Only accept packets that are signed, verified, fresh, and from a key on the approved-device
     * list. Anything else is dropped before it reaches the UI.
     */
    Strict;

    val isStrict: Boolean get() = this == Strict
}

/**
 * Persisted security policy. Read from the mesh layer (which has no Compose/UI access), written from
 * settings, and observable so the UI can react.
 */
object SecurityPolicy {
    private const val PREFS = "akasha_security"
    private const val KEY_MODE = "mesh_mode"

    /** Packets older/newer than this are treated as replays. Generous, to tolerate clock skew. */
    const val FRESHNESS_WINDOW_MS: Long = 10 * 60 * 1000L

    private val _mode = MutableStateFlow(MeshMode.Lenient)
    val mode: StateFlow<MeshMode> = _mode

    /** Snapshot for non-Compose callers on the packet path. */
    val current: MeshMode get() = _mode.value

    fun init(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val saved = prefs.getString(KEY_MODE, MeshMode.Lenient.name)
        _mode.value = runCatching { MeshMode.valueOf(saved!!) }.getOrDefault(MeshMode.Lenient)
    }

    fun set(context: Context, mode: MeshMode) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_MODE, mode.name).apply()
        _mode.value = mode
    }
}
