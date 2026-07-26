package com.MeshLink.android.ai

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * Manages the offline Gemini Nano model lifecycle.
 * 
 * The model file is bundled directly inside the APK assets directory (`assets/gemma3-1b-it-int4.task`).
 * On first launch, ModelManager extracts the bundled model into internal app storage (`context.filesDir`),
 * ensuring the offline AI is 100% pre-packaged and ready out-of-the-box with zero downloads required!
 */
object ModelManager {

    private const val TAG = "ModelManager"
    
    // Model file name 
    const val MODEL_FILENAME = "gemma3-1b-it-int4.task"
    
    // Common locations to search for the model
    private val SEARCH_PATHS = listOf(
        "/data/local/tmp/llm/$MODEL_FILENAME",           // ADB push location
        "/data/local/tmp/$MODEL_FILENAME",                // Alternative ADB location
        "/sdcard/Download/$MODEL_FILENAME",                // Phone Download folder
        "/sdcard/Downloads/$MODEL_FILENAME",               // Phone Downloads folder
        "/storage/emulated/0/Download/$MODEL_FILENAME"     // Android standard Download folder
    )
    
    const val MODEL_SIZE_MB = 554L
    const val MODEL_NAME = "Gemini Nano (Offline)"
    
    sealed class ModelState {
        data object NotFound : ModelState()
        data object Checking : ModelState()
        data class Extracting(val progressPercent: Int) : ModelState()
        data class Found(val path: String) : ModelState()
        data class Error(val message: String) : ModelState()
    }
    
    private val _modelState = MutableStateFlow<ModelState>(ModelState.Checking)
    val modelState: StateFlow<ModelState> = _modelState.asStateFlow()

    /**
     * Scan known locations and bundled assets for the model file.
     * If found in bundled APK assets, automatically extracts it to internal storage.
     * Returns the path if ready, null otherwise.
     */
    suspend fun findModel(context: Context): String? = withContext(Dispatchers.IO) {
        _modelState.value = ModelState.Checking
        
        // 1. Check internal storage first
        val internalModel = File(context.filesDir, MODEL_FILENAME)
        if (internalModel.exists() && internalModel.length() > 500_000_000) {
            Log.i(TAG, "Model found in internal storage: ${internalModel.absolutePath} (${internalModel.length() / 1_000_000} MB)")
            _modelState.value = ModelState.Found(internalModel.absolutePath)
            return@withContext internalModel.absolutePath
        }
        
        // 2. Check bundled APK assets
        try {
            val assetList = context.assets.list("") ?: emptyArray()
            if (assetList.contains(MODEL_FILENAME)) {
                Log.i(TAG, "Bundled model found in APK assets! Extracting to internal storage...")
                return@withContext extractBundledAsset(context, internalModel)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error checking assets: ${e.message}")
        }
        
        // 3. Check external ADB / Download locations
        for (path in SEARCH_PATHS) {
            try {
                val file = File(path)
                if (file.exists() && file.length() > 500_000_000) {
                    Log.i(TAG, "Model found at external path: $path (${file.length() / 1_000_000} MB)")
                    _modelState.value = ModelState.Found(path)
                    return@withContext path
                }
            } catch (e: SecurityException) {
                Log.w(TAG, "Cannot access $path: ${e.message}")
            }
        }
        
        Log.w(TAG, "Model not found in storage or bundled assets")
        _modelState.value = ModelState.NotFound
        null
    }
    
    /**
     * Extract bundled model from APK assets to internal filesDir.
     */
    private suspend fun extractBundledAsset(context: Context, destinationFile: File): String? = withContext(Dispatchers.IO) {
        try {
            _modelState.value = ModelState.Extracting(0)
            
            context.assets.open(MODEL_FILENAME).use { inputStream ->
                val assetSize = inputStream.available().toLong()
                var copiedBytes = 0L
                val buffer = ByteArray(64 * 1024) // 64 KB buffer for fast copying
                
                FileOutputStream(destinationFile).use { outputStream ->
                    var bytesRead: Int
                    while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                        outputStream.write(buffer, 0, bytesRead)
                        copiedBytes += bytesRead
                        if (assetSize > 0) {
                            val progress = ((copiedBytes * 100) / assetSize).toInt()
                            _modelState.value = ModelState.Extracting(progress.coerceIn(0, 99))
                        }
                    }
                }
            }
            
            Log.i(TAG, "Bundled model successfully extracted to: ${destinationFile.absolutePath} (${destinationFile.length() / 1_000_000} MB)")
            _modelState.value = ModelState.Found(destinationFile.absolutePath)
            destinationFile.absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "Asset extraction failed: ${e.message}", e)
            _modelState.value = ModelState.Error("Asset extraction failed: ${e.message}")
            null
        }
    }
    
    fun getModelPathIfReady(): String? {
        return when (val state = _modelState.value) {
            is ModelState.Found -> state.path
            else -> null
        }
    }
}
