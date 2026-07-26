package com.MeshLink.android.security

import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Proof that a device was running a genuine Akasha install when it enrolled.
 *
 * ## Why this shape
 *
 * An app can't prove its own integrity to a peer — anything baked into the APK can be extracted, so
 * a shared secret buys nothing. The only mechanism with real teeth is Play Integrity, which needs
 * the network. That's useless in the middle of a disaster, which is exactly when this app matters.
 *
 * So attestation and admission are split apart:
 *
 *  - **Once, with internet:** the device sends its identity key plus a Play Integrity token to the
 *    enrolment server. The server confirms it's a genuine, unmodified Akasha install and returns a
 *    certificate signed by the network root key.
 *  - **Forever after, offline:** peers verify that certificate against the root *public* key which
 *    is compiled into the app. No server, no WiFi, no internet.
 *
 * Embedding a public key is safe: it lets anyone verify a certificate and no one forge one. The
 * matching private key never leaves the enrolment server.
 *
 * Certificates ride along in ANNOUNCE packets as a TLV, so membership spreads through the mesh
 * itself — a device that rarely sees internet still learns who is legitimate from its neighbours.
 *
 * ## What this does and does not stop
 *
 * It raises the bar from "recompile the public source" to "defeat Play Integrity *and* extract a
 * hardware-backed private key". It is not unbreakable — no client-side check ever is — and it is
 * deliberately not the only defence: signatures still bind every packet to its sender, so even an
 * admitted device stays accountable for what it sends.
 */
data class MembershipCertificate(
    /** Ed25519 identity (signing) public key of the enrolled device, 32 bytes. */
    val devicePublicKey: ByteArray,
    /** Epoch millis the certificate was issued. */
    val issuedAt: Long,
    /** Epoch millis the certificate stops being valid. */
    val expiresAt: Long,
    /** Ed25519 signature over [signedBody], made with the network root private key. */
    val signature: ByteArray,
) {

    /** The exact bytes the root key signs — order matters and must match the server. */
    fun signedBody(): ByteArray = buildSignedBody(devicePublicKey, issuedAt, expiresAt)

    fun isExpired(now: Long = System.currentTimeMillis()): Boolean = now > expiresAt

    /** Serialise as a TLV chunk suitable for appending to an ANNOUNCE payload. */
    fun encodeTlv(): ByteArray {
        val body = signedBody()
        val value = body + signature
        if (value.size > 255) {
            Log.w(TAG, "Certificate too large for a single-byte TLV length: ${value.size}")
            return ByteArray(0)
        }
        return byteArrayOf(TLV_TYPE.toByte(), value.size.toByte()) + value
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return false
        if (other !is MembershipCertificate) return false
        return devicePublicKey.contentEquals(other.devicePublicKey) &&
            issuedAt == other.issuedAt &&
            expiresAt == other.expiresAt &&
            signature.contentEquals(other.signature)
    }

    override fun hashCode(): Int {
        var result = devicePublicKey.contentHashCode()
        result = 31 * result + issuedAt.hashCode()
        result = 31 * result + expiresAt.hashCode()
        result = 31 * result + signature.contentHashCode()
        return result
    }

    companion object {
        private const val TAG = "MembershipCertificate"

        /** TLV type for a membership certificate inside an ANNOUNCE payload. */
        const val TLV_TYPE: UByte = 0x05u

        private const val KEY_LEN = 32
        private const val SIG_LEN = 64
        private const val BODY_LEN = KEY_LEN + 8 + 8 // key + issuedAt + expiresAt
        private const val TOTAL_LEN = BODY_LEN + SIG_LEN

        fun buildSignedBody(devicePublicKey: ByteArray, issuedAt: Long, expiresAt: Long): ByteArray =
            ByteBuffer.allocate(BODY_LEN).order(ByteOrder.BIG_ENDIAN).apply {
                put(devicePublicKey.copyOf(KEY_LEN))
                putLong(issuedAt)
                putLong(expiresAt)
            }.array()

        /** Parse from raw TLV value bytes (body + signature). */
        fun fromValue(value: ByteArray): MembershipCertificate? {
            if (value.size < TOTAL_LEN) {
                Log.w(TAG, "Certificate value too short: ${value.size} < $TOTAL_LEN")
                return null
            }
            return try {
                val buf = ByteBuffer.wrap(value).order(ByteOrder.BIG_ENDIAN)
                val key = ByteArray(KEY_LEN).also { buf.get(it) }
                val issued = buf.long
                val expires = buf.long
                val sig = ByteArray(SIG_LEN).also { buf.get(it) }
                MembershipCertificate(key, issued, expires, sig)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to parse certificate: ${e.message}")
                null
            }
        }

        /**
         * Scan a TLV-encoded ANNOUNCE payload for a certificate.
         * Returns null when absent — which is the normal case for older clients.
         */
        fun fromAnnouncePayload(payload: ByteArray): MembershipCertificate? {
            var offset = 0
            while (offset + 2 <= payload.size) {
                val type = payload[offset].toUByte()
                val len = payload[offset + 1].toUByte().toInt()
                offset += 2
                if (offset + len > payload.size) break
                val value = payload.sliceArray(offset until offset + len)
                offset += len
                if (type == TLV_TYPE) return fromValue(value)
            }
            return null
        }
    }
}
