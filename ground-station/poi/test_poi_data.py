"""
Data-integrity gate for the Kolkata facility pack.

These are not style checks. Each one corresponds to a way that bad facility data would send
a person somewhere wrong during an emergency:

  * transposed lat/lon      -> a pin in the Bay of Bengal
  * geohash disagreeing with coordinates -> facility invisible to nearby-search
  * unverified phone number -> someone calls a stranger instead of a hospital
  * single geohash-4 cell   -> all of south Kolkata silently missing

Run:  python -m pytest poi/test_poi_data.py -q
"""
import json
import math
import os

import pytest

HERE = os.path.dirname(__file__)
POI_PATH = os.path.join(HERE, "kolkata_poi.json")
HELPLINE_PATH = os.path.join(HERE, "helplines.json")
SYNONYM_PATH = os.path.join(HERE, "synonyms.json")

CATEGORIES = {
    "hospital", "clinic", "blood_bank", "pharmacy", "police",
    "fire_station", "shelter", "rescue_centre", "relief_distribution", "helpline",
}
SPECIALTIES = {
    "orthopaedic", "trauma", "burns", "cardiac", "neuro", "paediatric", "maternity",
    "general_surgery", "general_medicine", "dialysis", "poison_control",
    "snake_antivenom", "psychiatric", "ophthalmology", "icu", "blood_transfusion",
}
OPERATORS = {"government", "private", "ngo", "military", "unknown"}
DATA_STATUS = {"verified", "unverified", "stale", "disputed"}

# Kolkata bounding box. Tight on purpose so transposed coordinates fail.
LAT_RANGE = (22.35, 22.80)
LON_RANGE = (88.15, 88.60)

B32 = "0123456789bcdefghjkmnpqrstuvwxyz"


def geohash(lat, lon, precision):
    lat_iv, lon_iv = [-90.0, 90.0], [-180.0, 180.0]
    even, bit, ch, out = True, 0, 0, ""
    while len(out) < precision:
        if even:
            mid = (lon_iv[0] + lon_iv[1]) / 2
            if lon >= mid:
                ch |= 1 << (4 - bit); lon_iv[0] = mid
            else:
                lon_iv[1] = mid
        else:
            mid = (lat_iv[0] + lat_iv[1]) / 2
            if lat >= mid:
                ch |= 1 << (4 - bit); lat_iv[0] = mid
            else:
                lat_iv[1] = mid
        even = not even
        if bit < 4:
            bit += 1
        else:
            out += B32[ch]; bit, ch = 0, 0
    return out


def haversine_km(lat1, lon1, lat2, lon2):
    r = 6371.0088
    p1, p2 = math.radians(lat1), math.radians(lat2)
    dp = p2 - p1
    dl = math.radians(lon2 - lon1)
    a = math.sin(dp / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(dl / 2) ** 2
    return 2 * r * math.asin(math.sqrt(a))


@pytest.fixture(scope="module")
def poi():
    with open(POI_PATH, encoding="utf-8") as f:
        return json.load(f)


def test_pack_is_present_and_non_empty(poi):
    assert isinstance(poi, list)
    assert len(poi) >= 20, "seed pack is too small to be useful"


def test_ids_are_unique_and_well_formed(poi):
    ids = [r["id"] for r in poi]
    assert len(ids) == len(set(ids)), "duplicate facility ids"
    for r in poi:
        parts = r["id"].split(":")
        assert len(parts) == 4 and parts[0] == "poi", r["id"]
        assert parts[1] == "kolkata", r["id"]
        assert parts[2] == r["category"], f"{r['id']} id category disagrees with field"


def test_closed_vocabularies(poi):
    for r in poi:
        assert r["category"] in CATEGORIES, r["id"]
        assert r["operator"] in OPERATORS, r["id"]
        assert r["data_status"] in DATA_STATUS, r["id"]
        for s in r.get("specialties") or []:
            assert s in SPECIALTIES, f"{r['id']} unknown specialty {s}"


def test_coordinates_are_inside_kolkata(poi):
    """Catches transposed lat/lon, which would otherwise validate as a number."""
    for r in poi:
        assert LAT_RANGE[0] <= r["latitude"] <= LAT_RANGE[1], \
            f"{r['id']} latitude {r['latitude']} outside Kolkata"
        assert LON_RANGE[0] <= r["longitude"] <= LON_RANGE[1], \
            f"{r['id']} longitude {r['longitude']} outside Kolkata"


def test_geohashes_match_their_coordinates(poi):
    """A geohash that disagrees with its coordinates makes a facility unfindable."""
    for r in poi:
        assert r["geohash4"] == geohash(r["latitude"], r["longitude"], 4), r["id"]
        assert r["geohash5"] == geohash(r["latitude"], r["longitude"], 5), r["id"]
        assert r["geohash5"].startswith(r["geohash4"]), r["id"]


def test_no_phone_number_on_an_unverified_record(poi):
    """A wrong number in a disaster is worse than no number."""
    for r in poi:
        if r["data_status"] != "verified":
            assert r["phone"] is None, f"{r['id']} has a phone but is {r['data_status']}"
            assert r["alt_phone"] is None, f"{r['id']} has alt_phone but is unverified"


def test_verified_records_carry_a_verification_date(poi):
    for r in poi:
        if r["data_status"] == "verified":
            assert r["verified_on"], f"{r['id']} verified but has no verified_on"


def test_every_record_carries_provenance(poi):
    for r in poi:
        assert r["source_doc"] and len(r["source_doc"]) >= 3, r["id"]
        assert r["pack_version"], r["id"]


def test_pack_spans_both_kolkata_geohash4_cells(poi):
    """Kolkata straddles tunb (north/central) and tgyz (south).

    If the pack only covers one cell, nearby-search for a user in Garia or Behala returns
    nothing while appearing to work correctly for everyone in the centre.
    """
    cells = {r["geohash4"] for r in poi}
    assert "tunb" in cells, "no north/central Kolkata facilities"
    assert "tgyz" in cells, "no south Kolkata facilities (Garia/Behala region)"


def test_south_kolkata_has_a_hospital(poi):
    south = [r for r in poi if r["geohash4"] == "tgyz" and r["category"] == "hospital"]
    assert south, "south Kolkata has no hospital in the pack"


def test_hospitals_declare_specialties(poi):
    for r in poi:
        if r["category"] == "hospital":
            assert r["specialties"], f"{r['id']} is a hospital with no specialties"


def test_orthopaedic_coverage_exists_in_both_cells(poi):
    """The user's stated example query must be answerable citywide."""
    for cell in ("tunb", "tgyz"):
        matches = [
            r for r in poi
            if r["geohash4"] == cell
            and r["category"] == "hospital"
            and "orthopaedic" in (r["specialties"] or [])
        ]
        assert matches, f"no orthopaedic hospital in geohash4 cell {cell}"


def test_each_emergency_category_is_represented(poi):
    present = {r["category"] for r in poi}
    for required in ("hospital", "police", "fire_station"):
        assert required in present, f"pack has no {required}"


def test_distance_sanity_between_known_landmarks(poi):
    """Guards the haversine implementation the app will mirror."""
    esplanade = (22.5626, 88.3495)
    sskm = next(r for r in poi if r["id"].endswith(":sskm"))
    d = haversine_km(esplanade[0], esplanade[1], sskm["latitude"], sskm["longitude"])
    assert 1.5 < d < 4.5, f"Esplanade -> SSKM computed as {d:.2f} km, expected ~2.8"

    salt_lake = next(r for r in poi if r["id"].endswith(":amri-salt-lake"))
    d2 = haversine_km(esplanade[0], esplanade[1], salt_lake["latitude"], salt_lake["longitude"])
    assert 5.0 < d2 < 12.0, f"Esplanade -> Salt Lake computed as {d2:.2f} km, expected ~7"


# ------------------------------------------------------------------ helplines
def test_helplines_are_verified_and_have_numbers():
    with open(HELPLINE_PATH, encoding="utf-8") as f:
        lines = json.load(f)
    assert len(lines) >= 6
    numbers = {h["service"]: h["number"] for h in lines}
    assert numbers["all_emergency"] == "112"
    assert numbers["police"] == "100"
    assert numbers["fire"] == "101"
    assert numbers["ambulance"] == "102"
    for h in lines:
        assert h["data_status"] == "verified", h["id"]
        assert h["number"].isdigit(), h["id"]
        assert h["source_doc"], h["id"]


# ------------------------------------------------------------------ synonyms
def test_synonym_map_only_targets_closed_vocabulary():
    with open(SYNONYM_PATH, encoding="utf-8") as f:
        syn = json.load(f)
    for key in syn["specialty"]:
        assert key in SPECIALTIES, f"synonym target {key} is not a valid specialty"
    for key in syn["category"]:
        assert key in CATEGORIES, f"synonym target {key} is not a valid category"


def test_synonyms_are_unambiguous():
    """One lay phrase must not map to two different targets, or slot resolution is a coin flip."""
    with open(SYNONYM_PATH, encoding="utf-8") as f:
        syn = json.load(f)
    for group_name in ("specialty", "category"):
        seen = {}
        for target, phrases in syn[group_name].items():
            for p in phrases:
                assert p not in seen, \
                    f"'{p}' maps to both {seen.get(p)} and {target} in {group_name}"
                seen[p] = target
