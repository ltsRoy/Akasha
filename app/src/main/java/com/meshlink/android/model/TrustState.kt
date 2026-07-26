package com.MeshLink.android.model

/**
 * Whether a received message could be cryptographically trusted.
 *
 * Every outgoing packet is already signed with the sender's Ed25519 identity key
 * (`BluetoothMeshService.signPacketBeforeBroadcast`), and receivers already verify that signature.
 * Until now the result was only logged and thrown away — this type carries it up to the UI so a
 * person can see, per message, whether it really came from who it claims.
 */
enum class TrustState {
    /** Signature present, verified against the sender's announced identity key. */
    Verified,

    /** Signature present but did not verify — tampered in flight, or an impostor. */
    Failed,

    /** No signature at all. Older clients, or someone bypassing the protocol. */
    Unsigned,

    /** Sender's key isn't on the approved-device list (strict mode surfaces this). */
    NotApproved,

    /** Not evaluated — e.g. our own outgoing messages. */
    Unknown;

    /** True only when we can positively vouch for the sender. */
    val isTrusted: Boolean get() = this == Verified

    /** True when the user should be warned about this message. */
    val isSuspect: Boolean get() = this == Failed || this == Unsigned || this == NotApproved
}
