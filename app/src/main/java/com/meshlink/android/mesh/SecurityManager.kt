package com.MeshLink.android.mesh

import android.util.Log
import com.MeshLink.android.crypto.EncryptionService
import com.MeshLink.android.protocol.MeshLinkPacket
import com.MeshLink.android.protocol.MessageType
import com.MeshLink.android.model.RoutedPacket
import com.MeshLink.android.model.TrustState
import com.MeshLink.android.util.toHexString
import kotlinx.coroutines.*
import java.util.*
import kotlin.collections.mutableSetOf

/**
 * Manages security aspects of the mesh network including duplicate detection,
 * replay attack protection, and key exchange handling
 * Extracted from BluetoothMeshService for better separation of concerns
 */
class SecurityManager(private val encryptionService: EncryptionService, private val myPeerID: String) {
    
    companion object {
        private const val TAG = "SecurityManager"
        private const val MESSAGE_TIMEOUT = com.MeshLink.android.util.AppConstants.Security.MESSAGE_TIMEOUT_MS // 5 minutes (same as iOS)
        private const val CLEANUP_INTERVAL = com.MeshLink.android.util.AppConstants.Security.CLEANUP_INTERVAL_MS // 5 minutes
        private const val MAX_PROCESSED_MESSAGES = com.MeshLink.android.util.AppConstants.Security.MAX_PROCESSED_MESSAGES
        private const val MAX_PROCESSED_KEY_EXCHANGES = com.MeshLink.android.util.AppConstants.Security.MAX_PROCESSED_KEY_EXCHANGES

        /** How many recent trust verdicts to keep so MessageHandler can read them back. */
        private const val TRUST_CACHE_SIZE = 512

        /**
         * Packet types that carry a user-visible payload and must be signed.
         *
         * NOISE_ENCRYPTED is intentionally absent: its authenticity comes from the Noise session's
         * AEAD, so a valid decryption already proves who sent it.
         */
        private val SIGNED_PACKET_TYPES = setOf(
            MessageType.ANNOUNCE,
            MessageType.MESSAGE,
            MessageType.FILE_TRANSFER,
        )
    }
    
    // Security tracking
    private val processedMessages = Collections.synchronizedSet(mutableSetOf<String>())
    private val processedKeyExchanges = Collections.synchronizedSet(mutableSetOf<String>())
    private val messageTimestamps = Collections.synchronizedMap(mutableMapOf<String, Long>())
    
    // Delegate for callbacks
    var delegate: SecurityManagerDelegate? = null
    
    // Coroutines
    private val managerScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    init {
        startPeriodicCleanup()
    }
    
    /**
     * Validate packet security (timestamp, replay attacks, duplicates, signatures)
     */
    fun validatePacket(packet: MeshLinkPacket, peerID: String): Boolean {
        // Skip validation for our own packets
        if (peerID == myPeerID) {
            Log.d(TAG, "Skipping validation for our own packet")
            return false
        }
        
        val currentTime = System.currentTimeMillis()
        val messageType = MessageType.fromValue(packet.type)

        // Replay protection. The dedup set below only catches repeats while the entry is still
        // cached; once it's cleaned out, a captured packet could be replayed. The signature covers
        // the timestamp, so an attacker can't move it — rejecting stale packets closes that window.
        //
        // Only enforced in strict mode: two devices with badly skewed clocks would otherwise drop
        // each other's traffic, and losing messages is worse than a replay when someone needs help.
        if (com.MeshLink.android.security.SecurityPolicy.current.isStrict) {
            val age = kotlin.math.abs(currentTime - packet.timestamp.toLong())
            if (age > com.MeshLink.android.security.SecurityPolicy.FRESHNESS_WINDOW_MS) {
                Log.w(TAG, "Dropping stale/replayed packet from $peerID (off by ${age}ms)")
                return false
            }
        }

        // Duplicate detection
        val messageID = generateMessageID(packet, peerID)
        
        if (processedMessages.contains(messageID)) {
            // Check for ANNOUNCE exception: allow if it looks like a direct neighbor (max TTL)
            // This ensures we catch the "first announce" on a new connection for binding,
            // while still dropping looped/relayed duplicates.
            val isFreshAnnounce = messageType == MessageType.ANNOUNCE &&
                    packet.ttl >= com.MeshLink.android.util.AppConstants.MESSAGE_TTL_HOPS

            if (!isFreshAnnounce) {
                Log.d(TAG, "Dropping duplicate packet: $messageID")
                return false
            }
            Log.d(TAG, "Allowing duplicate ANNOUNCE from direct neighbor: $messageID")
        }

        // Add to processed messages
        processedMessages.add(messageID)
        messageTimestamps[messageID] = currentTime
        
        // Signature verification. The outcome is cached rather than discarded so the UI can show a
        // per-message tick or warning instead of the user having to trust the whole channel blindly.
        val trust = evaluateTrust(packet, peerID)
        recordTrust(packet, peerID, trust)

        if (!trust.isTrusted) {
            // Strict mode refuses anything unverifiable — the strong guarantee, at the cost of
            // cutting off older clients. Lenient mode admits it but tags it, so the message still
            // reaches a person in trouble and the UI shows a warning marker instead of silence.
            if (com.MeshLink.android.security.SecurityPolicy.current.isStrict) {
                Log.w(TAG, "Dropping packet from $peerID: trust=$trust (strict mode)")
                return false
            }
            Log.w(TAG, "Admitting packet from $peerID with trust=$trust (lenient mode — will be flagged in UI)")
        }

        Log.d(TAG, "Packet validation passed for $peerID, messageID: $messageID, trust=$trust")
        return true
    }

    /**
     * Trust verdict for the most recently validated packet from a peer, keyed by peer + payload so
     * concurrent traffic from several peers can't pick up each other's verdicts.
     *
     * This exists because validation happens in [validatePacket], well before the message object is
     * built in MessageHandler, and re-verifying the signature there would mean hashing large media
     * payloads twice.
     */
    private val recentTrust = java.util.Collections.synchronizedMap(
        object : LinkedHashMap<String, TrustState>(64, 0.75f, false) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, TrustState>?): Boolean =
                size > TRUST_CACHE_SIZE
        }
    )

    private fun trustKey(packet: MeshLinkPacket, peerID: String): String =
        "$peerID-${packet.timestamp}-${packet.payload.contentHashCode()}"

    private fun recordTrust(packet: MeshLinkPacket, peerID: String, trust: TrustState) {
        recentTrust[trustKey(packet, peerID)] = trust
    }

    /**
     * Trust verdict recorded during validation for this packet.
     *
     * Falls back to re-evaluating if the entry has aged out of the cache, so a verdict is always
     * available rather than silently defaulting to "verified".
     */
    fun trustFor(packet: MeshLinkPacket, peerID: String): TrustState =
        recentTrust[trustKey(packet, peerID)] ?: evaluateTrust(packet, peerID)

    /**
     * Classify how much we can trust a packet, rather than just accept/reject.
     *
     * Packet types without a signature requirement (handshakes, fragments, NOISE_ENCRYPTED — whose
     * authenticity comes from the Noise session itself) report [TrustState.Verified].
     */
    fun evaluateTrust(packet: MeshLinkPacket, peerID: String): TrustState {
        try {
            if (MessageType.fromValue(packet.type) !in SIGNED_PACKET_TYPES) {
                return TrustState.Verified
            }

            if (packet.signature == null) {
                Log.w(TAG, "❌ Signature check for $peerID: NO_SIGNATURE (packet type ${packet.type})")
                return TrustState.Unsigned
            }

            // Resolve the signing key. ANNOUNCE carries its own (trust-on-first-use); everything
            // else must match the key we already learned from that peer's announce.
            val signingPublicKey: ByteArray? =
                if (MessageType.fromValue(packet.type) == MessageType.ANNOUNCE) {
                    try {
                        com.MeshLink.android.model.IdentityAnnouncement
                            .decode(packet.payload)?.signingPublicKey
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to decode announcement for key extraction: ${e.message}")
                        null
                    }
                } else {
                    delegate?.getPeerInfo(peerID)?.signingPublicKey
                }

            if (signingPublicKey == null) {
                Log.w(TAG, "❌ Signature check for $peerID: NO_SIGNING_KEY_AVAILABLE (type ${packet.type})")
                return TrustState.NotApproved
            }

            val packetDataForSigning = packet.toBinaryDataForSigning()
            if (packetDataForSigning == null) {
                Log.w(TAG, "❌ Signature check for $peerID: ENCODING_ERROR (type ${packet.type})")
                return TrustState.Failed
            }

            val isSignatureValid = encryptionService.verifyEd25519Signature(
                packet.signature!!,
                packetDataForSigning,
                signingPublicKey
            )

            return if (isSignatureValid) {
                TrustState.Verified
            } else {
                Log.w(TAG, "❌ Signature INVALID for $peerID (type ${packet.type})")
                TrustState.Failed
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Signature verification error for $peerID: ${e.message}")
            return TrustState.Failed
        }
    }
    
    /**
     * Handle Noise handshake packet - SIMPLIFIED iOS-compatible version
     * Single handshake type with automatic response handling
     */
    suspend fun handleNoiseHandshake(routed: RoutedPacket): Boolean {
        val packet = routed.packet
        val peerID = routed.peerID ?: "unknown"

        // Skip handshakes not addressed to us
        if (packet.recipientID?.toHexString() != myPeerID) {
            Log.d(TAG, "Skipping handshake not addressed to us: $peerID")
            return false
        }
            
        // Skip our own handshake messages
        if (peerID == myPeerID) return false

        // If we already have an established session but the peer is initiating a new handshake,
        // drop the existing session so we can re-establish cleanly.
        var forcedRehandshake = false
        if (encryptionService.hasEstablishedSession(peerID)) {
            Log.d(TAG, "Received new Noise handshake from $peerID with an existing session. Dropping old session to re-handshake.")
            try {
                encryptionService.removePeer(peerID)
                forcedRehandshake = true
            } catch (e: Exception) {
                Log.w(TAG, "Failed to remove existing Noise session for $peerID: ${e.message}")
            }
        }
        
        if (packet.payload.isEmpty()) {
            Log.w(TAG, "Noise handshake packet has empty payload")
            return false
        }
        
        // Prevent duplicate handshake processing
        val exchangeKey = "$peerID-${packet.payload.sliceArray(0 until minOf(16, packet.payload.size)).contentHashCode()}"
        
        if (!forcedRehandshake && processedKeyExchanges.contains(exchangeKey)) {
            Log.d(TAG, "Already processed handshake: $exchangeKey")
            return false
        }
        Log.d(TAG, "Processing Noise handshake from $peerID (${packet.payload.size} bytes)")
        processedKeyExchanges.add(exchangeKey)
        
        try {
            // Process the Noise handshake through the updated EncryptionService
            val response = encryptionService.processHandshakeMessage(packet.payload, peerID)
            
            if (response != null) {
                Log.d(TAG, "Successfully processed Noise handshake from $peerID, sending response")
                // Send handshake response through delegate
                delegate?.sendHandshakeResponse(peerID, response)
            }
            // Check if session is now established (handshake complete)
            if (encryptionService.hasEstablishedSession(peerID)) {
                Log.d(TAG, "✅ Noise handshake completed with $peerID")
                delegate?.onKeyExchangeCompleted(peerID, packet.payload)
            }
            return true

            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to process Noise handshake from $peerID: ${e.message}")
            return false
        }
    }

    /**
     * Verify packet signature
     */
    fun verifySignature(packet: MeshLinkPacket, peerID: String): Boolean {
        return packet.signature?.let { signature ->
            try {
                val isValid = encryptionService.verify(signature, packet.payload, peerID)
                if (!isValid) {
                    Log.w(TAG, "Invalid signature for packet from $peerID")
                }
                isValid
            } catch (e: Exception) {
                Log.e(TAG, "Failed to verify signature from $peerID: ${e.message}")
                false
            }
        } ?: true // No signature means verification passes
    }
    
    /**
     * Sign packet payload
     */
    fun signPacket(payload: ByteArray): ByteArray? {
        return try {
            encryptionService.sign(payload)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to sign packet: ${e.message}")
            null
        }
    }
    
    /**
     * Encrypt payload for specific peer
     */
    fun encryptForPeer(data: ByteArray, recipientPeerID: String): ByteArray? {
        return try {
            encryptionService.encrypt(data, recipientPeerID)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to encrypt for $recipientPeerID: ${e.message}")
            null
        }
    }
    
    /**
     * Decrypt payload from specific peer
     */
    fun decryptFromPeer(encryptedData: ByteArray, senderPeerID: String): ByteArray? {
        return try {
            encryptionService.decrypt(encryptedData, senderPeerID)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to decrypt from $senderPeerID: ${e.message}")
            null
        }
    }
    
    /**
     * Get combined public key data for key exchange
     */
    fun getCombinedPublicKeyData(): ByteArray {
        return encryptionService.getCombinedPublicKeyData()
    }
    
    /**
     * Generate message ID for duplicate detection
     */
    private fun generateMessageID(packet: MeshLinkPacket, peerID: String): String {
        return when (MessageType.fromValue(packet.type)) {
            MessageType.FRAGMENT -> {
                // For fragments, include the payload hash to distinguish different fragments
                "${packet.timestamp}-$peerID-${packet.type}-${packet.payload.contentHashCode()}"
            }
            MessageType.VOICE_STREAM -> {
                // Keyed on the ORIGINATOR plus frame sequence, not the relaying neighbour.
                //
                // Every other type keys on peerID, which is whoever handed us the packet. For voice
                // that's wrong: mesh flooding delivers the same frame via several neighbours, each
                // producing a different ID, so no copy is recognised as a duplicate and every one
                // gets played and relayed. At 25 frames a second that multiplication both stutters
                // the audio and saturates BLE.
                val origin = packet.senderID.joinToString("") { "%02x".format(it) }
                val seq = if (packet.payload.size > 5) {
                    (packet.payload[4].toInt() and 0xFF) or ((packet.payload[5].toInt() and 0xFF) shl 8)
                } else {
                    packet.payload.contentHashCode()
                }
                "voice-$origin-$seq"
            }
            else -> {
                // For other messages, use a truncated payload hash
                val payloadHash = packet.payload.sliceArray(0 until minOf(64, packet.payload.size)).contentHashCode()
                "${packet.timestamp}-$peerID-$payloadHash"
            }
        }
    }
    
    /**
     * Check if we have encryption keys for a peer
     */
    fun hasKeysForPeer(peerID: String): Boolean {
        return encryptionService.hasEstablishedSession(peerID)
    }
    
    /**
     * Get debug information
     */
    fun getDebugInfo(): String {
        return buildString {
            appendLine("=== Security Manager Debug Info ===")
            appendLine("Processed Messages: ${processedMessages.size}")
            appendLine("Processed Key Exchanges: ${processedKeyExchanges.size}")
            appendLine("Message Timestamps: ${messageTimestamps.size}")
            
            if (processedKeyExchanges.isNotEmpty()) {
                appendLine("Key Exchange History:")
                processedKeyExchanges.take(10).forEach { exchange ->
                    appendLine("  - $exchange")
                }
                if (processedKeyExchanges.size > 10) {
                    appendLine("  ... and ${processedKeyExchanges.size - 10} more")
                }
            }
        }
    }
    
    /**
     * Start periodic cleanup
     */
    private fun startPeriodicCleanup() {
        managerScope.launch {
            while (isActive) {
                delay(CLEANUP_INTERVAL)
                cleanupOldData()
            }
        }
    }
    
    /**
     * Clean up old processed messages and timestamps
     */
    private fun cleanupOldData() {
        val cutoffTime = System.currentTimeMillis() - MESSAGE_TIMEOUT
        var removedCount = 0
        
        // Clean up old message timestamps and corresponding processed messages
        val messagesToRemove = messageTimestamps.entries.filter { (_, timestamp) ->
            timestamp < cutoffTime
        }.map { it.key }
        
        messagesToRemove.forEach { messageId ->
            messageTimestamps.remove(messageId)
            if (processedMessages.remove(messageId)) {
                removedCount++
            }
        }
        
        // Limit the size of processed messages set
        if (processedMessages.size > MAX_PROCESSED_MESSAGES) {
            val excess = processedMessages.size - MAX_PROCESSED_MESSAGES
            val toRemove = processedMessages.take(excess)
            processedMessages.removeAll(toRemove.toSet())
            removeFromMessageTimestamps(toRemove)
            removedCount += excess
        }
        
        // Limit the size of processed key exchanges set
        if (processedKeyExchanges.size > MAX_PROCESSED_KEY_EXCHANGES) {
            val excess = processedKeyExchanges.size - MAX_PROCESSED_KEY_EXCHANGES
            val toRemove = processedKeyExchanges.take(excess)
            processedKeyExchanges.removeAll(toRemove.toSet())
        }
        
        if (removedCount > 0) {
            Log.d(TAG, "Cleaned up $removedCount old processed messages")
        }
    }
    
    /**
     * Helper to remove entries from messageTimestamps
     */
    private fun removeFromMessageTimestamps(messageIds: List<String>) {
        messageIds.forEach { messageId ->
            messageTimestamps.remove(messageId)
        }
    }
    
    /**
     * Clear all security data
     */
    fun clearAllData() {
        processedMessages.clear()
        processedKeyExchanges.clear()
        messageTimestamps.clear()
    }
    
    /**
     * Shutdown the manager
     */
    fun shutdown() {
        managerScope.cancel()
        clearAllData()
    }
}

/**
 * Delegate interface for security manager callbacks
 */
interface SecurityManagerDelegate {
    fun onKeyExchangeCompleted(peerID: String, peerPublicKeyData: ByteArray)
    fun sendHandshakeResponse(peerID: String, response: ByteArray)
    fun getPeerInfo(peerID: String): PeerInfo? // NEW: For signature verification
}
