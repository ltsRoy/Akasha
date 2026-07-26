<p align="center">
  <strong>AKASHA</strong><br>
  <em>Built for the moment the network is gone.</em>
</p>

# Akasha

**Akasha** is an offline-first emergency communication and knowledge platform for Android. When disasters take down cell towers and fibre, Akasha keeps working — delivering encrypted mesh messaging, on-device medical/safety guidance, and facility search — all without internet.

> *"Every tool we have for closing the information gap assumes a working network. So all of them fail at exactly the moment they are needed. Akasha is built for that moment."*

---

## Three-Tier Architecture

Akasha never shows a blank screen. It degrades gracefully through three tiers:

| Tier | Condition | Capability |
|---|---|---|
| **LOCAL** | Phone alone, no connectivity | On-device knowledge pack + embedded LLM |
| **MESH** | Other Akasha phones nearby | BLE mesh relay, shared knowledge, store-and-forward chat |
| **FULL** | Any phone reaches the Ground Station | Full vector database, POI/facility search, 53+ locations |

The app tells you which tier you are on. It never pretends to know more than it does.

---

## Core Features

### 🔗 Offline Mesh Communication
- **Bluetooth LE mesh** with automatic peer discovery
- **Multi-hop relay** — two phones out of range talk through a third
- **Store-and-forward** — messages cached for offline peers, delivered on reconnect
- **No accounts, no phone numbers, no servers** — just pure encrypted P2P
- **Geohash channels over Nostr** — location-based group chat when internet is available

### 🔐 Security & Encryption
- **X25519 key exchange + AES-256-GCM** for private messages
- **Noise Protocol handshake** for secure channels
- **Ed25519 digital signatures** for message authenticity
- **No persistent identifiers** — new key pairs each session
- **Emergency wipe** — triple-tap the logo to instantly erase all data
- **Bundled Tor support** via Arti for anonymous routing when internet is available

### 🧠 On-Device Knowledge Layer
- **Semantic search** — type natural language ("her face is drooping and she can't speak") and get cited medical protocols
- **384-dim MiniLM embeddings** via ONNX Runtime, running entirely on the handset
- **Refusal gate** — off-topic queries score below the 0.45 threshold and are refused. The LLM is *never invoked* for unvetted content
- **Vetted sources only** — every passage cites AHA, WHO, FEMA, or Red Cross guidance
- **Drug dosage refusal** — the system will not provide dosing information, period

### 🤖 Tiered AI Fallback
Three AI tiers — never a blank screen:

| Priority | Engine | Detail |
|---|---|---|
| 1 | **Gemma 3 1B** (MediaPipe) | Full on-device LLM, rephrases cited passages |
| 2 | **Local Aria Engine** | Lighter rule-based engine |
| 3 | **Retrieval Only** | Raw knowledge pack results with citations |

The LLM is *grounded in retrieval* — it can only rephrase passages the retrieval system has already cleared through the refusal gate.

### 📍 Facility Search (Ground Station)
- **53 hospitals, police stations, fire stations, rescue centres, blood banks** around Kolkata
- **Situation-aware ranking** — a bleeding casualty gets a trauma hospital ranked above the nearest general one
- **GPS bounding-box filtering** pushed into the vector database before ranking
- **Honest labelling** — closer options shown with capability caveats ("closest option, but no trauma capability")
- **Null phone numbers** — no unverified contact info; only national helplines are shown

### 🌍 Embedding Parity Guarantee
The phone and Ground Station use the *identical* embedding contract:
- Same query → same score: **0.4969 on handset = 0.4969 in Actian**
- int8 quantisation was **rejected** because it failed the 0.99 parity gate at 0.9496
- The app ships a larger ONNX model rather than break this guarantee

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
| **Ground Station** | FastAPI + Actian VectorAI DB, Docker |
| **Vector Search** | Cosine similarity, HNSW (m=16, ef_construct=200) |
| **Encryption** | X25519, AES-256-GCM, Noise Protocol, Ed25519 |

---

## Project Structure

```
Akasha/
├── app/                        # Android application module
│   ├── src/main/java/          # Kotlin source (com.meshlink.android)
│   │   ├── ai/                 # AI engines (Aria, Gemma, ModelManager)
│   │   ├── mesh/               # BLE mesh networking & relay
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
├── ground-station/             # FastAPI server + Actian VectorAI
│   ├── app.py                  # Main server
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
- **Docker** (for Ground Station)

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

The Gemma 3 1B model is **not bundled** (554 MB). Push it to the device separately:

```bash
adb push gemma3-1b-it-int4.task /data/local/tmp/llm/
```

The app searches: app storage → APK assets → `/data/local/tmp/llm/` → Downloads.
Loading takes ~2 minutes. Until then, the app answers from retrieval only (correct behaviour, not a failure).

### Run the Ground Station

```bash
cd ground-station
docker compose up -d --build
curl http://localhost:8000/health
```

Expect `"backend":"actian"` and `"recall_ok":true`.

---

## Verified Metrics

| Metric | Value |
|---|---|
| Stroke query similarity | **0.5090** (HIGH, cited) |
| Off-topic refusal | **0.1090** vs 0.45 gate |
| Embedding parity | **0.4969 = 0.4969** (phone vs server) |
| int8 quantisation parity | 0.9496 vs 0.99 gate → **rejected** |
| Filter push-down | **53 → 22** candidates |
| Tests | 156 Kotlin · 40 pytest · 39/39 e2e · 56/56 POI |

---

## Known Limitations

1. **Two-phone mesh relay** — unit-tested, not yet verified with two handsets simultaneously
2. **Facility search** runs on the Ground Station, not yet on the phone itself
3. **Single text vector per collection** — no multimodal (not needed for the disaster workflow yet)
4. **int8 model rejected** — the fp32 ONNX ships, which is why the app is larger

---

## Security & Privacy

- **No registration** — no accounts, emails, or phone numbers
- **Ephemeral by default** — messages exist only in device memory
- **Cover traffic** — random delays and dummy messages prevent traffic analysis
- **Forward secrecy** — new key pairs generated each session
- **Emergency wipe** — triple-tap to destroy all data instantly

---

## License

This project is released into the public domain. See [LICENSE.md](LICENSE.md) for details.

---

## Contributing

Key areas for contribution:
1. **On-device facility search** — bring POI ranking to the phone
2. **Multi-phone mesh relay testing** — verify with real hardware
3. **UI polish** — tier badge, result cards, diagnostics sheet
4. **Battery optimization** — adaptive scanning improvements
5. **Additional knowledge packs** — more regions and languages

---

<p align="center">
  <em>One laptop. No internet. An answer you can trust.</em>
</p>
