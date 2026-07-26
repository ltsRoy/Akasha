"""
AKASHA Ground Station API.

HTTP JSON shim that fronts the Actian VectorAI DB for the MeshLink Android app.
Mobile clients never speak gRPC directly; they POST 384-dim query vectors here.

Actian VectorAI DB exposes a Qdrant-compatible REST surface (default port 6573):
    PUT    /collections/{name}
    PUT    /collections/{name}/points
    POST   /collections/{name}/points/search
    POST   /collections/{name}/points/count

If Actian is unreachable the service degrades to an in-memory NumPy store so the
app still works in development. /health always reports which backend is live.
"""
import hashlib
import json
import logging
import os
import time
import uuid
from typing import Any, Dict, List, Optional

import httpx
import numpy as np
from fastapi import FastAPI, HTTPException
from pydantic import BaseModel, Field

logging.basicConfig(level=logging.INFO)
logger = logging.getLogger(__name__)

app = FastAPI(title="AKASHA Ground Station API")

ACTIAN_HOST = os.environ.get("ACTIAN_HOST", "localhost")
ACTIAN_PORTS = [
    int(p) for p in os.environ.get("ACTIAN_PORTS", "6573,6574,6575,50051").split(",")
]
COLLECTION_NAME = os.environ.get("COLLECTION_NAME", "akasha_safety")
VECTOR_DIM = int(os.environ.get("VECTOR_DIM", "384"))


def text_to_point_id(text: str) -> str:
    """Deterministic UUID from the text content hash.

    Actian/Qdrant point ids must be unsigned ints or UUIDs, so the raw SHA-256
    hex digest cannot be used directly. Deriving a UUID from the digest keeps
    ingest idempotent while satisfying the id type constraint.
    """
    digest = hashlib.sha256(text.encode("utf-8")).digest()
    return str(uuid.UUID(bytes=digest[:16]))


class InMemoryStore:
    """Development fallback. Not durable: contents are lost on restart."""

    backend_name = "in-memory"

    def __init__(self) -> None:
        self.points: Dict[str, dict] = {}
        self.port = None

    def upsert(self, points: List[dict]) -> int:
        written = 0
        for p in points:
            if p["id"] not in self.points:
                written += 1
            self.points[p["id"]] = p
        return written

    def verify_recall(self) -> bool:
        """Exact cosine over a dict has no index to degrade."""
        return True

    def search(
        self,
        vector: List[float],
        top_k: int = 5,
        filters: Optional[Dict[str, Any]] = None,
    ) -> List[dict]:
        if not self.points:
            return []
        query_vec = np.asarray(vector, dtype=np.float64)
        norm_q = np.linalg.norm(query_vec)
        results = []
        for p_data in self.points.values():
            payload = p_data["payload"]
            if filters and any(payload.get(k) != v for k, v in filters.items()):
                continue
            v = np.asarray(p_data["vector"], dtype=np.float64)
            norm_v = np.linalg.norm(v)
            score = (
                0.0
                if norm_q == 0 or norm_v == 0
                else float(np.dot(query_vec, v) / (norm_q * norm_v))
            )
            results.append({"score": score, "payload": payload})
        results.sort(key=lambda x: x["score"], reverse=True)
        return results[:top_k]

    def count(self) -> int:
        return len(self.points)

    def all_payloads(self) -> List[dict]:
        return [p["payload"] for p in self.points.values()]


class ActianStore:
    """Actian VectorAI DB client over its Qdrant-compatible REST API."""

    backend_name = "actian"

    def __init__(self, base_url: str, collection: str, dim: int, port: int) -> None:
        self.base_url = base_url.rstrip("/")
        self.collection = collection
        self.dim = dim
        self.port = port
        self.client = httpx.Client(timeout=30.0)

    # -- lifecycle ----------------------------------------------------------
    @classmethod
    def probe(cls, host: str, ports: List[int], collection: str, dim: int):
        """Try each candidate port until one answers the REST collections call."""
        for port in ports:
            base = f"http://{host}:{port}"
            try:
                r = httpx.get(f"{base}/collections", timeout=4.0)
                if r.status_code == 200 and "result" in r.json():
                    logger.info("Actian REST detected on port %s", port)
                    store = cls(base, collection, dim, port)
                    store.ensure_collection()
                    try:
                        store.await_ready()
                    except RuntimeError:
                        # The collection is listed but its segments never opened, so it
                        # cannot be read or written. Rebuild it from the shipped pack.
                        store.recreate()
                    return store
            except Exception as e:
                logger.warning("Actian not usable on port %s yet: %s", port, e)
        return None

    def is_queryable(self) -> bool:
        try:
            self.count()
            return True
        except Exception:
            return False

    def await_ready(self, attempts: int = 8, delay: float = 2.0) -> None:
        """Block until the collection actually answers point queries.

        Actian lists a collection in /collections before its segments are open, and
        point operations return 404 "Collection not found" during that window. If the
        window never closes the collection did not reload at all, and the caller is
        expected to rebuild it.
        """
        for i in range(attempts):
            if self.is_queryable():
                if i:
                    logger.info("Actian collection ready after ~%.0fs", i * delay)
                return
            time.sleep(delay)
        raise RuntimeError(f"collection {self.collection!r} never became queryable")

    def recreate(self) -> None:
        """Drop and recreate the collection."""
        logger.warning("Recreating Actian collection %r", self.collection)
        try:
            self.client.delete(f"{self.base_url}/collections/{self.collection}")
        except Exception as e:
            logger.warning("Delete during recreate failed (continuing): %s", e)
        self._unwrap(
            self.client.put(
                f"{self.base_url}/collections/{self.collection}",
                json={"vectors": {"size": self.dim, "distance": "Cosine"}},
            )
        )
        self.await_ready()

    def _unwrap(self, r: httpx.Response) -> Any:
        r.raise_for_status()
        return r.json().get("result")

    def ensure_collection(self) -> None:
        existing = self._unwrap(self.client.get(f"{self.base_url}/collections"))
        names = {
            (c.get("name") if isinstance(c, dict) else c)
            for c in (existing or {}).get("collections", [])
        }
        if self.collection in names:
            logger.info("Actian collection %r already present", self.collection)
            return
        logger.info("Creating Actian collection %r (dim=%s)", self.collection, self.dim)
        self._unwrap(
            self.client.put(
                f"{self.base_url}/collections/{self.collection}",
                json={"vectors": {"size": self.dim, "distance": "Cosine"}},
            )
        )

    # -- data ---------------------------------------------------------------
    def existing_ids(self) -> set:
        """Ids currently stored, used to avoid rewriting unchanged points."""
        ids = set()
        offset = None
        while True:
            body: Dict[str, Any] = {"limit": 256, "with_payload": False, "with_vector": False}
            if offset is not None:
                body["offset"] = offset
            raw = self._unwrap(
                self.client.post(
                    f"{self.base_url}/collections/{self.collection}/points/scroll",
                    json=body,
                )
            )
            if isinstance(raw, dict):
                batch = raw.get("points") or []
                offset = raw.get("next_page_offset")
            else:
                batch = raw or []
                offset = None
            ids.update(p.get("id") for p in batch)
            if not offset or not batch:
                break
        return ids

    def upsert(self, points: List[dict]) -> int:
        """Write points, skipping any whose id is already stored.

        Point ids are content hashes, so an id that already exists holds byte-identical
        data and rewriting it achieves nothing. Re-sending them does real harm: repeatedly
        upserting the same ids progressively degrades Actian's HNSW graph until recall
        collapses to exact matches only and the collection reports "red". At that point
        every paraphrased query silently returns zero results, which for a safety corpus
        is a worse failure than an outright error.

        Returns the number of points actually written.
        """
        try:
            already = self.existing_ids()
        except Exception as e:
            logger.warning("Could not enumerate existing ids, writing all: %s", e)
            already = set()

        fresh = [p for p in points if p["id"] not in already]
        skipped = len(points) - len(fresh)
        if skipped:
            logger.info("Skipping %s unchanged point(s) to protect the index", skipped)
        if not fresh:
            return 0

        body = {
            "points": [
                {"id": p["id"], "vector": p["vector"], "payload": p["payload"]}
                for p in fresh
            ]
        }
        self._unwrap(
            self.client.put(
                f"{self.base_url}/collections/{self.collection}/points",
                params={"wait": "true"},
                json=body,
            )
        )
        return len(fresh)

    def verify_recall(self) -> bool:
        """Confirm the index can actually retrieve, not just hold, its vectors.

        Takes a stored vector and searches with it. A healthy HNSW graph returns that
        point at score ~1.0 plus its neighbours. A degraded graph returns only the exact
        match (or nothing), which is indistinguishable from "no answer exists" at the API
        layer -- so it has to be probed explicitly rather than inferred from point counts.
        """
        try:
            if self.count() == 0:
                return True  # nothing to retrieve yet; not a failure
            raw = self._unwrap(
                self.client.post(
                    f"{self.base_url}/collections/{self.collection}/points/scroll",
                    json={"limit": 1, "with_payload": False, "with_vector": True},
                )
            )
            batch = raw.get("points") if isinstance(raw, dict) else raw
            if not batch:
                return False
            probe = batch[0].get("vector")
            if not probe:
                return False

            hits = self.search(probe, top_k=5)
            if not hits:
                logger.error("Recall probe returned nothing at all")
                return False
            if hits[0]["score"] < 0.99:
                logger.error("Recall probe did not retrieve itself (top=%.4f)", hits[0]["score"])
                return False
            # A 32+ point corpus must yield neighbours, not just the identity match.
            if self.count() > 2 and len(hits) < 2:
                logger.error(
                    "Recall probe found only the exact match; HNSW graph is degraded"
                )
                return False
            return True
        except Exception as e:
            logger.error("Recall verification failed: %s", e)
            return False

    def search(
        self,
        vector: List[float],
        top_k: int = 5,
        filters: Optional[Dict[str, Any]] = None,
    ) -> List[dict]:
        body: Dict[str, Any] = {
            "vector": vector,
            "limit": top_k,
            "with_payload": True,
        }
        if filters:
            body["filter"] = {
                "must": [
                    {"key": k, "match": {"value": v}} for k, v in filters.items()
                ]
            }
        raw = self._unwrap(
            self.client.post(
                f"{self.base_url}/collections/{self.collection}/points/search",
                json=body,
            )
        )
        return [
            {"score": float(hit.get("score", 0.0)), "payload": hit.get("payload") or {}}
            for hit in (raw or [])
        ]

    def count(self) -> int:
        raw = self._unwrap(
            self.client.post(
                f"{self.base_url}/collections/{self.collection}/points/count", json={}
            )
        )
        return int((raw or {}).get("count", 0))

    def all_payloads(self) -> List[dict]:
        """Enumerate every stored payload via the scroll API.

        Enumeration must not go through vector search: a similarity query needs a
        reference vector, and a zero vector has no direction under cosine distance,
        so it silently returns only a subset of points.
        """
        payloads: List[dict] = []
        offset = None
        while True:
            body: Dict[str, Any] = {
                "limit": 256,
                "with_payload": True,
                "with_vector": False,
            }
            if offset is not None:
                body["offset"] = offset
            raw = self._unwrap(
                self.client.post(
                    f"{self.base_url}/collections/{self.collection}/points/scroll",
                    json=body,
                )
            )
            if isinstance(raw, dict):
                batch = raw.get("points") or []
                offset = raw.get("next_page_offset")
            else:
                batch = raw or []
                offset = None
            payloads.extend(p.get("payload") or {} for p in batch)
            if not offset or not batch:
                break
        return payloads


store: Any = None


def init_store() -> bool:
    """Bind to Actian if possible, otherwise the in-memory fallback.

    Never raises: a transient Actian problem must not take the API down, because the
    app depends on this shim being reachable to know the Ground Station exists at all.
    """
    global store
    try:
        actian = ActianStore.probe(ACTIAN_HOST, ACTIAN_PORTS, COLLECTION_NAME, VECTOR_DIM)
    except Exception as e:
        logger.error("Actian initialization error: %s", e)
        actian = None

    if actian is not None:
        store = actian
        logger.info(
            "Using Actian VectorAI DB at %s (collection=%s, points=%s)",
            actian.base_url,
            COLLECTION_NAME,
            actian.count(),
        )
        return True

    if store is None:
        store = InMemoryStore()
    logger.warning(
        "Actian VectorAI DB unusable on %s:%s - serving from in-memory fallback",
        ACTIAN_HOST,
        ACTIAN_PORTS,
    )
    return False


def ensure_actian() -> None:
    """Upgrade from the fallback to Actian once it becomes available.

    Lets the API survive being started before the database and self-heal afterwards
    instead of staying degraded until someone restarts it.
    """
    global store
    if isinstance(store, ActianStore):
        return
    try:
        actian = ActianStore(
            f"http://{ACTIAN_HOST}:{ACTIAN_PORTS[0]}",
            COLLECTION_NAME,
            VECTOR_DIM,
            ACTIAN_PORTS[0],
        )
        actian.ensure_collection()
        actian.count()
    except Exception:
        return
    logger.info("Actian became available; switching off the in-memory fallback")
    store = actian
    bootstrap_from_pack()


BOOTSTRAP_PACK = os.environ.get("BOOTSTRAP_PACK", "distilled_pack.json")


def bootstrap_from_pack() -> None:
    """Load the shipped knowledge pack if the backend is empty.

    The corpus is a versioned build artifact (produced by seed.py with precomputed
    vectors), not user data, so rebuilding the index from it is always safe and
    deterministic. This is what makes the Ground Station survive a cold start even
    though Actian in this build does not reopen a persisted collection.
    """
    if store is None:
        return
    try:
        if store.count() > 0:
            return
    except Exception:
        return

    if not os.path.exists(BOOTSTRAP_PACK):
        logger.warning("No bootstrap pack at %s; backend starts empty", BOOTSTRAP_PACK)
        return

    try:
        with open(BOOTSTRAP_PACK, encoding="utf-8") as f:
            pack = json.load(f)
        points = [
            {
                "id": text_to_point_id(item["text"]),
                "vector": item["vector"],
                "payload": {
                    "text": item["text"],
                    "source_doc": item.get("source_doc") or item.get("sourceDoc", ""),
                    "pack_version": item.get("pack_version")
                    or item.get("packVersion", ""),
                    "category": item.get("category", ""),
                    "lang": item.get("lang", "en"),
                },
            }
            for item in pack
        ]
        store.upsert(points)
        logger.info(
            "Bootstrapped %s knowledge points from %s into %s",
            store.count(),
            BOOTSTRAP_PACK,
            store.backend_name,
        )
    except Exception as e:
        logger.error("Bootstrap from pack failed: %s", e)


def ensure_healthy_index() -> None:
    """Rebuild the collection if it holds vectors it can no longer retrieve.

    Guards the failure mode where the index degrades and every semantic query quietly
    returns nothing. Rebuilding is cheap and safe because the corpus is a versioned
    build artifact, so the worst case is re-ingesting a pack we already have.
    """
    global store
    if not isinstance(store, ActianStore):
        return
    if store.verify_recall():
        return

    logger.error("Index cannot retrieve its own vectors; rebuilding from the pack")
    try:
        store.recreate()
    except Exception as e:
        logger.error("Rebuild failed: %s", e)
        return
    bootstrap_from_pack()

    if store.verify_recall():
        logger.info("Index rebuilt and recall restored")
    else:
        logger.error("Recall still broken after rebuild; serving may be degraded")


@app.on_event("startup")
async def startup_event() -> None:
    init_store()
    bootstrap_from_pack()
    ensure_healthy_index()


class SearchRequest(BaseModel):
    vector: List[float] = Field(..., max_length=VECTOR_DIM, min_length=VECTOR_DIM)
    top_k: int = 5
    filters: Optional[Dict[str, Any]] = None


class IngestItem(BaseModel):
    text: str
    vector: List[float] = Field(..., max_length=VECTOR_DIM, min_length=VECTOR_DIM)
    source_doc: str
    pack_version: str
    category: str
    lang: str


class SearchResult(BaseModel):
    text: str
    source_doc: str
    pack_version: str
    score: float
    category: str


@app.get("/health")
async def health():
    # Health is what the Android client polls to decide whether a Ground Station is
    # reachable, so use it as the point to recover from a degraded start.
    ensure_actian()
    if store is None:
        raise HTTPException(status_code=503, detail="Store not initialized")
    try:
        count = store.count()
    except Exception as e:
        logger.error("Backend health probe failed: %s", e)
        raise HTTPException(status_code=503, detail=f"backend unhealthy: {e}")
    # Reported so a client can distinguish "no match exists" from "the index is broken".
    # Without it, a degraded index looks identical to an empty answer.
    recall_ok = store.verify_recall()
    return {
        "ok": True,
        "backend": store.backend_name,
        "port": store.port,
        "collection": COLLECTION_NAME,
        "dim": VECTOR_DIM,
        "points": count,
        "recall_ok": recall_ok,
    }


@app.post("/search")
async def search(req: SearchRequest):
    if store is None:
        raise HTTPException(status_code=500, detail="Store not initialized")
    try:
        raw_results = store.search(req.vector, req.top_k, req.filters)
    except Exception as e:
        logger.error("Search failed: %s", e)
        raise HTTPException(status_code=500, detail=str(e))

    formatted = []
    for r in raw_results:
        payload = r["payload"]
        formatted.append(
            {
                "text": payload.get("text", ""),
                "source_doc": payload.get("source_doc", ""),
                "pack_version": payload.get("pack_version", ""),
                "score": r["score"],
                "category": payload.get("category", ""),
            }
        )
    return {"results": formatted}


@app.post("/ingest")
async def ingest(items: List[IngestItem]):
    if store is None:
        raise HTTPException(status_code=500, detail="Store not initialized")

    points = [
        {
            "id": text_to_point_id(item.text),
            "vector": item.vector,
            "payload": {
                "text": item.text,
                "source_doc": item.source_doc,
                "pack_version": item.pack_version,
                "category": item.category,
                "lang": item.lang,
            },
        }
        for item in items
    ]

    try:
        written = store.upsert(points)
        total = store.count()
    except Exception as e:
        logger.error("Ingest failed: %s", e)
        raise HTTPException(status_code=500, detail=str(e))

    return {
        # Accepted count stays stable across repeat calls so callers can treat ingest as
        # idempotent; `written` exposes how many were genuinely new.
        "ingested": len(points),
        "written": written,
        "skipped": len(points) - written,
        "collection": COLLECTION_NAME,
        "backend": store.backend_name,
        "total_points": total,
    }


@app.post("/reindex")
async def reindex():
    """Drop and rebuild the collection from the shipped pack.

    Manual recovery hook for the degraded-index case. Destructive for anything ingested
    beyond the bundled pack, so it is deliberately explicit rather than automatic here
    (startup already self-heals when it detects broken recall).
    """
    if store is None:
        raise HTTPException(status_code=500, detail="Store not initialized")
    if not isinstance(store, ActianStore):
        raise HTTPException(status_code=400, detail="Reindex only applies to Actian")
    try:
        store.recreate()
        bootstrap_from_pack()
        return {
            "reindexed": True,
            "points": store.count(),
            "recall_ok": store.verify_recall(),
        }
    except Exception as e:
        logger.error("Reindex failed: %s", e)
        raise HTTPException(status_code=500, detail=str(e))


@app.get("/packs")
async def packs(since: Optional[str] = None):
    """Real pack inventory derived from stored payloads.

    Lets the app decide whether its bundled offline pack is stale.
    """
    if store is None:
        raise HTTPException(status_code=500, detail="Store not initialized")

    try:
        payloads = store.all_payloads()
    except Exception as e:
        logger.error("Pack listing failed: %s", e)
        raise HTTPException(status_code=500, detail=str(e))

    grouped: Dict[str, Dict[str, Any]] = {}
    for p in payloads:
        version = p.get("pack_version", "unknown")
        entry = grouped.setdefault(
            version,
            {
                "pack_version": version,
                "collection": COLLECTION_NAME,
                "entries": 0,
                "categories": set(),
                "source_docs": set(),
                "langs": set(),
            },
        )
        entry["entries"] += 1
        entry["categories"].add(p.get("category", ""))
        entry["source_docs"].add(p.get("source_doc", ""))
        entry["langs"].add(p.get("lang", "en"))

    result = []
    for entry in grouped.values():
        if since and entry["pack_version"] <= since:
            continue
        result.append(
            {
                "pack_version": entry["pack_version"],
                "collection": entry["collection"],
                "entries": entry["entries"],
                "categories": sorted(c for c in entry["categories"] if c),
                "source_docs": sorted(s for s in entry["source_docs"] if s),
                "langs": sorted(l for l in entry["langs"] if l),
                "dim": VECTOR_DIM,
                "embedding_model": "all-MiniLM-L6-v2",
            }
        )
    result.sort(key=lambda x: x["pack_version"], reverse=True)
    return {"packs": result, "backend": store.backend_name}
