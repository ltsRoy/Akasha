package com.MeshLink.android.ai

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

data class AriaMessage(
    val text: String,
    val isUser: Boolean,
    /**
     * Retrieval provenance for an assistant reply, when the knowledge layer was consulted.
     *
     * Carried on the message rather than held as screen-level state so the citations stay attached to
     * the answer they justify, even after later turns scroll past.
     */
    val sources: List<com.MeshLink.android.features.knowledge.SearchResult> = emptyList(),
    val backend: com.MeshLink.android.features.knowledge.Backend? = null,
    val tier: com.MeshLink.android.features.knowledge.Tier? = null,
    val confidence: com.MeshLink.android.features.knowledge.Confidence? = null,
    /** Nearby facilities from the emergency database, rendered as cards below the answer. */
    val facilities: List<com.MeshLink.android.features.knowledge.Facility> = emptyList(),
    /** The database's own caveat about facility data, shown as-is. */
    val facilityAdvisory: String? = null,
)

class AriaViewModel(application: Application) : AndroidViewModel(application) {
    
    private val _messages = MutableStateFlow<List<AriaMessage>>(emptyList())
    val messages: StateFlow<List<AriaMessage>> = _messages.asStateFlow()

    private val _isTyping = MutableStateFlow(false)
    val isTyping: StateFlow<Boolean> = _isTyping.asStateFlow()

    /** The name of the engine that handled the last request. */
    private val _activeEngine = MutableStateFlow(AriaEngineManager.activeEngineName)
    val activeEngine: StateFlow<String> = _activeEngine.asStateFlow()

    /**
     * True when a real on-device LLM is loaded, as opposed to the keyword fallback.
     *
     * This is a flag rather than a comparison against the engine name: the name reflects whichever
     * model bundle actually loaded (Gemma, Qwen, ...), so matching it against a fixed string left
     * the UI claiming no model was installed while inference was demonstrably running.
     */
    private val _isModelReady = MutableStateFlow(AriaEngineManager.isLocalModelReady())
    val isModelReady: StateFlow<Boolean> = _isModelReady.asStateFlow()

    /**
     * True while the model is being loaded from disk.
     *
     * Separate from [isModelReady] because loading a half-gigabyte model takes several seconds, and
     * during that window the UI was previously showing "Offline Basic" plus an "Install AI Model"
     * prompt — telling the user to install something that was already installed and loading.
     */
    private val _isModelLoading = MutableStateFlow(false)
    val isModelLoading: StateFlow<Boolean> = _isModelLoading.asStateFlow()

    init {
        // Probe engine availability in the background so the first real
        // request doesn't pay the latency cost.
        viewModelScope.launch {
            try {
                // Load the on-device model first so the engine indicator is accurate before the
                // user's first question, rather than reporting the keyword fallback then switching.
                _isModelLoading.value = true
                AriaEngineManager.initializeGemma(getApplication())
                _activeEngine.value = AriaEngineManager.activeEngineName
                _isModelReady.value = AriaEngineManager.isLocalModelReady()
                _isModelLoading.value = false
            } catch (_: Exception) {
                _isModelLoading.value = false
                // Tier 3 always works, so this shouldn't fail
            }
            // Remove the probe response — it was just for engine discovery
        }
    }

    /**
     * Clear the transcript and the model's conversation memory together.
     *
     * Clearing only the UI list would leave the session's KV cache populated, so the model would
     * keep answering as if the "deleted" turns were still there — and keep consuming context budget.
     */
    fun clearChat() {
        _messages.value = emptyList()
        AriaEngineManager.resetConversation()
    }

    fun triggerDownload() {
        viewModelScope.launch {
            _messages.value = _messages.value + AriaMessage(
                "Initiating model download. This may take a few minutes depending on your connection. Please wait...",
                isUser = false
            )
            AriaEngineManager.triggerDownload()
            _activeEngine.value = AriaEngineManager.activeEngineName
            _isModelReady.value = AriaEngineManager.isLocalModelReady()
            if (_isModelReady.value) {
                _messages.value = _messages.value + AriaMessage(
                    "Model successfully activated! I am now running on-device AI.",
                    isUser = false
                )
            } else {
                _messages.value = _messages.value + AriaMessage(
                    "Model download started or pending. Google Play Services will download it in the background.",
                    isUser = false
                )
            }
        }
    }

    /**
     * Best-effort coordinates for facility lookup, as (latitude, longitude).
     *
     * Returns null without a permission or a fix, which makes the facility path report
     * `needsLocation` instead of searching around a fabricated point.
     */
    private suspend fun lastKnownLocation(): Pair<Double, Double>? {
        val app = getApplication<Application>()
        val granted = androidx.core.content.ContextCompat.checkSelfPermission(
            app,
            android.Manifest.permission.ACCESS_FINE_LOCATION,
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!granted) return null

        return try {
            val client = com.google.android.gms.location.LocationServices
                .getFusedLocationProviderClient(app)
            val location: android.location.Location? = client.lastLocation.await()
            location?.let { it.latitude to it.longitude }
        } catch (e: Exception) {
            null
        }
    }

    fun sendMessage(text: String) {
        if (text.isBlank()) return
        
        // Add user message
        val userMsg = AriaMessage(text, isUser = true)
        _messages.value = _messages.value + userMsg
        
        _isTyping.value = true

        viewModelScope.launch {
            try {
                // Retrieval runs first and decides what happens next. When it refuses, the LLM is
                // never invoked — that's what stops the model answering from its own weights.
                // The device supplies coordinates; the model is never allowed to guess them.
                val here = lastKnownLocation()
                val grounding = com.MeshLink.android.features.knowledge.llm.AkashaAriaBridge
                    .ground(text, here?.first, here?.second)
                val kb = grounding.response

                // Placeholder carrying its provenance from the outset, rewritten in place as tokens
                // stream in so the answer appears progressively instead of after a long silence.
                //
                // Retrieval results are attached NOW rather than after generation finishes. Writing
                // them only at the end meant any late streaming callback replaced the message with a
                // text-only copy and silently dropped the facility cards — the data was retrieved and
                // then thrown away by a race. Every subsequent update copies this message, so
                // whichever write lands last, the provenance survives.
                val placeholderIndex = _messages.value.size
                _messages.value = _messages.value + if (grounding.isVerified) {
                    AriaMessage(
                        text = "",
                        isUser = false,
                        sources = kb.results,
                        backend = grounding.effectiveBackend,
                        tier = kb.tier,
                        confidence = kb.confidence,
                        facilities = grounding.facilities,
                        facilityAdvisory = grounding.facilityAdvisory,
                    )
                } else {
                    // Nothing verified matched: an ordinary-looking answer with no badge.
                    AriaMessage(text = "", isUser = false)
                }

                val (response, engineName) = AriaEngineManager.chatStreaming(
                    userMessage = text,
                    chatContext = grounding.groundingBlock ?: "",
                    context = getApplication(),
                ) { partial ->
                    if (partial.isNotBlank()) {
                        _messages.value = _messages.value.toMutableList().also { list ->
                            if (placeholderIndex < list.size) {
                                list[placeholderIndex] = list[placeholderIndex].copy(text = partial)
                            }
                        }
                        _isTyping.value = false
                    }
                }

                _activeEngine.value = engineName
                _isModelReady.value = AriaEngineManager.isLocalModelReady()
                _messages.value = _messages.value.toMutableList().also { list ->
                    if (placeholderIndex < list.size) {
                        list[placeholderIndex] = list[placeholderIndex].copy(text = response)
                    }
                }
            } catch (e: Exception) {
                _messages.value = _messages.value + AriaMessage(
                    "⚠️ All AI engines failed: ${e.message}\n\n" +
                    "Try asking about: first aid, water, shelter, fire, signaling, or navigation.",
                    isUser = false
                )
            } finally {
                _isTyping.value = false
            }
        }
    }
}
