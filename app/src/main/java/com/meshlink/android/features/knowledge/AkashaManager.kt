package com.MeshLink.android.features.knowledge

import android.content.Context
import android.util.Log
import com.MeshLink.android.features.knowledge.embedder.KeywordFallbackEmbedder
import com.MeshLink.android.features.knowledge.embedder.OnnxMiniLmEmbedder
import com.MeshLink.android.features.knowledge.embedder.TextEmbedder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Single entry point for the knowledge layer. Owns the embedder, the backends and the health loop.
 *
 * Callers use [ask]. Everything about which backend answers, whether a peer is relaying, and how
 * confident the result is stays inside here.
 */
object AkashaManager {

    private const val TAG = "AkashaManager"

    /** How often connectivity is re-checked. Covers joining Wi-Fi, or the server starting later. */
    private const val HEALTH_POLL_MS = 20_000L

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private lateinit var appContext: Context

    private var localSearch: LocalIndexSearch? = null
    private var groundStation: GroundStationSearch? = null
    private var prober: GroundStationProber? = null
    private var meshRelay: MeshRelaySearch? = null
    private var handler: QueryHandler? = null

    @Volatile
    private var embedder: TextEmbedder = KeywordFallbackEmbedder()

    private val _health = MutableStateFlow(HealthState())
    val health: StateFlow<HealthState> = _health.asStateFlow()

    private val _ready = MutableStateFlow(false)
    val ready: StateFlow<Boolean> = _ready.asStateFlow()

    /** Name of the live embedder, for the diagnostics line in the UI. */
    val embedderName: String get() = embedder.name
    val semanticSearchReady: Boolean get() = embedder.isSemantic

    @Volatile
    private var initialised = false

    /** Supplied by the mesh service so health can report peers without a dependency on it. */
    private var peerCountProvider: (() -> Int)? = null

    /** A peer answering within this window is treated as evidence a gateway is reachable. */
    private const val GATEWAY_EVIDENCE_WINDOW_MS = 120_000L

    /** Categories queried per question. Each is a separate round trip, so this bounds latency. */
    private const val MAX_FACILITY_CATEGORIES = 3

    /** Rows requested per category before merging. */
    private const val PER_CATEGORY_LIMIT = 3

    /** Cards shown in total. More than this is unreadable on a phone under stress. */
    private const val MAX_FACILITIES_SHOWN = 5

    /**
     * Wire everything up. Safe to call repeatedly.
     *
     * [sendQuery] / [sendResult] broadcast mesh packets; they're passed in rather than imported so
     * this object doesn't depend on the mesh service, which already depends on it.
     */
    fun init(
        context: Context,
        sendQuery: (ByteArray) -> Unit,
        sendResult: (ByteArray) -> Unit,
    ) {
        if (initialised) return
        initialised = true
        appContext = context.applicationContext

        val local = LocalIndexSearch(appContext)
        val station = GroundStationSearch()
        val probe = GroundStationProber(appContext, station)
        val relay = MeshRelaySearch(sendQuery, sendResult)

        localSearch = local
        groundStation = station
        prober = probe
        meshRelay = relay

        handler = QueryHandler(
            embedder = { embedder },
            localSearch = local,
            groundStation = station,
            meshRelay = relay,
            healthProvider = { _health.value },
        )

        // Answer peers from our own cascade, but without re-broadcasting: a relayed query may reach
        // the Ground Station, it may not start another mesh fan-out.
        // Facility queries from peers are served from the Ground Station only. If this device has no
        // route either, an empty list keeps the query hopping toward one that does.
        relay.facilityResponder = { question, lat, lon, topK ->
            if (!station.isAvailable()) {
                emptyList()
            } else {
                val intent = IntentRouter.classify(question)
                val categories = intent.facilityCategories.ifEmpty {
                    listOf(IntentRouter.Category.HOSPITAL, IntentRouter.Category.POLICE)
                }
                val found = LinkedHashMap<String, Facility>()
                for (category in categories.take(MAX_FACILITY_CATEGORIES)) {
                    station.searchFacilities(
                        latitude = lat,
                        longitude = lon,
                        category = category,
                        topK = topK,
                    ).forEach { found.putIfAbsent(it.id, it) }
                }
                found.values
                    .sortedBy { it.distanceKm }
                    // Three records is what fits in one unfragmented BLE packet.
                    .take(3)
                    .map { it.toSearchable() }
            }
        }

        relay.localResponder = { question, topK ->
            handler!!.ask(
                question = question,
                topK = topK,
                allowGroundStation = true,
                allowMeshFanout = false,
            )
        }

        scope.launch { loadEmbedder() }
        scope.launch { healthLoop() }

        Log.i(TAG, "AkashaManager initialised")
    }

    /** Let the mesh service report peer state without this object importing it. */
    fun setMeshStateProviders(peerCount: () -> Int) {
        peerCountProvider = peerCount
    }

    /**
     * Try to bring up the real embedder, falling back to keyword matching.
     *
     * Not fatal when the 87 MB ONNX model is missing — that's the expected state on a fresh install,
     * since the model ships out-of-band like the LLM weights.
     */
    private suspend fun loadEmbedder() {
        val onnx = OnnxMiniLmEmbedder.create(appContext)
        if (onnx != null) {
            embedder = onnx
            Log.i(TAG, "Semantic search ready: ${onnx.name}")
        } else {
            Log.w(TAG, "Semantic search unavailable; keyword fallback in use")
        }
        _ready.value = localSearch?.isAvailable() == true
    }

    /**
     * Re-probe connectivity on a loop.
     *
     * Runs continuously rather than once at startup because the interesting transitions all happen
     * later: joining Wi-Fi, the server coming up after the app, or a gateway peer walking into range.
     */
    private suspend fun healthLoop() {
        while (scope.isActive) {
            try {
                refreshHealth()
            } catch (e: Exception) {
                Log.w(TAG, "Health refresh failed: ${e.message}")
            }
            delay(HEALTH_POLL_MS)
        }
    }

    /** One health pass: probe the station, fold in mesh state, publish. */
    suspend fun refreshHealth() {
        val stationHealth = prober?.discover()
        val peers = peerCountProvider?.invoke() ?: 0
        val onWifi = prober?.isOnWifi() ?: false

        meshRelay?.peersPresent = peers > 0

        // "A gateway exists" is inferred from a peer having actually answered recently, rather than
        // from an advertised flag. Evidence beats a claim, and it needs no protocol change.
        val relayProven = (meshRelay?.lastRelaySuccessAt ?: 0L)
            .let { it > 0L && System.currentTimeMillis() - it < GATEWAY_EVIDENCE_WINDOW_MS }

        _health.value = HealthState(
            groundStationReachable = stationHealth != null,
            groundStationUrl = stationHealth?.groundStationUrl,
            backendName = stationHealth?.backendName,
            recallOk = stationHealth?.recallOk ?: false,
            peerCount = peers,
            gatewayAvailable = relayProven,
            // We advertise as a gateway only when we can actually serve: a route to the station,
            // not merely Wi-Fi. Claiming otherwise would make peers relay into a dead end.
            thisDeviceIsGateway = stationHealth != null && onWifi,
        )
    }

    /** True when peers should be told this device can reach the database. */
    fun isGateway(): Boolean = _health.value.thisDeviceIsGateway

    /**
     * Look up physical facilities near [latitude]/[longitude] for whatever the question implies.
     *
     * Coordinates come from the device, never from the LLM — a model must not be able to guess where
     * someone is. Tries each category the intent suggests in order and returns the first that yields
     * rows, so "I'm being followed" gets police stations and falls back to rescue centres.
     */
    suspend fun findFacilities(
        question: String,
        latitude: Double?,
        longitude: Double?,
        topK: Int = 3,
    ): FacilityResponse {
        val intent = IntentRouter.classify(question)
        val station = groundStation

        Log.i(
            TAG,
            "Facility lookup: intent=${intent.kind} categories=${intent.facilityCategories} " +
                "specialty=${intent.specialty} haveLocation=${latitude != null && longitude != null} " +
                "stationUp=${station?.baseUrl != null}",
        )

        if (intent.facilityCategories.isEmpty() || station == null) {
            return FacilityResponse(emptyList(), Backend.NONE, _health.value.tier, null)
        }

        if (latitude == null || longitude == null) {
            // The model is expected to ask for an area rather than invent one.
            return FacilityResponse(emptyList(), Backend.NONE, _health.value.tier, null, needsLocation = true)
        }

        if (!station.isAvailable()) {
            // No route of our own. Ask the mesh: a peer on the server's Wi-Fi can run the lookup and
            // send the records back, which is the whole point of the relay tier — this device gets
            // database-backed places without any network of its own.
            val relay = meshRelay
            if (relay != null && relay.peersPresent) {
                Log.i(TAG, "Ground Station unreachable — asking peers for facilities")
                val relayed = relay.searchFacilities(question, latitude, longitude, topK)
                if (relayed.isNotEmpty()) {
                    return FacilityResponse(
                        results = relayed.map { it.toFacility() },
                        backend = Backend.MESH_GATEWAY,
                        tier = _health.value.tier,
                        advisory = null,
                    )
                }
                Log.d(TAG, "No peer could answer the facility query")
            }

            // Nothing reachable, and no offline POI pack ships with the app, so there is genuinely
            // nothing to search. Reported honestly rather than as "none nearby".
            Log.d(TAG, "Facility lookup skipped: no Ground Station and no peer route")
            return FacilityResponse(emptyList(), Backend.NONE, _health.value.tier, null)
        }

        // Query every relevant category, not just the first that answers.
        //
        // Returning only the primary category was wrong: someone being followed needs the nearest
        // police station *and* the nearest rescue centre, and someone injured may be better served by
        // a closer clinic than a distant hospital. Results are merged and sorted by distance so the
        // list reads as "closest places that can help", regardless of category.
        val merged = LinkedHashMap<String, Facility>()
        for (category in intent.facilityCategories.take(MAX_FACILITY_CATEGORIES)) {
            Log.i(TAG, "POI query -> $category")
            val results = station.searchFacilities(
                latitude = latitude,
                longitude = longitude,
                category = category,
                specialty = intent.specialty.takeIf {
                    category == IntentRouter.Category.HOSPITAL || category == IntentRouter.Category.CLINIC
                },
                topK = PER_CATEGORY_LIMIT,
            )
            if (results.isEmpty()) {
                Log.d(TAG, "No $category rows near the user")
                continue
            }
            Log.i(TAG, "Facilities: ${results.size} $category within ${results.maxOf { it.distanceKm }} km")
            // Keyed by id so a facility listed under two categories appears once.
            results.forEach { merged.putIfAbsent(it.id, it) }
        }

        if (merged.isNotEmpty()) {
            val ordered = merged.values.sortedBy { it.distanceKm }.take(MAX_FACILITIES_SHOWN)
            return FacilityResponse(
                results = ordered,
                backend = Backend.GROUND_STATION,
                tier = _health.value.tier,
                advisory = station.lastFacilityAdvisory,
            )
        }

        // A specialty filter can empty an otherwise populated category, so retry without it before
        // concluding there's nothing there.
        if (intent.specialty != null) {
            val fallback = station.searchFacilities(
                latitude = latitude,
                longitude = longitude,
                category = intent.facilityCategories.first(),
                specialty = null,
                topK = topK,
            )
            if (fallback.isNotEmpty()) {
                return FacilityResponse(fallback, Backend.GROUND_STATION, _health.value.tier, station.lastFacilityAdvisory)
            }
        }

        return FacilityResponse(emptyList(), Backend.GROUND_STATION, _health.value.tier, null)
    }

    /** The question-answering entry point. */
    suspend fun ask(question: String, topK: Int = 3): QueryResponse {
        val h = handler ?: return QueryResponse(
            question = question,
            confidence = Confidence.REFUSED,
            results = emptyList(),
            backend = Backend.NONE,
            tier = Tier.T0_ALONE,
            refusalReason = "Knowledge layer not initialised",
        )
        return h.ask(question, topK)
    }

    // --- Mesh packet entry points, called by BluetoothMeshService ---

    suspend fun onMeshQuery(payload: ByteArray) {
        meshRelay?.onQueryReceived(payload)
    }

    fun onMeshQueryResult(payload: ByteArray) {
        meshRelay?.onResultReceived(payload)
    }

    /** Manually pin a Ground Station address, for when discovery can't work on a given network. */
    fun setManualGroundStation(url: String?) {
        prober?.manualUrl = url
        scope.launch { refreshHealth() }
    }

    fun manualGroundStation(): String? = prober?.manualUrl

    val packVersion: String? get() = localSearch?.packVersion
}

/**
 * Shrink a database record to the subset that fits in a BLE packet.
 *
 * Specialties and operator are dropped, and the phone number is never carried — the database does not
 * vouch for phone numbers, so relaying one would launder an unverified value across a hop.
 */
private fun Facility.toSearchable() = MeshQueryCodec.SearchableFacility(
    name = name,
    category = category,
    address = address.orEmpty(),
    latitude = latitude,
    longitude = longitude,
    distanceKm = distanceKm,
    emergency24h = emergency24h,
)

/**
 * Rebuild a UI-facing record from a relayed one.
 *
 * Provenance is set to reflect what actually happened: the data came from Actian, but by way of a
 * peer, so it is marked as relayed rather than claiming a direct database read.
 */
private fun MeshQueryCodec.SearchableFacility.toFacility() = Facility(
    id = "relay:$name",
    name = name,
    nameLocal = null,
    category = category,
    specialties = emptyList(),
    latitude = latitude,
    longitude = longitude,
    distanceKm = distanceKm,
    address = address.ifBlank { null },
    phone = null,
    emergency24h = emergency24h,
    hasEmergencyDept = null,
    operator = null,
    dataStatus = "relayed",
    sourceDoc = "Actian VectorAI DB (via nearby device)",
    packVersion = "",
)
