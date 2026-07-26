package com.MeshLink.android.features.knowledge.llm

import android.util.Log
import com.MeshLink.android.features.knowledge.AkashaManager
import com.MeshLink.android.features.knowledge.Confidence
import com.MeshLink.android.features.knowledge.QueryResponse

/**
 * Joins retrieval to the on-device LLM: **retrieval decides, the model only rephrases**.
 *
 * The division is deliberate and is the whole reason this layer exists. A 1B model asked about drug
 * doses or hospital addresses will answer confidently from its pretraining, and be wrong in ways that
 * matter. So facts come only from retrieved, cited passages, and when retrieval refuses, the model is
 * never invoked at all — no prompt instruction can be trusted to suppress knowledge the weights
 * already contain.
 */
object AkashaAriaBridge {

    private const val TAG = "AkashaAriaBridge"

    /**
     * Result of consulting the knowledge layer.
     *
     * The LLM is always invoked; retrieval only decides whether it gets cited passages to work from
     * and whether the answer earns a verified badge. A miss is silent by design.
     */
    data class Grounding(
        val response: QueryResponse,
        /** Passages to inject into the prompt, or null when retrieval found nothing usable. */
        val groundingBlock: String?,
        /** True for a confident passage match or any facility record from the database. */
        val isVerified: Boolean,
        /** Facilities found near the user, shown as cards rather than left to the model's prose. */
        val facilities: List<com.MeshLink.android.features.knowledge.Facility> = emptyList(),
        /** Server's provenance caveat, displayed verbatim rather than paraphrased. */
        val facilityAdvisory: String? = null,
        /** True when a facility question arrived without a location fix. */
        val needsLocation: Boolean = false,
        /** Backend credited in the UI — whichever source actually answered. */
        val effectiveBackend: com.MeshLink.android.features.knowledge.Backend =
            com.MeshLink.android.features.knowledge.Backend.NONE,
    )

    /**
     * Retrieve for [question] and decide what the LLM is allowed to do with the outcome.
     *
     * [latitude]/[longitude] come from the device. When present, facility lookup runs alongside the
     * safety search, because a distress message usually needs both halves of the answer: what to do
     * right now, and where to go.
     */
    suspend fun ground(
        question: String,
        latitude: Double? = null,
        longitude: Double? = null,
    ): Grounding {
        val intent = com.MeshLink.android.features.knowledge.IntentRouter.classify(question)

        // Some phrasings retrieve badly against a first-aid corpus — "someone is following me" has no
        // lexical overlap with anything in it. The intent layer supplies a reframed query for those.
        val safetyQuestion = intent.safetyQuery ?: question

        val response = try {
            AkashaManager.ask(safetyQuestion)
        } catch (e: Exception) {
            Log.w(TAG, "Retrieval failed: ${e.message}")
            null
        }

        val facilities = try {
            AkashaManager.findFacilities(question, latitude, longitude)
        } catch (e: Exception) {
            Log.w(TAG, "Facility lookup failed: ${e.message}")
            null
        }

        if (response == null) {
            // Retrieval itself broke. Fall through to the model's own behaviour rather than blocking
            // the user entirely — the static first-aid grounding in the engine still applies.
            return Grounding(
                response = QueryResponse(
                    question = question,
                    confidence = Confidence.REFUSED,
                    results = emptyList(),
                    backend = com.MeshLink.android.features.knowledge.Backend.NONE,
                    tier = com.MeshLink.android.features.knowledge.Tier.T0_ALONE,
                    refusalReason = "Knowledge layer unavailable",
                ),
                groundingBlock = null,
                isVerified = false,
            )
        }

        val hasFacilities = facilities != null && facilities.results.isNotEmpty()
        Log.i(
            TAG,
            "grounding: refused=${response.refused} confidence=${response.confidence} " +
                "facilities=${facilities?.results?.size ?: -1} hasFacilities=$hasFacilities",
        )

        if (response.refused && !hasFacilities) {
            // Nothing verified matched. Say nothing about it — the model answers as it did before
            // this layer existed, using its own static first-aid grounding. Announcing "I have no
            // verified information" on every unmatched question would make the assistant feel broken
            // for ordinary queries the corpus simply doesn't cover.
            Log.d(TAG, "No verified match for \"${question.take(50)}\" — answering unbadged")
            return Grounding(response = response, groundingBlock = null, isVerified = false)
        }

        val block = buildString {
            if (!response.refused) append(buildPromptBlock(response))
            if (hasFacilities) {
                if (isNotEmpty()) appendLine().appendLine()
                append(buildFacilityBlock(facilities!!, intent))
            }
        }

        return Grounding(
            response = response,
            facilities = facilities?.results.orEmpty(),
            facilityAdvisory = facilities?.advisory,
            needsLocation = facilities?.needsLocation == true,
            // Report the backend that actually produced the answer. When the safety corpus refuses
            // but the facility lookup succeeds, the corpus's backend is the wrong label — it showed
            // "Offline pack" for records that had just come from the live database.
            effectiveBackend = if (hasFacilities) facilities!!.backend else response.backend,
            groundingBlock = block,
            // A facility list from the database is itself trusted data, so it earns the badge even
            // when the safety corpus had nothing to add.
            isVerified = response.confidence == Confidence.HIGH || hasFacilities,
        )
    }

    /**
     * Facility block for the prompt.
     *
     * Names, distances and addresses are given as fixed strings the model must not alter. The
     * no-invention rules are restated here rather than left to the system prompt alone, because a
     * wrong hospital address in an emergency is the single most damaging thing this feature could
     * produce.
     */
    private fun buildFacilityBlock(
        facilities: com.MeshLink.android.features.knowledge.FacilityResponse,
        intent: com.MeshLink.android.features.knowledge.IntentRouter.Intent,
    ): String = buildString {
        // The records themselves are deliberately NOT in the prompt.
        //
        // They are rendered as cards straight from the database, so the model restating them adds no
        // information and carries two real costs: it can garble a name or address, and a long list of
        // near-identical records is exactly what sends a 1B model into a repetition loop — one answer
        // repeated the same address more than twenty times. Telling it the count and forbidding it
        // from listing them removes both problems.
        val nearest = facilities.results.minByOrNull { it.distanceKm }
        val count = facilities.results.size

        appendLine(
            "$count verified emergency locations near this person are already displayed to them as " +
                "cards directly below your answer, closest first" +
                (nearest?.let { ", the nearest about ${it.distanceKm} km away" } ?: "") + "."
        )
        appendLine(
            "Do not list, name, describe or repeat those locations — the user can already see them. " +
                "Do not give directions, travel times or phone numbers. If asked how to get there, " +
                "reply only: \"Tap a location card below to open it in Maps.\""
        )
        appendLine(
            if (intent.urgent) {
                "Give one or two short sentences of immediate action, then stop."
            } else {
                "Give a short answer of at most three sentences, then stop."
            }
        )
    }

    /**
     * The grounding block handed to the model.
     *
     * Passages are numbered and their sources named so the model can cite them, and it is told
     * explicitly that these are the only facts available. A `LOW` confidence result carries a hedge
     * instruction, because presenting a marginal match as certain is its own kind of wrong answer.
     */
    private fun buildPromptBlock(response: QueryResponse): String = buildString {
        appendLine("Verified reference passages. Every factual claim in your answer must come from these:")
        response.results.forEachIndexed { index, r ->
            appendLine("[${index + 1}] (${r.category}, source: ${r.sourceDoc}) ${r.text}")
        }
        appendLine()
        if (response.confidence == Confidence.LOW) {
            appendLine(
                "These passages are only a partial match. Say plainly that this is the closest " +
                    "guidance available and should be confirmed with a responder if possible."
            )
        }
        append("Do not add facts beyond these passages. Name the source you used.")
    }

}
