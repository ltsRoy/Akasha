package com.MeshLink.android.ai

import android.util.Log
import com.google.ai.edge.aicore.Content
import com.google.ai.edge.aicore.DownloadCallback
import com.google.ai.edge.aicore.DownloadConfig
import com.google.ai.edge.aicore.GenerativeModel
import com.google.ai.edge.aicore.content
import com.google.ai.edge.aicore.generationConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Tier 1 engine: On-device Gemini Nano via Google AI Edge SDK (AICore).
 *
 * Uses the AICore system service which manages model downloads, updates,
 * and hardware acceleration automatically. The manifest metadata tags
 * trigger model download when the app is installed.
 *
 * Requires a compatible device (Pixel 8 Pro+, Samsung S24+, etc.)
 * with Android 14+ and Google Play Services.
 */
object GeminiNanoEngine : AriaEngine {
    private const val TAG = "GeminiNanoEngine"

    @Volatile
    private var available: Boolean? = null

    @Volatile
    private var downloadProgress: Long = 0

    private val downloadCallback = object : DownloadCallback {
        override fun onDownloadProgress(bytesDownloaded: Long) {
            downloadProgress = bytesDownloaded
            Log.d(TAG, "AICore model download progress: $bytesDownloaded bytes")
        }

        override fun onDownloadCompleted() {
            Log.i(TAG, "AICore model download completed!")
            available = null // Reset so next isAvailable() probe succeeds
        }

        override fun onDownloadFailed(failureStatus: String, e: com.google.ai.edge.aicore.GenerativeAIException) {
            Log.w(TAG, "AICore model download failed ($failureStatus): ${e.message}")
        }

        override fun onDownloadStarted(bytesToDownload: Long) {
            Log.i(TAG, "AICore model download started: $bytesToDownload bytes to download")
        }

        override fun onDownloadPending() {
            Log.i(TAG, "AICore model download pending...")
        }
    }

    private val model by lazy {
        GenerativeModel(
            generationConfig = generationConfig {
                temperature = 0.4f
                maxOutputTokens = 500
            },
            downloadConfig = DownloadConfig(downloadCallback)
        )
    }

    private val sosModel by lazy {
        GenerativeModel(
            generationConfig = generationConfig {
                temperature = 0.1f
                topK = 1
                maxOutputTokens = 10
            },
            downloadConfig = DownloadConfig(downloadCallback)
        )
    }

    // System instruction prepended to chat messages since AICore SDK
    // doesn't have a systemInstruction constructor parameter
    private const val SYSTEM_PROMPT = "You are Akasha, an offline disaster response and survival " +
        "assistant embedded in a mesh communication app. Provide concise, actionable, and " +
        "calm advice for survival, first aid, and emergency coordination.\n\nUser: "

    private const val SOS_PROMPT = "You are a disaster response AI. Your ONLY job is to analyze " +
        "the following message and determine if it is a distress signal, call for help, or SOS. " +
        "Respond ONLY with \"TRUE\" or \"FALSE\".\n\nMessage: "

    // Conversation history for multi-turn chat (manual tracking since no Chat API)
    private val conversationHistory = mutableListOf<Content>()

    override fun engineName(): String = "On-Device AI"

    override suspend fun isAvailable(): Boolean {
        if (available != null) return available!!
        return withContext(Dispatchers.IO) {
            try {
                // Probe with a trivial generation to see if AICore + model are ready
                val response = sosModel.generateContent("test")
                response.text // Force evaluation
                available = true
                Log.i(TAG, "Gemini Nano is available on this device via AICore")
                true
            } catch (e: Exception) {
                Log.w(TAG, "Gemini Nano is NOT available: ${e.message}")
                available = false
                false
            }
        }
    }

    /** Reset availability flag — call after model might have been downloaded. */
    fun resetAvailability() {
        available = null
    }

    /** Explicitly trigger download by resetting flag and querying */
    suspend fun triggerDownload() {
        available = null
        withContext(Dispatchers.IO) {
            try {
                Log.i(TAG, "Manually triggering Gemini Nano download...")
                model.generateContent("ping")
                available = true
            } catch (e: Exception) {
                Log.w(TAG, "Download trigger failed: ${e.message}")
            }
        }
    }

    override suspend fun chat(userMessage: String): String = withContext(Dispatchers.IO) {
        // Prepend system prompt to the user message for context
        val fullPrompt = SYSTEM_PROMPT + userMessage

        val inputContent = content {
            text(fullPrompt)
        }

        // Add conversation history context (last 6 turns max to stay within context window)
        val trimmedHistory = conversationHistory.takeLast(6)

        val response = model.generateContent(inputContent)
        val responseText = response.text ?: "I'm having trouble processing that right now."

        // Track conversation for context
        conversationHistory.add(inputContent)
        conversationHistory.add(content { text(responseText) })

        // Keep history manageable
        if (conversationHistory.size > 12) {
            conversationHistory.removeAt(0)
            conversationHistory.removeAt(0)
        }

        responseText
    }

    override suspend fun detectSOS(message: String): Boolean = withContext(Dispatchers.IO) {
        if (message.isBlank()) return@withContext false
        try {
            val fullPrompt = SOS_PROMPT + message

            val response = sosModel.generateContent(fullPrompt)
            val resultText = response.text?.trim()?.uppercase() ?: "FALSE"
            Log.d(TAG, "SOS Analysis for '${message.take(20)}...': $resultText")
            resultText.contains("TRUE")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to analyze SOS with Gemini Nano: ${e.message}")
            throw e // Let AriaEngineManager handle the fallthrough
        }
    }
}

/**
 * Legacy compatibility alias.
 * Existing callers can continue to reference GeminiManager,
 * but new code should use AriaEngineManager.detectSOS() instead.
 */
object GeminiManager {
    private const val TAG = "GeminiManager"

    /**
     * Analyzes a message to determine if it's a distress signal.
     * Now delegates to AriaEngineManager's tiered detection.
     */
    suspend fun analyzeMessageForSOS(messageText: String): Boolean {
        return AriaEngineManager.detectSOS(messageText)
    }
}
