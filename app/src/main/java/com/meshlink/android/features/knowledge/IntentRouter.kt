package com.MeshLink.android.features.knowledge

/**
 * Works out what a distressed person is actually asking for.
 *
 * People in trouble don't type queries, they type situations: "im hurt", "someone is following me",
 * "i think im being watched". None of those contain the words *hospital* or *police*, so a plain
 * semantic search over a safety corpus answers the wrong question — it returns first-aid prose when
 * what's needed is the nearest police station.
 *
 * This maps the situation onto two things at once: which safety passages to retrieve, and which
 * category of physical facility to look up. Both are useful together — how to stop the bleeding *and*
 * where the nearest hospital is.
 *
 * Deliberately rule-based rather than model-driven. It runs before the LLM, must be instant, and must
 * behave identically every time — a classifier that occasionally routes "I'm being followed" to
 * first-aid advice is worse than one that is blunt but predictable.
 */
object IntentRouter {

    /** Facility categories the POI collection actually contains. Verified against the live server. */
    object Category {
        const val HOSPITAL = "hospital"
        const val POLICE = "police"
        const val RESCUE_CENTRE = "rescue_centre"
        const val BLOOD_BANK = "blood_bank"
        const val CLINIC = "clinic"
        const val PHARMACY = "pharmacy"
        const val FIRE_STATION = "fire_station"
    }

    /**
     * What the user needs.
     *
     * [facilityCategories] is ordered by relevance: the first is the primary answer, the rest are
     * offered as alternatives.
     */
    data class Intent(
        val kind: Kind,
        val facilityCategories: List<String>,
        /** Hospital specialty to prefer, when the wording implies one. */
        val specialty: String? = null,
        /** Rewritten question for the safety corpus, when the raw wording retrieves poorly. */
        val safetyQuery: String? = null,
        /** True when the situation implies immediate personal danger, not just a question. */
        val urgent: Boolean = false,
    )

    enum class Kind {
        /** Physical injury: needs first aid plus a hospital. */
        INJURY,

        /** Being followed, watched, threatened: needs police, not first aid. */
        PERSONAL_THREAT,

        /** Fire, chemical, flood, collapse: needs safety steps plus fire/rescue. */
        HAZARD,

        /** Someone unresponsive or critical: needs CPR guidance plus emergency department. */
        MEDICAL_CRITICAL,

        /** Explicitly asking where something is. */
        FACILITY_LOOKUP,

        /** No facility need detected; safety corpus only. */
        GENERAL,
    }

    // Matched against the lowercased, apostrophe-stripped question, so "i'm"/"im"/"i’m" all hit.
    private val INJURY = listOf(
        "hurt", "wounded", "wound", "injured", "injury", "scratched", "scratch", "bleeding",
        "bleed", "cut myself", "gash", "broken", "fracture", "sprain", "burn", "burnt", "scalded",
        "twisted my", "hit my head", "fell", "fallen",
    )

    private val PERSONAL_THREAT = listOf(
        "followed", "following me", "stalked", "stalking", "someone suspicious", "suspicious man",
        "suspicious person", "being watched", "watching me", "threatened", "threatening",
        "harassed", "harassing", "attacked", "assault", "kidnap", "abduct", "chasing me",
        "chased", "unsafe", "scared of", "afraid of him", "molest", "groping", "robbed",
        "mugged", "stole my", "trapped in a car",
    )

    private val MEDICAL_CRITICAL = listOf(
        "not breathing", "stopped breathing", "no pulse", "unconscious", "unresponsive",
        "collapsed", "passed out", "heart attack", "cardiac", "choking", "seizure",
        "drooping", "slurred", "stroke", "overdose", "poisoned", "snake bite", "snakebite",
    )

    private val HAZARD = listOf(
        "fire", "burning", "smoke", "gas leak", "chemical", "chemicals", "hazmat", "explosion",
        "flood", "flooding", "drowning", "earthquake", "building collapsed", "collapse",
        "landslide", "electrocuted", "live wire",
    )

    private val FACILITY_WORDS = listOf(
        "nearest", "near me", "nearby", "where is", "where can i", "how do i get to",
        "closest", "hospital", "clinic", "pharmacy", "chemist",
        "blood bank", "fire station", "shelter", "ngo", "relief", "rescue", "help centre",
        "help center",
        // Bare nouns matter as much as full phrases. Someone typing just "police" or "ambulance" is
        // asking where to go; requiring "police station" made those fall through to a generic
        // corpus search that returns nothing.
        "police", "thana", "ambulance", "doctor", "hospital", "fire brigade", "medical help",
    )

    /** Lay wording mapped onto the specialty vocabulary the POI collection uses. */
    private val SPECIALTY_HINTS = mapOf(
        "orthopaedic" to listOf("broken", "fracture", "bone", "leg bent", "arm bent", "sprain"),
        "burns" to listOf("burn", "burnt", "scalded", "scald"),
        "trauma" to listOf("accident", "crash", "stabbed", "shot", "fell from", "head injury"),
        "cardiac" to listOf("heart attack", "chest pain", "cardiac"),
        "neuro" to listOf("stroke", "drooping", "slurred", "seizure"),
        "paediatric" to listOf("my child", "my son", "my daughter", "baby", "infant"),
        "maternity" to listOf("pregnant", "labour", "giving birth", "contractions"),
        "poison_control" to listOf("poison", "poisoned", "swallowed", "overdose"),
        "snake_antivenom" to listOf("snake bite", "snakebite", "bitten by a snake"),
        "blood_transfusion" to listOf("blood", "transfusion", "lost a lot of blood"),
    )

    fun classify(question: String): Intent {
        val q = question.lowercase().replace(Regex("['’]"), "")

        val specialty = SPECIALTY_HINTS.entries
            .firstOrNull { (_, hints) -> hints.any { q.contains(it) } }
            ?.key

        // Order matters. A critical medical situation outranks a generic injury, and personal threat
        // is checked before facility wording so "someone suspicious is following me" doesn't get
        // treated as a neutral lookup.
        return when {
            MEDICAL_CRITICAL.any { q.contains(it) } -> Intent(
                kind = Kind.MEDICAL_CRITICAL,
                facilityCategories = listOf(Category.HOSPITAL, Category.CLINIC),
                specialty = specialty,
                urgent = true,
            )

            PERSONAL_THREAT.any { q.contains(it) } -> Intent(
                kind = Kind.PERSONAL_THREAT,
                // Police first. Rescue centres (municipality offices, civil defence) are the only
                // shelter-like rows the database actually has — there is no NGO category in it.
                facilityCategories = listOf(Category.POLICE, Category.RESCUE_CENTRE),
                // The safety corpus is first-aid oriented, so the raw wording retrieves nothing
                // useful. Reframing gives it a chance to match personal-safety guidance if present.
                safetyQuery = "personal safety when being followed or threatened by a stranger",
                urgent = true,
            )

            HAZARD.any { q.contains(it) } -> Intent(
                kind = Kind.HAZARD,
                facilityCategories = listOf(Category.FIRE_STATION, Category.HOSPITAL, Category.RESCUE_CENTRE),
                specialty = specialty,
                urgent = true,
            )

            INJURY.any { q.contains(it) } -> Intent(
                kind = Kind.INJURY,
                facilityCategories = listOf(Category.HOSPITAL, Category.CLINIC, Category.PHARMACY),
                specialty = specialty,
                urgent = false,
            )

            FACILITY_WORDS.any { q.contains(it) } -> Intent(
                kind = Kind.FACILITY_LOOKUP,
                facilityCategories = categoriesFromWording(q),
                specialty = specialty,
            )

            else -> Intent(kind = Kind.GENERAL, facilityCategories = emptyList())
        }
    }

    /**
     * Pick categories from an explicit lookup.
     *
     * "ngo", "shelter" and "relief" all resolve to [Category.RESCUE_CENTRE]: those categories return
     * zero rows from the live collection, and answering with the municipality and civil-defence
     * offices that do exist is more useful than an empty list.
     */
    private fun categoriesFromWording(q: String): List<String> = when {
        q.contains("police") || q.contains("thana") -> listOf(Category.POLICE)
        q.contains("blood") -> listOf(Category.BLOOD_BANK, Category.HOSPITAL)
        q.contains("pharmacy") || q.contains("chemist") || q.contains("medicine") ->
            listOf(Category.PHARMACY, Category.HOSPITAL)
        q.contains("fire") -> listOf(Category.FIRE_STATION)
        q.contains("ngo") || q.contains("shelter") || q.contains("relief") || q.contains("rescue") ->
            listOf(Category.RESCUE_CENTRE)
        q.contains("clinic") -> listOf(Category.CLINIC, Category.HOSPITAL)
        else -> listOf(Category.HOSPITAL, Category.POLICE)
    }
}
