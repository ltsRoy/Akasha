package com.MeshLink.android.ai

import android.content.Context
import android.util.Log
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Lightweight, Ultra-Fast Offline AI engine using MediaPipe LLM Inference with Gemma 3 1B.
 * 
 * 100% on-device local execution with zero internet or cloud dependencies.
 * Max token budget set to 60 for near-instant GPU/NPU response generation.
 */
class GemmaLocalEngine(private val context: Context) : AriaEngine {

    companion object {
        private const val TAG = "GemmaLocalEngine"
        private const val ENGINE_NAME = "Gemma 3 1B (Offline AI)"
        
        private const val LITE_PROMPT = "You are Aria, a fast assistant. Answer in 1 short sentence."

        private fun trimTo40Words(text: String): String {
            val trimmed = text.trim()
            val words = trimmed.split(Regex("\\s+"))
            if (words.size <= 40) return trimmed
            return words.take(40).joinToString(" ") + "..."
        }
    }

    private var llmInference: LlmInference? = null
    private var isInitialized = false

    suspend fun initialize(modelPath: String): Boolean = withContext(Dispatchers.IO) {
        try {
            Log.i(TAG, "Initializing Gemma 3 1B model from: $modelPath")
            
            val options = LlmInference.LlmInferenceOptions.builder()
                .setModelPath(modelPath)
                .setMaxTokens(60) // Ultra-fast token budget
                .build()

            llmInference = LlmInference.createFromOptions(context, options)
            isInitialized = true
            Log.i(TAG, "Gemma 3 1B model ready (ultra-fast mode)")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize Gemma 3 1B model: ${e.message}", e)
            isInitialized = false
            false
        }
    }

    override suspend fun chat(userMessage: String): String = withContext(Dispatchers.IO) {
        if (!isInitialized || llmInference == null) {
            return@withContext "⚠️ Gemma 3 1B offline model loading..."
        }

        try {
            val prompt = "$LITE_PROMPT\nUser: $userMessage\nAria:"
            val response = llmInference!!.generateResponse(prompt)
            val rawText = response?.trim() ?: "No response generated."
            trimTo40Words(rawText)
        } catch (e: Exception) {
            Log.e(TAG, "Chat inference failed: ${e.message}", e)
            "⚠️ AI error: ${e.message}"
        }
    }

    override suspend fun chatWithContext(userMessage: String, chatContext: String): String = withContext(Dispatchers.IO) {
        if (!isInitialized || llmInference == null) {
            return@withContext "⚠️ Gemma 3 1B offline model loading..."
        }

        try {
            val prompt = if (chatContext.isNotBlank()) {
                "$LITE_PROMPT\nContext: $chatContext\nUser: $userMessage\nAria:"
            } else {
                "$LITE_PROMPT\nUser: $userMessage\nAria:"
            }

            val response = llmInference!!.generateResponse(prompt)
            val rawText = response?.trim() ?: "No response generated."
            trimTo40Words(rawText)
        } catch (e: Exception) {
            Log.e(TAG, "Contextual chat inference failed: ${e.message}", e)
            "⚠️ AI error: ${e.message}"
        }
    }

    override suspend fun detectSOS(message: String): Boolean = withContext(Dispatchers.IO) {
        if (!isInitialized || llmInference == null) {
            return@withContext message.lowercase().let { msg ->
                msg.contains("help") || msg.contains("sos") || msg.contains("emergency")
            }
        }

        try {
            val prompt = "Is this an SOS? Answer 'true' or 'false': \"$message\""
            val response = llmInference!!.generateResponse(prompt)
            response?.trim()?.lowercase()?.contains("true") == true
        } catch (e: Exception) {
            false
        }
    }

    override fun engineName(): String = ENGINE_NAME

    override suspend fun isAvailable(): Boolean {
        return isInitialized && llmInference != null
    }

    fun release() {
        try {
            llmInference?.close()
            llmInference = null
            isInitialized = false
        } catch (e: Exception) {
            // Ignore
        }
    }
}
