"""
Single source of truth for AKASHA knowledge packs.

Reads corpus.json, computes REAL all-MiniLM-L6-v2 embeddings (384-dim, L2
normalized), then emits both artifacts from that one computation so the
Ground Station and the Android app can never drift apart:

  1. POST /ingest  -> Ground Station (snake_case wire schema)
  2. android_pack  -> Android asset (camelCase schema + content-hash id)

Usage:
    python seed.py                      # ingest + write android pack
    python seed.py --no-ingest          # only write the android pack
    python seed.py --android-out PATH   # override android asset path
"""
import argparse
import hashlib
import json
import os
import sys

import httpx
from sentence_transformers import SentenceTransformer

MODEL_NAME = "all-MiniLM-L6-v2"
VECTOR_DIM = 384
API_BASE = os.environ.get("API_BASE", "http://localhost:8000")
DEFAULT_ANDROID_OUT = os.environ.get("ANDROID_OUT", "distilled_pack.android.json")


def content_id(text: str) -> str:
    """Stable content-hash id. Matches the Ground Station's ingest id scheme."""
    return hashlib.sha256(text.encode("utf-8")).hexdigest()


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--corpus", default="corpus.json")
    ap.add_argument("--android-out", default=DEFAULT_ANDROID_OUT)
    ap.add_argument("--gs-out", default="distilled_pack.json")
    ap.add_argument("--no-ingest", action="store_true")
    ap.add_argument("--api", default=API_BASE)
    args = ap.parse_args()

    print(f"Loading model {MODEL_NAME} ...")
    model = SentenceTransformer(MODEL_NAME)

    with open(args.corpus, "r", encoding="utf-8") as f:
        corpus = json.load(f)
    print(f"Loaded {len(corpus)} entries from {args.corpus}")

    texts = [item["text"] for item in corpus]
    print("Computing embeddings ...")
    vectors = model.encode(
        texts, normalize_embeddings=True, batch_size=32, show_progress_bar=False
    )

    gs_pack = []
    android_pack = []
    for item, vec in zip(corpus, vectors):
        vec_list = [round(float(v), 8) for v in vec]
        if len(vec_list) != VECTOR_DIM:
            raise SystemExit(f"embedding dim {len(vec_list)} != {VECTOR_DIM}")

        text = item["text"]
        source_doc = item["source_doc"]
        pack_version = item["pack_version"]

        # Ground Station wire schema (snake_case)
        gs_pack.append(
            {
                "text": text,
                "vector": vec_list,
                "source_doc": source_doc,
                "pack_version": pack_version,
                "category": item["category"],
                "lang": item["lang"],
            }
        )

        # Android KnowledgePoint schema (camelCase + id)
        android_pack.append(
            {
                "id": content_id(text),
                "text": text,
                "vector": vec_list,
                "sourceDoc": source_doc,
                "packVersion": pack_version,
                "category": item["category"],
                "lang": item["lang"],
            }
        )

    with open(args.gs_out, "w", encoding="utf-8") as f:
        json.dump(gs_pack, f, indent=2)
    print(f"Wrote {args.gs_out} ({len(gs_pack)} entries, ground-station schema)")

    with open(args.android_out, "w", encoding="utf-8") as f:
        json.dump(android_pack, f, indent=2)
    print(f"Wrote {args.android_out} ({len(android_pack)} entries, android schema)")

    if args.no_ingest:
        print("Skipping ingest (--no-ingest)")
        return 0

    print(f"Ingesting into {args.api}/ingest ...")
    try:
        resp = httpx.post(f"{args.api}/ingest", json=gs_pack, timeout=180.0)
    except Exception as e:
        print(f"Ingest request failed: {e}")
        return 1

    if resp.status_code != 200:
        print(f"Ingest failed: HTTP {resp.status_code}\n{resp.text}")
        return 1

    print(f"Ingestion successful: {resp.json()}")
    health = httpx.get(f"{args.api}/health", timeout=15).json()
    print(f"Backend now serving: {health.get('backend')} (port {health.get('port')})")
    return 0


if __name__ == "__main__":
    sys.exit(main())
