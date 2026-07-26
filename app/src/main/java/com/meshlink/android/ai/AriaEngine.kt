package com.MeshLink.android.ai

import android.content.Context
import android.util.Log

/**
 * Common interface for all Aria AI backends.
 * Each engine can handle chat and SOS detection.
 */
interface AriaEngine {
    /** Send a chat message and get a response. */
    suspend fun chat(userMessage: String): String

    /**
     * Chat with surrounding conversation context. Engines that can use context override this;
     * the rest fall back to a plain [chat].
     */
    suspend fun chatWithContext(userMessage: String, chatContext: String): String = chat(userMessage)

    /** Analyze a message for distress signals. Returns true if SOS detected. */
    suspend fun detectSOS(message: String): Boolean

    /** Human-readable name of this engine for UI display. */
    fun engineName(): String

    /** Check if this engine is currently available on this device/network. */
    suspend fun isAvailable(): Boolean
}

/**
 * Tiered AI engine orchestrator.
 *
 * Resolution order:
 *   1. Gemma 3 1B   (bundled/side-loaded weights, runs on any device, fully offline)
 *   2. Gemini Nano  (system-provided via AICore — faster and no model file, but only on devices
 *                    where Google actually provisions the model)
 *   3. Local engine (keyword rules, zero dependencies, always works)
 *
 * Gemma is deliberately tried *first* rather than Nano. Nano depends on AICore having downloaded a
 * model, which silently doesn't happen on plenty of otherwise-capable hardware — including Exynos
 * Samsung devices where AICore is installed but never serves the model. Gemma only needs its
 * weights present on disk, so when it's there it's the dependable path; Nano remains a fallback for
 * devices carrying no model file.
 *
 * Availability is probed once per engine and cached. A tier that starts failing at runtime falls
 * through for that request without being permanently disabled.
 */
object AriaEngineManager {

    private const val TAG = "AriaEngineManager"

    /** Cached availability flags — null means "not yet probed". */
    private var geminiAvailable: Boolean? = null

    private var gemmaEngine: GemmaLocalEngine? = null

    @Volatile
    private var isGemmaReady: Boolean = false

    /** Guards against several callers racing to initialise the model at once. */
    private val gemmaLock = Any()

    @Volatile
    private var gemmaInitAttempted: Boolean = false

    private val geminiEngine: AriaEngine by lazy { GeminiNanoEngine }
    private val localEngine: AriaEngine by lazy { LocalAriaEngine }

    /** Which engine is currently active — exposed for the UI. */
    @Volatile
    var activeEngineName: String = localEngine.engineName()
        private set

    /** True once the on-device Gemma weights are loaded and inference is live. */
    fun isLocalModelReady(): Boolean = isGemmaReady

    /** True while the model file is being located and loaded — distinct from "no model present". */
    @Volatile
    var isLoadingModel: Boolean = false
        private set

    /**
     * Stream a reply from the on-device model, falling back to the non-streaming tiers when it isn't
     * available. [onPartial] receives the answer so far.
     */
    suspend fun chatStreaming(
        userMessage: String,
        chatContext: String = "",
        context: Context? = null,
        onPartial: (String) -> Unit,
    ): Pair<String, String> {
        if (context != null && !gemmaInitAttempted) initializeGemma(context)

        if (isGemmaReady) {
            gemmaEngine?.let { engine ->
                try {
                    val response = engine.chatStreaming(userMessage, chatContext, onPartial)
                    activeEngineName = engine.engineName()
                    return response to engine.engineName()
                } catch (e: Exception) {
                    Log.w(TAG, "Streaming failed, falling through: ${e.message}")
                }
            }
        }

        // No local model: the remaining tiers answer in one shot, so there's nothing to stream.
        return chatWithContext(userMessage, chatContext, context)
    }

    /**
     * Locate the Gemma weights and spin up inference. Safe to call repeatedly — the work happens
     * once. Called lazily on the first chat so startup isn't blocked by a large model load.
     */
    suspend fun initializeGemma(context: Context) {
        synchronized(gemmaLock) {
            if (gemmaInitAttempted) return
            gemmaInitAttempted = true
        }
        isLoadingModel = true
        try {
            val modelPath = ModelManager.findModel(context)
            if (modelPath == null) {
                Log.i(TAG, "No local .task weights on device — falling back to Nano/keyword tiers")
                return
            }
            val engine = GemmaLocalEngine(context)
            if (engine.initialize(modelPath)) {
                gemmaEngine = engine
                isGemmaReady = true
                activeEngineName = engine.engineName()
                Log.i(TAG, "${engine.engineName()} ready — offline inference active")
            } else {
                Log.w(TAG, "Local weights found at $modelPath but inference failed to start")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Gemma initialisation error: ${e.message}", e)
        } finally {
            isLoadingModel = false
        }
    }

    /** Drop the on-device model's conversation history. */
    fun resetConversation() {
        gemmaEngine?.resetConversation()
    }

    /**
     * Send a chat message through the tiered engine stack.
     * Returns a pair of (response text, engine name that handled it).
     *
     * [context] is optional purely for backwards compatibility with older call sites; when supplied
     * it lets Gemma initialise on first use.
     */
    suspend fun chat(userMessage: String, context: Context? = null): Pair<String, String> =
        chatWithContext(userMessage, "", context)

    /** As [chat], but passes surrounding conversation context to engines that can use it. */
    suspend fun chatWithContext(
        userMessage: String,
        chatContext: String = "",
        context: Context? = null,
    ): Pair<String, String> {
        if (context != null && !gemmaInitAttempted) initializeGemma(context)

        // Tier 1: Gemma 3 1B, on-device
        if (isGemmaReady) {
            gemmaEngine?.let { engine ->
                try {
                    val response = engine.chatWithContext(userMessage, chatContext)
                    activeEngineName = engine.engineName()
                    return response to engine.engineName()
                } catch (e: Exception) {
                    Log.w(TAG, "Gemma inference failed, falling through: ${e.message}")
                }
            }
        }

        // Tier 2: Gemini Nano via AICore
        if (geminiAvailable != false) {
            try {
                if (geminiAvailable == null) {
                    geminiAvailable = geminiEngine.isAvailable()
                }
                if (geminiAvailable == true) {
                    val response = geminiEngine.chat(userMessage)
                    activeEngineName = geminiEngine.engineName()
                    return response to geminiEngine.engineName()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Gemini Nano chat failed, falling through: ${e.message}")
                geminiAvailable = false
            }
        }

        // Tier 3: Local keyword engine (always available)
        val response = localEngine.chatWithContext(userMessage, chatContext)
        activeEngineName = localEngine.engineName()
        return response to localEngine.engineName()
    }

    /**
     * Detect SOS in a message through the tiered engine stack.
     */
    suspend fun detectSOS(message: String): Boolean {
        // Tier 1: Gemma
        if (isGemmaReady) {
            gemmaEngine?.let { engine ->
                try {
                    return engine.detectSOS(message)
                } catch (e: Exception) {
                    Log.w(TAG, "Gemma SOS detection failed, falling through: ${e.message}")
                }
            }
        }

        // Tier 2: Gemini Nano
        if (geminiAvailable != false) {
            try {
                if (geminiAvailable == null) {
                    geminiAvailable = geminiEngine.isAvailable()
                }
                if (geminiAvailable == true) {
                    return geminiEngine.detectSOS(message)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Gemini Nano SOS detection failed, falling through: ${e.message}")
                geminiAvailable = false
            }
        }

        // Tier 3: Local keyword engine
        return localEngine.detectSOS(message)
    }

    /** Reset cached availability — useful for settings/retry. */
    fun resetAvailability() {
        geminiAvailable = null
        activeEngineName = if (isGemmaReady) {
            gemmaEngine?.engineName() ?: localEngine.engineName()
        } else {
            localEngine.engineName()
        }
    }

    suspend fun triggerDownload() {
        geminiAvailable = null
        GeminiNanoEngine.triggerDownload()
        // If it succeeds, update active engine
        if (GeminiNanoEngine.isAvailable()) {
            activeEngineName = GeminiNanoEngine.engineName()
        }
    }
}
