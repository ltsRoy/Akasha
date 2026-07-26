# Kolkata Emergency Facility (POI) Data Model

## The central design decision, and why

**Do not store facilities as semantic text chunks in `akasha_safety`.** It is tempting
because the retrieval machinery already exists, but it produces wrong answers for the exact
question users will ask.

"Nearest orthopaedic hospital" needs two things cosine similarity cannot provide:

1. **Distance ranking.** An embedding of "SSKM Hospital, orthopaedic, Bhowanipore" is no
   closer in vector space to a user 1 km away than to one 30 km away. Semantic search will
   confidently return a facility across the city.
2. **Hard attribute filters.** "Has a burns unit" is a boolean fact, not a similarity
   score. A 0.62-similarity match on "burns" might be a hospital that merely mentions burns
   in its description.

In an emergency, sending someone to the wrong hospital is a safety failure, not a relevance
failure.

**Therefore: hybrid retrieval.** Structured filtering decides *which* facilities are
eligible; embeddings only help interpret fuzzy user wording.

```
user text ──► (a) intent + slots ──► (b) STRUCTURED FILTER  ──► (c) DISTANCE SORT ──► (d) optional semantic rerank
                 e.g. "bone doctor"     geohash cells            haversine km          for tie-breaks / free text
                 -> orthopaedic         + category + specialty
```

Step (b) is exact and cheap. Step (d) is optional polish. The embedding is used for
**slot resolution** ("bone doctor" → `orthopaedic`) and never for choosing between
facilities that the filter already ranked by distance.

## Geohash strategy

Reuse the existing `com.MeshLink.android.geohash.Geohash` — it already has `encode`,
`decodeToBounds` and `neighborsSamePrecision`.

| Precision | Cell size | Use |
|---|---|---|
| 4 | ~39 × 20 km | City-region bucket, pack partitioning |
| **5** | **~4.9 × 4.9 km** | **Primary index. "Nearby" queries.** |
| 6 | ~1.2 × 0.6 km | Reserved; too fine for facility density |

**Critical gotcha, verified by computation:** Kolkata straddles two geohash-4 cells.

| Area | gh4 | gh5 |
|---|---|---|
| Esplanade (centre) | `tunb` | `tunb6` |
| SSKM / PG Hospital | `tunb` | `tunb4` |
| R G Kar Medical College | `tunb` | `tunbe` |
| Salt Lake Sector V | `tunb` | `tunbk` |
| Howrah Station | `tunb` | `tunb6` |
| Dum Dum Airport | `tunb` | `tunbu` |
| **Garia (south)** | **`tgyz`** | `tgyzg` |
| **Behala (south-west)** | **`tgyz`** | `tgyzc` |

A single-prefix query on `tunb` **silently loses all of south Kolkata**. Always query the
user's gh5 cell **plus its 8 neighbours** (`neighborsSamePrecision`), which covers roughly
15 × 15 km. If fewer than `top_k` results are found, widen to the gh4 prefix set
`{tunb, tgyz}` before giving up.

Write a test for the Garia case specifically. It is the one that will regress.

## Categories

Closed vocabulary. Never invent a category at query time.

| `category` | Meaning |
|---|---|
| `hospital` | Inpatient medical facility |
| `clinic` | Outpatient / primary health centre |
| `blood_bank` | Blood collection or storage |
| `pharmacy` | Medicine dispensary, esp. 24-hour |
| `police` | Police station or outpost |
| `fire_station` | Fire and emergency services |
| `shelter` | Cyclone/flood shelter, relief camp |
| `rescue_centre` | NDRF/SDRF/civil defence staging |
| `relief_distribution` | Food/water distribution point |
| `helpline` | Phone-only service, no physical location |

## Hospital specialties

`specialties` is a **list**, because one hospital has many. Filtering is "must contain".

`orthopaedic`, `trauma`, `burns`, `cardiac`, `neuro`, `paediatric`, `maternity`,
`general_surgery`, `general_medicine`, `dialysis`, `poison_control`, `snake_antivenom`,
`psychiatric`, `ophthalmology`, `icu`, `blood_transfusion`

Provide a **synonym map** so the LLM's slot resolution is deterministic rather than
model-dependent:

```json
{
  "orthopaedic": ["bone", "fracture", "broken bone", "orthopedic", "joint", "haar", "haddi"],
  "burns":       ["burn", "scald", "fire injury", "jhulse"],
  "cardiac":     ["heart", "chest pain", "cardiology", "heart attack"],
  "trauma":      ["accident", "emergency", "casualty", "road accident"],
  "paediatric":  ["child", "kids", "baby", "children", "shishu"],
  "maternity":   ["pregnancy", "delivery", "labour", "obstetric", "prasab"],
  "snake_antivenom": ["snake bite", "antivenom", "saap"],
  "poison_control":  ["poison", "overdose", "swallowed chemical"]
}
```

Bengali/Hinglish terms matter for Kolkata. Keep them in the synonym map, not in the model.

## Canonical record schema

One record per facility. This is the contract; both stores derive from it.

```json
{
  "id": "poi:kolkata:hospital:sskm",
  "name": "SSKM Hospital (IPGMER)",
  "name_local": "এসএসকেএম হাসপাতাল",
  "category": "hospital",
  "specialties": ["trauma", "orthopaedic", "general_surgery", "icu", "burns"],
  "latitude": 22.5390,
  "longitude": 88.3419,
  "geohash4": "tunb",
  "geohash5": "tunb4",
  "address": "244, AJC Bose Road, Bhowanipore, Kolkata 700020",
  "ward": "70",
  "phone": null,
  "alt_phone": null,
  "emergency_24h": true,
  "capacity_beds": null,
  "has_emergency_dept": true,
  "wheelchair_accessible": null,
  "operator": "government",
  "source_doc": "WB Health Dept facility directory",
  "source_url": null,
  "verified_on": null,
  "data_status": "unverified",
  "pack_version": "kolkata-1.0.0",
  "lang": "en",
  "notes": null
}
```

### Field rules

- `id` — stable, human-readable, `poi:<city>:<category>:<slug>`. Used for dedup and for the
  mesh wire format's compact reference.
- `phone` — **null unless verified.** See "Data integrity" below.
- `data_status` — one of `verified`, `unverified`, `stale`, `disputed`. The UI **must**
  surface anything that is not `verified`.
- `verified_on` — ISO date. Absent means never verified.
- `operator` — `government` | `private` | `ngo` | `military` | `unknown`. Users need this;
  a private hospital may refuse a casualty without payment.
- `geohash4` / `geohash5` — derived, stored denormalised so they can be indexed.

## Data integrity — read this before adding data

A wrong hospital phone number in a disaster is worse than no number. The same applies to
coordinates: a pin 2 km off can send someone across a flooded canal.

Rules:

1. **Never invent a phone number, address, or coordinate.** If you are not working from a
   source, leave the field `null` and set `data_status: "unverified"`.
2. Facility names and approximate locations of major Kolkata hospitals are well established
   and safe to seed. Precise phone numbers are not — they change and are frequently wrong in
   scraped datasets.
3. **National/state helplines are safe to hardcode** because they are stable and
   well-known:

| Service | Number |
|---|---|
| All-in-one emergency | 112 |
| Police | 100 |
| Fire | 101 |
| Ambulance | 102 |
| Emergency medical (EMRI) | 108 |
| Disaster management (state) | 1070 |
| Childline | 1098 |
| Women's helpline | 1091 |

4. The seed file must pass a **schema validation test** before ingest — see
   `06-TESTING-STRATEGY.md`.
5. Recommended real sources for the verification pass: West Bengal Health & Family Welfare
   facility directory, Kolkata Municipal Corporation ward data, Kolkata Police station list,
   OpenStreetMap (`amenity=hospital`, `healthcare:speciality`) via Overpass. OSM is the most
   practical bulk source and carries an ODbL attribution requirement — record it in
   `source_doc`/`source_url`.

## Storage — on device

New Room entities alongside the existing `AppDatabase` (currently version 1, one entity).
Bump the version and **write a real migration**; the database currently uses
`fallbackToDestructiveMigration()`, which would silently wipe user messages. Change that.

```kotlin
@Entity(
    tableName = "poi",
    indices = [
        Index(value = ["geohash5", "category"]),   // primary access path
        Index(value = ["geohash4", "category"]),   // widened fallback
        Index(value = ["category"])
    ]
)
data class PoiEntity(
    @PrimaryKey val id: String,
    val name: String,
    val nameLocal: String?,
    val category: String,
    val specialtiesCsv: String,      // Room has no list type; store "a,b,c"
    val latitude: Double,
    val longitude: Double,
    val geohash4: String,
    val geohash5: String,
    val address: String?,
    val phone: String?,
    val emergency24h: Boolean,
    val hasEmergencyDept: Boolean,
    val operator: String,
    val sourceDoc: String,
    val verifiedOn: String?,
    val dataStatus: String,
    val packVersion: String
)
```

Query shape — filter in SQL, sort by distance in Kotlin (SQLite has no haversine):

```kotlin
@Query("""
    SELECT * FROM poi
    WHERE geohash5 IN (:cells)
      AND category = :category
      AND (:specialty IS NULL OR specialtiesCsv LIKE '%' || :specialty || '%')
""")
suspend fun findNearby(cells: List<String>, category: String, specialty: String?): List<PoiEntity>
```

`specialtiesCsv LIKE` is a deliberate simplification. Guard it: `orthopaedic` must not match
a hypothetical `neuro_orthopaedic`. Either wrap values (`",a,b,c,"` and match `",a,"`) or
normalise into a join table. **Wrap them** — it is one line and removes the whole class of
bug.

## Storage — Ground Station

A **separate Actian collection**, `akasha_poi`. Do not mix with `akasha_safety`: different
payload schema, different retrieval semantics, and mixing them means a safety question can
return a hospital record.

```
PUT /collections/akasha_poi   {"vectors":{"size":384,"distance":"Cosine"}}
```

Payload carries every filterable field so filters push down to the database:

```json
{
  "id": "poi:kolkata:hospital:sskm",
  "name": "SSKM Hospital (IPGMER)",
  "category": "hospital",
  "specialties": ["trauma", "orthopaedic"],
  "geohash4": "tunb",
  "geohash5": "tunb4",
  "latitude": 22.5390,
  "longitude": 88.3419,
  "phone": null,
  "operator": "government",
  "data_status": "unverified",
  "pack_version": "kolkata-1.0.0",
  "source_doc": "WB Health Dept facility directory"
}
```

The **vector** is the embedding of a canonical descriptor string, used only for rerank and
slot resolution:

```
"{name} — {category} in {locality}, Kolkata. Specialties: {specialties joined}. {operator} facility."
```

Build it deterministically in `seed_poi.py` so it can be regenerated identically.

New endpoints on the Ground Station:

| Endpoint | Purpose |
|---|---|
| `POST /poi/search` | `{geohash_cells[], category, specialty?, top_k, vector?}` → ranked records |
| `POST /poi/ingest` | Same idempotent, skip-unchanged semantics as `/ingest` |
| `GET /poi/packs` | Pack inventory for staleness checks |

Reuse `ActianStore` — give it a `collection` parameter rather than duplicating the class.
**Keep the skip-unchanged behaviour in `upsert()`**: re-upserting identical ids is what
destroyed the HNSW index once already (`07-KNOWN-ISSUES.md`).

## Distance calculation

```kotlin
/** Haversine distance in km. Earth radius 6371.0088 km (mean). */
fun distanceKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double
```

Report distance to the user as **"approx N.N km"**, never as travel time or a route. The app
has no routing data, roads may be flooded, and implying a route it cannot verify would be
misleading in exactly the situation where it matters.

## Pack versioning

`pack_version: "kolkata-1.0.0"`, namespaced by city so multiple city packs can coexist. The
existing `/packs` endpoint already groups by `pack_version`; `/poi/packs` should mirror it so
a device can detect a stale bundled pack and pull an update from a Ground Station.

## Deliverables for this section

1. `ground-station/poi/kolkata_poi.json` — seed records, schema-valid, honest
   `data_status`.
2. `ground-station/poi/schema.json` — JSON Schema for validation.
3. `ground-station/poi/synonyms.json` — the specialty synonym map.
4. `ground-station/seed_poi.py` — builds descriptors, embeds, emits server + Android packs.
5. `app/src/main/assets/akasha/kolkata_poi.json` — Android asset.
6. Room entity, DAO, migration, and `PoiSearch` implementing the filter → sort pipeline.
7. Tests per `06-TESTING-STRATEGY.md`, including the Garia geohash boundary case.
