# POI over BLE Mesh — Wire Protocol

## Constraints

- **Fragment size is 150 bytes.** From `PROJECT_CONTEXT.md`, verified in the existing codec.
- **Never send float vectors.** 384 floats ≈ 1536 bytes ≈ 11 fragments for one query. Send
  text or structured slots; each device embeds locally. Parity guarantees agreement.
- Opcodes live in `protocol/BinaryProtocol.kt`. Taken so far: `0x01` ANNOUNCE, `0x02`
  MESSAGE, `0x03` LEAVE, `0x10` NOISE_HANDSHAKE, `0x11` NOISE_ENCRYPTED, `0x20` FRAGMENT,
  `0x21` REQUEST_SYNC, `0x22` FILE_TRANSFER, `0x30` QUERY, `0x31` QUERY_RESULT.
- The TLV decoder is forward-compatible: unknown tags are skipped, so adding fields is safe
  for older peers.

## New opcodes

```kotlin
POI_QUERY(0x32u),   // structured facility lookup over mesh
POI_RESULT(0x33u);  // facility records in reply
```

Add to `BinaryProtocol.kt`, dispatch in `PacketProcessor.handleReceivedPacket()` alongside
the existing `QUERY` / `QUERY_RESULT` cases, and route through
`BluetoothMeshService` delegates the same way `handleQuery` does.

## Why POI gets its own opcode

`QUERY (0x30)` carries free text and returns text passages. A POI lookup is structured
(category, specialty, geohash) and returns structured records with coordinates. Squeezing it
into 0x30 would mean either sending a text question a peer has to re-parse — losing the
determinism that makes the structured path trustworthy — or overloading the payload with a
mode flag, which is the same thing with extra ambiguity.

Separate opcode, explicit slots, no re-parsing on the responder.

## POI_QUERY (0x32) layout

Fixed header then optional free-text tail. Designed to fit one fragment.

```
offset size  field
0      1     version            = 1
1      8     queryId            (int64, big-endian)
9      1     category           (enum ordinal, see table)
10     1     specialty          (enum ordinal, 0xFF = any)
11     5     originGeohash5     (5 ASCII base32 chars, e.g. "tunb4")
16     1     radiusCells        (0 = origin cell only, 1 = +8 neighbours, 2 = gh4 widen)
17     1     topK               (1..5)
18     1     flags              bit0 ALLOW_GATEWAY, bit1 EMERGENCY_24H_ONLY
19     1     freeTextLen        (0..110)
20     n     freeText           (UTF-8, optional, for semantic rerank)
```

Minimum 20 bytes, maximum 130. Always one fragment.

### Category ordinals

Freeze these. Changing an ordinal silently reinterprets old packets.

```
0 hospital      1 clinic         2 blood_bank    3 pharmacy    4 police
5 fire_station  6 shelter        7 rescue_centre 8 relief_distribution
9 helpline
```

### Specialty ordinals

```
0 orthopaedic   1 trauma         2 burns         3 cardiac     4 neuro
5 paediatric    6 maternity      7 general_surgery
8 general_medicine  9 dialysis  10 poison_control  11 snake_antivenom
12 psychiatric  13 ophthalmology 14 icu          15 blood_transfusion
0xFF any
```

## POI_RESULT (0x33) layout

Records are much larger than text snippets, so cap at **2 records** and keep each compact.

```
offset size  field
0      1     version            = 1
1      8     queryId            (int64, must echo the request)
9      1     count              (0..2)
then per record:
  0    1     nameLen            (1..48)
  1    n     name               (UTF-8, truncated at 48 bytes)
  ...  4     latitudeE6         (int32, degrees x 1e6)
  ...  4     longitudeE6        (int32, degrees x 1e6)
  ...  2     distanceDam        (uint16, decametres; 10 m units, max 655 km)
  ...  1     category           (ordinal)
  ...  2     specialtyMask      (uint16 bitmask over specialty ordinals 0..15)
  ...  1     flags              bit0 EMERGENCY_24H, bit1 HAS_EMERGENCY_DEPT,
                                bit2 VERIFIED, bit3 GOVERNMENT
  ...  1     phoneLen           (0..12)
  ...  n     phone              (ASCII digits, empty when unverified)
  ...  1     idLen              (1..40)
  ...  n     id                 (ASCII, canonical poi id for follow-up detail fetch)
```

Per record: 17 fixed bytes + name + phone + id. With a 48-byte name, 12-byte phone and
40-byte id the worst case is ~117 bytes, so two records fit in **≤ 3 fragments** — matching
the budget QUERY_RESULT already uses.

### Design notes

- `latitudeE6` at 1e-6 degrees is ~0.11 m precision. Ample, and integer-safe.
- `distanceDam` in 10 m units keeps sub-100 m resolution without a float.
- `specialtyMask` as a bitmask carries all 16 specialties in 2 bytes; a CSV would not fit.
- **`VERIFIED` has its own flag bit** so the receiving device can label unverified records in
  the UI. Provenance must survive the wire, not be assumed.
- Address is deliberately omitted: it does not fit and the coordinates plus name are enough
  to navigate. Fetch the full record later via the `id` if the peer is still reachable.

## Codec placement

New file `features/knowledge/MeshPoiCodec.kt`, mirroring `MeshQueryCodec` exactly:

```kotlin
object MeshPoiCodec {
    const val VERSION: Byte = 1
    const val FLAG_ALLOW_GATEWAY: Byte = 0x01
    const val FLAG_EMERGENCY_24H: Byte = 0x02
    const val MAX_RECORDS = 2
    const val MAX_NAME_BYTES = 48
    const val MAX_FREE_TEXT_BYTES = 110

    data class PoiQuery(...)
    data class PoiRecord(...)
    data class PoiQueryResult(val queryId: Long, val records: List<PoiRecord>)

    fun encodeQuery(q: PoiQuery): ByteArray
    fun decodeQuery(b: ByteArray): PoiQuery?      // null on malformed input
    fun encodeResult(r: PoiQueryResult): ByteArray
    fun decodeResult(b: ByteArray): PoiQueryResult?
}
```

Decoders **must return null rather than throw** on truncated or malformed input — the
existing codec does this and the packet processor relies on it. A malformed packet from a
peer must never crash the app.

## Responder behaviour

`MeshPoiRelay` should mirror the hardening already applied to `MeshRelaySearch`, because the
same five bugs apply verbatim:

1. **Dedup by `queryId`** — a broadcast arrives via several neighbours. Bounded set, 256
   entries.
2. **Ignore your own echo** — track ids this device originated.
3. **No re-fan-out** — answering a mesh POI query must not broadcast another POI query.
4. **Honour `ALLOW_GATEWAY`** — do not escalate to the Ground Station when the requester
   cleared the bit.
5. **Silence on no match** — do not ship a far-away or unverified-and-irrelevant facility
   just to have something to send.

Reuse the structure in `MeshRelaySearch.kt`; it is already tested for all five in
`MeshResponderTest.kt`. Write the equivalent tests for POI **before** implementing.

## Gateway escalation

When a gateway peer receives `POI_QUERY` with `ALLOW_GATEWAY` set, it should:

1. Expand `originGeohash5` + `radiusCells` into the cell list.
2. `POST /poi/search` on the Ground Station with those cells and filters.
3. Compute `distanceDam` from the **requester's** origin cell centre, not its own position.
   Getting this wrong makes distances silently wrong for the person who asked — use
   `Geohash.decodeToCenter(originGeohash5)`.

Point 3 is the subtle one. Write a test that asserts distance is measured from the requester.

## Backward compatibility

An older peer that does not know `0x32` will ignore it (the dispatcher's `else` branch), so
deployment is safe and incremental. A newer device gets no reply from an old peer and falls
through the cascade to refusal — correct behaviour, but log it clearly so the field
difference is diagnosable rather than looking like a bug.
