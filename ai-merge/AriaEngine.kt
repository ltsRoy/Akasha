package com.MeshLink.android.ai

import android.content.Context
import android.util.Log

interface AriaEngine {
    suspend fun chat(userMessage: String): String
    suspend fun chatWithContext(userMessage: String, chatContext: String): String {
        return chat(userMessage)
    }
    suspend fun detectSOS(message: String): Boolean
    fun engineName(): String
    suspend fun isAvailable(): Boolean
}

/**
 * Pure On-Device Offline AI Engine Manager.
 * 
 * Uses 100% local Gemma 3 1B model (pre-packaged in APK assets).
 * Completely offline with zero cloud or internet dependencies.
 */
object AriaEngineManager {

    private const val TAG = "AriaEngineManager"

    private var gemmaEngine: GemmaLocalEngine? = null
    private val localKeywordEngine: AriaEngine by lazy { LocalAriaEngine }

    @Volatile
    var activeEngineName: String = localKeywordEngine.engineName()
        private set

    @Volatile
    private var isGemmaReady: Boolean = false

    suspend fun initializeGemma(context: Context) {
        try {
            val modelPath = ModelManager.findModel(context)
            if (modelPath != null) {
                val engine = GemmaLocalEngine(context)
                val success = engine.initialize(modelPath)
                if (success) {
                    gemmaEngine = engine
                    isGemmaReady = true
                    activeEngineName = engine.engineName()
                    Log.i(TAG, "Gemma 3 1B local offline engine ready")
                } else {
                    isGemmaReady = false
                }
            } else {
                isGemmaReady = false
            }
        } catch (e: Exception) {
            isGemmaReady = false
            Log.e(TAG, "Gemma 3 1B initialization error: ${e.message}", e)
        }
    }

    suspend fun chatWithContext(userMessage: String, chatContext: String = "", context: Context? = null): Pair<String, String> {
        // 1. Primary: Gemma 3 1B On-Device Model (100% Offline)
        if (isGemmaReady && gemmaEngine != null) {
            try {
                val response = gemmaEngine!!.chatWithContext(userMessage, chatContext)
                activeEngineName = gemmaEngine!!.engineName()
                return response to activeEngineName
            } catch (e: Exception) {
                Log.w(TAG, "Gemma 3 1B local inference failed: ${e.message}")
            }
        }

        // 2. Fallback: Local Keyword Engine
        val response = localKeywordEngine.chatWithContext(userMessage, chatContext)
        activeEngineName = localKeywordEngine.engineName()
        return response to localKeywordEngine.engineName()
    }

    suspend fun chat(userMessage: String, context: Context? = null): Pair<String, String> {
        return chatWithContext(userMessage, "", context)
    }

    suspend fun detectSOS(message: String, context: Context? = null): Boolean {
        if (isGemmaReady && gemmaEngine != null) {
            try {
                return gemmaEngine!!.detectSOS(message)
            } catch (e: Exception) {
                // Ignore
            }
        }
        return localKeywordEngine.detectSOS(message)
    }

    fun isLocalModelReady(): Boolean = isGemmaReady
}
