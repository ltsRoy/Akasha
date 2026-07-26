import app as app_module
from app import InMemoryStore, app, text_to_point_id
from fastapi.testclient import TestClient

client = TestClient(app)

VECTOR_DIM = 384


def fresh_store():
    """Bind a clean in-memory store for a test."""
    app_module.store = InMemoryStore()
    return app_module.store


def item(text, vector, category="test", source="Test Doc"):
    return {
        "text": text,
        "vector": vector,
        "source_doc": source,
        "pack_version": "1.1.0",
        "category": category,
        "lang": "en",
    }


def test_health():
    fresh_store()
    response = client.get("/health")
    assert response.status_code == 200
    data = response.json()
    assert data["ok"] is True
    assert data["collection"] == "akasha_safety"
    assert data["dim"] == VECTOR_DIM
    # Clients need to tell a broken index apart from an empty answer.
    assert "recall_ok" in data


def test_point_id_is_a_stable_uuid_derived_from_content():
    """Actian rejects raw hex digests as ids, so they are folded into a UUID."""
    a = text_to_point_id("identical text")
    b = text_to_point_id("identical text")
    c = text_to_point_id("different text")
    assert a == b
    assert a != c
    assert len(a) == 36 and a.count("-") == 4


def test_ingest_and_search():
    store = fresh_store()

    dummy_vec1 = [0.1] * VECTOR_DIM
    dummy_vec2 = [0.2] * VECTOR_DIM
    ingest_data = [
        item("Test safety instruction", dummy_vec1, "test", "Test Doc"),
        item("Another different instruction", dummy_vec2, "test2", "Test Doc 2"),
    ]

    response = client.post("/ingest", json=ingest_data)
    assert response.status_code == 200
    body = response.json()
    assert body["ingested"] == 2
    assert body["written"] == 2

    # Search
    response = client.post("/search", json={"vector": dummy_vec1, "top_k": 1})
    assert response.status_code == 200
    results = response.json()["results"]
    assert len(results) == 1
    assert results[0]["text"] == "Test safety instruction"

    # Filter
    response = client.post(
        "/search",
        json={"vector": dummy_vec1, "top_k": 5, "filters": {"category": "test2"}},
    )
    assert response.status_code == 200
    results = response.json()["results"]
    assert len(results) == 1
    assert results[0]["category"] == "test2"
    assert len(store.points) == 2


def test_reingest_does_not_rewrite_unchanged_points():
    """Re-sending identical content must be a no-op at the storage layer.

    Repeatedly upserting the same ids is what degraded the live Actian HNSW index until
    recall collapsed to exact matches, so the skip is load-bearing rather than cosmetic.
    """
    store = fresh_store()
    data = [item("Stable instruction", [0.3] * VECTOR_DIM)]

    first = client.post("/ingest", json=data).json()
    assert first["written"] == 1
    assert first["skipped"] == 0

    second = client.post("/ingest", json=data).json()
    assert second["ingested"] == 1, "accepted count stays stable for idempotent callers"
    assert second["written"] == 0, "unchanged point was rewritten"
    assert second["skipped"] == 1
    assert second["total_points"] == 1
    assert len(store.points) == 1


def test_changed_text_is_written_as_a_new_point():
    fresh_store()
    client.post("/ingest", json=[item("Original wording", [0.4] * VECTOR_DIM)])
    second = client.post(
        "/ingest", json=[item("Revised wording", [0.4] * VECTOR_DIM)]
    ).json()
    assert second["written"] == 1
    assert second["total_points"] == 2


def test_packs_accounts_for_every_stored_point():
    """/packs must enumerate storage, not run a similarity search.

    Listing via a zero-vector search silently undercounted, because a zero vector has no
    direction under cosine distance and the backend drops low-relevance hits.
    """
    fresh_store()
    items = [
        item(f"instruction number {i}", [0.01 * (i + 1)] * VECTOR_DIM, f"cat{i}")
        for i in range(7)
    ]
    client.post("/ingest", json=items)

    packs = client.get("/packs").json()["packs"]
    assert len(packs) == 1
    assert packs[0]["entries"] == 7
    assert len(packs[0]["categories"]) == 7
    assert packs[0]["dim"] == VECTOR_DIM
    assert packs[0]["embedding_model"] == "all-MiniLM-L6-v2"

    # Compare against the store directly. /health deliberately calls ensure_actian(),
    # which would swap this in-memory fixture for the live backend mid-test.
    total = sum(p["entries"] for p in packs)
    assert total == app_module.store.count()


def test_search_rejects_wrong_dimensionality():
    fresh_store()
    response = client.post("/search", json={"vector": [0.1] * 128, "top_k": 1})
    assert response.status_code == 422


def test_empty_store_returns_no_results_rather_than_an_error():
    fresh_store()
    response = client.post("/search", json={"vector": [0.1] * VECTOR_DIM, "top_k": 3})
    assert response.status_code == 200
    assert response.json()["results"] == []
