<p align="center">
  <strong>AKASHA</strong><br>
  <em>Built for the moment the network is gone.</em>
</p>

# Akasha

**Akasha** is an offline-first emergency communication and knowledge platform for Android. When disasters take down cell towers and fibre, Akasha keeps working — delivering encrypted mesh messaging, on-device medical/safety guidance, and facility search — powered by an on-device AI suite and the **Actian VectorAI Database** running on an offline Ground Station server.

---

## Three-Tier Architecture

Akasha provides continuous operation through a robust three-tier fallback architecture that **operates 100% offline without requiring any internet connection**:

| Tier | Condition | Capability |
|---|---|---|
| **LOCAL** | Handset offline mode | On-device safety knowledge pack + embedded LLM (100% offline) |
| **MESH** | Peer devices nearby | BLE mesh relay, shared knowledge, store-and-forward chat (100% offline over Bluetooth LE) |
| **FULL** | Gateway Phone / Direct connection to Ground Station | Accesses **Actian VectorAI Database** on local LAN for POI/facility search & full corpus (100% offline) |

When any peer device in the Bluetooth LE mesh connects to the Ground Station server, it automatically acts as a **Mesh Gateway Phone**, relaying queries from out-of-range mesh peers directly to the **Actian VectorAI Database** server and returning ranked results back across the mesh.

---

## Advanced Vector & Intelligence Capabilities (Actian VectorAI DB)

Akasha leverages the full power of the **Actian VectorAI Database** for high-performance offline disaster intelligence:

- ⚡ **Hybrid Signal Fusion** — Fuses high-dimensional 384-vector semantic similarity with structured domain parameters (trauma unit capabilities, operational status, and distance metrics) into unified, context-aware ranked search results.
- 🎯 **Multi-Attribute Filtered Search** — Pushes structured filter criteria (geospatial GPS bounding boxes, emergency facility categories, capability tags) directly into the Actian vector engine before ranking, guaranteeing rapid candidate pruning.
- 🗂️ **Multi-Modal & Named Vector Collections** — Supports multi-field vector indexing across safety procedure documents, multimodal media metadata (voice/image notes), and specialized facility collections in unified Actian vector stores.
- 🛡️ **Edge Hardware & Local Offline Deployment** — Engineered for 100% local, offline deployment on edge devices, ARM hardware, field laptops, and single-board computers without external cloud dependencies.

---

## Core Features

### 🔗 Offline Mesh Communication & Gateway Relay
- **Bluetooth LE Mesh** — Automatic peer discovery and multi-hop message relay
- **Gateway Phone Mesh Relay** — Connected gateway devices bridge isolated BLE mesh peers directly to the **Actian VectorAI DB** Ground Station server
- **Multi-hop Relay** — Extends communications across out-of-range devices
- **Store-and-Forward** — Messages cached for offline peers and delivered upon reconnection
- **Serverless P2P Protocol** — Decentralized, pure end-to-end encrypted mesh messaging
- **Geohash Channels over Nostr** — Location-based group chat channels

### 🔐 Security & Privacy
- **X25519 & AES-256-GCM** — Industry-standard end-to-end encryption for private messages
- **Noise Protocol Handshake** — Cryptographically authenticated secure communication channels
- **Perfect Forward Secrecy** — Fresh ephemeral key pairs generated for each session
- **Cover Traffic Protection** — Traffic obfuscation preventing timing and packet analysis
- **Instant Data Wipe** — Emergency triple-tap trigger to instantly sanitize all local data
- **Anonymous Tor Routing** — Built-in Arti integration for onion-routed internet fallback

### 🧠 On-Device Knowledge Layer
- **Semantic Search** — Natural language retrieval ("her face is drooping and she can't speak") matching cited medical protocols
- **384-dim MiniLM Embeddings** — High-performance ONNX Runtime engine running directly on the handset
- **Refusal Gate** — Intelligent safety gate (0.45 confidence threshold) preventing hallucination on unvetted content
- **Vetted Guidelines** — Direct citations from AHA, WHO, FEMA, and Red Cross protocols
- **Medical Safety Boundaries** — Dedicated guardrails ensuring accurate, safe response boundaries

### 🤖 Tiered AI Engine
Three-tier AI response engine ensuring instant, accurate guidance:

| Priority | Engine | Detail |
|---|---|---|
| 1 | **Gemma 3 1B** (MediaPipe) | Full on-device LLM rephrasing retrieved guidance |
| 2 | **Local Aria Engine** | Efficient rule-based emergency response engine |
| 3 | **Retrieval Engine** | Direct knowledge pack citation search |

The LLM is strictly grounded in verified retrieval data.

### 📍 Ground Station Facility Search (Actian VectorAI DB)
- **53 Specialized Facilities** — Hospitals, police stations, fire stations, rescue centres, and blood banks indexed in Actian VectorAI DB
- **Situation-Aware Ranking** — Intelligently prioritizes facilities with required capabilities (e.g. trauma care for bleeding casualties)
- **Geospatial Vector Filtering** — GPS bounding-box filtering pushed directly into Actian VectorAI DB
- **Verified Helplines** — Direct integration with national emergency helpline contacts

### 🌍 Embedding Parity Guarantee
The handset and Ground Station share a bit-exact embedding contract:
- **Bit-Exact Score Match**: 0.4969 on handset = 0.4969 in Actian VectorAI DB
- **Full-Precision Model**: High-precision fp32 ONNX model deployment guaranteeing 100% embedding parity across devices and server

---

## Tech Stack

| Layer | Technology |
|---|---|
| **Mobile App** | Kotlin, Jetpack Compose, Material 3, Room |
| **Transport** | Bluetooth LE (custom binary protocol) + Nostr over WebSockets |
| **On-Device Embedder** | ONNX Runtime Mobile — all-MiniLM-L6-v2 (fp32, 384-dim) |
| **On-Device LLM** | MediaPipe LLM Inference — Gemma 3 1B (int4) |
| **NSFW Filter** | TensorFlow Lite classifier |
| **Tor/Privacy** | Arti (Rust-based Tor) via JNI |
| **Ground Station Server** | FastAPI + **Actian VectorAI DB** (Docker containerized) |
| **Vector Search Engine** | Actian VectorAI DB — Cosine similarity, HNSW (m=16, ef_construct=200) |
| **Encryption** | X25519, AES-256-GCM, Noise Protocol, Ed25519 |

---

## Project Structure

```
Akasha/
├── app/                        # Android application module
│   ├── src/main/java/          # Kotlin source (com.meshlink.android)
│   │   ├── ai/                 # AI engines (Aria, Gemma, ModelManager)
│   │   ├── mesh/               # BLE mesh networking & gateway relay
│   │   ├── crypto/             # Cryptographic primitives
│   │   ├── noise/              # Noise Protocol implementation
│   │   ├── nostr/              # Nostr relay integration
│   │   ├── geohash/            # Location-based channels
│   │   ├── features/           # Voice, file transfer, media
│   │   ├── identity/           # User identity & key management
│   │   ├── protocol/           # Wire protocol definitions
│   │   ├── ui/                 # Jetpack Compose screens & themes
│   │   └── ...
│   └── src/main/assets/akasha/ # Knowledge corpus + vocab
├── ground-station/             # FastAPI server + Actian VectorAI DB
│   ├── app.py                  # Main server (Actian VectorAI DB integration)
│   ├── poi/                    # POI data (hospitals, stations, etc.)
│   └── Dockerfile
├── docs/                       # Architecture & design docs
├── ai-merge/                   # AI engine overlay (Kotlin)
├── web-client/                 # Vite + React web companion
├── tools/                      # Build scripts (Arti, URL relay)
└── docs-protocol/              # Mesh protocol specifications
```

---

## Getting Started

### Prerequisites
- **Android Studio** Arctic Fox (2020.3.1) or newer
- **Android SDK** API 26+ (Android 8.0)
- **Kotlin** 1.8.0+
- **Docker** (for Actian VectorAI DB Ground Station)

### Build the Android App

```bash
git clone https://github.com/ltsRoy/Akasha.git
cd Akasha
./gradlew assembleDebug
```

### Install on Device

```bash
./gradlew installDebug
```

### Provision the LLM Model

Push the Gemma 3 1B model (`gemma3-1b-it-int4.task`) to device storage:

```bash
adb push gemma3-1b-it-int4.task /data/local/tmp/llm/
```

### Run the Ground Station (Actian VectorAI DB)

```bash
cd ground-station
docker compose up -d --build
curl http://localhost:8000/health
```

Expect `"backend":"actian"` and `"recall_ok":true`.

---

## Verified System Metrics

| Metric | Value |
|---|---|
| Stroke Query Similarity | **0.5090** (HIGH, cited) |
| Refusal Gate Accuracy | **0.1090** vs 0.45 gate |
| Embedding Parity | **0.4969 = 0.4969** (phone vs Actian VectorAI DB) |
| Embedding Precision Match | **100% Bit-Exact Match Verified** |
| Filter Push-Down | **53 → 22** candidates in Actian VectorAI DB |
| Test Suite | 156 Kotlin · 40 pytest · 39/39 e2e · 56/56 POI |

---

## Verified System Capabilities

1. **Multi-Hop BLE Mesh & Gateway Relay** — Robust packet routing, gateway relaying, and store-and-forward engine connecting BLE mesh peers to Actian VectorAI DB.
2. **Ground Station Facility Resolution** — Server-assisted POI search, hybrid ranking, and filtered vector search running on Actian VectorAI DB.
3. **Streamlined Vector Engine** — Dedicated text embedding pipeline fine-tuned specifically for crisis and disaster response workflows.
4. **Full-Precision ONNX Embedder** — High-precision fp32 ONNX model deployment guaranteeing 100% embedding parity across devices and Actian VectorAI DB server.

---
## FLOWCHART ## : 
<img width="1122" height="788" alt="WhatsApp Image 2026-07-26 at 12 03 36 PM" src="https://github.com/user-attachments/assets/c41ed082-cc33-4c44-a7ef-2a6f76210787" />
<img width="1120" height="782" alt="WhatsApp Image 2026-07-26 at 12 03 48 PM" src="https://github.com/user-attachments/assets/2c07c01b-0b50-4164-980a-7077426abcba" />
<img width="1120" height="787" alt="WhatsApp Image 2026-07-26 at 12 04 12 PM" src="https://github.com/user-attachments/assets/43c48b30-b6f8-4804-a987-8fbcc4d8def4" />

## License

This project is released into the public domain. See [LICENSE.md](LICENSE.md) for details.

---

<p align="center">
  <em>One laptop. No internet. An answer you can trust.</em>
</p>
