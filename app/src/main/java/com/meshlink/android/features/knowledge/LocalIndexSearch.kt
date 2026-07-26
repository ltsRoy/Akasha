package com.MeshLink.android.features.knowledge

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray

/**
 * Cosine search over the offline pack bundled in `assets/akasha/distilled_pack.json`.
 *
 * This is the backend that always works — no network, no peers, no permissions. Everything else in
 * the cascade is an upgrade on top of it.
 *
 * Also provides a keyword path for when no real embedder is available. That path is deliberately
 * separate: scoring hashed pseudo-vectors against real MiniLM vectors would produce confident
 * nonsense, so when the embedder isn't semantic we match words instead and score conservatively.
 */
class LocalIndexSearch(private val context: Context) : KnowledgeSearch {

    override val backend = Backend.LOCAL_PACK

    companion object {
        private const val TAG = "LocalIndexSearch"
        private const val PACK_ASSET = "akasha/distilled_pack.json"

        /**
         * Words too common to indicate topical overlap. Without this, "what do I do if" matches
         * every passage in the pack roughly equally.
         */
        private val STOPWORDS = setOf(
            "a", "an", "the", "is", "are", "was", "were", "be", "been", "being", "do", "does",
            "did", "have", "has", "had", "i", "you", "he", "she", "it", "we", "they", "my", "his",
            "her", "its", "our", "their", "me", "him", "them", "what", "how", "why", "when",
            "where", "who", "which", "to", "of", "in", "on", "at", "for", "with", "and", "or",
            "but", "if", "then", "than", "so", "should", "would", "could", "can", "will", "there",
            "this", "that", "these", "those", "not", "no", "yes", "get", "got", "very", "some",
        )

        /**
         * Ceiling applied to keyword-derived scores.
         *
         * Keyword overlap is a weak signal, so it is capped below the HIGH threshold. A word match
         * can surface a passage as a suggestion but can never claim the confidence that a real
         * embedding match earns.
         */
        private const val KEYWORD_SCORE_CEILING = 0.44f
    }

    @Volatile
    private var points: List<KnowledgePoint>? = null

    var packVersion: String? = null
        private set

    /** Parse the pack once, on first use. */
    private suspend fun ensureLoaded(): List<KnowledgePoint> = withContext(Dispatchers.IO) {
        points?.let { return@withContext it }

        val loaded = try {
            val json = context.assets.open(PACK_ASSET).bufferedReader().use { it.readText() }
            val array = JSONArray(json)
            val out = ArrayList<KnowledgePoint>(array.length())

            for (i in 0 until array.length()) {
                val o = array.getJSONObject(i)
                val vecArray = o.getJSONArray("vector")
                val vec = FloatArray(vecArray.length()) { vecArray.getDouble(it).toFloat() }
                out += KnowledgePoint(
                    id = o.optString("id"),
                    text = o.optString("text"),
                    vector = vec,
                    sourceDoc = o.optString("sourceDoc"),
                    packVersion = o.optString("packVersion"),
                    category = o.optString("category"),
                    lang = o.optString("lang", "en"),
                )
            }
            packVersion = out.firstOrNull()?.packVersion
            Log.i(TAG, "Loaded ${out.size} passages, pack $packVersion, dim ${out.firstOrNull()?.vector?.size}")
            out
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load pack: ${e.message}")
            emptyList()
        }

        points = loaded
        loaded
    }

    override suspend fun isAvailable(): Boolean = ensureLoaded().isNotEmpty()

    override suspend fun search(queryText: String, queryVector: FloatArray, topK: Int): List<SearchResult> {
        val pack = ensureLoaded()
        if (pack.isEmpty()) return emptyList()

        return pack.asSequence()
            .map { point -> point to cosine(queryVector, point.vector) }
            .sortedByDescending { it.second }
            .take(topK)
            .map { (point, score) -> point.toResult(score) }
            .toList()
    }

    /**
     * Keyword fallback for when no semantic embedder is loaded.
     *
     * Scores by proportion of meaningful query words found in the passage, plus a bonus when the
     * passage's own category is named. Capped by [KEYWORD_SCORE_CEILING] so it can never masquerade
     * as a high-confidence semantic hit.
     */
    suspend fun searchByKeyword(queryText: String, topK: Int): List<SearchResult> {
        val pack = ensureLoaded()
        if (pack.isEmpty()) return emptyList()

        val terms = queryText.lowercase()
            .split(Regex("\\W+"))
            .filter { it.length > 2 && it !in STOPWORDS }
            .toSet()

        if (terms.isEmpty()) return emptyList()

        return pack.asSequence()
            .map { point ->
                val haystack = (point.text + " " + point.category).lowercase()
                val hits = terms.count { haystack.contains(it) }
                var score = hits.toFloat() / terms.size
                if (terms.any { point.category.lowercase().contains(it) }) score += 0.15f
                point to minOf(score, KEYWORD_SCORE_CEILING)
            }
            .filter { it.second > 0f }
            .sortedByDescending { it.second }
            .take(topK)
            .map { (point, score) -> point.toResult(score) }
            .toList()
    }

    private fun KnowledgePoint.toResult(score: Float) = SearchResult(
        text = text,
        score = score,
        category = category,
        sourceDoc = sourceDoc,
        packVersion = packVersion,
    )

    /**
     * Dot product, which equals cosine similarity because both the pack vectors and the embedder's
     * output are L2-normalised. Falls back to full cosine if a vector arrives un-normalised.
     */
    private fun cosine(a: FloatArray, b: FloatArray): Float {
        val n = minOf(a.size, b.size)
        var dot = 0f
        var na = 0f
        var nb = 0f
        for (i in 0 until n) {
            dot += a[i] * b[i]
            na += a[i] * a[i]
            nb += b[i] * b[i]
        }
        if (na == 0f || nb == 0f) return 0f
        val denom = kotlin.math.sqrt(na) * kotlin.math.sqrt(nb)
        return if (denom > 0f) dot / denom else 0f
    }
}
