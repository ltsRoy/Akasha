package com.MeshLink.android.features.knowledge

import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap

/**
 * Asks a nearby peer to answer a knowledge query, and answers them in turn.
 *
 * This is the tier that makes the whole design worth building: a phone with no Wi-Fi and no cell
 * signal broadcasts its question over BLE, a peer that *does* have a route runs it against the live
 * Actian database, and the answer comes back with its provenance intact. Neither device needs to be
 * the one with connectivity.
 *
 * Both roles live here on purpose — requester and responder share the request-id and timeout rules,
 * and splitting them across files is how those two drift apart.
 */
class MeshRelaySearch(
    /** Broadcasts a QUERY packet. Supplied by the mesh service to avoid a circular dependency. */
    private val sendQuery: (ByteArray) -> Unit,
    /** Broadcasts a QUERY_RESULT packet back toward the requester. */
    private val sendResult: (ByteArray) -> Unit,
) : KnowledgeSearch {

    override val backend = Backend.MESH_GATEWAY

    companion object {
        private const val TAG = "MeshRelaySearch"

        /**
         * How long to wait for a peer to answer.
         *
         * Covers BLE transmission, the peer's own embedding pass, and its HTTP round trip to the
         * Ground Station. Long enough to be realistic, short enough that a silent mesh falls back to
         * the offline pack while the user is still paying attention.
         */
        private const val RESPONSE_TIMEOUT_MS = 8000L
    }

    /** In-flight passage requests, keyed by hex request id. */
    private val pending = ConcurrentHashMap<String, CompletableDeferred<MeshQueryCodec.Result>>()

    /** In-flight facility requests, kept separate since the reply payload differs. */
    private val pendingFacilities =
        ConcurrentHashMap<String, CompletableDeferred<MeshQueryCodec.FacilityResult>>()

    /**
     * Answers facility queries from peers, wired by [AkashaManager] to the Ground Station only.
     *
     * Returns an empty list when this device has no route to the database, which keeps the query
     * propagating to a device that does.
     */
    var facilityResponder: (suspend (String, Double, Double, Int) -> List<MeshQueryCodec.SearchableFacility>)? = null

    /**
     * Whether any peer is in range.
     *
     * Note what this deliberately is *not*: a gateway advertisement. Announcing "I can reach the
     * database" would need a new TLV in the identity announce, which risks wire compatibility with
     * older clients already on the mesh. Instead the query is simply broadcast and whichever peer can
     * answer does. The cost is a timeout when nobody can; the benefit is no protocol change and no
     * stale flag claiming a route that has since died.
     */
    @Volatile
    var peersPresent: Boolean = false

    /** Set when a peer last answered, which is the only honest evidence a gateway exists. */
    @Volatile
    var lastRelaySuccessAt: Long = 0L
        private set

    /**
     * Answers queries received from peers. Wired to the local cascade by [AkashaManager], scoped so
     * a relayed query can reach the Ground Station but cannot trigger another mesh fan-out — that
     * would let two isolated devices bounce the same question between them.
     */
    var localResponder: (suspend (String, Int) -> QueryResponse)? = null

    override suspend fun isAvailable(): Boolean = peersPresent

    override suspend fun search(queryText: String, queryVector: FloatArray, topK: Int): List<SearchResult> {
        // Note the unused queryVector: only text goes over the air, and the responder re-embeds it.
        val requestId = MeshQueryCodec.newRequestId()
        val key = requestId.toHex()
        val deferred = CompletableDeferred<MeshQueryCodec.Result>()
        pending[key] = deferred

        return try {
            sendQuery(MeshQueryCodec.encodeQuery(requestId, queryText, topK))
            Log.d(TAG, "Sent mesh QUERY ${key.take(8)} for \"${queryText.take(40)}\"")

            val answer = withTimeoutOrNull(RESPONSE_TIMEOUT_MS) { deferred.await() }
            if (answer == null) {
                Log.d(TAG, "Mesh QUERY ${key.take(8)} timed out after ${RESPONSE_TIMEOUT_MS}ms")
                emptyList()
            } else {
                Log.d(TAG, "Mesh QUERY ${key.take(8)} answered: ${answer.results.size} results, ${answer.confidence}")
                lastRelaySuccessAt = System.currentTimeMillis()
                answer.results
            }
        } finally {
            pending.remove(key)
        }
    }

    /**
     * Ask a peer to look up facilities on our behalf.
     *
     * Our own coordinates travel with the request because the responder cannot know where we are, and
     * a facility search without a location is meaningless. Only the position is shared — no identity.
     */
    suspend fun searchFacilities(
        question: String,
        latitude: Double,
        longitude: Double,
        topK: Int,
    ): List<MeshQueryCodec.SearchableFacility> {
        val requestId = MeshQueryCodec.newRequestId()
        val key = requestId.toHex()
        val deferred = CompletableDeferred<MeshQueryCodec.FacilityResult>()
        pendingFacilities[key] = deferred

        return try {
            sendQuery(
                MeshQueryCodec.encodeQuery(
                    requestId = requestId,
                    question = question,
                    topK = topK,
                    kind = MeshQueryCodec.Kind.FACILITY,
                    latitude = latitude,
                    longitude = longitude,
                )
            )
            Log.d(TAG, "Sent mesh facility QUERY ${key.take(8)}")

            val answer = withTimeoutOrNull(RESPONSE_TIMEOUT_MS) { deferred.await() }
            if (answer == null) {
                Log.d(TAG, "Facility QUERY ${key.take(8)} timed out")
                emptyList()
            } else {
                Log.i(TAG, "Facility QUERY ${key.take(8)} answered with ${answer.facilities.size} places")
                lastRelaySuccessAt = System.currentTimeMillis()
                answer.facilities
            }
        } finally {
            pendingFacilities.remove(key)
        }
    }

    /** Called by the mesh service when a QUERY arrives from a peer. */
    suspend fun onQueryReceived(payload: ByteArray) {
        val query = MeshQueryCodec.decodeQuery(payload) ?: run {
            Log.w(TAG, "Dropping malformed QUERY (${payload.size} bytes)")
            return
        }

        if (query.kind == MeshQueryCodec.Kind.FACILITY) {
            answerFacilityQuery(query)
            return
        }

        val responder = localResponder ?: return
        Log.d(TAG, "Answering peer QUERY ${query.requestId.toHex().take(8)}: \"${query.question.take(40)}\"")

        val response = try {
            responder(query.question, query.topK)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to answer peer query: ${e.message}")
            return
        }

        // Only a device that actually reached the Ground Station may answer.
        //
        // This is what lets a query hop. Every device in range can search its own offline pack, so if
        // any of them replied, the requester would be satisfied by the first weak local answer and the
        // query would never travel further. Staying silent unless the result came from the live
        // database means the broadcast keeps being relayed, hop by hop, until it reaches a phone on
        // the same Wi-Fi as the server — which is the entire point of the relay tier.
        if (response.backend != Backend.GROUND_STATION || response.results.isEmpty()) {
            Log.d(
                TAG,
                "Not answering peer query (backend=${response.backend}, results=${response.results.size}) " +
                    "— letting it propagate to a device with a route",
            )
            return
        }

        sendResult(
            MeshQueryCodec.encodeResult(
                requestId = query.requestId,
                confidence = response.confidence,
                backend = response.backend,
                results = response.results,
            )
        )
    }

    /**
     * Look up facilities for a peer and reply, but only when we can actually reach the database.
     *
     * Silence is deliberate otherwise: if every device answered from nothing, the requester would be
     * satisfied by the first empty reply and the query would stop hopping before reaching a phone on
     * the server's network.
     */
    private suspend fun answerFacilityQuery(query: MeshQueryCodec.Query) {
        val responder = facilityResponder ?: return
        val lat = query.latitude
        val lon = query.longitude

        if (lat == null || lon == null) {
            Log.d(TAG, "Peer facility query carried no location; ignoring")
            return
        }

        val found = try {
            responder(query.question, lat, lon, query.topK)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to answer peer facility query: ${e.message}")
            return
        }

        if (found.isEmpty()) {
            Log.d(TAG, "No route to the database for peer facility query — letting it propagate")
            return
        }

        Log.i(TAG, "Relaying ${found.size} facilities to peer ${query.requestId.toHex().take(8)}")
        sendResult(MeshQueryCodec.encodeFacilityResult(query.requestId, found))
    }

    /** Called by the mesh service when a QUERY_RESULT arrives. */
    fun onResultReceived(payload: ByteArray) {
        // Facility and passage replies share the QUERY_RESULT packet type, distinguished by a marker
        // inside the payload, so try the facility shape first.
        MeshQueryCodec.decodeFacilityResult(payload)?.let { facilityResult ->
            pendingFacilities[facilityResult.requestId.toHex()]?.complete(facilityResult)
            return
        }

        val result = MeshQueryCodec.decodeResult(payload) ?: run {
            Log.w(TAG, "Dropping malformed QUERY_RESULT (${payload.size} bytes)")
            return
        }
        // Unknown ids are normal: results are broadcast, so every device sees replies to questions
        // it never asked.
        pending[result.requestId.toHex()]?.complete(result)
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
