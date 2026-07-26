# AKASHA Handoff — Start Here

You are picking up the AKASHA knowledge & safety layer inside the MeshLink Android app.
This document is the entry point. Read it fully before touching code.

## Read in this order

| File | Why |
|---|---|
| `00-HANDOFF-README.md` (this) | State of the world, how to run things |
| `01-ARCHITECTURE.md` | How the existing system works, code map |
| `02-POI-DATA-MODEL.md` | The Kolkata facility database you are building |
| `03-LLM-TOOL-CONTRACT.md` | How the on-device Gemma model calls AKASHA |
| `04-MESH-WIRE-PROTOCOL.md` | POI lookups over BLE, byte layouts |
| `05-UI-REQUIREMENTS.md` | Screens and states that do not exist yet |
| `06-TESTING-STRATEGY.md` | TDD plan and the hardware test that is still unrun |
| `07-KNOWN-ISSUES.md` | Traps that already cost days. Read before debugging anything |

## What AKASHA is

An offline-first emergency knowledge system. A user asks a natural-language question and
gets back **verbatim, cited passages** from a curated corpus. It is a *retrieval* system:
nothing is generated. Confidence shown to the user is retrieval confidence.

Three answer sources, tried in order:

1. **Local pack** — 32 safety passages bundled in the APK with precomputed embeddings.
   Works with zero connectivity.
2. **Ground Station** — a laptop/Pi running Actian VectorAI DB, reached over local Wi-Fi.
   Holds the full corpus. No internet involved.
3. **BLE mesh peers** — the question is relayed to nearby phones. A peer that can reach
   the Ground Station acts as a *gateway* and answers on behalf of an isolated device.

## Current state — what works

Verified on real hardware and against the live stack:

- On-device ONNX all-MiniLM-L6-v2 embedding. `embed() dim=384 l2norm=0.99999997`.
- Local pack retrieval, semantic, 22/22 on paraphrased probes.
- Ground Station over real LAN (phone `192.168.1.96` → host `192.168.1.67:8000`),
  confirmed at TCP level, scores matching the server to 6 decimal places.
- Actian VectorAI DB as the live backend, 32 points, 384-dim cosine, HNSW.
- Refusal path: off-topic questions score ≤0.18 and are refused.
- Ground Station E2E: **39/39** checks pass.
- Kotlin unit tests: **156 pass, 0 fail** (54 in the knowledge feature).
- `assembleDebug` produces working APKs.

## Current state — what does NOT work / does not exist

Be precise about this with the user. Do not overclaim.

| Gap | Detail |
|---|---|
| **No UI at all** | Nothing in `ui/` references AKASHA. Driven only by a debug broadcast receiver. See `05-UI-REQUIREMENTS.md`. |
| **Two-phone mesh test never run** | The BLE gateway relay is verified by unit tests and code review only, never on hardware. This is the highest-value thing to prove. |
| **No POI data** | The whole Kolkata facility feature is design-only. That is your main job. |
| **No local LLM** | Gemma is not integrated. Contract is specified in `03-LLM-TOOL-CONTRACT.md`. |
| **Full-precision fp32 model** | High-precision fp32 model selected to guarantee 100% bit-exact embedding parity across handset and server. |
| **Actian does not reload collections** | After restart it lists the collection but cannot read it. Worked around by rebuilding from the shipped pack on boot. See `07-KNOWN-ISSUES.md`. |

## Running the stack

### Ground Station

```bash
cd ground-station
docker compose up -d --build
curl http://localhost:8000/health
# {"ok":true,"backend":"actian","port":6573,...,"points":32,"recall_ok":true}
```

`backend` must read `actian`. If it reads `in-memory`, Actian is unreachable and you are
testing a fallback, not the real thing. `recall_ok` must be `true`; if false the index is
degraded and every query will silently return nothing — `POST /reindex` to rebuild.

### Tests

```bash
# Ground Station unit tests
docker exec akasha-api python -m pytest test_app.py -q      # 8 passed

# Ground Station end-to-end against the live stack
docker exec akasha-api python e2e_test.py                    # 39/39

# Embedding parity gate (must be > 0.99)
docker exec akasha-api python parity_check.py distilled_pack.android.json

# Threshold calibration (regenerates calibration.json)
docker exec akasha-api python calibrate.py

# Android
./gradlew testDebugUnitTest        # 156 pass
./gradlew assembleDebug
./gradlew connectedDebugAndroidTest   # needs a device; EncryptionServiceTest lives here
```

### Regenerating the knowledge pack

`ground-station/corpus.json` is the single source of truth. `seed.py` computes real
embeddings once and emits **both** the server pack and the Android asset, so they cannot
drift:

```bash
docker exec akasha-api python seed.py --android-out /app/distilled_pack.android.json
docker cp akasha-api:/app/distilled_pack.android.json \
          app/src/main/assets/akasha/distilled_pack.json
docker cp akasha-api:/app/distilled_pack.json ground-station/distilled_pack.json
```

Then re-run `parity_check.py`. If parity fails, **do not ship** — the app's vectors and the
server's vectors would disagree and retrieval would be silently wrong.

### Driving the app on a device (no UI yet)

```bash
ADB=C:\Android\Sdk\platform-tools\adb.exe
RCV=com.MeshLink.droid/com.MeshLink.android.features.knowledge.AkashaDebugReceiver

$ADB shell am broadcast -a com.MeshLink.debug.AKASHA_STATUS -n $RCV -f 0x00000020
$ADB shell am broadcast -a com.MeshLink.debug.AKASHA_SET_URL --es url http://192.168.1.67:8000 -n $RCV -f 0x00000020
$ADB shell am broadcast -a com.MeshLink.debug.AKASHA_ASK --es q "'her face is drooping'" -n $RCV -f 0x00000020
$ADB logcat -s AkashaDebug
```

Add `--es backend groundstation` to bypass the local pack and force the remote path.
The receiver is in `app/src/debug/` so it never reaches a release build.

## Non-negotiable rules

These come from `PROJECT_CONTEXT.md` and are load-bearing for a safety product.

1. **Retrieval decides.** An LLM may only rephrase passages that were retrieved. It must
   never invent a fact, a dosage, a phone number, or a facility.
2. **Refusal is a first-class outcome.** "No verified match" is a valid, correct answer.
   Never fill the gap with a guess.
3. **Every result carries `sourceDoc` + `packVersion`.** No unattributed content reaches a
   user.
4. **Confidence shown is retrieval confidence**, not model confidence.
5. **Never send float vectors over the mesh.** Send query text; embed on each device.
   A 384-float vector is ~1.5 KB against a 150-byte fragment budget.
6. **Actian binds loopback/LAN only.** Never expose it to the open internet.

## Working agreement

- **Test-first.** Write the failing test, watch it fail for the right reason, then fix.
  The fake-vector bug survived for weeks because the only tests passed `queryVector = null`
  and never exercised the vector path. Tests that cannot fail are worse than no tests.
- **Verify, do not assume.** Run the command. Read the log. A build that exits 0 is not
  evidence the feature works.
- **Say what you did not check.** The user has repeatedly asked for real proof. Distinguish
  "I ran this and saw X" from "this should work".
