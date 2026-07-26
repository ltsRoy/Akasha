# Testing Strategy

## Why this file exists

The most expensive bug in this project was **32 fake vectors** shipped in the Android asset:
random unit vectors that looked plausible (correctly L2-normalised) but carried no semantic
signal. On-device search returned noise.

It survived because the entire test suite passed. Every existing test called
`query(text, queryVector = null)`, exercising only the keyword fallback. The vector path had
no coverage at all, and `AkashaEndToEndIntegrationTest` even constructed a `FakeMiniLmEmbedder`
and then never used it.

**A test that cannot fail is worse than no test**, because it buys false confidence. Write
tests that assert properties only a correct implementation can satisfy.

## Red-Green-Refactor with an AI agent

The loop that worked here:

1. **Frame precisely.** Not "test the mesh". Instead: "the responder must embed the incoming
   text rather than passing null, because otherwise a paraphrased query cannot match."
2. **Write the failing test first.** Include the *reason* in the assertion message so a future
   failure explains itself.
3. **Watch it fail for the right reason.** A compile error because the seam does not exist yet
   is a valid RED. A test that passes immediately means you tested nothing.
4. **Implement the minimum** to go green.
5. **Run the whole suite.** Two pre-existing tests legitimately broke when mesh availability
   stopped defaulting to `true`; that was the *intended* behaviour change, so the tests were
   updated with a comment explaining why.
6. **Quality radar.** Interrogate green results. Ask: could this pass while the feature is
   broken? For the fake-vector case the answer was yes, which is what
   `SemanticVectorSearchTest` now closes.

## Current state

| Suite | Count | Status |
|---|---|---|
| Kotlin unit — knowledge feature | 54 | pass |
| Kotlin unit — whole app | 156 | pass, 12 skipped |
| Ground Station `pytest test_app.py` | 8 | pass |
| Ground Station `e2e_test.py` (live stack) | 39 | pass |
| Embedding parity gate | 32 entries | cosine 1.000000 |
| Instrumented (`connectedDebugAndroidTest`) | 1 | needs a device |

## Tests that exist and why they matter

`SemanticVectorSearchTest` — the fake-vector guard. Asserts properties random vectors cannot
fake:

- every vector is 384-dim and L2-normalised
- **max pairwise similarity > 0.35** — random 384-dim unit vectors are near-orthogonal, so a
  synthetic pack fails this
- **same-category entries are closer than cross-category** — only true of real embeddings
- searching with an entry's own vector returns that entry at score > 0.99
- an anti-centroid vector is refused
- thresholds keep a usable band between the measured populations

`MeshResponderTest` — the nine responder behaviours: embeds instead of passing null, no
re-fan-out, dedup by `queryId`, ignores own echo, honours `allowGateway` both ways, silence on
refusal, availability reflects peer count.

`test_app.py::test_reingest_does_not_rewrite_unchanged_points` — guards the HNSW degradation
described in `07-KNOWN-ISSUES.md`. Load-bearing, not cosmetic.

`e2e_test.py::T7b` — `/packs` must account for every stored point. Catches enumeration via
similarity search, which silently undercounted 23 of 32.

## Tests to write for POI — before implementing

### Data integrity

- Every seed record validates against `poi/schema.json`.
- `category` and every `specialties` entry are in the closed vocabulary.
- `geohash4` / `geohash5` are consistent with `latitude`/`longitude` (recompute and compare).
- Coordinates fall inside a Kolkata bounding box (roughly 22.40–22.75 N, 88.20–88.55 E) —
  catches transposed lat/lon, a classic and dangerous error.
- Every record with a non-null `phone` has `data_status == "verified"`. **No unverified phone
  numbers.**
- `id` values are unique.

### Geohash / retrieval

- **The Garia case.** A user in `tgyzg` (south Kolkata) must find south-Kolkata facilities.
  Kolkata straddles gh4 `tunb` and `tgyz`; a naive single-prefix query loses the south
  entirely. This is the regression most likely to recur.
- A user at a gh5 boundary finds facilities in the adjacent cell (proves neighbour expansion).
- Results are sorted strictly by ascending distance.
- `specialty` filtering is exact: `orthopaedic` must not match via substring against another
  value. (Wrap CSV values in delimiters.)
- `emergency_24h_only` excludes non-24h facilities.
- Zero matches inside the radius produces a **refusal**, not the nearest far-away facility.
- Widening to gh4 only happens after the gh5 + neighbours set is exhausted.

### Distance

- Haversine against known Kolkata pairs, e.g. Esplanade → SSKM ≈ 2.8 km, Esplanade → Salt
  Lake Sector V ≈ 9 km. Assert with a tolerance, not exact equality.
- Gateway-answered queries measure distance from the **requester's** origin cell, not the
  gateway's own position. Easy to get wrong; silently wrong for the person who asked.

### Mesh POI codec

- Round-trip every field, including `specialtyMask` bit positions.
- Encoded `POI_QUERY` is ≤ 150 bytes (one fragment) for the maximum free-text length.
- Encoded `POI_RESULT` with 2 maximum-size records stays within 3 fragments.
- Truncated, empty, and garbage input return `null` and never throw.
- Version mismatch returns `null`.
- The `VERIFIED` flag bit survives the round trip — provenance must not be lost on the wire.

### LLM tool layer

- Unknown tool name → explicit error result, not an empty list.
- Invalid `specialty` enum → explicit error, not silent "none found".
- `needs_location: true` when location is unavailable, and the model is not given coordinates.
- Refusal is preserved: `akasha_ask_safety` returning `refused` must produce a refusal in the
  final answer. **Include the tramadol-dose case** — the model knows the answer from
  pretraining and must not use it.
- Tool execution respects its timeout and returns a partial result rather than hanging.

## Hardware test — the two-phone mesh scenario

**Still unrun.** This is the highest-value verification outstanding, because it is the tier the
whole offline story rests on and it is currently backed only by unit tests and code review.

### Setup

| Role | Device | Wi-Fi | Expected |
|---|---|---|---|
| A — gateway | on Wi-Fi with the Ground Station | ON | advertises gateway flag; `T3_WEAK` or `T4_FULL` |
| B — isolated | Wi-Fi **OFF**, Bluetooth ON | OFF | `T2_TRICKLE` once it sees A |

Both need USB debugging. B must be on USB — wireless adb would die with Wi-Fi off.

### Procedure

```bash
ADB=C:\Android\Sdk\platform-tools\adb.exe
RCV=com.MeshLink.droid/com.MeshLink.android.features.knowledge.AkashaDebugReceiver

# 1. Confirm both devices
$ADB devices -l

# 2. On A: point at the Ground Station and confirm it is a gateway
$ADB -s <A> shell am broadcast -a com.MeshLink.debug.AKASHA_SET_URL \
     --es url http://192.168.1.67:8000 -n $RCV -f 0x00000020
$ADB -s <A> shell am broadcast -a com.MeshLink.debug.AKASHA_STATUS -n $RCV -f 0x00000020
#    expect: ground station up: true

# 3. On B: Wi-Fi off. Confirm it sees a gateway peer
$ADB -s <B> shell svc wifi disable
$ADB -s <B> shell am broadcast -a com.MeshLink.debug.AKASHA_STATUS -n $RCV -f 0x00000020
#    expect: ground station up: false, mesh peers: >=1, gateway available: TRUE

# 4. Capture Actian's counter, then ask from B with a question B's local pack cannot answer
#    well but the Ground Station can.
$ADB -s <B> shell am broadcast -a com.MeshLink.debug.AKASHA_ASK \
     --es q "'a tank of chemicals ruptured near us'" -n $RCV -f 0x00000020

# 5. Read both logs
$ADB -s <B> logcat -s AkashaDebug MeshRelaySearch QueryHandler
$ADB -s <A> logcat -s MeshRelaySearch AkashaDebug
```

### Pass criteria

Do not accept anything weaker than all of these:

1. B reports `gateway available: true` with Wi-Fi off.
2. B's log shows a `QUERY` packet sent (`MeshRelaySearch: Sending mesh query #N`).
3. A's log shows the same `#N` received (`Received incoming mesh query #N`).
4. A escalated to the Ground Station and Actian's point-operation counter increased.
5. B's answer has `backend: Mesh Peer Relay` and a real `sourceDoc` + `packVersion`.
6. **The score B receives matches what the Ground Station computed** — proving the responder
   embedded correctly rather than falling back to keywords.
7. A did **not** re-broadcast a QUERY (no ping-pong).
8. Repeating the identical query does not produce duplicate `QUERY_RESULT` packets.

Criterion 6 is the one that would have caught the original null-vector bug.

### If it fails

Check in this order: is `gateway available` true on B (the flag was historically never set);
does A's `isMeshAvailableProvider` see peers; is A's `queryHandler` non-null; did A's
`embed()` return non-null (if the ONNX asset is missing it silently degrades to the hash
embedder and scores will not match).

## Commands

```bash
# Android — run from C:\akasha-build, not the OneDrive path (see 07-KNOWN-ISSUES.md)
./gradlew testDebugUnitTest
./gradlew testDebugUnitTest --tests "com.MeshLink.android.features.knowledge.*"
./gradlew connectedDebugAndroidTest
./gradlew assembleDebug

# Ground Station
docker exec akasha-api python -m pytest test_app.py -q
docker exec akasha-api python e2e_test.py
docker exec akasha-api python parity_check.py distilled_pack.android.json
docker exec akasha-api python calibrate.py
```

## Definition of done for the POI feature

- All new tests above written and passing.
- Existing 156 Kotlin + 8 pytest + 39 E2E still green (no regressions).
- Parity gate still 1.000000 for both packs.
- `calibrate.py` re-run if the corpus changed, thresholds re-tuned if the distribution moved.
- Two-phone hardware test passed against all 8 criteria, with logs captured.
- No unverified phone number anywhere in the shipped data.
- APK size impact measured and reported.
