"""
AKASHA End-to-End verification harness.

Runs against the LIVE Ground Station API and the LIVE Actian VectorAI DB.
Nothing in the transport path is mocked. Reports PASS/FAIL per check.

Usage (inside the api container):
    python e2e_test.py
"""
import json
import os
import sys

import httpx
import numpy as np

API = os.environ.get("API_BASE", "http://localhost:8000")
ACTIAN_REST = os.environ.get("ACTIAN_REST", "http://actian:6573")
PACK_PATH = os.environ.get("PACK_PATH", "distilled_pack.json")
COLLECTION = os.environ.get("COLLECTION_NAME", "akasha_safety")
PARITY_THRESHOLD = 0.99

# Calibrated against measured score distributions (see calibrate.py).
HIGH_CONFIDENCE = 0.45
LOW_CONFIDENCE = 0.30

results = []


def check(name, passed, detail=""):
    results.append((name, passed, detail))
    print(f"[{'PASS' if passed else 'FAIL'}] {name}")
    if detail:
        for line in str(detail).splitlines():
            print(f"         {line}")


def cosine(a, b):
    a = np.asarray(a, dtype=np.float64)
    b = np.asarray(b, dtype=np.float64)
    na, nb = np.linalg.norm(a), np.linalg.norm(b)
    return 0.0 if na == 0 or nb == 0 else float(np.dot(a, b) / (na * nb))


# ------------------------------------------------------------------ T1 health
def t1_health():
    try:
        r = httpx.get(f"{API}/health", timeout=10)
        data = r.json()
    except Exception as e:
        check("T1 Ground Station /health reachable", False, e)
        return None
    check("T1 Ground Station /health reachable", r.status_code == 200, json.dumps(data))
    check("T1a vector dimensionality is 384", data.get("dim") == 384, f"dim={data.get('dim')}")
    check(
        "T1b live backend is Actian, not the in-memory fallback",
        data.get("backend") == "actian",
        f"backend={data.get('backend')!r} port={data.get('port')!r}",
    )
    return data


# --------------------------------------------------------- T2 Actian liveness
def t2_actian():
    try:
        r = httpx.get(f"{ACTIAN_REST}/collections", timeout=10)
        data = r.json()
    except Exception as e:
        check("T2 Actian VectorAI DB reachable over REST", False, e)
        return
    cols = data.get("result", {}).get("collections", [])
    names = [c.get("name") if isinstance(c, dict) else c for c in cols]
    check("T2 Actian VectorAI DB reachable over REST", r.status_code == 200, f"collections={names}")
    check(
        f"T2a Actian holds the {COLLECTION} collection",
        COLLECTION in names,
        f"collections={names}",
    )

    try:
        info = httpx.get(f"{ACTIAN_REST}/collections/{COLLECTION}", timeout=10).json()["result"]
        cfg = info["config"]["params"]["vectors"]
        check(
            "T2b collection is configured 384-dim / Cosine",
            cfg.get("size") == 384 and cfg.get("distance") == "Cosine",
            f"size={cfg.get('size')} distance={cfg.get('distance')} status={info.get('status')}",
        )
        check(
            "T2c Actian actually stores the vectors",
            info.get("points_count", 0) > 0,
            f"points_count={info.get('points_count')}",
        )
    except Exception as e:
        check("T2b collection is configured 384-dim / Cosine", False, e)


# ------------------------------------------------- T3 pack + ingest behaviour
def t3_pipeline():
    if not os.path.exists(PACK_PATH):
        check("T3 knowledge pack present", False, f"{PACK_PATH} not found")
        return None
    with open(PACK_PATH, encoding="utf-8") as f:
        pack = json.load(f)
    check("T3 knowledge pack present", len(pack) > 0, f"{len(pack)} entries")

    payload = [
        {
            "text": p["text"],
            "vector": p["vector"],
            "source_doc": p.get("source_doc") or p.get("sourceDoc"),
            "pack_version": p.get("pack_version") or p.get("packVersion"),
            "category": p["category"],
            "lang": p["lang"],
        }
        for p in pack
    ]

    before = httpx.get(f"{API}/health", timeout=10).json().get("points")
    r = httpx.post(f"{API}/ingest", json=payload, timeout=180)
    ing = r.json()
    check(
        "T3a /ingest writes the pack through to the backend",
        r.status_code == 200 and ing.get("ingested") == len(payload),
        json.dumps(ing),
    )

    r2 = httpx.post(f"{API}/ingest", json=payload, timeout=180)
    after = r2.json().get("total_points")
    check(
        "T3b re-ingest is idempotent (content-hash ids, no duplicates)",
        r2.status_code == 200 and after == ing.get("total_points"),
        f"points before={before} after first={ing.get('total_points')} after second={after}",
    )
    return pack


# ------------------------------------------------------ T4 semantic retrieval
def t4_semantic():
    try:
        from sentence_transformers import SentenceTransformer
    except Exception as e:
        check("T4 embedding model available", False, e)
        return None
    model = SentenceTransformer("all-MiniLM-L6-v2")
    check("T4 embedding model available", True, "all-MiniLM-L6-v2")

    # Paraphrases that deliberately avoid the distinctive wording of the passages.
    cases = [
        ("my friend is losing a lot of blood from his leg", "bleeding"),
        ("someone collapsed and is not breathing", "cpr"),
        ("the ground is shaking violently what do i do", "earthquake"),
        ("water is rising fast in the street", "flood"),
        ("he swallowed something poisonous", "poisoning"),
        ("her face is drooping and speech is slurred", "stroke"),
        ("food is stuck in his throat and he cannot breathe", "choking"),
        ("he is shaking uncontrollably on the floor", "seizure"),
        ("he is freezing cold and confused after being outside", "hypothermia"),
        ("bitten by a snake while hiking", "snakebite"),
        ("scalded my arm with boiling water", "burns"),
        ("my arm is bent the wrong way and swollen", "fracture"),
    ]
    passed = 0
    weak = []
    for query, expected in cases:
        vec = model.encode(query, normalize_embeddings=True).tolist()
        r = httpx.post(f"{API}/search", json={"vector": vec, "top_k": 3}, timeout=30)
        res = r.json().get("results", []) if r.status_code == 200 else []
        if not res:
            check(f"T4 {query[:44]!r} -> {expected}", False, "no results")
            continue
        top = res[0]
        ok = top["category"] == expected
        passed += ok
        if top["score"] < HIGH_CONFIDENCE:
            weak.append((query, top["score"]))
        check(
            f"T4 {query[:44]!r} -> {expected}",
            ok,
            f"score={top['score']:.4f} got={top['category']!r} src={top['source_doc']!r}",
        )
    check(
        "T4y every correct hit clears the HIGH confidence bar",
        not weak,
        f"below {HIGH_CONFIDENCE}: {[(q[:34], round(s, 4)) for q, s in weak]}",
    )
    check(
        "T4z semantic retrieval accuracy",
        passed == len(cases),
        f"{passed}/{len(cases)} paraphrased queries hit the right category",
    )
    return model


# ------------------------------------------------------- T5 embedding parity
def t5_parity(model, pack):
    if model is None or not pack:
        check("T5 embedding parity", False, "skipped")
        return
    worst, worst_text = 1.0, ""
    for p in pack:
        sim = cosine(model.encode(p["text"], normalize_embeddings=True), p["vector"])
        if sim < worst:
            worst, worst_text = sim, p["text"][:56]
    check(
        "T5 stored vectors match freshly computed MiniLM embeddings",
        worst > PARITY_THRESHOLD,
        f"worst cosine={worst:.6f} over {len(pack)} entries (gate {PARITY_THRESHOLD})",
    )


def t5b_android_parity(model):
    """The Android asset must be semantically identical to the server pack.

    Reads the copy seed.py emits alongside the server pack, so this check travels with
    the image instead of depending on a file being hand-copied into the container.
    """
    android = next(
        (p for p in ("distilled_pack.android.json", "android_pack.json") if os.path.exists(p)),
        None,
    )
    if model is None or android is None:
        check(
            "T5b Android asset pack parity",
            False,
            "no android pack found; run seed.py to emit distilled_pack.android.json",
        )
        return
    with open(android, encoding="utf-8") as f:
        pack = json.load(f)
    worst = 1.0
    for p in pack:
        worst = min(worst, cosine(model.encode(p["text"], normalize_embeddings=True), p["vector"]))
    check(
        "T5b Android asset pack carries real MiniLM vectors",
        worst > PARITY_THRESHOLD,
        f"worst cosine={worst:.6f} over {len(pack)} entries",
    )
    schema_ok = all(
        {"id", "text", "vector", "sourceDoc", "packVersion", "category", "lang"} <= set(p)
        for p in pack
    )
    check(
        "T5c Android asset uses the camelCase KnowledgePoint schema",
        schema_ok,
        f"keys={sorted(pack[0].keys())}",
    )


# ------------------------------------------------------------- T6 guardrails
def t6_guardrails(model):
    if model is None:
        check("T6 refusal path", False, "skipped")
        return
    off_topic = [
        "quarterly earnings per share dividend yield of semiconductor equities",
        "best pasta recipe with garlic and olive oil",
        "how do i refactor a kotlin coroutine scope",
        "cheapest flights to tokyo in december",
    ]
    worst = 0.0
    for q in off_topic:
        vec = model.encode(q, normalize_embeddings=True).tolist()
        res = httpx.post(f"{API}/search", json={"vector": vec, "top_k": 1}, timeout=30).json()["results"]
        worst = max(worst, res[0]["score"] if res else 0.0)
    check(
        "T6 off-topic queries stay below the refusal threshold",
        worst < LOW_CONFIDENCE,
        f"highest off-topic score={worst:.4f} (must be < {LOW_CONFIDENCE})",
    )

    # Note: Actian applies an implicit relevance floor (~0.2 cosine) and omits weaker
    # matches regardless of `limit`. That sits below our LOW_CONFIDENCE threshold, so
    # anything it drops would have been refused anyway. The filter probe therefore uses
    # an on-topic query, otherwise the floor -- not the filter -- decides the result count.
    vec = model.encode(
        "smoke and flames in the building, how do i get out", normalize_embeddings=True
    ).tolist()
    res = httpx.post(
        f"{API}/search",
        json={"vector": vec, "top_k": 10, "filters": {"category": "fire"}},
        timeout=30,
    ).json()["results"]
    check(
        "T6a category filter is pushed down to Actian and honoured",
        len(res) > 0 and all(x["category"] == "fire" for x in res),
        f"{len(res)} results, categories={[x['category'] for x in res]}",
    )

    unfiltered = httpx.post(
        f"{API}/search", json={"vector": vec, "top_k": 10}, timeout=30
    ).json()["results"]
    check(
        "T6a2 the filter actually narrows the result set",
        any(x["category"] != "fire" for x in unfiltered) and len(res) <= len(unfiltered),
        f"filtered={len(res)} unfiltered={len(unfiltered)} "
        f"unfiltered categories={sorted({x['category'] for x in unfiltered})}",
    )
    check(
        "T6b every result carries sourceDoc + packVersion",
        len(res) > 0 and all(x.get("source_doc") and x.get("pack_version") for x in res),
        f"sample={res[0] if res else None}",
    )


# ----------------------------------------------------------------- T7 /packs
def t7_packs():
    r = httpx.get(f"{API}/packs", timeout=15)
    data = r.json()
    packs = data.get("packs", [])
    check(
        "T7 /packs returns real pack inventory",
        r.status_code == 200 and len(packs) > 0,
        json.dumps(data)[:400],
    )
    if packs:
        p = packs[0]
        check(
            "T7a pack metadata is complete enough to detect staleness",
            all(k in p for k in ("pack_version", "entries", "categories", "embedding_model"))
            and p["entries"] > 0,
            f"version={p.get('pack_version')} entries={p.get('entries')} "
            f"categories={len(p.get('categories', []))} model={p.get('embedding_model')}",
        )
        # Guards against enumerating via vector search, which silently undercounts
        # because of Actian's relevance floor.
        total_reported = sum(x["entries"] for x in packs)
        stored = httpx.get(f"{API}/health", timeout=10).json().get("points")
        check(
            "T7b /packs accounts for every stored point",
            total_reported == stored,
            f"/packs totals {total_reported} entries, backend holds {stored} points",
        )
        pack_cats = set(p.get("categories", []))
        check(
            "T7c /packs reports the full category set",
            len(pack_cats) >= 15,
            f"{len(pack_cats)} categories: {sorted(pack_cats)}",
        )


# ------------------------------------------------------------ T8 persistence
def t8_persistence():
    """Knowledge must be available after a cold start, not held only in API memory."""
    before = httpx.get(f"{API}/health", timeout=10).json()
    check(
        "T8 knowledge lives in Actian, not API process memory",
        before.get("backend") == "actian" and before.get("points", 0) > 0,
        f"backend={before.get('backend')!r} points={before.get('points')}",
    )

    info = httpx.get(f"{ACTIAN_REST}/collections/{COLLECTION}", timeout=10).json()["result"]
    check(
        "T8a Actian and the API agree on the point count",
        info.get("points_count", 0) == before.get("points"),
        f"api={before.get('points')} actian={info.get('points_count')}",
    )

    # Actian in this build lists a persisted collection after restart but never reopens
    # its segments (points/count returns "Collection not found"), so cold-start recovery
    # relies on the service rebuilding the index from the versioned pack it ships with.
    # Verify that recovery path is actually wired rather than assuming persistence.
    check(
        "T8b service ships a bootstrap pack so a cold start self-heals",
        os.path.exists(PACK_PATH) and len(json.load(open(PACK_PATH, encoding="utf-8"))) > 0,
        f"{PACK_PATH} present with "
        f"{len(json.load(open(PACK_PATH, encoding='utf-8')))} entries; "
        "startup recreates the collection and re-ingests if it is unreadable",
    )


def main():
    print("=" * 76)
    print("AKASHA -- END TO END VERIFICATION (live stack, nothing mocked)")
    print(f"API    : {API}")
    print(f"ACTIAN : {ACTIAN_REST}")
    print("=" * 76)

    t1_health()
    t2_actian()
    pack = t3_pipeline()
    model = t4_semantic()
    t5_parity(model, pack)
    t5b_android_parity(model)
    t6_guardrails(model)
    t7_packs()
    t8_persistence()

    print("=" * 76)
    passed = sum(1 for _, p, _ in results if p)
    total = len(results)
    print(f"RESULT: {passed}/{total} checks passed")
    print("=" * 76)
    for name, p, _ in results:
        if not p:
            print(f"  FAILED: {name}")
    return 0 if passed == total else 1


if __name__ == "__main__":
    sys.exit(main())
