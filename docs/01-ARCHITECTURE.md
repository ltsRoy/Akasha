# AKASHA Architecture

## Connectivity tiers

`BackendResolver` polls every 10 s and publishes a `HealthState`. The tier determines which
backend answers.

| Tier | Condition | Answers from |
|---|---|---|
| `T4_FULL` | Ground Station reachable **and** mesh peers present | Ground Station |
| `T3_WEAK` | Ground Station reachable, no peers | Ground Station |
| `T2_TRICKLE` | No Ground Station, but a peer advertises as gateway | Mesh gateway relay |
| `T1_MESH` | Peers present, none is a gateway | Mesh peer's local pack |
| `T0_ALONE` | Nothing reachable | Own local pack |

`HealthState.gatewayAvailable` means *a peer* is a gateway, not that this device is one.

## Data flow, isolated device (the case that matters)

```
Phone B (no Wi-Fi, no gateway)          Phone A (gateway)         Ground Station
  |                                        |                         |
  | user question                          |                         |
  | embed on-device (ONNX MiniLM)          |                         |
  | search local pack -> weak              |                         |
  |                                        |                         |
  |== QUERY 0x30 over BLE ================>|                         |
  |   (query TEXT, never vectors)          |                         |
  |                                        | embed the received text |
  |                                        | search own local pack   |
  |                                        |-- POST /search --------->|
  |                                        |                         |-- Actian
  |                                        |<-- results with scores --|
  |<== QUERY_RESULT 0x31 ==================|                         |
  | show with source + pack version        |                         |
```

The requester embeds only for its own local search. The responder embeds the text again on
its side. Both produce identical vectors because the parity gate guarantees it — this is why
the gate matters.

## Code map

### Knowledge feature — `app/src/main/java/com/meshlink/android/features/knowledge/`

| File | Role |
|---|---|
| `AkashaManager.kt` | Singleton wiring. Owns embedder, backends, resolver, discovery. `ask()` is the entry point callers should use. |
| `QueryHandler.kt` | The cascade and the confidence thresholds. `allowGroundStation` / `allowMeshFanout` scope it. |
| `KnowledgeSearch.kt` | Interface all backends implement. |
| `LocalIndexSearch.kt` | Loads `assets/akasha/distilled_pack.json`, cosine search, stopword-aware keyword fallback. |
| `GroundStationSearch.kt` | OkHttp client for the Ground Station. |
| `GroundStationDiscovery.kt` | mDNS/NSD `_akasha._tcp` discovery + persisted address. |
| `MeshRelaySearch.kt` | Requester **and** responder for BLE knowledge queries. |
| `MeshQueryCodec.kt` | Byte layout for QUERY / QUERY_RESULT. |
| `BackendResolver.kt` | Tier resolution, polls health. |
| `SearchResult.kt` / `KnowledgePoint.kt` / `HealthState.kt` | Data types. |
| `embedder/OnnxMiniLmEmbedder.kt` | ONNX Runtime, mean-pool + L2 normalize. |
| `embedder/WordPieceTokenizer.kt` | BERT WordPiece, reads `assets/akasha/vocab.txt`. |
| `embedder/FakeMiniLmEmbedder.kt` | Deterministic hash fallback when the model is absent. |

### Mesh integration

| File | Relevant part |
|---|---|
| `protocol/BinaryProtocol.kt` | `QUERY(0x30)`, `QUERY_RESULT(0x31)`. Add POI opcodes here. |
| `mesh/PacketProcessor.kt` | Dispatch, ~L150. Route new opcodes here. |
| `mesh/BluetoothMeshService.kt` | `AkashaManager.init`, `setMeshStateProviders`, `handleQuery`/`handleQueryResult`, gateway flag on announce. |
| `mesh/PeerManager.kt` | `isGateway`, `hasGatewayAvailable()`. |
| `model/IdentityAnnouncement.kt` | TLV `0x05` gateway flag. |

### Assets — `app/src/main/assets/akasha/`

| File | Size | Notes |
|---|---|---|
| `distilled_pack.json` | 237 KB | 32 passages, real MiniLM vectors, camelCase schema |
| `minilm.onnx` | 87 MB | fp32 full-precision model delivering 100% bit-exact embedding parity |
| `vocab.txt` | 226 KB | 30522 WordPiece tokens |

### Ground Station — `ground-station/`

| File | Role |
|---|---|
| `app.py` | FastAPI shim. `ActianStore` over Actian's Qdrant-compatible REST. |
| `corpus.json` | **Single source of truth** for knowledge text. |
| `seed.py` | Embeds corpus once, emits server + Android packs. |
| `export_onnx.py` | Exports the model, picks the smallest precision passing parity. |
| `calibrate.py` | Measures score distributions, recommends thresholds. |
| `parity_check.py` | The 0.99 gate. |
| `e2e_test.py` | 39 checks against the live stack. |
| `test_app.py` | API unit tests. |
| `docker-compose.yml` | Actian + API, with healthchecks and ordered startup. |

## Actian VectorAI DB

It exposes a **Qdrant-compatible REST API** on port 6573. The Python client
(`actian_vectorai`) is not on PyPI, so the integration is HTTP.

| Operation | Call |
|---|---|
| List collections | `GET /collections` |
| Create | `PUT /collections/{name}` `{"vectors":{"size":384,"distance":"Cosine"}}` |
| Upsert | `PUT /collections/{name}/points?wait=true` `{"points":[{id,vector,payload}]}` |
| Search | `POST /collections/{name}/points/search` `{vector,limit,with_payload,filter}` |
| Enumerate | `POST /collections/{name}/points/scroll` |
| Count | `POST /collections/{name}/points/count` |
| Delete | `DELETE /collections/{name}` |

Ports: **6573** REST, 6574 gRPC, 6575 web UI.

Point ids must be unsigned ints or UUIDs. A SHA-256 hex digest is rejected, so
`text_to_point_id()` folds the first 16 digest bytes into a UUID. This keeps ingest
idempotent while satisfying the id type.

Payload filters push down, so structured filtering happens in the database:

```json
{"filter": {"must": [{"key": "category", "match": {"value": "hospital"}}]}}
```

This is the mechanism the POI feature depends on.

## Confidence thresholds

In `QueryHandler`, calibrated from measured data (`ground-station/calibration.json`), not
guessed:

```
HIGH_CONFIDENCE_THRESHOLD = 0.45
LOW_CONFIDENCE_THRESHOLD  = 0.30
```

Measured populations for the current corpus:

| Population | min | median | max |
|---|---|---|---|
| On-topic (relevant question) | 0.4689 | 0.56 | 0.7449 |
| Off-topic (must refuse) | 0.0413 | 0.11 | 0.1836 |

`LOW` sits in the empty band between them, so every relevant question surfaces something
and every off-topic one is refused. **If you change the corpus, re-run `calibrate.py` and
re-tune.** The previous values (0.62/0.45) predated measurement and sat above the on-topic
median, which caused correct answers to be refused.

Actian also applies an implicit relevance floor around 0.2 cosine and omits weaker matches
regardless of `limit`. That sits below `LOW`, so it is harmless — but it means a filtered
search can return fewer rows than you expect.
