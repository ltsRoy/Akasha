package com.MeshLink.android.features.knowledge

/**
 * One passage of verified safety guidance, with its embedding.
 *
 * Field names match the shipped `assets/akasha/distilled_pack.json` (camelCase) so the pack can be
 * regenerated server-side without touching this parser.
 */
data class KnowledgePoint(
    val id: String,
    val text: String,
    val vector: FloatArray,
    val sourceDoc: String,
    val packVersion: String,
    val category: String,
    val lang: String,
) {
    // Explicit equals/hashCode: FloatArray uses identity comparison by default, which would make
    // two identical points compare unequal.
    override fun equals(other: Any?): Boolean = this === other || (other is KnowledgePoint && id == other.id)
    override fun hashCode(): Int = id.hashCode()
}

/** A retrieved passage with its similarity score and provenance. */
data class SearchResult(
    val text: String,
    val score: Float,
    val category: String,
    val sourceDoc: String,
    val packVersion: String,
)

/**
 * Where an answer came from. Shown to the user because provenance is the whole point: an answer from
 * the live database and an answer from a stale offline pack deserve different amounts of trust.
 */
enum class Backend(val label: String) {
    LOCAL_PACK("Offline pack"),
    GROUND_STATION("Ground Station"),
    MESH_GATEWAY("Relayed via peer"),
    NONE("No source"),
}

/**
 * Connectivity tier, deciding which backend answers.
 *
 * The distinction that matters is T2: this device has no route to the Ground Station, but a nearby
 * peer does, so the question travels over BLE and comes back answered. That's the case the whole
 * design exists for — a phone with no Wi-Fi and no signal still reaching a live database.
 */
enum class Tier(val label: String) {
    T4_FULL("Full"),
    T3_WEAK("Direct"),
    T2_TRICKLE("Relayed"),
    T1_MESH("Mesh"),
    T0_ALONE("Offline"),
}

/**
 * Snapshot of what's reachable right now.
 *
 * [gatewayAvailable] means *a peer* advertises itself as a gateway, not that this device is one —
 * a distinction that is easy to invert and produces a cascade that never relays.
 */
data class HealthState(
    val groundStationReachable: Boolean = false,
    val groundStationUrl: String? = null,
    val backendName: String? = null,
    val recallOk: Boolean = false,
    val peerCount: Int = 0,
    val gatewayAvailable: Boolean = false,
    val thisDeviceIsGateway: Boolean = false,
) {
    val tier: Tier
        get() = when {
            groundStationReachable && peerCount > 0 -> Tier.T4_FULL
            groundStationReachable -> Tier.T3_WEAK
            gatewayAvailable -> Tier.T2_TRICKLE
            peerCount > 0 -> Tier.T1_MESH
            else -> Tier.T0_ALONE
        }
}

/** Every retrieval backend implements this, so [QueryHandler] can treat them uniformly. */
interface KnowledgeSearch {
    val backend: Backend

    /** Search with a pre-computed query embedding. [queryText] is passed for backends that need it. */
    suspend fun search(queryText: String, queryVector: FloatArray, topK: Int): List<SearchResult>

    suspend fun isAvailable(): Boolean
}

/** How much the retrieval layer trusts its own best match. */
enum class Confidence { HIGH, LOW, REFUSED }

/**
 * Final answer from the retrieval cascade, before any LLM sees it.
 *
 * [Confidence.REFUSED] is load-bearing: it means the LLM must not answer from its own weights, even
 * when it plainly knows something. That's what stops a language model from inventing a drug dose.
 */
data class QueryResponse(
    val question: String,
    val confidence: Confidence,
    val results: List<SearchResult>,
    val backend: Backend,
    val tier: Tier,
    val refusalReason: String? = null,
) {
    val refused: Boolean get() = confidence == Confidence.REFUSED
    val topScore: Float get() = results.firstOrNull()?.score ?: 0f
}

/**
 * A physical emergency facility from the POI collection.
 *
 * Distinct from [SearchResult] because the useful fields are entirely different: a facility is ranked
 * by distance rather than semantic score, and it carries an address, coordinates and a verification
 * status that a text passage doesn't have.
 */
data class Facility(
    val id: String,
    val name: String,
    val nameLocal: String?,
    val category: String,
    val specialties: List<String>,
    val latitude: Double,
    val longitude: Double,
    val distanceKm: Double,
    val address: String?,
    val phone: String?,
    val emergency24h: Boolean,
    val hasEmergencyDept: Boolean?,
    val operator: String?,
    /** "verified" or otherwise. Anything else must be presented with that caveat attached. */
    val dataStatus: String,
    val sourceDoc: String,
    val packVersion: String,
)

/** Result of a facility lookup, including the advisory the server attaches. */
data class FacilityResponse(
    val results: List<Facility>,
    val backend: Backend,
    val tier: Tier,
    /** Server-supplied caveat about data provenance; shown rather than paraphrased. */
    val advisory: String?,
    val needsLocation: Boolean = false,
) {
    val isEmpty: Boolean get() = results.isEmpty()
}
