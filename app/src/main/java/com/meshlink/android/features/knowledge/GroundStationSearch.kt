package com.MeshLink.android.features.knowledge

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * HTTP client for the Ground Station, which fronts the Actian VectorAI DB.
 *
 * The server takes a **vector**, never text: `POST /search {vector[384], top_k, filters}`. So the
 * query is embedded on this device and the embedding is what crosses the network. That's why the
 * embedder has to match the one used to build the index â€” a mismatch produces plausible-looking but
 * meaningless scores.
 *
 * Timeouts are deliberately short. This runs in an emergency app where a stalled request must lose
 * to the offline pack quickly rather than leaving someone staring at a spinner.
 */
class GroundStationSearch : KnowledgeSearch {

    override val backend = Backend.GROUND_STATION

    companion object {
        private const val TAG = "GroundStationSearch"
        private const val VECTOR_DIM = 384
        private val JSON = "application/json; charset=utf-8".toMediaType()
    }

    /** Internal rather than private so the POI extension in this file can reuse the same client. */
    internal val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(2, TimeUnit.SECONDS)
        .readTimeout(4, TimeUnit.SECONDS)
        .writeTimeout(4, TimeUnit.SECONDS)
        // Retry enabled specifically because of pooled-connection staleness: the station closes idle
        // keep-alive sockets, and reusing one produces "unexpected end of stream" on the next poll.
        // Without a retry, every other health check fails even though the server is perfectly healthy.
        .retryOnConnectionFailure(true)
        .build()

    /** Base URL like `http://192.168.1.20:8000`, set once discovery finds a station. */
    @Volatile
    var baseUrl: String? = null

    @Volatile
    var lastHealth: HealthState? = null
        private set

    /** Provenance caveat from the most recent facility lookup, surfaced verbatim to the user. */
    @Volatile
    internal var lastFacilityAdvisory: String? = null

    override suspend fun isAvailable(): Boolean = baseUrl != null && (lastHealth?.groundStationReachable == true)

    /**
     * Probe `/health`.
     *
     * Also reports `backend` and `recall_ok`: the station can be up while serving from an in-memory
     * fallback, or with a degraded vector index. Both answer requests, so only this endpoint
     * distinguishes "working" from "actually backed by Actian".
     */
    suspend fun checkHealth(url: String): HealthState? = withContext(Dispatchers.IO) {
        try {
            // No keep-alive for polls. The station drops idle sockets between the 20s health checks,
            // and a reused one fails with "unexpected end of stream" *after* the request is sent,
            // which OkHttp's retry does not cover. Asking for a fresh connection each poll trades a
            // negligible handshake cost for a check that doesn't spuriously report the server down.
            val request = Request.Builder()
                .url("$url/health")
                .header("Connection", "close")
                .get()
                .build()
            okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val body = response.body?.string() ?: return@withContext null
                val json = JSONObject(body)

                if (json.optInt("dim", VECTOR_DIM) != VECTOR_DIM) {
                    Log.w(TAG, "Station dim ${json.optInt("dim")} != $VECTOR_DIM â€” refusing to use it")
                    return@withContext null
                }

                HealthState(
                    groundStationReachable = json.optBoolean("ok", true),
                    groundStationUrl = url,
                    backendName = json.optString("backend", "unknown"),
                    recallOk = json.optBoolean("recall_ok", false),
                ).also { lastHealth = it }
            }
        } catch (e: Exception) {
            Log.d(TAG, "Health check failed for $url: ${e.message}")
            null
        }
    }

    override suspend fun search(queryText: String, queryVector: FloatArray, topK: Int): List<SearchResult> =
        withContext(Dispatchers.IO) {
            val url = baseUrl ?: return@withContext emptyList()

            if (queryVector.size != VECTOR_DIM) {
                // The endpoint enforces exactly 384 and would 422; fail locally with a clear reason.
                Log.w(TAG, "Query vector is ${queryVector.size} dims, expected $VECTOR_DIM")
                return@withContext emptyList()
            }

            try {
                val vectorJson = JSONArray().apply { queryVector.forEach { put(it.toDouble()) } }
                val payload = JSONObject()
                    .put("vector", vectorJson)
                    .put("top_k", topK)
                    .toString()

                val request = Request.Builder()
                    .url("$url/search")
                    .post(payload.toRequestBody(JSON))
                    .build()

                okHttpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        Log.w(TAG, "Search failed: HTTP ${response.code}")
                        return@withContext emptyList()
                    }
                    val body = response.body?.string() ?: return@withContext emptyList()
                    parseResults(JSONObject(body).optJSONArray("results"))
                }
            } catch (e: Exception) {
                Log.w(TAG, "Search error: ${e.message}")
                emptyList()
            }
        }

    /** Server response uses snake_case, unlike the bundled pack's camelCase. */
    private fun parseResults(array: JSONArray?): List<SearchResult> {
        if (array == null) return emptyList()
        val out = ArrayList<SearchResult>(array.length())
        for (i in 0 until array.length()) {
            val o = array.optJSONObject(i) ?: continue
            out += SearchResult(
                text = o.optString("text"),
                score = o.optDouble("score", 0.0).toFloat(),
                category = o.optString("category"),
                sourceDoc = o.optString("source_doc"),
                packVersion = o.optString("pack_version"),
            )
        }
        return out
    }
}

/**
 * Facility lookup against the Ground Station's POI collection (`/poi/search`).
 *
 * Kept as an extension rather than folded into [KnowledgeSearch] because the two searches answer
 * different questions: the safety corpus returns advice ranked by semantic similarity, while this
 * returns places ranked by distance. Forcing them through one interface would mean pretending a
 * hospital has a cosine score.
 *
 * The device supplies the coordinates â€” deliberately never the LLM, which must not be able to
 * hallucinate where the user is.
 */
suspend fun GroundStationSearch.searchFacilities(
    latitude: Double,
    longitude: Double,
    category: String? = null,
    specialty: String? = null,
    topK: Int = 3,
    maxKm: Double = 15.0,
    require24h: Boolean = false,
): List<Facility> = withContext(Dispatchers.IO) {
    val url = baseUrl ?: return@withContext emptyList()

    try {
        val payload = JSONObject()
            .put("latitude", latitude)
            .put("longitude", longitude)
            .put("top_k", topK)
            .put("max_km", maxKm)
            .apply {
                if (category != null) put("category", category)
                if (specialty != null) put("specialty", specialty)
                if (require24h) put("require_24h", true)
            }
            .toString()

        val request = Request.Builder()
            .url("$url/poi/search")
            .header("Connection", "close")
            .post(payload.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()

        okHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                Log.w("GroundStationSearch", "POI search failed: HTTP ${response.code}")
                return@withContext emptyList()
            }
            val body = response.body?.string() ?: return@withContext emptyList()
            val json = JSONObject(body)
            lastFacilityAdvisory = json.optString("data_advisory").ifBlank { null }
            parseFacilities(json.optJSONArray("results"))
        }
    } catch (e: Exception) {
        Log.w("GroundStationSearch", "POI search error: ${e.message}")
        emptyList()
    }
}

private fun parseFacilities(array: JSONArray?): List<Facility> {
    if (array == null) return emptyList()
    val out = ArrayList<Facility>(array.length())
    for (i in 0 until array.length()) {
        val o = array.optJSONObject(i) ?: continue
        val specs = o.optJSONArray("specialties")
        out += Facility(
            id = o.optString("poi_id"),
            name = o.optString("name"),
            nameLocal = o.optString("name_local").ifBlank { null },
            category = o.optString("category"),
            specialties = buildList {
                for (s in 0 until (specs?.length() ?: 0)) add(specs!!.optString(s))
            },
            latitude = o.optDouble("latitude", 0.0),
            longitude = o.optDouble("longitude", 0.0),
            distanceKm = o.optDouble("distance_km", -1.0),
            address = o.optString("address").ifBlank { null },
            // Null rather than a guess: the server omits phone numbers it can't vouch for, and
            // inventing one in an emergency is worse than having none.
            phone = if (o.isNull("phone")) null else o.optString("phone").ifBlank { null },
            emergency24h = o.optBoolean("emergency_24h", false),
            hasEmergencyDept = if (o.isNull("has_emergency_dept")) null else o.optBoolean("has_emergency_dept"),
            operator = o.optString("operator").ifBlank { null },
            dataStatus = o.optString("data_status", "unverified"),
            sourceDoc = o.optString("source_doc"),
            packVersion = o.optString("pack_version"),
        )
    }
    return out
}
