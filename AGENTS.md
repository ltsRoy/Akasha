# Akasha — Agent Guide

This document provides context, architectural insights, and development standards for AI agents working on the Akasha codebase.

## 1. Project Overview
**Akasha** is an offline-first emergency communication and knowledge platform. It utilizes mesh networking (Bluetooth LE), on-device AI (semantic retrieval + LLM), and a Ground Station server to provide messaging, medical/safety guidance, and facility search — all without requiring internet connectivity.

**Key Technologies:**
- **Language:** Kotlin (JVM Target 1.8)
- **UI Framework:** Jetpack Compose (Material 3)
- **Asynchronous:** Kotlin Coroutines & Flow
- **Networking:** Bluetooth Low Energy (BLE), Tor (Arti Rust bridge), OkHttp, Nostr
- **AI/ML:** ONNX Runtime (MiniLM embeddings), MediaPipe (Gemma 3 1B), TensorFlow Lite (NSFW)
- **Architecture:** MVVM with Clean Architecture principles
- **Build System:** Gradle (Kotlin DSL)
- **Ground Station:** FastAPI + Actian VectorAI DB (Docker)

## 2. Architecture & Directory Structure
The application follows a clean architecture pattern, heavily modularized by feature within the `app` module.

**Root Package:** `com.MeshLink.android` *(legacy package name — applicationId is `com.akasha.app`)*

| Directory | Purpose |
|-----------|---------|
| `ai/` | **AI Engines**: Aria, Gemma, ModelManager — tiered LLM fallback. |
| `ui/` | **Presentation Layer**: Jetpack Compose screens, themes, and ViewModels. |
| `service/` | **Core Service**: Contains `MeshForegroundService`, managing persistent background connectivity. |
| `mesh/` | **Mesh Networking**: Logic for peer discovery, advertising, multi-hop relay, and store-and-forward. |
| `protocol/` | **Wire Protocol**: Definitions of messages exchanged between peers. |
| `crypto/` | **Security**: Cryptographic primitives and key management. |
| `noise/` | **Encryption**: Implementation of the Noise Protocol Framework for secure channels. |
| `identity/` | **User Identity**: Management of user profiles and public/private keys. |
| `features/` | **App Features**: Sub-modules for `voice`, `file`, `media`, and `knowledge` handling. |
| `nostr/` | **Relay Integration**: Logic for Nostr protocol integration and relay management. |
| `geohash/` | **Location**: Geohash-based location channels. |
| `net/` | **Networking**: General network utilities and abstractions. |
| `india/` | **Regional**: India-specific emergency features (SOS, helplines). |

## 3. Key Components

### Three-Tier Knowledge System (100% Offline Capable)
1. **LOCAL** — On-device knowledge pack + embedded LLM (100% offline on single device)
2. **MESH** — BLE peer-to-peer relay queries across nearby phones (100% offline via Bluetooth LE)
3. **FULL** — Ground Station laptop over local Wi-Fi with Actian VectorAI DB (100% offline via LAN)

### Refusal Gate
The semantic retrieval system has a hard threshold (0.45). Off-topic queries are refused, and the LLM is *never invoked* for unvetted content. This is a critical safety invariant — do not bypass it.

### UI Layer (Jetpack Compose)
- **Activity**: Single-Activity architecture (`MainActivity.kt`).
- **Navigation**: Jetpack Compose Navigation.
- **State Management**: `ViewModel` exposing `StateFlow` to Composables.
- **Theme**: Custom theme definitions in `ui/theme`.

### Networking & Connectivity
- **MeshForegroundService**: The critical component that keeps the mesh network alive.
- **BLE Stack**: Located in `mesh/` and `net/`, handles Android Bluetooth interactions.
- **Tor/Arti**: Integrated via JNI for anonymous internet routing.

## 4. Development Standards

### Code Style
- **Kotlin**: Adhere to official Kotlin coding conventions.
- **Compose**: Use functional components. Hoist state to ViewModels where possible.
- **Coroutines**: Use `suspend` functions for all I/O operations. Strictly avoid blocking the main thread.
- **Naming**: Clear, descriptive names. Follow standard Android naming patterns.

### Testing
- **Unit Tests**: Located in `app/src/test/`. Use for business logic, protocols, and utility testing.
- **Instrumented Tests**: Located in `app/src/androidTest/`. Use for UI and permission integration testing.
- **Ground Station Tests**: `ground-station/test_app.py`, `ground-station/e2e_test.py`, `ground-station/poi/test_poi_data.py`

### Execution
- Unit: `./gradlew test`
- Instrumented: `./gradlew connectedAndroidTest`
- Ground Station: `cd ground-station && pytest`

## 5. Critical Constraints & Gotchas
1. **Permissions**: The app relies on dangerous runtime permissions (Location, Bluetooth, Audio). Always verify permission handling patterns before adding features.
2. **Hardware Dependency**: BLE features are difficult to emulate. Focus on robust error handling.
3. **Background Limits**: Network operations must be tied to `MeshForegroundService`.
4. **Embedding Parity**: On-device and server embeddings must produce identical scores. Any model change must pass the 0.99 parity gate.
5. **Package Name**: The Java package is `com.MeshLink.android` for historical reasons. The `applicationId` is `com.akasha.app`. Do not rename the package without a full migration plan.

## 6. Common Tasks
- **Build Debug APK**: `./gradlew assembleDebug`
- **Lint Check**: `./gradlew lint`
- **Clean Build**: `./gradlew clean`
- **Run Ground Station**: `cd ground-station && docker compose up -d --build`

---
*Note: This file is intended to assist AI agents in navigating and modifying the codebase efficiently. Always verify context by reading the actual files before making changes.*
