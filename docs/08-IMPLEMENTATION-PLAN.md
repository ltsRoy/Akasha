# Implementation Plan

Ordered so each step is independently verifiable and nothing is built on an unproven
foundation. Do not skip ahead — step 1 in particular is the thing the user has asked for
repeatedly and it gates the credibility of everything else.

## Step 0 — Get your bearings (before writing code)

- [ ] Read `00`–`07`.
- [ ] `cd ground-station && docker compose up -d --build`
- [ ] `curl http://localhost:8000/health` → must show `"backend":"actian"` and
      `"recall_ok":true`.
- [ ] `docker exec akasha-api python e2e_test.py` → 39/39.
- [ ] `docker exec akasha-api python -m pytest test_app.py poi/test_poi_data.py -q`
      → 8 + 17 passed.
- [ ] Sync to `C:\akasha-build` and `./gradlew testDebugUnitTest` → 156 pass.

If any of those fail, fix that before starting. You need a known-good baseline to attribute
regressions to your own changes.

## Step 1 — Prove the two-phone mesh path on hardware

**Highest priority.** The gateway relay is the foundation of the offline story and has never
run on real devices. Everything about the isolated-phone experience is unverified until this
passes.

- [ ] Get both phones on USB simultaneously (Device B must be USB — Wi-Fi will be off).
- [ ] Run the procedure in `06-TESTING-STRATEGY.md` § "Hardware test".
- [ ] Verify all 8 pass criteria, especially **#6** (B's score matches the Ground Station's,
      which proves the responder embedded rather than falling back to keywords).
- [ ] Capture both logs and report them.

If it fails, use the ordered debug list at the end of that section. Do not start the POI
feature on top of a broken mesh path.

## Step 2 — POI data pipeline (Ground Station)

- [ ] `python poi/build_kolkata_poi.py` — already produces 33 validated records.
- [ ] Write `seed_poi.py`, modelled on `seed.py`: build the canonical descriptor string,
      embed once, emit **both** the server pack and the Android asset. Same
      single-computation pattern so the two cannot drift.
- [ ] Generalise `ActianStore` to take a collection name; create `akasha_poi`.
      **Keep the skip-unchanged `upsert()`** — see `07-KNOWN-ISSUES.md` §1.
- [ ] Add `POST /poi/search`, `POST /poi/ingest`, `GET /poi/packs`.
- [ ] Extend `test_app.py` for the new endpoints; extend `e2e_test.py` with POI checks
      (geo filter, specialty filter, distance ordering, refusal outside radius).

Verify: `/poi/search` for `category=hospital, specialty=orthopaedic` around `tunb4` returns
SSKM first; the same query around `tgyzg` returns a *south* Kolkata facility.

## Step 3 — POI on device

- [ ] `PoiEntity`, `PoiDao`, bump `AppDatabase` to version 2 **with a real migration**.
      The database currently uses `fallbackToDestructiveMigration()`, which would wipe user
      messages — change it.
- [ ] Wrap `specialtiesCsv` values in delimiters (`",a,b,"`) so `LIKE` cannot match a partial
      specialty name.
- [ ] `PoiSearch`: geohash cell expansion (`Geohash.encode` + `neighborsSamePrecision`) →
      SQL filter → haversine sort. Widen to gh4 only when gh5+neighbours is exhausted.
- [ ] Bundle `app/src/main/assets/akasha/kolkata_poi.json`.
- [ ] Write the tests from `06-TESTING-STRATEGY.md` § "Geohash / retrieval" **first**.
      The Garia case is the one that will regress.

## Step 4 — POI over mesh

- [ ] Add `POI_QUERY(0x32)` / `POI_RESULT(0x33)` to `BinaryProtocol.kt`, dispatch in
      `PacketProcessor`, delegate in `BluetoothMeshService`.
- [ ] `MeshPoiCodec` per the byte layout in `04-MESH-WIRE-PROTOCOL.md`. Decoders return
      `null` on malformed input, never throw.
- [ ] `MeshPoiRelay` with all five hardenings (dedup, own-echo, no re-fan-out, honour
      gateway flag, silence on no match). Copy the structure from `MeshRelaySearch.kt`.
- [ ] Gateway must compute distance from the **requester's** origin cell, not its own.
- [ ] Codec round-trip and fragment-budget tests before implementing the relay.

## Step 5 — LLM tool layer

- [ ] `AkashaToolExecutor` in `features/knowledge/llm/`, implementing the three tools in
      `03-LLM-TOOL-CONTRACT.md`.
- [ ] Location injected by the app, never accepted from the model.
- [ ] Validate tool names and enum values; return explicit errors rather than empty lists.
- [ ] Load `synonyms.json` for deterministic slot resolution.
- [ ] Integrate Gemma. **Measure memory alongside the 87 MB MiniLM** and report. If the
      combined footprint is not viable, say so and take one of the options in that doc rather
      than shipping something that OOMs.
- [ ] Test the refusal-override case (tramadol dose) explicitly.

## Step 6 — UI

Per `05-UI-REQUIREMENTS.md`. Suggested order so each is demoable:

- [ ] Tier badge (consumes the `healthState` nobody reads today)
- [ ] Ask screen + result cards
- [ ] Refusal card
- [ ] Facility cards with the unverified banner
- [ ] Diagnostics sheet, including the manual Ground Station address field
- [ ] Gateway indicator
- [ ] Compose tests: refusal, LOW hedge, unverified banner, 200% font scale

## Step 7 — Ship

- [ ] Full regression: 156+ Kotlin, 8 pytest, 17 POI data, 39+ E2E, parity 1.000000.
- [ ] `calibrate.py` re-run; thresholds re-tuned if the corpus moved.
- [ ] Two-phone hardware test passed with logs.
- [ ] **Data verification pass**: no unverified phone numbers; coordinates cross-checked
      against a primary source; `data_status` honest.
- [ ] Release-build cleartext decision made (`07-KNOWN-ISSUES.md` §9) — do not blanket-enable.
- [ ] APK size measured and reported.

## Deliberately out of scope

State these plainly rather than half-building them:

- **Routing / directions.** No road data, and roads may be flooded. Distance only.
- **Shelter lists.** Only meaningful if they reflect what is open during a specific event. A
  stale shelter record sends people to a locked gate. Populate operationally, not in the APK.
- **Live bed availability.** Would need an authoritative real-time feed that does not exist
  offline.
- **Multi-city packs.** Schema and `pack_version` are namespaced for it (`kolkata-1.0.0`), but
  get one city right first.

## If you get stuck

Check `07-KNOWN-ISSUES.md` first — most surprising behaviour in this project is already
documented there, including the two that look like your bug but are not: Actian's HNSW
degradation and OneDrive breaking Gradle.
