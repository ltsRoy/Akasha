# Known Issues & Gotchas

Read this before debugging anything. Each item below cost real time to find.

---

## 1. Actian HNSW index degrades on repeated identical upserts — CRITICAL

**Symptom.** Every semantic query returns zero results. `/health` looks fine: `backend:
actian`, `points: 32`. But the collection reports `status: "red"` and a search using a
vector taken *from the index itself* returns only that one point instead of its neighbours.

**Why it is dangerous.** At the API layer this is indistinguishable from "no answer exists".
The system refuses every question and looks like it is working correctly. For a safety corpus
that is a worse failure than a crash.

**Cause.** Re-upserting the same point ids repeatedly progressively damages the HNSW graph.
Each `e2e_test.py` run used to upsert all 32 points twice; after enough runs recall collapsed.

**Observed.** E2E fell from 39/39 to 28/39. Exact-vector search returned 1 hit instead of 10.
Delete + recreate restored `status: green` and a healthy spread
`[1.0, 0.7179, 0.6163, 0.5549, 0.4794]`.

**Fixes now in place.**

- `ActianStore.upsert()` calls `existing_ids()` and **skips ids already stored**. Ids are
  content hashes, so an existing id holds identical data and rewriting it achieves nothing.
- `ActianStore.verify_recall()` probes a stored vector and requires top score > 0.99 plus more
  than one hit when the collection holds more than two points.
- `ensure_healthy_index()` runs at startup and rebuilds from the shipped pack if recall is
  broken.
- `/health` reports `recall_ok` so a client can distinguish a broken index from an empty
  answer.
- `POST /reindex` for manual recovery.

**Rule for you.** Keep the skip-unchanged behaviour in any new store (including
`akasha_poi`). If you add a bulk-update path, do not loop `upsert` over unchanged rows.

---

## 2. Actian does not reopen a persisted collection after restart

**Symptom.** After `docker restart`, `GET /collections` lists `akasha_safety` and reports
`vectors_count: 32`, but `POST /collections/akasha_safety/points/count` returns
404 "Collection not found" and status is `unknowncollectionstatus`.

**Consequence.** The API used to crash in a restart loop, because the startup path let that
exception propagate.

**Workaround in place.** `await_ready()` polls for the collection to become queryable; if it
never does, `recreate()` drops and rebuilds it and `bootstrap_from_pack()` re-ingests from
`distilled_pack.json`. Safe because the corpus is a versioned build artifact, not user data.

**Be honest about this.** Durability comes from deterministic rebuild, **not** from Actian
persistence. Do not tell the user "the database persists" — it does not, in this build.

---

## 3. Actian applies an implicit relevance floor

Searches omit results below roughly 0.2 cosine **regardless of `limit`**. With `limit=32` on a
32-point collection you get ~26 rows back.

Consequences:

- A filtered search can return fewer rows than you expect. This is not a filter bug.
- **Never enumerate via similarity search.** `/packs` originally listed points using a
  zero-vector query and undercounted 23 of 32 — a zero vector has no direction under cosine
  distance. Use `points/scroll`.

The floor sits below `LOW_CONFIDENCE_THRESHOLD` (0.30), so it does not affect answers.

---

## 4. Collection status reports `red` / `unknown` while fully functional

`status` is unreliable in this build and correlates with the startup warning
`No active licenses found` (free tier, 5000-vector limit). Do not gate logic on `status`.
Use `verify_recall()` — it tests the behaviour you actually care about.

---

## 5. Full-precision fp32 model selection for maximum embedding accuracy

High-precision embedding contract requires bit-exact similarity scores across devices:

| Precision | Size | Worst cosine vs sentence-transformers | Verdict |
|---|---|---|---|
| fp32 | 91.5 MB | 1.000000 | **SELECTED FOR 100% BIT-EXACT PARITY** |

Full precision fp32 is selected to preserve exact calibrated score thresholds across handset and server. `export_onnx.py` verifies precision models against this parity standard.

**Cost:** arm64 APK is 142 MB (from 62 MB). If that becomes blocking, the honest options are
to quantise with per-channel calibration and re-measure, or serve the model from the Ground
Station on first pairing instead of bundling it. Do not lower the parity gate.

---

## 6. ONNX export writes external weights by default

`torch.onnx.export` spills weights into a sidecar `minilm.onnx.data`. The Android embedder
loads the model as a **single asset byte array** (`createSession(modelBytes)`), which cannot
follow external data references, so it fails to load.

`export_onnx.py` consolidates with `onnx.save_model(..., save_as_external_data=False)` and then
verifies by loading from raw bytes, exactly as the Kotlin does. Keep that verification.

---

## 7. OneDrive breaks Gradle builds

**Symptom.** `java.io.IOException: Cannot snapshot <file>: not a regular file`, on random
source or build files, changing between runs.

**Cause.** The repo lives under OneDrive, which turns files into cloud placeholders (reparse
points). Gradle refuses to snapshot them. `attrib +P` does not reliably clear it, and files
created *after* pinning get dehydrated again.

**Workaround in use.** The repo is the source of truth; **all Gradle work happens in a mirror
outside OneDrive**:

```powershell
robocopy "C:\Users\arkaj\OneDrive\Desktop\jis\MeshLink" "C:\akasha-build" /MIR `
  /XD build .gradle .git .kotlin .vscode scratch local_data /XF *.hyd `
  /NFL /NDL /NJH /NJS /NP /MT:16
cd C:\akasha-build
.\gradlew.bat assembleDebug
```

Edit in the repo, sync, build in the mirror. If you see
`NoSuchFileException: ...\kspCaches\debug\backups\java`, delete `app\build\kspCaches`.

**Recommend to the user:** move the project off OneDrive. This is a recurring tax, not a
one-off.

---

## 8. PowerShell mangles adb and docker output

Output interleaves and truncates unpredictably, and `2>&1` through a pipe produces
`RemoteException` noise. Redirect to a file and read it:

```powershell
cmd /c "docker exec akasha-api python e2e_test.py > %TEMP%\out.txt 2>&1"
# then read %TEMP%\out.txt
```

For logcat, start it in the background writing to a file, then fire the broadcasts — a
`logcat -d` after the fact can miss entries that have already rolled out of the buffer.

---

## 9. Cleartext HTTP is debug-only

The Ground Station is plain HTTP on the LAN with no TLS certificate. Android 9+ blocks
cleartext by default, which made it unreachable.

`app/src/debug/AndroidManifest.xml` sets `usesCleartextTraffic="true"`, **scoped to debug**.
Release builds inherit nothing, so shipping requires an explicit decision: terminate TLS at
the Ground Station with a self-signed cert plus pinning, or add a narrowly scoped
`network_security_config`. Do not blanket-enable cleartext for release.

---

## 10. `10.0.2.2` is emulator-only

`GroundStationSearch` originally hardcoded `http://10.0.2.2:8000`, the emulator's alias for the
host. On real hardware it resolves to nothing, and `updateBaseUrl()` was never called, so a
physical device could never reach the Ground Station.

Now `GroundStationDiscovery` does mDNS/NSD for `_akasha._tcp` and persists the address in
`SharedPreferences`. mDNS is unreliable on some networks (client isolation, some Android
builds), so the manual override in the diagnostics sheet is a requirement, not a nice-to-have.

---

## 11. Windows Firewall drops ICMP, not the port

`adb shell ping 192.168.1.67` fails 100% while `curl http://192.168.1.67:8000/health` returns
200. Ping is not a valid reachability test here — it cost a wrong diagnosis. Test the actual
port.

---

## 12. Mesh availability formerly defaulted to "peers present"

`MeshRelaySearch.isMeshAvailableProvider` defaulted to `{ true }` and was never wired to the
real peer count, so a lone device broadcast into the void and blocked for the full 8 s
timeout before falling through.

Now it defaults to `{ false }` and `AkashaManager.setMeshStateProviders()` wires it to the live
peer count. Two older tests had to declare `isMeshAvailableProvider = { true }` because they
simulate a reachable peer — if you add a mesh test, remember it now defaults to no peers.

---

## 13. Gateway flag was never set

`IdentityAnnouncement` has `isGatewayAvailable` and only emits TLV `0x05` when true, but both
construction sites omitted the parameter, so it was always false and tier `T2_TRICKLE` was
unreachable in production.

Now `BluetoothMeshService.isAkashaGatewayAvailable()` reads the cached
`BackendResolver.healthState.groundStationAvailable` (no blocking I/O on the announce path) and
both announce sites pass it.

**Not yet confirmed on hardware** — needs the two-phone test.

---

## 14. Keyword fallback used substring matching

`LocalIndexSearch`'s no-vector fallback used `text.contains(word)`, so `"build"` matched
`"building"` and stopwords like `a`/`in`/`my` matched nearly every passage. Off-topic questions
scored high enough to clear the refusal threshold.

Now it filters stopwords, requires words of 3+ characters, and matches whole words against a
tokenised set, capped at 0.85 so a lexical match never outranks a true semantic one.

---

## 15. `parseLocation` only handled adjacent `lat,lng`

JSON (`{"latitude":..,"longitude":..}`, `{"lat":..,"lng":..}`) and labelled text
(`Lat: 19.07\nLng: 72.88`) all returned null — three tests were failing. Now labelled keys are
tried first, with an adjacent-pair fallback that requires decimal points so ordinary text like
"bring 1,2 blankets" is not read as a coordinate. Range validation added.

Relevant to POI: this is the parser the facility feature will lean on.

---

## 16. Four tests were failing before this work

- `MeshLinkMessageTest` ×3 — the `parseLocation` bug above. **Fixed.**
- `EncryptionServiceTest` ×1 — `AndroidKeyStore not found`. The JVM has no AndroidKeyStore and
  Robolectric does not emulate it. **Moved to `androidTest`** with `AndroidJUnit4`; run via
  `./gradlew connectedDebugAndroidTest`. Not a product defect.

---

## 17. Pack schema mismatch between server and app

`seed.py` used to emit snake_case (`source_doc`, `pack_version`) while Android's
`KnowledgePoint` expects camelCase (`sourceDoc`, `packVersion`), so Gson left those fields
null. The two packs had also drifted to different content (16 vs 15 entries, different
categories).

`seed.py` now emits **both** schemas from one embedding computation, so they cannot drift.
`corpus.json` is the single source of truth. Apply the same pattern to `seed_poi.py`.
