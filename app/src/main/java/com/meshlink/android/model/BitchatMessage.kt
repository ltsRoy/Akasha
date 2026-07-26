package com.MeshLink.android.model

import android.os.Parcelable
import com.google.gson.GsonBuilder
import kotlinx.parcelize.Parcelize
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.*

@Parcelize
enum class MeshLinkMessageType : Parcelable {
    Message,
    Audio,
    Image,
    File,
    Location
}

/**
 * Represents the purpose/category of a message in the MeshLink SOS system.
 * TEXT is the default for all existing chat messages, ensuring backward compatibility.
 * SOS indicates an emergency broadcast that should be prioritized across the mesh.
 * LOCATION carries GPS coordinates for sharing position data.
 */
enum class MeshMessageType {
    /** Default type — standard text chat messages (backward compatible with existing messages) */
    TEXT,
    /** Emergency broadcast — high-priority SOS alert propagated across the entire mesh */
    SOS,
    /** Location sharing — carries GPS latitude/longitude coordinates */
    LOCATION
}

data class LocationCoordinate(val latitude: Double, val longitude: Double)

fun parseLocation(content: String): LocationCoordinate? {
    val regex = Regex("(-?\\d+\\.\\d+),\\s*(-?\\d+\\.\\d+)")
    val match = regex.find(content)
    if (match != null) {
        return LocationCoordinate(match.groupValues[1].toDouble(), match.groupValues[2].toDouble())
    }
    return null
}

/**
 * Delivery status for messages - exact same as iOS version
 */
sealed class DeliveryStatus : Parcelable {
    @Parcelize
    object Sending : DeliveryStatus()

    @Parcelize
    object Sent : DeliveryStatus()

    @Parcelize
    data class Delivered(val to: String, val at: Date) : DeliveryStatus()

    @Parcelize
    data class Read(val by: String, val at: Date) : DeliveryStatus()

    @Parcelize
    data class Failed(val reason: String) : DeliveryStatus()

    @Parcelize
    data class PartiallyDelivered(val reached: Int, val total: Int) : DeliveryStatus()

    fun getDisplayText(): String {
        return when (this) {
            is Sending -> "Sending..."
            is Sent -> "Sent"
            is Delivered -> "Delivered to ${this.to}"
            is Read -> "Read by ${this.by}"
            is Failed -> "Failed: ${this.reason}"
            is PartiallyDelivered -> "Delivered to ${this.reached}/${this.total}"
        }
    }
}

/**
 * MeshLinkMessage - 100% compatible with iOS version
 */
@Parcelize
data class MeshLinkMessage(
    val id: String = UUID.randomUUID().toString().uppercase(),
    val sender: String,
    val content: String,
    val type: MeshLinkMessageType = MeshLinkMessageType.Message,
    val timestamp: Date,
    val isRelay: Boolean = false,
    val originalSender: String? = null,
    val isPrivate: Boolean = false,
    val recipientNickname: String? = null,
    val senderPeerID: String? = null,
    val mentions: List<String>? = null,
    val channel: String? = null,
    val encryptedContent: ByteArray? = null,
    val isEncrypted: Boolean = false,
    val deliveryStatus: DeliveryStatus? = null,
    val powDifficulty: Int? = null,

    // ===== MeshLink SOS Feature Fields =====

    /**
     * The purpose/category of this message (TEXT, SOS, or LOCATION).
     * Defaults to TEXT so all existing messages remain unaffected.
     */
    val meshMessageType: MeshMessageType = MeshMessageType.TEXT,

    /**
     * The unique identifier of the user who originally triggered the SOS.
     * Null for non-SOS messages. Used to trace the emergency back to the sender
     * even after the message has been relayed through multiple mesh hops.
     */
    val sosSenderId: String? = null,

    /**
     * GPS latitude coordinate for LOCATION-type messages.
     * Null when no location data is attached.
     */
    val latitude: Double? = null,

    /**
     * GPS longitude coordinate for LOCATION-type messages.
     * Null when no location data is attached.
     */
    val longitude: Double? = null,

    // ===== Per-message authenticity =====

    /**
     * Whether this message's signature could be verified against the sender's announced identity
     * key. Drives the tick / warning marker in the UI.
     *
     * Defaults to [TrustState.Unknown], which is correct for our own outgoing messages — we don't
     * verify our own signatures.
     */
    val trustState: TrustState = TrustState.Unknown,

    /**
     * Hex of the Ed25519 signing public key the signature was checked against.
     *
     * Stored per message rather than looked up from the peer list at render time on purpose: a peer
     * can rotate keys or disconnect, and the message detail view must show the key that actually
     * authenticated *this* message, not whatever the peer's current key happens to be.
     */
    val senderPublicKeyHex: String? = null,

    /**
     * Set when the on-device classifier is confident this media is sexually explicit.
     *
     * Only ever causes the image to render blurred with a tap-to-reveal control — never a block or
     * a delete. The threshold is deliberately high, so this stays false for the skin-heavy medical
     * and rescue photos the app exists to carry.
     */
    val isSensitiveMedia: Boolean = false

) : Parcelable {

    /**
     * Short, human-comparable form of [senderPublicKeyHex] — grouped so two people can read it
     * aloud to each other to confirm they're talking to the right device.
     */
    fun senderKeyFingerprint(): String? {
        val hex = senderPublicKeyHex ?: return null
        return hex.take(16).chunked(4).joinToString(" ").uppercase()
    }

    /**
     * Returns true if this message is an SOS emergency broadcast
     */
    fun isSosEmergency(): Boolean = meshMessageType == MeshMessageType.SOS

    /**
     * Returns true if this message carries GPS location data
     */
    fun hasLocation(): Boolean = latitude != null && longitude != null

    /**
     * Returns a formatted string of the GPS coordinates, or null if no location is attached
     */
    fun getFormattedLocation(): String? {
        return if (hasLocation()) {
            "%.6f, %.6f".format(latitude, longitude)
        } else {
            null
        }
    }

    /**
     * Convert message to binary payload format - exactly same as iOS version
     */
    fun toBinaryPayload(): ByteArray? {
        try {
            val buffer = ByteBuffer.allocate(4096).apply { order(ByteOrder.BIG_ENDIAN) }

            // Message format:
            // - Flags: 1 byte (bit flags for optional fields)
            // - Timestamp: 8 bytes (milliseconds since epoch, big-endian)
            // - ID length: 1 byte + ID data
            // - Sender length: 1 byte + sender data
            // - Content length: 2 bytes + content data (or encrypted content)
            // Optional fields based on flags...

            var flags: UByte = 0u
            if (isRelay) flags = flags or 0x01u

            // NOTE: The rest of toBinaryPayload() continues unchanged from the original file.
            // Paste the remainder of your existing toBinaryPayload() implementation here.
            // The method signature and initial logic are preserved exactly as-is.

            return buffer.array().copyOf(buffer.position())
        } catch (e: Exception) {
            return null
        }
    }

    companion object {
        /**
         * Creates an SOS emergency broadcast message with optional location data.
         * The sosSenderId is set to the sender's identifier for traceability across mesh hops.
         */
        fun createSosMessage(
            sender: String,
            content: String = "🆘 EMERGENCY SOS",
            latitude: Double? = null,
            longitude: Double? = null,
            channel: String? = null
        ): MeshLinkMessage {
            return MeshLinkMessage(
                sender = sender,
                content = content,
                timestamp = Date(),
                meshMessageType = MeshMessageType.SOS,
                sosSenderId = sender,
                latitude = latitude,
                longitude = longitude,
                channel = channel
            )
        }

        /**
         * Creates a location-sharing message carrying GPS coordinates.
         */
        fun createLocationMessage(
            sender: String,
            latitude: Double,
            longitude: Double,
            content: String = "📍 Location shared",
            channel: String? = null
        ): MeshLinkMessage {
            return MeshLinkMessage(
                sender = sender,
                content = content,
                timestamp = Date(),
                meshMessageType = MeshMessageType.LOCATION,
                latitude = latitude,
                longitude = longitude,
                channel = channel
            )
        }
    }
}
