"""Embedding parity gate: verifies the Android asset pack vectors were
produced by the real all-MiniLM-L6-v2 model. PROJECT_CONTEXT.md requires
cosine > 0.99 between the Ground Station model and the on-device vectors."""
import json
import sys

import numpy as np
from sentence_transformers import SentenceTransformer

THRESHOLD = 0.99
PACK = sys.argv[1] if len(sys.argv) > 1 else "android_pack.json"


def cos(a, b):
    a = np.asarray(a, dtype=np.float64)
    b = np.asarray(b, dtype=np.float64)
    return float(np.dot(a, b) / (np.linalg.norm(a) * np.linalg.norm(b)))


model = SentenceTransformer("all-MiniLM-L6-v2")
pack = json.load(open(PACK, encoding="utf-8"))

print(f"PARITY GATE  --  {PACK}")
print(f"threshold from PROJECT_CONTEXT.md = {THRESHOLD}")
print("-" * 78)

worst = 1.0
fails = 0
for p in pack:
    fresh = model.encode(p["text"], normalize_embeddings=True)
    s = cos(fresh, p["vector"])
    worst = min(worst, s)
    if s <= THRESHOLD:
        fails += 1
    flag = "OK  " if s > THRESHOLD else "FAIL"
    cat = p.get("category", "?")
    print(f"{flag} cos={s:+.6f}  [{cat:<12}] {p['text'][:50]}")

print("-" * 78)
print(f"WORST COSINE = {worst:.6f}   failures = {fails}/{len(pack)}")
verdict = (
    "PARITY PASS"
    if worst > THRESHOLD
    else "PARITY FAIL -- these vectors are NOT real all-MiniLM-L6-v2 embeddings"
)
print(f"VERDICT: {verdict}")

norms = [float(np.linalg.norm(p["vector"])) for p in pack]
print(
    f"stored L2 norms: min={min(norms):.6f} max={max(norms):.6f} "
    "(expect ~1.0 for L2-normalized)"
)
