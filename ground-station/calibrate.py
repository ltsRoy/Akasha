"""
Measures the real all-MiniLM-L6-v2 score distribution against the live Ground
Station so the QueryHandler confidence thresholds are grounded in data instead
of guesswork.

Prints the separation between on-topic (relevant) and off-topic (must-refuse)
score populations and recommends HIGH / LOW thresholds.
"""
import json
import os
import statistics

import httpx
from sentence_transformers import SentenceTransformer

API = os.environ.get("API_BASE", "http://localhost:8000")

# (paraphrased question, expected category) -- deliberately avoids reusing the
# distinctive wording of the stored passages.
ON_TOPIC = [
    ("my friend is losing a lot of blood from his leg", "bleeding"),
    ("he is bleeding heavily and it will not stop", "bleeding"),
    ("someone collapsed and is not breathing", "cpr"),
    ("no pulse, do i push on the chest", "cpr"),
    ("the ground is shaking violently what do i do", "earthquake"),
    ("buildings are trembling, where should i hide", "earthquake"),
    ("water is rising fast in the street", "flood"),
    ("my car is surrounded by rising water", "flood"),
    ("he swallowed something poisonous", "poisoning"),
    ("my child drank cleaning liquid", "poisoning"),
    ("her face is drooping and speech is slurred", "stroke"),
    ("one arm went weak and he cannot talk properly", "stroke"),
    ("food is stuck in his throat and he cannot breathe", "choking"),
    ("scalded my arm with boiling water", "burns"),
    ("he is shaking uncontrollably on the floor", "seizure"),
    ("bitten by a snake while hiking", "snakebite"),
    ("pulled a kid out of the pool, not breathing", "drowning"),
    ("he is freezing cold and confused after being outside", "hypothermia"),
    ("very hot skin, confused, body temperature high", "heat"),
    ("a tank of chemicals ruptured near us", "hazmat"),
    ("my arm is bent the wrong way and swollen", "fracture"),
    ("smoke is filling the stairwell", "fire"),
]

OFF_TOPIC = [
    "quarterly earnings per share dividend yield of semiconductor equities",
    "how do i refactor a kotlin coroutine scope",
    "best pasta recipe with garlic and olive oil",
    "who won the football league last season",
    "explain quantum entanglement to me",
    "how to build a quantum computer in my basement",
    "cheapest flights to tokyo in december",
    "my laptop battery drains too fast",
]


def main():
    model = SentenceTransformer("all-MiniLM-L6-v2")
    client = httpx.Client(timeout=30)

    def top(query, k=3):
        vec = model.encode(query, normalize_embeddings=True).tolist()
        r = client.post(f"{API}/search", json={"vector": vec, "top_k": k})
        r.raise_for_status()
        return r.json()["results"]

    print("=" * 78)
    print("ON-TOPIC QUERIES (expect a correct, confident answer)")
    print("=" * 78)
    on_scores = []
    correct = 0
    for q, expected in ON_TOPIC:
        res = top(q)
        if not res:
            print(f"  MISS(no results)  {q!r}")
            continue
        hit = res[0]
        ok = hit["category"] == expected
        correct += ok
        on_scores.append(hit["score"])
        mark = "ok  " if ok else "WRONG"
        print(f"  {mark} {hit['score']:.4f}  exp={expected:<12} got={hit['category']:<12} {q[:44]!r}")

    print()
    print("=" * 78)
    print("OFF-TOPIC QUERIES (must fall below the refusal threshold)")
    print("=" * 78)
    off_scores = []
    for q in OFF_TOPIC:
        res = top(q, k=1)
        s = res[0]["score"] if res else 0.0
        off_scores.append(s)
        print(f"  {s:.4f}  {q[:60]!r}")

    print()
    print("=" * 78)
    print("DISTRIBUTION")
    print("=" * 78)
    print(f"  retrieval accuracy      : {correct}/{len(ON_TOPIC)}")
    print(
        f"  on-topic  min={min(on_scores):.4f} "
        f"mean={statistics.mean(on_scores):.4f} max={max(on_scores):.4f}"
    )
    print(
        f"  off-topic min={min(off_scores):.4f} "
        f"mean={statistics.mean(off_scores):.4f} max={max(off_scores):.4f}"
    )

    on_min = min(on_scores)
    off_max = max(off_scores)
    gap = on_min - off_max
    print(f"  separation gap          : {gap:.4f}")

    # LOW sits just above the worst off-topic score, HIGH at the on-topic median
    # so a typical good match reads as HIGH while weak matches are flagged.
    low = round(off_max + gap * 0.35, 2)
    high = round(statistics.median(on_scores), 2)
    if high <= low:
        high = round(low + 0.10, 2)
    print()
    print(f"  RECOMMENDED LOW_CONFIDENCE_THRESHOLD  = {low}")
    print(f"  RECOMMENDED HIGH_CONFIDENCE_THRESHOLD = {high}")
    print("=" * 78)

    json.dump(
        {
            "accuracy": f"{correct}/{len(ON_TOPIC)}",
            "on_topic": {"min": min(on_scores), "max": max(on_scores)},
            "off_topic": {"min": min(off_scores), "max": max(off_scores)},
            "recommended_low": low,
            "recommended_high": high,
        },
        open("calibration.json", "w"),
        indent=2,
    )
    print("wrote calibration.json")


if __name__ == "__main__":
    main()
