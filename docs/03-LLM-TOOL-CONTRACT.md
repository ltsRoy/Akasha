# Local LLM (Gemma) ↔ AKASHA Contract

## Division of responsibility

The local model is a **translator and presenter**, never a source of facts.

| Gemma does | AKASHA does |
|---|---|
| Parse messy user text into intent + slots | Decide which facilities/passages are eligible |
| Resolve "bone doctor" → `orthopaedic` | Rank by distance and confidence |
| Rephrase returned records into readable prose | Supply names, coordinates, phones, provenance |
| Ask a clarifying question when a slot is missing | Refuse when nothing verified matches |
| Translate output to Bengali/Hindi on request | Attach `source_doc`, `pack_version`, `data_status` |

Gemma must not:

- invent or "correct" a facility name, address, phone number, or coordinate
- state a distance or travel time not present in the tool result
- turn a `data_status: unverified` record into a confident recommendation
- answer a medical question from its own weights when AKASHA refused
- merge two records into one composite facility

This is the same rule the system already enforces elsewhere: retrieval decides, the model
only rephrases retrieved, cited content.

## System prompt (use verbatim as the base)

```
You are the offline assistant inside MeshLink, used during emergencies when there is no
internet. You have no knowledge of your own about places, phone numbers, or medical
procedures. Every factual claim you make must come from a tool result in this conversation.

Rules you must never break:
1. Call a tool before answering any question about facilities, locations, or safety steps.
2. Quote only what the tool returned. Never add a fact the tool did not provide.
3. If a tool returns refused=true or an empty list, tell the user plainly that you have no
   verified information, and give the emergency number 112. Do not guess.
4. If a record has data_status other than "verified", say so in your answer.
5. Never state travel time or directions. You may repeat the distance_km the tool gave you.
6. Always name the source (source_doc) for facility and safety information.
7. If the user's request is missing something you need (such as their location), ask one
   short clarifying question instead of guessing.
8. Keep answers short. People using this are under stress. Lead with the action.

Answer in the language the user wrote in.
```

## Tool 1 — `akasha_find_facility`

Structured facility lookup. This is the Kolkata POI path.

```json
{
  "name": "akasha_find_facility",
  "description": "Find nearby emergency facilities such as hospitals, police stations, shelters or rescue centres. Use this for any question about where to go or who to contact for physical help.",
  "parameters": {
    "type": "object",
    "properties": {
      "category": {
        "type": "string",
        "enum": ["hospital", "clinic", "blood_bank", "pharmacy", "police",
                 "fire_station", "shelter", "rescue_centre",
                 "relief_distribution", "helpline"],
        "description": "Kind of facility required."
      },
      "specialty": {
        "type": "string",
        "enum": ["orthopaedic", "trauma", "burns", "cardiac", "neuro", "paediatric",
                 "maternity", "general_surgery", "general_medicine", "dialysis",
                 "poison_control", "snake_antivenom", "psychiatric",
                 "ophthalmology", "icu", "blood_transfusion"],
        "description": "Only for category=hospital or clinic. Map lay wording to this list, e.g. broken bone -> orthopaedic."
      },
      "emergency_24h_only": {
        "type": "boolean",
        "description": "Restrict to facilities open 24 hours."
      },
      "top_k": { "type": "integer", "minimum": 1, "maximum": 5, "default": 3 },
      "free_text": {
        "type": "string",
        "description": "The user's original wording, passed through for semantic rerank."
      }
    },
    "required": ["category"]
  }
}
```

**The device supplies location, not the model.** There is deliberately no latitude/longitude
parameter. The app injects the current geohash cells from `FusedLocationProvider`. A model
must never be able to hallucinate the user's coordinates.

If location is unavailable the tool returns `needs_location: true` and the model must ask
the user for their area, which the app resolves via the existing geocoder.

### Response

```json
{
  "refused": false,
  "needs_location": false,
  "query": { "category": "hospital", "specialty": "orthopaedic", "origin_geohash5": "tunb4" },
  "results": [
    {
      "id": "poi:kolkata:hospital:sskm",
      "name": "SSKM Hospital (IPGMER)",
      "name_local": "এসএসকেএম হাসপাতাল",
      "category": "hospital",
      "specialties": ["trauma", "orthopaedic", "icu"],
      "address": "244, AJC Bose Road, Bhowanipore, Kolkata 700020",
      "latitude": 22.5390,
      "longitude": 88.3419,
      "distance_km": 1.4,
      "phone": null,
      "emergency_24h": true,
      "operator": "government",
      "data_status": "unverified",
      "source_doc": "WB Health Dept facility directory",
      "pack_version": "kolkata-1.0.0"
    }
  ],
  "backend": "Local POI Pack",
  "tier": "T0_ALONE"
}
```

### Refusal

```json
{
  "refused": true,
  "reason": "No orthopaedic facility found within 15 km of your location in the offline pack.",
  "results": [],
  "fallback_advice": "In an emergency call 112.",
  "backend": "Local POI Pack",
  "tier": "T0_ALONE"
}
```

## Tool 2 — `akasha_ask_safety`

Free-text safety guidance. Wraps the existing `AkashaManager.ask()`.

```json
{
  "name": "akasha_ask_safety",
  "description": "Retrieve verified first-aid or disaster-safety guidance for a described situation. Use for what-do-I-do questions such as bleeding, CPR, earthquake, flood, snake bite.",
  "parameters": {
    "type": "object",
    "properties": {
      "question": { "type": "string", "description": "The user's situation in their own words." },
      "top_k": { "type": "integer", "minimum": 1, "maximum": 5, "default": 3 }
    },
    "required": ["question"]
  }
}
```

Response maps directly from `QueryHandler.QueryResponse`:

```json
{
  "refused": false,
  "confidence": "HIGH",
  "backend": "Ground Station",
  "tier": "T4_FULL",
  "results": [
    {
      "text": "A drooping face on one side, a weak or numb arm, and slurred or confused speech are warning signs of a stroke. Call emergency services immediately and record the time symptoms began. Do not give the person anything to eat or drink.",
      "score": 0.5090,
      "category": "stroke",
      "source_doc": "AHA Stroke Guidelines",
      "pack_version": "1.1.0"
    }
  ]
}
```

`confidence` is one of `HIGH` | `LOW` | `REFUSED`, straight from `QueryHandler.Confidence`.

**When `confidence` is `LOW`, the model must include a hedge** such as "this is the closest
guidance I have, please confirm with a responder if you can". When `REFUSED`, it must not
answer from its own knowledge.

## Tool 3 — `akasha_get_helpline`

Zero-dependency lookup for stable national/state numbers. Always available, never refuses.

```json
{
  "name": "akasha_get_helpline",
  "description": "Get a national or state emergency phone number. Always available even with no data.",
  "parameters": {
    "type": "object",
    "properties": {
      "service": {
        "type": "string",
        "enum": ["all_emergency", "police", "fire", "ambulance", "medical",
                 "disaster", "child", "women"]
      }
    },
    "required": ["service"]
  }
}
```

Returns `{"service":"ambulance","number":"102","scope":"India"}`. These are hardcoded and
`data_status: "verified"`.

## Worked examples

### A. Fracture, isolated device

```
User: my brother fell off the roof, his leg is bent badly, where do I take him

Gemma → akasha_ask_safety {"question": "leg bent badly after falling from height"}
        ← HIGH, fracture passage, Red Cross First Aid Guide

Gemma → akasha_find_facility {"category":"hospital","specialty":"orthopaedic",
                              "free_text":"leg bent badly after fall","top_k":3}
        ← 2 results, nearest 1.4 km, data_status unverified

Gemma: Do not straighten his leg. Support it in the position you found it, pad around it
       with rolled cloth, and keep his toes warm and pink.
       (Red Cross First Aid Guide)

       Nearest orthopaedic facilities:
       1. SSKM Hospital (IPGMER) — approx 1.4 km — 244 AJC Bose Road, Bhowanipore
       2. ...
       These locations come from an offline pack and are not individually verified.
       If you can get through, call 112.
```

### B. Location unknown

```
User: nearest police station

Gemma → akasha_find_facility {"category":"police"}
        ← {"needs_location": true}

Gemma: I do not have your location. Which area of Kolkata are you in?
```

The model asks; it does not assume a location.

### C. Refusal held

```
User: what dose of tramadol for the pain

Gemma → akasha_ask_safety {"question":"tramadol dose for pain"}
        ← refused=true

Gemma: I do not have verified guidance on medicine doses, and I will not guess. If you can
       reach anyone, call 112 or a pharmacist.
```

Note what does *not* happen: Gemma knows tramadol dosing from pretraining. The system prompt
and the refusal must override that. **Write an explicit test for this case.**

## Android integration

```kotlin
/**
 * Executes a tool call from the on-device LLM against AKASHA.
 * The LLM never receives raw backends or the user's coordinates; both are injected here.
 */
interface AkashaToolExecutor {
    suspend fun execute(name: String, argumentsJson: String): String   // JSON result
}
```

Place in `features/knowledge/llm/`. It should:

1. Validate the tool name against the closed set; reject unknown names rather than guessing.
2. Validate enum values. A hallucinated `specialty` must produce a clear error result, not a
   silent empty list — otherwise the model will report "none found" for a typo.
3. Inject location itself, from `FusedLocationProvider`, and compute geohash cells via
   `Geohash.encode(lat, lon, 5)` plus `neighborsSamePrecision`.
4. Enforce a timeout so a stalled mesh fan-out cannot hang the conversation. The mesh path
   already has an 8 s timeout; keep the tool budget above it (~12 s) and return a partial
   result rather than blocking.
5. Log every call and result under tag `AkashaTool` for the same kind of on-device proof the
   debug receiver provides today.

## Model choice note

Gemma is specified by the user. Whatever variant is used, it must fit alongside the **87 MB
fp32 MiniLM** already in assets, on a phone that also runs BLE scanning and a foreground
service. Budget accordingly and measure. If the combined footprint is not viable, the
options in order of preference are: quantise Gemma; download it on first Ground Station
pairing instead of bundling; or drop to slot-extraction by rules and skip the LLM for
facility lookup (the structured path does not actually need a model).

Do not silently ship something that OOMs mid-emergency. Measure and report.
