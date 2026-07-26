package com.MeshLink.android.features.knowledge

import android.util.Log
import com.MeshLink.android.features.knowledge.embedder.TextEmbedder

/**
 * The retrieval cascade and the confidence rules.
 *
 * Order is best-source-first: the live database when reachable, a peer's route to it when not, and
 * the bundled offline pack as the floor. Each step is only tried if the previous one didn't produce a
 * confident answer, so a working Ground Station costs one request rather than a full fan-out.
 *
 * The thresholds are measured, not guessed — see `ground-station/calibration.json`. For the shipped
 * 32-passage corpus, on-topic questions score 0.4689 at worst and off-topic ones 0.1836 at best, so
 * [LOW_CONFIDENCE_THRESHOLD] sits in the empty band between the two populations. **If the corpus
 * changes, re-run calibrate.py**: earlier values of 0.62/0.45 sat above the on-topic median and
 * caused correct answers to be refused.
 */
class QueryHandler(
    private val embedder: () -> TextEmbedder,
    private val localSearch: LocalIndexSearch,
    private val groundStation: GroundStationSearch,
    private val meshRelay: MeshRelaySearch,
    private val healthProvider: () -> HealthState,
) {

    companion object {
        private const val TAG = "QueryHandler"

        const val HIGH_CONFIDENCE_THRESHOLD = 0.45f
        const val LOW_CONFIDENCE_THRESHOLD = 0.30f

        private const val DEFAULT_TOP_K = 3
    }

    /**
     * Answer a question.
     *
     * [allowGroundStation] and [allowMeshFanout] scope the cascade. Both are false when answering a
     * query that arrived *from* a peer over the mesh, which prevents two isolated devices from
     * relaying the same question back and forth forever.
     */
    suspend fun ask(
        question: String,
        topK: Int = DEFAULT_TOP_K,
        allowGroundStation: Boolean = true,
        allowMeshFanout: Boolean = true,
    ): QueryResponse {
        val health = healthProvider()
        val trimmed = question.trim()

        if (trimmed.isBlank()) {
            return refused(trimmed, health, "Empty question")
        }

        val embed = embedder()
        val vector = embed.embed(trimmed)

        // 1. Ground Station, when this device can reach it directly.
        var reachedGroundStation = false
        if (allowGroundStation && vector != null && embed.isSemantic && groundStation.isAvailable()) {
            val results = groundStation.search(trimmed, vector, topK)
            reachedGroundStation = true
            val response = classify(trimmed, results, Backend.GROUND_STATION, health)
            if (response.confidence != Confidence.REFUSED) {
                Log.d(TAG, "Answered from Ground Station, top=${response.topScore}")
                return response
            }
            Log.d(TAG, "Ground Station returned nothing confident (top=${response.topScore})")
        }

        // 2. A peer with a route. The question travels as text and is embedded on their side.
        //
        // Skipped when we already queried the database ourselves: a peer would relay to the *same*
        // Actian instance, so it cannot know anything we don't. Fanning out anyway cost a pointless
        // 8-second timeout on every unmatched question, which is most of them.
        if (allowMeshFanout && !reachedGroundStation && meshRelay.isAvailable()) {
            val results = meshRelay.search(trimmed, vector ?: FloatArray(0), topK)
            if (results.isNotEmpty()) {
                val response = classify(trimmed, results, Backend.MESH_GATEWAY, health)
                if (response.confidence != Confidence.REFUSED) {
                    Log.d(TAG, "Answered via mesh gateway, top=${response.topScore}")
                    return response
                }
            }
        }

        // 3. The offline pack. Always present, so this is the floor rather than a failure.
        val localResults = if (vector != null && embed.isSemantic) {
            localSearch.search(trimmed, vector, topK)
        } else {
            // No real embedder: hashed vectors are not comparable with MiniLM's space, so match
            // words instead of pretending the cosine scores mean anything.
            Log.d(TAG, "No semantic embedder; using keyword search over local pack")
            localSearch.searchByKeyword(trimmed, topK)
        }

        return classify(trimmed, localResults, Backend.LOCAL_PACK, health)
    }

    /**
     * Turn scored results into a confidence verdict.
     *
     * Anything below [LOW_CONFIDENCE_THRESHOLD] is refused outright rather than shown with a caveat.
     * That is the point of the whole layer: an emergency assistant that answers a question it has no
     * material for is worse than one that admits it doesn't know.
     */
    private fun classify(
        question: String,
        results: List<SearchResult>,
        backend: Backend,
        health: HealthState,
    ): QueryResponse {
        val ranked = results.filter { it.text.isNotBlank() }.sortedByDescending { it.score }
        val top = ranked.firstOrNull()

        if (top == null || top.score < LOW_CONFIDENCE_THRESHOLD) {
            return QueryResponse(
                question = question,
                confidence = Confidence.REFUSED,
                results = emptyList(),
                backend = backend,
                tier = health.tier,
                refusalReason = if (top == null) {
                    "Nothing in the knowledge base matches this."
                } else {
                    "Closest match scored ${"%.2f".format(top.score)}, below the ${LOW_CONFIDENCE_THRESHOLD} floor."
                },
            )
        }

        val confidence = if (top.score >= HIGH_CONFIDENCE_THRESHOLD) Confidence.HIGH else Confidence.LOW

        return QueryResponse(
            question = question,
            confidence = confidence,
            // Keep only results that clear the floor, so a weak tail isn't cited as support.
            results = ranked.filter { it.score >= LOW_CONFIDENCE_THRESHOLD },
            backend = backend,
            tier = health.tier,
        )
    }

    private fun refused(question: String, health: HealthState, reason: String) = QueryResponse(
        question = question,
        confidence = Confidence.REFUSED,
        results = emptyList(),
        backend = Backend.NONE,
        tier = health.tier,
        refusalReason = reason,
    )
}
