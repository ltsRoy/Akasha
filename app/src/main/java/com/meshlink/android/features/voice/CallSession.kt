package com.MeshLink.android.features.voice

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Who this device is currently on a call with — at most one peer.
 *
 * Calls were previously an open broadcast: the invitation went to everyone, and anyone who accepted
 * both heard and was heard by the whole mesh. That is the wrong default. A person reporting an
 * emergency is having a conversation, not addressing a room, and a second listener joining silently
 * is a privacy problem rather than a feature.
 *
 * So the invitation is still broadcast — the caller cannot know who will pick up — but the *first*
 * peer to accept claims the call, and everyone else is turned away. Audio is addressed to that peer
 * from then on, and frames from anyone else are discarded.
 */
object CallSession {

    private const val TAG = "CallSession"

    private val _peer = MutableStateFlow<String?>(null)

    /** The single remote party, or null when not in a call. */
    val peer: StateFlow<String?> = _peer.asStateFlow()

    /** True while we've invited the mesh but nobody has accepted yet. */
    @Volatile
    var awaitingAnswer: Boolean = false
        private set

    val activePeer: String? get() = _peer.value

    /** True once a peer has claimed the call. */
    val isConnected: Boolean get() = _peer.value != null

    /** Mark that we've sent an invitation and are waiting for someone to take it. */
    fun startOutgoing() {
        synchronized(this) {
            _peer.value = null
            awaitingAnswer = true
        }
        Log.d(TAG, "Outgoing call: awaiting an answer")
    }

    /**
     * Claim the call for [peerId].
     *
     * Returns false when someone else already holds it, which is how second and third accepts are
     * rejected rather than silently joining.
     */
    fun engage(peerId: String): Boolean = synchronized(this) {
        val current = _peer.value
        if (current != null && !current.equals(peerId, ignoreCase = true)) {
            Log.d(TAG, "Rejecting $peerId — already connected to $current")
            return false
        }
        _peer.value = peerId
        awaitingAnswer = false
        Log.i(TAG, "Call connected with $peerId")
        true
    }

    fun clear() {
        synchronized(this) {
            if (_peer.value != null || awaitingAnswer) Log.i(TAG, "Call ended")
            _peer.value = null
            awaitingAnswer = false
        }
    }

    /** True when [peerId] is the party we're talking to. Used to filter incoming audio. */
    fun isWith(peerId: String?): Boolean {
        if (peerId == null) return false
        return _peer.value?.equals(peerId, ignoreCase = true) == true
    }
}
