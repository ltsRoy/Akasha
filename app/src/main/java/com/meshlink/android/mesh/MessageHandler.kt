package com.MeshLink.android.mesh

import android.util.Log
import com.MeshLink.android.model.MeshLinkMessage
import com.MeshLink.android.model.MeshMessageType
import com.MeshLink.android.model.MeshLinkMessageType
import com.MeshLink.android.model.IdentityAnnouncement
import com.MeshLink.android.model.RoutedPacket
import com.MeshLink.android.protocol.MeshLinkPacket
import com.MeshLink.android.protocol.MessageType
import com.MeshLink.android.util.toHexString
import kotlinx.coroutines.*
import java.util.*
import kotlin.random.Random

/**
 * Handles processing of different message types
 * Extracted from BluetoothMeshService for better separation of concerns
 */
class MessageHandler(private val myPeerID: String, private val appContext: android.content.Context) {
    
    companion object {
        private const val TAG = "MessageHandler"
    }
    
    // Delegate for callbacks
    var delegate: MessageHandlerDelegate? = null
    
    // Reference to PacketProcessor for recursive packet handling
    var packetProcessor: PacketProcessor? = null
    
    // Coroutines
    private val handlerScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    /**
     * Handle Noise encrypted transport message - SIMPLIFIED iOS-compatible version
     * Uses NoisePayloadType system exactly like iOS SimplifiedBluetoothService
     */
    suspend fun handleNoiseEncrypted(routed: RoutedPacket) {
        val packet = routed.packet
        val peerID = routed.peerID ?: "unknown"
        
        Log.d(TAG, "Processing Noise encrypted message from $peerID (${packet.payload.size} bytes)")
        
        // Skip our own messages
        if (peerID == myPeerID) return
        
        // Check if this message is for us
        val recipientID = packet.recipientID?.toHexString()
        if (recipientID != myPeerID) {
            Log.d(TAG, "🔐 Encrypted message not for me (for $recipientID, I am $myPeerID)")
            return
        }
        
        try {
            // Decrypt the message using the Noise service
            val decryptedData = delegate?.decryptFromPeer(packet.payload, peerID)
            if (decryptedData == null) {
                Log.w(TAG, "Failed to decrypt Noise message from $peerID - may need handshake")
                return
            }
            
            if (decryptedData.isEmpty()) {
                Log.w(TAG, "Decrypted data is empty from $peerID")
                return
            }
            
            // NEW: Use NoisePayload system exactly like iOS
            val noisePayload = com.MeshLink.android.model.NoisePayload.decode(decryptedData)
            if (noisePayload == null) {
                Log.w(TAG, "Failed to parse NoisePayload from $peerID")
                return
            }
            
            Log.d(TAG, "🔓 Decrypted NoisePayload type ${noisePayload.type} from $peerID")
            
            when (noisePayload.type) {
                com.MeshLink.android.model.NoisePayloadType.PRIVATE_MESSAGE -> {
                    // Decode TLV private message exactly like iOS
                    val privateMessage = com.MeshLink.android.model.PrivateMessagePacket.decode(noisePayload.data)
                    if (privateMessage != null) {
                        Log.d(TAG, "🔓 Decrypted TLV PM from $peerID: ${privateMessage.content.take(30)}...")

                        // Handle favorite/unfavorite notifications embedded as PMs
                        val pmContent = privateMessage.content
                        if (pmContent.startsWith("[FAVORITED]") || pmContent.startsWith("[UNFAVORITED]")) {
                            handleFavoriteNotificationFromMesh(pmContent, peerID)
                            // Acknowledge delivery for UX parity
                            sendDeliveryAck(privateMessage.messageID, peerID)
                            return
                        }
                        
                        // Create MeshLinkMessage - preserve source packet timestamp
                        var isSos = pmContent.startsWith("🆘")
                        val isLocation = pmContent.startsWith("📍")
                        
                        if (!isSos && !isLocation) {
                            isSos = com.MeshLink.android.ai.GeminiManager.analyzeMessageForSOS(pmContent)
                        }
                        var lat: Double? = null
                        var lng: Double? = null
                        if (isLocation) {
                            try {
                                val coords = pmContent.substringAfter("📍 Location Shared:").split(",")
                                lat = coords[0].trim().toDoubleOrNull()
                                lng = coords[1].trim().toDoubleOrNull()
                            } catch (_: Exception) {}
                        }
                        val message = MeshLinkMessage(
                            id = privateMessage.messageID,
                            sender = delegate?.getPeerNickname(peerID) ?: "Unknown",
                            content = pmContent,
                            timestamp = java.util.Date(packet.timestamp.toLong()),
                            isRelay = false,
                            originalSender = null,
                            isPrivate = true,
                            recipientNickname = delegate?.getMyNickname(),
                            senderPeerID = peerID,
                            mentions = null, // TODO: Parse mentions if needed
                            meshMessageType = when {
                                isSos -> MeshMessageType.SOS
                                isLocation -> MeshMessageType.LOCATION
                                else -> MeshMessageType.TEXT
                            },
                            sosSenderId = if (isSos) (delegate?.getPeerNickname(peerID) ?: "Unknown") else null,
                            latitude = lat,
                            longitude = lng,
                            // Successful Noise decryption is itself proof of origin: only the holder
                            // of the session keys could have produced this ciphertext.
                            trustState = com.MeshLink.android.model.TrustState.Verified,
                            senderPublicKeyHex = delegate?.getPeerInfo(peerID)?.signingPublicKey
                                ?.joinToString("") { "%02x".format(it) }
                        )
                        
                        // Notify delegate
                        delegate?.onMessageReceived(message)
                        
                        // Send delivery ACK exactly like iOS
                        sendDeliveryAck(privateMessage.messageID, peerID)
                    }
                }
                
                com.MeshLink.android.model.NoisePayloadType.FILE_TRANSFER -> {
                    // Handle encrypted file transfer; generate unique message ID
                    val file = com.MeshLink.android.model.MeshLinkFilePacket.decode(noisePayload.data)
                    if (file != null) {
                        Log.d(TAG, "🔓 Decrypted encrypted file from $peerID: name='${file.fileName}', size=${file.fileSize}, mime='${file.mimeType}'")
                        val uniqueMsgId = java.util.UUID.randomUUID().toString().uppercase()
                        val savedPath = com.MeshLink.android.features.file.FileUtils.saveIncomingFile(appContext, file)
                        val message = MeshLinkMessage(
                            id = uniqueMsgId,
                            sender = delegate?.getPeerNickname(peerID) ?: "Unknown",
                            content = savedPath,
                            type = com.MeshLink.android.features.file.FileUtils.messageTypeForMime(file.mimeType),
                            timestamp = java.util.Date(packet.timestamp.toLong()),
                            isRelay = false,
                            isPrivate = true,
                            recipientNickname = delegate?.getMyNickname(),
                            senderPeerID = peerID,
                            // Noise AEAD already authenticates the sender for encrypted media.
                            trustState = com.MeshLink.android.model.TrustState.Verified,
                            senderPublicKeyHex = delegate?.getPeerInfo(peerID)?.signingPublicKey
                                ?.joinToString("") { "%02x".format(it) },
                            isSensitiveMedia = isExplicitMedia(savedPath, file.mimeType)
                        )

                        Log.d(TAG, "📄 Saved encrypted incoming file to $savedPath (msgId=$uniqueMsgId)")
                        delegate?.onMessageReceived(message)

                        // Send delivery ACK with generated message ID
                        sendDeliveryAck(uniqueMsgId, peerID)
                    } else {
                        Log.w(TAG, "⚠️ Failed to decode encrypted file transfer from $peerID")
                    }
                }
                
                com.MeshLink.android.model.NoisePayloadType.DELIVERED -> {
                    // Handle delivery ACK exactly like iOS
                    val messageID = String(noisePayload.data, Charsets.UTF_8)
                    Log.d(TAG, "📬 Delivery ACK received from $peerID for message $messageID")
                    
                    // Simplified: Call delegate with messageID and peerID directly
                    delegate?.onDeliveryAckReceived(messageID, peerID)
                }
                
                com.MeshLink.android.model.NoisePayloadType.READ_RECEIPT -> {
                    // Handle read receipt exactly like iOS
                    val messageID = String(noisePayload.data, Charsets.UTF_8)
                    Log.d(TAG, "👁️ Read receipt received from $peerID for message $messageID")
                    
                    // Simplified: Call delegate with messageID and peerID directly
                    delegate?.onReadReceiptReceived(messageID, peerID)
                }
                com.MeshLink.android.model.NoisePayloadType.VERIFY_CHALLENGE -> {
                    Log.d(TAG, "🔐 Verify challenge received from $peerID (${noisePayload.data.size} bytes)")
                    delegate?.onVerifyChallengeReceived(peerID, noisePayload.data, packet.timestamp.toLong())
                }
                com.MeshLink.android.model.NoisePayloadType.VERIFY_RESPONSE -> {
                    Log.d(TAG, "🔐 Verify response received from $peerID (${noisePayload.data.size} bytes)")
                    delegate?.onVerifyResponseReceived(peerID, noisePayload.data, packet.timestamp.toLong())
                }
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Error processing Noise encrypted message from $peerID: ${e.message}")
        }
    }
    
    /**
     * Send delivery ACK for a received private message - exactly like iOS
     */
    private suspend fun sendDeliveryAck(messageID: String, senderPeerID: String) {
        try {
            // Create ACK payload: [type byte] + [message ID] - exactly like iOS
            val ackPayload = com.MeshLink.android.model.NoisePayload(
                type = com.MeshLink.android.model.NoisePayloadType.DELIVERED,
                data = messageID.toByteArray(Charsets.UTF_8)
            )
            
            // Encrypt the payload
            val encryptedPayload = delegate?.encryptForPeer(ackPayload.encode(), senderPeerID)
            if (encryptedPayload == null) {
                Log.w(TAG, "Failed to encrypt delivery ACK for $senderPeerID")
                return
            }
            
            // Create NOISE_ENCRYPTED packet exactly like iOS
                val packet = MeshLinkPacket(
                    version = 1u,
                    type = MessageType.NOISE_ENCRYPTED.value,
                    senderID = hexStringToByteArray(myPeerID),
                    recipientID = hexStringToByteArray(senderPeerID),
                    timestamp = System.currentTimeMillis().toULong(),
                    payload = encryptedPayload,
                    signature = null,
                    ttl = com.MeshLink.android.util.AppConstants.MESSAGE_TTL_HOPS // Same TTL as iOS messageTTL
                )
            
            delegate?.sendPacket(packet)
            Log.d(TAG, "📤 Sent delivery ACK to $senderPeerID for message $messageID")
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send delivery ACK to $senderPeerID: ${e.message}")
        }
    }
    
    /**
     * Handle announce message with TLV decoding and signature verification - exactly like iOS
     */
    suspend fun handleAnnounce(routed: RoutedPacket): Boolean {
        val packet = routed.packet
        val peerID = routed.peerID ?: "unknown"

        if (peerID == myPeerID) return false

        // Ignore stale announcements older than STALE_PEER_TIMEOUT
        val now = System.currentTimeMillis()
        val age = now - packet.timestamp.toLong()
        if (age > com.MeshLink.android.util.AppConstants.Mesh.STALE_PEER_TIMEOUT_MS) {
            Log.w(TAG, "Ignoring stale ANNOUNCE from ${peerID.take(8)} (age=${age}ms > ${com.MeshLink.android.util.AppConstants.Mesh.STALE_PEER_TIMEOUT_MS}ms)")
            return false
        }
        
        // Try to decode as iOS-compatible IdentityAnnouncement with TLV format
        val announcement = IdentityAnnouncement.decode(packet.payload)
        if (announcement == null) {
            Log.w(TAG, "Failed to decode announce from $peerID as iOS-compatible TLV format")
            return false
        }
        
        // Verify packet signature using the announced signing public key
        var verified = false
        if (packet.signature != null) {
            // Verify that the packet was signed by the signing private key corresponding to the announced signing public key
            verified = delegate?.verifyEd25519Signature(packet.signature!!, packet.toBinaryDataForSigning()!!, announcement.signingPublicKey) ?: false
            if (!verified) {
                Log.w(TAG, "⚠️ Signature verification for announce failed ${peerID.take(8)}")
            }
        }

        // Check for existing peer with different noise public key
        // If existing peer has a different noise public key, do not consider this verified
        val existingPeer = delegate?.getPeerInfo(peerID)
        
        if (existingPeer != null && existingPeer.noisePublicKey != null && !existingPeer.noisePublicKey!!.contentEquals(announcement.noisePublicKey)) {
            Log.w(TAG, "⚠️ Announce key mismatch for ${peerID.take(8)}... — keeping unverified")
            verified = false
        }

        // Require verified announce; ignore otherwise (no backward compatibility)
        if (!verified) {
            Log.w(TAG, "❌ Ignoring unverified announce from ${peerID.take(8)}...")
            return false
        }
        
        // Successfully decoded TLV format exactly like iOS
        Log.d(TAG, "✅ Verified announce from $peerID: nickname=${announcement.nickname}, " +
                "noisePublicKey=${announcement.noisePublicKey.joinToString("") { "%02x".format(it) }.take(16)}..., " +
                "signingPublicKey=${announcement.signingPublicKey.joinToString("") { "%02x".format(it) }.take(16)}...")
        
        // Extract nickname and public keys from TLV data
        val nickname = announcement.nickname
        val noisePublicKey = announcement.noisePublicKey
        val signingPublicKey = announcement.signingPublicKey
        
        // Update peer info with verification status through new method
        val isFirstAnnounce = delegate?.updatePeerInfo(
            peerID = peerID,
            nickname = nickname,
            noisePublicKey = noisePublicKey,
            signingPublicKey = signingPublicKey,
            isVerified = true
        ) ?: false

        // Update peer ID binding with noise public key for identity management
        delegate?.updatePeerIDBinding(
            newPeerID = peerID,
            nickname = nickname,
            publicKey = noisePublicKey,
            previousPeerID = null
        )
        
        // Update mesh graph from gossip neighbors (only if TLV present)
        try {
            val neighborsOrNull = com.MeshLink.android.services.meshgraph.GossipTLV.decodeNeighborsFromAnnouncementPayload(packet.payload)
            com.MeshLink.android.services.meshgraph.MeshGraphService.getInstance()
                .updateFromAnnouncement(peerID, nickname, neighborsOrNull, packet.timestamp)
        } catch (_: Exception) { }

        Log.d(TAG, "✅ Processed verified TLV announce: stored identity for $peerID")
        return isFirstAnnounce
    }
    
    /**
     * Handle Noise handshake - SIMPLIFIED iOS-compatible version
     * Single handshake type (0x10) with response determined by payload analysis
     */
    suspend fun handleNoiseHandshake(routed: RoutedPacket) {
        val packet = routed.packet
        val peerID = routed.peerID ?: "unknown"
        
        Log.d(TAG, "Processing Noise handshake from $peerID (${packet.payload.size} bytes)")
        
        // Skip our own handshake messages
        if (peerID == myPeerID) return
        
        // Check if handshake is addressed to us
        val recipientID = packet.recipientID?.toHexString()
        if (recipientID != myPeerID) {
            Log.d(TAG, "Handshake not for me (for $recipientID, I am $myPeerID)")
            return
        }
        
        try {
            // Process handshake message through delegate (simplified approach)
            val response = delegate?.processNoiseHandshakeMessage(packet.payload, peerID)
            
            if (response != null) {
                Log.d(TAG, "Generated handshake response for $peerID (${response.size} bytes)")
                
                // Send response using same packet type (simplified iOS approach)
                val responsePacket = MeshLinkPacket(
                    version = 1u,
                    type = MessageType.NOISE_HANDSHAKE.value,
                    senderID = hexStringToByteArray(myPeerID),
                    recipientID = hexStringToByteArray(peerID),
                    timestamp = System.currentTimeMillis().toULong(),
                    payload = response,
                    signature = null,
                    ttl = com.MeshLink.android.util.AppConstants.MESSAGE_TTL_HOPS // Same TTL as iOS
                )
                
                delegate?.sendPacket(responsePacket)
                Log.d(TAG, "📤 Sent handshake response to $peerID")
            }
            
            // Check if session is now established
            val hasSession = delegate?.hasNoiseSession(peerID) ?: false
            if (hasSession) {
                Log.d(TAG, "✅ Noise session established with $peerID")
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to process Noise handshake from $peerID: ${e.message}")
        }
    }
    
    /**
     * Handle broadcast or private message
     */
    suspend fun handleMessage(routed: RoutedPacket) {
        val packet = routed.packet
        val peerID = routed.peerID ?: "unknown"
        if (peerID == myPeerID) return
        val senderNickname = delegate?.getPeerNickname(peerID)
        if (senderNickname != null) {
            Log.d(TAG, "Received message from $senderNickname")
            delegate?.updatePeerNickname(peerID, senderNickname)
        }
        
        val recipientID = packet.recipientID?.takeIf { !it.contentEquals(delegate?.getBroadcastRecipient()) }
        
        if (recipientID == null) {
            // BROADCAST MESSAGE
            handleBroadcastMessage(routed)
        } else if (recipientID.toHexString() == myPeerID) {
            // PRIVATE MESSAGE FOR US
            handlePrivateMessage(packet, peerID)
        }
        // Message relay is now handled by centralized PacketRelayManager
    }
    
    /**
     * Classify received media as explicit, so the UI can blur it.
     *
     * Runs on the receiving device rather than trusting the sender: a modified client would simply
     * skip a send-side check, so receive-side is the only placement that actually protects the
     * person looking at the screen.
     *
     * Images only — video and audio aren't inspected, and this is stated plainly rather than implied
     * to be covered. Returns false on any doubt; a classifier problem must never hide legitimate
     * media, which in this app can mean a wound photo someone needs triage help with.
     */
    private suspend fun isExplicitMedia(savedPath: String?, mimeType: String): Boolean {
        if (savedPath.isNullOrBlank()) return false
        if (!mimeType.lowercase().startsWith("image/")) return false

        return try {
            com.MeshLink.android.moderation.NsfwClassifier
                .scoreFile(appContext, savedPath)?.isExplicit == true
        } catch (e: Exception) {
            Log.w(TAG, "Media classification failed, allowing: ${e.message}")
            false
        }
    }

    /**
     * Handle broadcast message with verification enforcement
     */
    private suspend fun handleBroadcastMessage(routed: RoutedPacket) {
        val packet = routed.packet
        val peerID = routed.peerID ?: "unknown"
        
        val peerInfo = delegate?.getPeerInfo(peerID)

        // Trust verdict for this exact packet, carried onto the message so the UI can mark it.
        val packetTrust = delegate?.getPacketTrust(packet, peerID)
            ?: com.MeshLink.android.model.TrustState.Unknown

        // An unknown or unverified peer is downgraded rather than always dropped: in strict mode we
        // refuse it outright, but in lenient mode the message still gets through carrying a warning
        // marker. Silently discarding a stranger's message is the wrong default when that stranger
        // may be the person who needs help.
        val trust = if (peerInfo == null || !peerInfo.isVerifiedNickname) {
            if (com.MeshLink.android.security.SecurityPolicy.current.isStrict) {
                Log.w(TAG, "🚫 Dropping public message from unverified/unknown peer ${peerID.take(8)}... (strict mode)")
                return
            }
            Log.w(TAG, "⚠️ Public message from unverified/unknown peer ${peerID.take(8)}... — flagging as unapproved")
            com.MeshLink.android.model.TrustState.NotApproved
        } else {
            packetTrust
        }

        val senderKeyHex = peerInfo?.signingPublicKey?.joinToString("") { "%02x".format(it) }

        try {
            // Try file packet first (voice, image, etc.) and log outcome for FILE_TRANSFER
            val isFileTransfer = com.MeshLink.android.protocol.MessageType.fromValue(packet.type) == com.MeshLink.android.protocol.MessageType.FILE_TRANSFER
            val file = com.MeshLink.android.model.MeshLinkFilePacket.decode(packet.payload)
            if (file != null) {
                if (isFileTransfer) {
                    Log.d(TAG, "📥 FILE_TRANSFER decode success (broadcast): name='${file.fileName}', size=${file.fileSize}, mime='${file.mimeType}', from=${peerID.take(8)}")
                }
                val savedPath = com.MeshLink.android.features.file.FileUtils.saveIncomingFile(appContext, file)
                val message = MeshLinkMessage(
                    id = java.util.UUID.randomUUID().toString().uppercase(),
                    sender = delegate?.getPeerNickname(peerID) ?: "unknown",
                    content = savedPath,
                    type = com.MeshLink.android.features.file.FileUtils.messageTypeForMime(file.mimeType),
                    senderPeerID = peerID,
                    timestamp = Date(packet.timestamp.toLong()),
                    trustState = trust,
                    senderPublicKeyHex = senderKeyHex,
                    isSensitiveMedia = isExplicitMedia(savedPath, file.mimeType)
                )
                Log.d(TAG, "📄 Saved incoming file to $savedPath")
                delegate?.onMessageReceived(message)
                return
            } else if (isFileTransfer) {
                Log.w(TAG, "⚠️ FILE_TRANSFER decode failed (broadcast) from ${peerID.take(8)} payloadSize=${packet.payload.size}")
            }

            // Fallback: plain text
            val contentStr = String(packet.payload, Charsets.UTF_8)
            var isSos = contentStr.startsWith("🆘")
            val isLocation = contentStr.startsWith("📍")
            
            if (!isSos && !isLocation) {
                isSos = com.MeshLink.android.ai.GeminiManager.analyzeMessageForSOS(contentStr)
            }
            var lat: Double? = null
            var lng: Double? = null
            if (isLocation) {
                try {
                    val coords = contentStr.substringAfter("📍 Location Shared:").split(",")
                    lat = coords[0].trim().toDoubleOrNull()
                    lng = coords[1].trim().toDoubleOrNull()
                } catch (_: Exception) {}
            }
            val message = MeshLinkMessage(
                sender = delegate?.getPeerNickname(peerID) ?: "unknown",
                content = contentStr,
                senderPeerID = peerID,
                timestamp = Date(packet.timestamp.toLong()),
                meshMessageType = when {
                    isSos -> MeshMessageType.SOS
                    isLocation -> MeshMessageType.LOCATION
                    else -> MeshMessageType.TEXT
                },
                sosSenderId = if (isSos) (delegate?.getPeerNickname(peerID) ?: "unknown") else null,
                latitude = lat,
                longitude = lng,
                trustState = trust,
                senderPublicKeyHex = senderKeyHex
            )
            delegate?.onMessageReceived(message)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to process broadcast message: ${e.message}")
        }
    }
    
    /**
     * Handle (decrypted) private message addressed to us
     */
    private suspend fun handlePrivateMessage(packet: MeshLinkPacket, peerID: String) {
        try {
            // Verify signature if present
            if (packet.signature != null && !delegate?.verifySignature(packet, peerID)!!) {
                Log.w(TAG, "Invalid signature for private message from $peerID")
                return
            }

            val trust = delegate?.getPacketTrust(packet, peerID)
                ?: com.MeshLink.android.model.TrustState.Unknown
            val senderKeyHex = delegate?.getPeerInfo(peerID)?.signingPublicKey
                ?.joinToString("") { "%02x".format(it) }

            // Try file packet first (voice, image, etc.) and log outcome for FILE_TRANSFER
            val isFileTransfer = com.MeshLink.android.protocol.MessageType.fromValue(packet.type) == com.MeshLink.android.protocol.MessageType.FILE_TRANSFER
            val file = com.MeshLink.android.model.MeshLinkFilePacket.decode(packet.payload)
            if (file != null) {
                if (isFileTransfer) {
                    Log.d(TAG, "📥 FILE_TRANSFER decode success (private): name='${file.fileName}', size=${file.fileSize}, mime='${file.mimeType}', from=${peerID.take(8)}")
                }
                val savedPath = com.MeshLink.android.features.file.FileUtils.saveIncomingFile(appContext, file)
                val message = MeshLinkMessage(
                    id = java.util.UUID.randomUUID().toString().uppercase(),
                    sender = delegate?.getPeerNickname(peerID) ?: "unknown",
                    content = savedPath,
                    type = com.MeshLink.android.features.file.FileUtils.messageTypeForMime(file.mimeType),
                    senderPeerID = peerID,
                    timestamp = Date(packet.timestamp.toLong()),
                    isPrivate = true,
                    recipientNickname = delegate?.getMyNickname(),
                    trustState = trust,
                    senderPublicKeyHex = senderKeyHex,
                    isSensitiveMedia = isExplicitMedia(savedPath, file.mimeType)
                )
                Log.d(TAG, "📄 Saved incoming file to $savedPath")
                delegate?.onMessageReceived(message)
                return
            } else if (isFileTransfer) {
                Log.w(TAG, "⚠️ FILE_TRANSFER decode failed (private) from ${peerID.take(8)} payloadSize=${packet.payload.size}")
            }

            // Fallback: plain text
            val contentStr = String(packet.payload, Charsets.UTF_8)
            var isSos = contentStr.startsWith("🆘")
            val isLocation = contentStr.startsWith("📍")
            
            if (!isSos && !isLocation) {
                isSos = com.MeshLink.android.ai.GeminiManager.analyzeMessageForSOS(contentStr)
            }
            var lat: Double? = null
            var lng: Double? = null
            if (isLocation) {
                try {
                    val coords = contentStr.substringAfter("📍 Location Shared:").split(",")
                    lat = coords[0].trim().toDoubleOrNull()
                    lng = coords[1].trim().toDoubleOrNull()
                } catch (_: Exception) {}
            }
            val message = MeshLinkMessage(
                sender = delegate?.getPeerNickname(peerID) ?: "unknown",
                content = contentStr,
                senderPeerID = peerID,
                timestamp = Date(packet.timestamp.toLong()),
                meshMessageType = when {
                    isSos -> MeshMessageType.SOS
                    isLocation -> MeshMessageType.LOCATION
                    else -> MeshMessageType.TEXT
                },
                sosSenderId = if (isSos) (delegate?.getPeerNickname(peerID) ?: "unknown") else null,
                latitude = lat,
                longitude = lng,
                trustState = trust,
                senderPublicKeyHex = senderKeyHex
            )
            delegate?.onMessageReceived(message)

        } catch (e: Exception) {
            Log.e(TAG, "Failed to process private message from $peerID: ${e.message}")
        }
    }

    
    
    /**
     * Handle leave message
     */
    suspend fun handleLeave(routed: RoutedPacket) {
        val packet = routed.packet
        val peerID = routed.peerID ?: "unknown"
        val content = String(packet.payload, Charsets.UTF_8)
        
        if (content.startsWith("#")) {
            // Channel leave
            delegate?.onChannelLeave(content, peerID)
        } else {
            // Peer disconnect
            delegate?.removePeer(peerID)
        }
        
        // Leave message relay is now handled by centralized PacketRelayManager
    }
    
    /**
     * Get debug information
     */
    fun getDebugInfo(): String {
        return buildString {
            appendLine("=== Message Handler Debug Info ===")
            appendLine("Handler Scope Active: ${handlerScope.isActive}")
            appendLine("My Peer ID: $myPeerID")
        }
    }
    
    /**
     * Convert hex string peer ID to binary data (8 bytes) - same as iOS implementation
     */
    private fun hexStringToByteArray(hexString: String): ByteArray {
        val result = ByteArray(8) { 0 } // Initialize with zeros, exactly 8 bytes
        var tempID = hexString
        var index = 0
        
        while (tempID.length >= 2 && index < 8) {
            val hexByte = tempID.substring(0, 2)
            val byte = hexByte.toIntOrNull(16)?.toByte()
            if (byte != null) {
                result[index] = byte
            }
            tempID = tempID.substring(2)
            index++
        }
        
        return result
    }

    /**
     * Shutdown the handler
     */
    fun shutdown() {
        handlerScope.cancel()
    }

    /**
     * Handle favorite/unfavorite notification received over mesh as a private message.
     * Content format: "[FAVORITED]:npub..." or "[UNFAVORITED]:npub..."
     */
    private fun handleFavoriteNotificationFromMesh(content: String, fromPeerID: String) {
        try {
            val isFavorite = content.startsWith("[FAVORITED]")
            val npub = content.substringAfter(":", "").trim().takeIf { it.startsWith("npub1") }

            // Update mutual favorite status in persistence
            // Resolve full Noise key if available via delegate peer info
            val peerInfo = delegate?.getPeerInfo(fromPeerID)
            val noiseKey = peerInfo?.noisePublicKey
            if (noiseKey != null) {
                com.MeshLink.android.favorites.FavoritesPersistenceService.shared.updatePeerFavoritedUs(noiseKey, isFavorite)
                if (npub != null) {
                    // Index by noise key and current mesh peerID for fast Nostr routing
                    com.MeshLink.android.favorites.FavoritesPersistenceService.shared.updateNostrPublicKey(noiseKey, npub)
                    com.MeshLink.android.favorites.FavoritesPersistenceService.shared.updateNostrPublicKeyForPeerID(fromPeerID, npub)
                }

                // Determine iOS-style guidance text
                val rel = com.MeshLink.android.favorites.FavoritesPersistenceService.shared.getFavoriteStatus(noiseKey)
                val guidance = if (isFavorite) {
                    if (rel?.isFavorite == true) {
                        " — mutual! You can continue DMs via Nostr when out of mesh."
                    } else {
                        " — favorite back to continue DMs later."
                    }
                } else {
                    ". DMs over Nostr will pause unless you both favorite again."
                }

                // Emit system message via delegate callback
                val action = if (isFavorite) "favorited" else "unfavorited"
                val sys = com.MeshLink.android.model.MeshLinkMessage(
                    sender = "system",
                    content = "${peerInfo.nickname} $action you$guidance",
                    timestamp = java.util.Date(),
                    isRelay = false
                )
                delegate?.onMessageReceived(sys)
            }
        } catch (_: Exception) {
            // Best-effort; ignore errors
        }
    }
}

/**
 * Delegate interface for message handler callbacks
 */
interface MessageHandlerDelegate {
    // Peer management
    fun addOrUpdatePeer(peerID: String, nickname: String): Boolean
    fun removePeer(peerID: String)
    fun updatePeerNickname(peerID: String, nickname: String)
    fun getPeerNickname(peerID: String): String?
    fun getNetworkSize(): Int
    fun getMyNickname(): String?
    fun getPeerInfo(peerID: String): PeerInfo?
    fun updatePeerInfo(peerID: String, nickname: String, noisePublicKey: ByteArray, signingPublicKey: ByteArray, isVerified: Boolean): Boolean
    
    // Packet operations
    fun sendPacket(packet: MeshLinkPacket)
    fun relayPacket(routed: RoutedPacket)
    fun getBroadcastRecipient(): ByteArray
    
    // Cryptographic operations
    fun verifySignature(packet: MeshLinkPacket, peerID: String): Boolean

    /**
     * Trust verdict for a packet, reusing the result computed during validation so large media
     * payloads aren't signature-checked twice.
     */
    fun getPacketTrust(packet: MeshLinkPacket, peerID: String): com.MeshLink.android.model.TrustState
    fun encryptForPeer(data: ByteArray, recipientPeerID: String): ByteArray?
    fun decryptFromPeer(encryptedData: ByteArray, senderPeerID: String): ByteArray?
    fun verifyEd25519Signature(signature: ByteArray, data: ByteArray, publicKey: ByteArray): Boolean
    
    // Noise protocol operations
    fun hasNoiseSession(peerID: String): Boolean
    fun initiateNoiseHandshake(peerID: String)
    fun processNoiseHandshakeMessage(payload: ByteArray, peerID: String): ByteArray?
    fun updatePeerIDBinding(newPeerID: String, nickname: String,
                           publicKey: ByteArray, previousPeerID: String?)
    
    // Message operations
    fun decryptChannelMessage(encryptedContent: ByteArray, channel: String): String?

    // Callbacks
    fun onMessageReceived(message: MeshLinkMessage)
    fun onChannelLeave(channel: String, fromPeer: String)
    fun onDeliveryAckReceived(messageID: String, peerID: String)
    fun onReadReceiptReceived(messageID: String, peerID: String)
    fun onVerifyChallengeReceived(peerID: String, payload: ByteArray, timestampMs: Long)
    fun onVerifyResponseReceived(peerID: String, payload: ByteArray, timestampMs: Long)
}
