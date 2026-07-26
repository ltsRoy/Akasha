# Akasha Privacy Policy

*Last updated: 2026*

## Our Commitment

Akasha is designed with privacy as its foundation. We believe private emergency communication and knowledge retrieval are fundamental rights. This policy explains how Akasha protects your privacy.

## Summary

- **Zero Personal Data Collection** — We do not collect names, emails, or phone numbers.
- **Privacy-Preserving Architecture** — Local processing for BLE mesh, location geohashing, and on-device AI retrieval.
- **Hybrid Offline & Relay Capabilities** — Akasha offers multi-tier emergency communication:
  - **Bluetooth Mesh Chat**: Completely offline peer-to-peer communication using local Bluetooth connections without servers or internet.
  - **Geohash Channels**: Communicates with users in a geographic region over Nostr relays when connectivity is available.
- **No Telemetry or Tracking** — Zero analytics, tracking pixels, or remote monitoring.
- **Open Source** — Publicly verifiable codebase.

## Local Device Storage

1. **Identity Keys** 
   - Cryptographic keys generated on first launch.
   - Stored locally in device secure storage.
   - Never transmitted out of device boundary.

2. **Nickname**
   - User-chosen display name, stored strictly on your device.

3. **Local Message & Knowledge History**
   - Stored encrypted on your device.
   - Erasable at any time.

4. **Emergency Data Wipe**
   - Triple-tap logo trigger instantly sanitizes memory and local data stores.

## Cryptography & Encryption

All private messages use industry-standard end-to-end encryption:
- **X25519** for key exchange
- **AES-256-GCM** for message encryption
- **Ed25519** for digital signatures
- **Noise Protocol Handshake** for secure channels

## Location Permissions & Purpose

1. **Bluetooth Low Energy (BLE) Scanning**
   - Required by Android OS to discover nearby Bluetooth LE peers for off-grid mesh communications.
   - No location data is recorded or stored during this scan.

2. **Geohash Channel Functionality**
   - Converts coarse coordinates to an alphanumeric geohash region for channel subscription.
   - Precise GPS coordinates are never sent to external servers or peers.

## Contact & Repository

Akasha is an open source project:
- Repository: [https://github.com/ltsRoy/Akasha](https://github.com/ltsRoy/Akasha)

---

*This policy is released into the public domain under The Unlicense.*