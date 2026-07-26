"""
Builds ground-station/poi/kolkata_poi.json from the curated facility list below.

Geohashes are COMPUTED here rather than typed by hand, because a hand-written geohash that
disagrees with its coordinates produces a facility that is invisible to nearby-search while
still validating against the schema.

DATA HONESTY POLICY (see docs/akasha/02-POI-DATA-MODEL.md):
  * Facility names and approximate locations of major Kolkata institutions are well
    established and safe to seed.
  * Phone numbers are NOT. Scraped facility numbers are frequently wrong and a wrong number
    in a disaster is worse than no number. Every `phone` here is None and every record is
    data_status="unverified" until a human confirms it against a primary source.
  * Coordinates are approximate (locality-level). They are good enough to identify and head
    toward a facility, and are explicitly marked unverified. They are NOT survey-grade.

VERIFICATION PASS (must happen before any real deployment):
  1. Cross-check each record against the West Bengal Health & Family Welfare facility
     directory and Kolkata Police station list.
  2. Pull precise coordinates from OpenStreetMap Overpass
     (amenity=hospital / police / fire_station, healthcare:speciality=*).
     OSM is ODbL — record the attribution in source_url.
  3. Set phone only when confirmed, then set data_status="verified" and verified_on.

Usage:
    python build_kolkata_poi.py            # writes kolkata_poi.json
    python build_kolkata_poi.py --check    # validate only, non-zero exit on failure
"""
import argparse
import json
import os
import sys

PACK_VERSION = "kolkata-1.0.0"

B32 = "0123456789bcdefghjkmnpqrstuvwxyz"


def geohash(lat: float, lon: float, precision: int) -> str:
    """Mirrors com.MeshLink.android.geohash.Geohash.encode exactly."""
    lat_iv = [-90.0, 90.0]
    lon_iv = [-180.0, 180.0]
    even, bit, ch, out = True, 0, 0, ""
    while len(out) < precision:
        if even:
            mid = (lon_iv[0] + lon_iv[1]) / 2
            if lon >= mid:
                ch |= 1 << (4 - bit)
                lon_iv[0] = mid
            else:
                lon_iv[1] = mid
        else:
            mid = (lat_iv[0] + lat_iv[1]) / 2
            if lat >= mid:
                ch |= 1 << (4 - bit)
                lat_iv[0] = mid
            else:
                lat_iv[1] = mid
        even = not even
        if bit < 4:
            bit += 1
        else:
            out += B32[ch]
            bit, ch = 0, 0
    return out


# ---------------------------------------------------------------------------
# Curated facility list.
#   (slug, name, name_local, category, specialties, lat, lon, address, operator,
#    emergency_24h, has_emergency_dept, source_doc)
# Coordinates are locality-level approximations. See DATA HONESTY POLICY above.
# ---------------------------------------------------------------------------
FACILITIES = [
    # ---------------- Government medical colleges & major hospitals -----------
    ("sskm", "SSKM Hospital (IPGMER)", "এসএসকেএম হাসপাতাল", "hospital",
     ["trauma", "orthopaedic", "burns", "general_surgery", "general_medicine", "icu",
      "cardiac", "neuro", "dialysis", "poison_control"],
     22.5390, 88.3419, "244 AJC Bose Road, Bhowanipore, Kolkata 700020",
     "government", True, True, "WB Health & Family Welfare facility directory"),

    ("calcutta-medical-college", "Calcutta Medical College and Hospital", "কলকাতা মেডিক্যাল কলেজ",
     "hospital",
     ["trauma", "orthopaedic", "burns", "general_surgery", "general_medicine", "icu",
      "paediatric", "maternity", "blood_transfusion"],
     22.5735, 88.3640, "88 College Street, Kolkata 700073",
     "government", True, True, "WB Health & Family Welfare facility directory"),

    ("nrs-medical-college", "Nil Ratan Sircar Medical College and Hospital", "এন আর এস মেডিক্যাল কলেজ",
     "hospital",
     ["trauma", "orthopaedic", "general_surgery", "general_medicine", "icu",
      "snake_antivenom", "poison_control"],
     22.5645, 88.3742, "138 AJC Bose Road, Sealdah, Kolkata 700014",
     "government", True, True, "WB Health & Family Welfare facility directory"),

    ("rg-kar-medical-college", "R G Kar Medical College and Hospital", "আর জি কর মেডিক্যাল কলেজ",
     "hospital",
     ["trauma", "orthopaedic", "general_surgery", "general_medicine", "icu", "maternity"],
     22.6011, 88.3792, "1 Khudiram Bose Sarani, Shyambazar, Kolkata 700004",
     "government", True, True, "WB Health & Family Welfare facility directory"),

    ("calcutta-national-medical-college", "Calcutta National Medical College", None, "hospital",
     ["trauma", "orthopaedic", "general_surgery", "general_medicine", "icu"],
     22.5490, 88.3720, "32 Gorachand Road, Park Circus, Kolkata 700014",
     "government", True, True, "WB Health & Family Welfare facility directory"),

    ("sambhunath-pandit", "Sambhunath Pandit Hospital", None, "hospital",
     ["trauma", "orthopaedic", "general_medicine"],
     22.5330, 88.3430, "Sambhunath Pandit Street, Bhowanipore, Kolkata 700020",
     "government", True, True, "WB Health & Family Welfare facility directory"),

    ("id-bg-hospital-beliaghata", "Infectious Diseases and Beliaghata General Hospital", None,
     "hospital", ["general_medicine", "icu"],
     22.5620, 88.3960, "57 Beliaghata Main Road, Kolkata 700010",
     "government", True, True, "WB Health & Family Welfare facility directory"),

    ("bc-roy-paediatric", "Dr B C Roy Post Graduate Institute of Paediatric Sciences", None,
     "hospital", ["paediatric", "icu", "general_medicine"],
     22.5100, 88.3900, "111 Narkeldanga Main Road, Phoolbagan, Kolkata 700054",
     "government", True, True, "WB Health & Family Welfare facility directory"),

    ("institute-child-health", "Institute of Child Health", None, "hospital",
     ["paediatric", "icu"],
     22.5410, 88.3690, "11 Dr Biresh Guha Street, Park Circus, Kolkata 700017",
     "ngo", True, True, "WB Health & Family Welfare facility directory"),

    # ---------------- South Kolkata (geohash-4 cell tgyz) ---------------------
    # Deliberately included so nearby-search is testable for south Kolkata, which
    # falls in a DIFFERENT geohash-4 cell from the city centre.
    ("mr-bangur", "M R Bangur Hospital", None, "hospital",
     ["trauma", "orthopaedic", "general_surgery", "general_medicine", "icu"],
     22.4930, 88.3480, "241 Deshapran Sasmal Road, Tollygunge, Kolkata 700033",
     "government", True, True, "WB Health & Family Welfare facility directory"),

    ("baghajatin-state-general", "Baghajatin State General Hospital", None, "hospital",
     ["general_medicine", "general_surgery", "orthopaedic"],
     22.4750, 88.3830, "Baghajatin, Ramgarh, Kolkata 700086",
     "government", True, True, "WB Health & Family Welfare facility directory"),

    ("vidyasagar-behala", "Vidyasagar State General Hospital", None, "hospital",
     ["general_medicine", "general_surgery", "orthopaedic", "maternity"],
     22.4980, 88.3120, "Behala, Diamond Harbour Road, Kolkata 700034",
     "government", True, True, "WB Health & Family Welfare facility directory"),

    ("peerless-hospital", "Peerless Hospital and B K Roy Research Centre", None, "hospital",
     ["cardiac", "orthopaedic", "general_surgery", "icu", "dialysis", "neuro"],
     22.4780, 88.3960, "360 Panchasayar Road, Kolkata 700094",
     "private", True, True, "Facility public listing"),

    # ---------------- Private multispeciality (central / east) ----------------
    ("apollo-multispeciality", "Apollo Multispeciality Hospitals", None, "hospital",
     ["cardiac", "neuro", "orthopaedic", "trauma", "icu", "dialysis", "general_surgery"],
     22.5450, 88.3980, "58 Canal Circular Road, Kadapara, Kolkata 700054",
     "private", True, True, "Facility public listing"),

    ("amri-salt-lake", "AMRI Hospital Salt Lake", None, "hospital",
     ["cardiac", "orthopaedic", "general_surgery", "icu", "paediatric"],
     22.5760, 88.4100, "Block A, Scheme L11, Salt Lake, Kolkata 700098",
     "private", True, True, "Facility public listing"),

    ("ruby-general", "Ruby General Hospital", None, "hospital",
     ["trauma", "orthopaedic", "cardiac", "icu", "general_surgery"],
     22.5120, 88.4020, "576 Anandapur, EM Bypass, Kasba, Kolkata 700107",
     "private", True, True, "Facility public listing"),

    ("fortis-anandapur", "Fortis Hospital Anandapur", None, "hospital",
     ["cardiac", "neuro", "orthopaedic", "icu", "dialysis"],
     22.5140, 88.4060, "730 Anandapur, EM Bypass, Kolkata 700107",
     "private", True, True, "Facility public listing"),

    ("command-hospital-alipore", "Command Hospital Eastern Command", None, "hospital",
     ["trauma", "orthopaedic", "general_surgery", "icu"],
     22.5340, 88.3320, "Alipore, Kolkata 700027",
     "military", True, True, "Public listing"),

    # ---------------- Blood banks --------------------------------------------
    ("central-blood-bank", "Central Blood Bank", None, "blood_bank",
     ["blood_transfusion"],
     22.5900, 88.3760, "Manicktala, Kolkata 700006",
     "government", True, None, "WB Health & Family Welfare facility directory"),

    ("sskm-blood-bank", "SSKM Hospital Blood Bank", None, "blood_bank",
     ["blood_transfusion"],
     22.5390, 88.3419, "244 AJC Bose Road, Bhowanipore, Kolkata 700020",
     "government", True, None, "WB Health & Family Welfare facility directory"),

    # ---------------- Police --------------------------------------------------
    ("lalbazar-hq", "Kolkata Police Headquarters, Lalbazar", "লালবাজার", "police", [],
     22.5697, 88.3510, "18 Lalbazar Street, Kolkata 700001",
     "government", True, None, "Kolkata Police station list"),

    ("bhowanipore-ps", "Bhowanipore Police Station", None, "police", [],
     22.5330, 88.3430, "Bhowanipore, Kolkata 700025",
     "government", True, None, "Kolkata Police station list"),

    ("jadavpur-ps", "Jadavpur Police Station", None, "police", [],
     22.4990, 88.3710, "Jadavpur, Kolkata 700032",
     "government", True, None, "Kolkata Police station list"),

    ("garia-ps", "Garia Police Station", None, "police", [],
     22.4630, 88.3900, "Garia, Kolkata 700084",
     "government", True, None, "Kolkata Police station list"),

    ("behala-ps", "Behala Police Station", None, "police", [],
     22.4980, 88.3120, "Behala Chowrasta, Kolkata 700034",
     "government", True, None, "Kolkata Police station list"),

    ("electronics-complex-ps", "Electronics Complex Police Station", None, "police", [],
     22.5760, 88.4340, "Sector V, Salt Lake, Kolkata 700091",
     "government", True, None, "Bidhannagar Police station list"),

    ("shyampukur-ps", "Shyampukur Police Station", None, "police", [],
     22.5980, 88.3660, "Shyampukur, Kolkata 700004",
     "government", True, None, "Kolkata Police station list"),

    # ---------------- Fire ----------------------------------------------------
    ("wb-fire-hq", "West Bengal Fire and Emergency Services Headquarters", None,
     "fire_station", [],
     22.5540, 88.3520, "13 Free School Street, Kolkata 700016",
     "government", True, None, "WB Fire and Emergency Services listing"),

    ("behala-fire-station", "Behala Fire Station", None, "fire_station", [],
     22.4975, 88.3140, "Behala, Kolkata 700034",
     "government", True, None, "WB Fire and Emergency Services listing"),

    ("salt-lake-fire-station", "Salt Lake Fire Station", None, "fire_station", [],
     22.5800, 88.4200, "Salt Lake, Kolkata 700091",
     "government", True, None, "WB Fire and Emergency Services listing"),

    ("tollygunge-fire-station", "Tollygunge Fire Station", None, "fire_station", [],
     22.4950, 88.3450, "Tollygunge, Kolkata 700033",
     "government", True, None, "WB Fire and Emergency Services listing"),

    # ---------------- Rescue / civil defence ---------------------------------
    ("wb-civil-defence-kolkata", "West Bengal Civil Defence, Kolkata", None,
     "rescue_centre", [],
     22.5720, 88.3480, "Kolkata 700001",
     "government", None, None, "WB Disaster Management listing"),

    ("kolkata-disaster-management", "Kolkata Municipal Corporation Disaster Management Cell",
     None, "rescue_centre", [],
     22.5650, 88.3520, "5 SN Banerjee Road, Kolkata 700013",
     "government", None, None, "KMC listing"),
]

# Shelters are intentionally NOT seeded. A shelter list is only meaningful when it reflects
# what is actually open during a specific event; a stale shelter record sends people to a
# locked gate. These must be populated operationally (by a responder, over the mesh or via
# the Ground Station) rather than baked into the APK. See docs/akasha/02-POI-DATA-MODEL.md.


def build():
    records = []
    seen = set()
    for (slug, name, name_local, category, specialties, lat, lon, address,
         operator, em24, emdept, source) in FACILITIES:
        poi_id = f"poi:kolkata:{category}:{slug}"
        if poi_id in seen:
            raise SystemExit(f"duplicate id: {poi_id}")
        seen.add(poi_id)
        records.append({
            "id": poi_id,
            "name": name,
            "name_local": name_local,
            "category": category,
            "specialties": specialties,
            "latitude": lat,
            "longitude": lon,
            "geohash4": geohash(lat, lon, 4),
            "geohash5": geohash(lat, lon, 5),
            "address": address,
            "ward": None,
            # Never seeded. See DATA HONESTY POLICY.
            "phone": None,
            "alt_phone": None,
            "emergency_24h": bool(em24),
            "capacity_beds": None,
            "has_emergency_dept": emdept,
            "wheelchair_accessible": None,
            "operator": operator,
            "source_doc": source,
            "source_url": None,
            "verified_on": None,
            "data_status": "unverified",
            "pack_version": PACK_VERSION,
            "lang": "en",
            "notes": None,
        })
    return records


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", default=os.path.join(os.path.dirname(__file__), "kolkata_poi.json"))
    ap.add_argument("--check", action="store_true")
    args = ap.parse_args()

    records = build()

    # Fail loudly rather than shipping a phone number nobody confirmed.
    for r in records:
        if r["phone"] and r["data_status"] != "verified":
            raise SystemExit(f"{r['id']}: phone set on an unverified record")

    cells4 = sorted({r["geohash4"] for r in records})
    cells5 = sorted({r["geohash5"] for r in records})
    cats = sorted({r["category"] for r in records})

    print(f"records      : {len(records)}")
    print(f"categories   : {cats}")
    print(f"geohash4 set : {cells4}")
    print(f"geohash5 set : {len(cells5)} cells -> {cells5}")
    if len(cells4) < 2:
        print("WARNING: expected Kolkata to span at least 2 geohash-4 cells "
              "(tunb north/central, tgyz south). South-Kolkata coverage may be missing.")

    if args.check:
        return 0

    with open(args.out, "w", encoding="utf-8") as f:
        json.dump(records, f, indent=2, ensure_ascii=False)
    print(f"wrote {args.out}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
