# MeshLink Android — Complete Technology Stack & Architecture Documentation

## 🌐 1. Project Overview
**MeshLink** is a decentralized, off-grid communication application engineered for privacy, censorship resistance, and emergency disaster response. It combines peer-to-peer Bluetooth Low Energy (BLE) mesh networking, Tor/Arti onion routing, Nostr protocol relaying, and hybrid online/offline artificial intelligence to enable secure messaging without reliance on centralized servers or cellular infrastructure.

---

## 🛠️ 2. Core Android & Application Framework

| Component | Technology | Description |
|-----------|------------|-------------|
| **Primary Language** | **Kotlin 1.9** (JVM Target 1.8 / Java 21 Toolchain) | Modern, concise, null-safe language for robust Android development. |
| **UI Framework** | **Jetpack Compose (Material 3)** | Declarative UI toolkit with Material Design 3 components, glassmorphism, dynamic color themes, and custom animations. |
| **Architecture Pattern** | **MVVM with Clean Architecture** | Unidirectional data flow (UDF) isolating Presentation (Compose/ViewModels), Domain (UseCases/Services), and Data (Repositories/Mesh) layers. |
| **Asynchronous Engine** | **Kotlin Coroutines & Flow** | Asynchronous programming model using `StateFlow`, `SharedFlow`, and structured concurrency for reactive state management. |
| **Navigation** | **Jetpack Compose Navigation** | Type-safe single-activity navigation architecture managing screen transitions and bottom sheet dialogs. |
| **Annotation Processing**| **KSP (Kotlin Symbol Processing)** | Lightweight, fast symbol processor for Room database generation and code synthesis. |
| **Build System** | **Gradle (Kotlin DSL - `build.gradle.kts`)** | Modular Gradle build scripts with ABI splits (`arm64-v8a`, `universal`), dependency bundling, and custom AAPT rules. |

---

## 🧠 3. Artificial Intelligence & Machine Learning Stack

MeshLink features a **3-tier Hybrid Online/Offline Intelligence Architecture** specialized in **Disaster Response & Emergency Medical Advice**:

```
                       ┌────────────────────────────────┐
                       │   User AI Question / Prompt    │
                       └───────────────┬────────────────┘
                                       │
                    ┌──────────────────┴──────────────────┐
                    ▼                                     ▼
          [ ONLINE MODE ]                       [ OFFLINE MODE ]
    (Internet & API Key Available)         (No Internet / Off-Grid / Flight)
                    │                                     │
                    ▼                                     ▼
 ┌─────────────────────────────────────┐ ┌─────────────────────────────────────┐
 │ Tier 1: Gemini Cloud REST API       │ │ Tier 0: On-Device Gemma 3 1B        │
 │ • Model Fallback Chain:             │ │ • Google MediaPipe LLM Inference    │
 │   - gemini-2.5-flash                │ │ • 100% On-Device Local LLM           │
 │   - gemini-2.0-flash                │ │ • gemma3-1b-it-int4.task (554 MB)   │
 │   - gemini-1.5-flash                │ │ • Zero Internet / Zero Cloud        │
 │   - gemini-1.5-pro                  │ └──────────────────┬──────────────────┘
 │ • 429 Rate-Limit Exponential        │                    │
 │   Backoff Retry Engine              │                    │ (if model file missing)
 └──────────────────┬──────────────────┘                    │
                    │                                       ▼
                    │ (on 429 quota / API fail)  ┌─────────────────────────────────────┐
                    └──────────────────────────> │ Tier 2: Emergency Medical Keywords  │
                                                 │ • Rule-based triage & emergency tips│
                                                 └─────────────────────────────────────┘
```

### AI Component Breakdown

| Feature | Technology / Library | Details |
|---------|----------------------|---------|
| **Offline LLM Engine** | **Google MediaPipe LLM Inference** (`com.google.mediapipe:tasks-genai:0.10.27`) | Runs open-weights Large Language Models directly on Android CPU/GPU using LiteRT/TFLite backends. |
| **Offline LLM Model** | **Gemma 3 1B Quantized** (`gemma3-1b-it-int4.task` - 554.6 MB) | 4-bit INT4 quantized 1-Billion parameter model pre-packaged inside APK `assets/` with AAPT `noCompress` rule. |
| **Cloud AI Engine** | **Google Gemini REST API** | High-speed cloud model access via OkHttp HTTP client with JSON payload construction. |
| **Cloud Model Fallback Chain**| `gemini-2.5-flash` → `gemini-2.0-flash` → `gemini-1.5-flash` → `gemini-1.5-pro` | Sequential model fallback chain preventing service outage when individual models are degraded. |
| **Rate Limit Handling**| **Exponential Backoff (`429 Too Many Requests`)** | Automatic retry algorithm (`1s -> 2s -> 4s`) with graceful fallback to on-device Gemma 3 1B if quota is exhausted. |
| **On-Device System AI** | **Google AI Edge AICore** (`com.google.ai.edge.aicore:aicore:0.0.1-exp02`) | Interface for hardware-accelerated system-managed models on Pixel 8+ / Samsung S24 devices. |
| **Medical & Disaster Persona**| **Custom System Instruction Prompt** | Trained on START emergency triage protocol, tourniquet/hemorrhage control, CPR, burn dressing, water purification, wilderness survival, and mesh setup. |

---

## 📡 4. Decentralized Mesh Networking & Protocol Stack

MeshLink operates without cellular towers or internet service providers by utilizing a multi-transport mesh stack:

```
┌────────────────────────────────────────────────────────────────────────┐
│                        MeshLink Presentation Layer                     │
└──────────────────────────────────┬─────────────────────────────────────┘
                                   │
      ┌────────────────────────────┼────────────────────────────┐
      ▼                            ▼                            ▼
┌──────────────────────────┐ ┌──────────────────────────┐ ┌──────────────────────────┐
│  BLE Peer-to-Peer Mesh   │ │   Tor / Arti Anonymity   │ │ Nostr Relay Transport    │
│  - Nordic BLE Library    │ │   - Rust Arti Native Bridge│ │ - Nostr Protocol NIPs    │
│  - Custom Packet Router  │ │   - Onion Routing        │ │ - OkHttp WebSockets      │
│  - Multi-hop Store&Fwd   │ │   - Censorship Resistance│ │ - Global Relay Sync      │
└──────────────────────────┘ └──────────────────────────┘ └──────────────────────────┘
```

| Transport Layer | Technology | Function |
|-----------------|------------|----------|
| **Bluetooth LE Mesh** | **Nordic Semiconductor Android BLE Library** (`no.nordicsemi.android:ble:2.7.5`) | Low-level BLE advertising, scanning, GATT server/client connection management, and MTU optimization. |
| **BLE Packet Routing** | **Custom Multi-Hop Router** (`com.MeshLink.android.mesh`) | Store-and-forward message routing, deduplication, TTL hop counting, and peer discovery protocol. |
| **Onion Routing (Tor)** | **Arti (Tor in Rust)** via JNI Native Bridge | Pure Rust implementation of Tor protocol embedded natively for anonymous, censorship-resistant internet transport. |
| **Nostr Integration** | **Nostr Protocol (NIP-01, NIP-04, NIP-44)** | Decentralized relay networking for public broadcasting, direct encrypted messages, and key-based identity. |
| **WebSockets** | **OkHttp WebSocket Client** (`com.squareup.okhttp3:okhttp:4.12.0`) | Persistent event-driven WebSocket connections to global Nostr relays. |
| **Embedded Web Portal** | **NanoHTTPD** (`org.nanohttpd:nanohttpd:2.3.1`) | Embedded lightweight web server turning the Android device into a local Wi-Fi captive portal / browser interface. |

---

## 🔒 5. Cryptography & Security Stack

| Security Feature | Implementation / Standard | Purpose |
|------------------|---------------------------|---------|
| **Channel Encryption** | **Noise Protocol Framework** (`southernstorm.protocol`) | Diffie-Hellman key exchange providing Forward Secrecy and mutual peer authentication over untrusted mesh links. |
| **Asymmetric Keys** | **Curve25519 & Ed25519** | Elliptic-curve cryptography for public key identity generation and digital message signatures. |
| **Symmetric Cipher** | **ChaCha20-Poly1305** | High-performance authenticated encryption with associated data (AEAD) for mesh packet payloads. |
| **Hashing & HKDF** | **SHA-256 & HMAC-SHA256** | Key derivation, message integrity checks, and peer ID hashing. |
| **Secure Key Storage** | **Android KeyStore & EncryptedSharedPreferences** (`androidx.security:security-crypto:1.1.0-alpha06`) | Hardware-backed keystore encryption for private key storage using AES-256-GCM. |

---

## 💾 6. Storage & Data Persistence

| Subsystem | Technology | Usage |
|-----------|------------|-------|
| **Database** | **Android Room ORM** (`androidx.room:room-runtime:2.6.1`) | SQLite abstraction for persistent message history, peer identities, and channel metadata. |
| **File / Media Storage**| **Android Internal Files Dir & FileProvider** | Encrypted local file storage for voice notes, media attachments, and model assets. |
| **Asset Management** | **Android AssetManager + AAPT2 `noCompress`** | Zero-latency direct memory mapping for large binary AI model assets (`gemma3-1b-it-int4.task`). |

---

## 📷 7. Hardware, Sensors & Utilities

| Category | Component / Library | Usage |
|----------|---------------------|-------|
| **Camera & QR Scanner** | **CameraX** (`androidx.camera:camera-lifecycle`) + **ML Kit Barcode Scanning** (`com.google.mlkit:barcode-scanning`) | Fast camera frame capture and ML-powered QR code parsing for peer identity sharing. |
| **QR Code Generation** | **ZXing Core** (`com.google.zxing:core:3.5.3`) | Matrix QR code rendering for public key exchange. |
| **Location & Geohash** | **Google Play Services Location** (`com.google.android.gms:play-services-location:21.1.0`) | Coarse/Fine location acquisition for location-based geohash channel grouping. |
| **Voice Messaging** | **Android AudioRecord & Exif2** | Audio capture, compress, and EXIF orientation handling for media attachments. |
| **Background Execution**| **Android Foreground Service (`MeshForegroundService`)** + **WorkManager** (`androidx.work:work-runtime-ktx`) | Persistent background Bluetooth scanning, advertising, and notification maintenance complying with Android 14+ limits. |

---

## 📊 8. Build Specifications Summary

```groovy
android {
    namespace = "com.MeshLink.android"
    compileSdk = 35
    minSdk = 26 (Android 8.0 Oreo)
    targetSdk = 35 (Android 15)

    compileOptions {
        sourceCompatibility JavaVersion.VERSION_1_8
        targetCompatibility JavaVersion.VERSION_1_8
    }

    // Architecture Splits
    splits {
        abi {
            enable = true
            include("arm64-v8a", "x86_64", "armeabi-v7a", "x86")
            universalApk = true
        }
    }
}
```

---

## 📝 Document Summary
This document serves as the authoritative reference for the MeshLink Android architecture, tech stack dependencies, and system modules.
