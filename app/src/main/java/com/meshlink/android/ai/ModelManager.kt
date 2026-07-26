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
 * Locates the on-device LLM weights used for fully offline inference.
 *
 * Any MediaPipe/LiteRT `.task` bundle is accepted. Gemma is preferred when present, but its weights
 * are licence-gated, so the app must work with whatever ungated substitute the user supplies rather
 * than hard-failing.
 *
 * Search order: app-private storage (internal, then the app's external files dir), bundled APK
 * assets, then shared ADB/Download locations. The model is deliberately *not* shipped inside the
 * APK — a half-gigabyte asset bloats the build and blows up Gradle's heap — so the normal path is
 * `adb push <model>.task` into the app's external files dir, which needs no runtime permission.
 */
object ModelManager {

    private const val TAG = "ModelManager"
    
    // Preferred model, checked before any generic scan.
    const val MODEL_FILENAME = "gemma3-1b-it-int4.task"

    /**
     * Any LiteRT `.task` bundle is accepted, not just Gemma.
     *
     * Google's Gemma weights are licence-gated, so a build can't assume they're present. MediaPipe's
     * LlmInference loads any `.task` bundle through the same API, which means an ungated model
     * (Qwen2.5-0.5B, SmolLM, etc.) is a drop-in substitute. Matching on extension instead of one
     * hardcoded filename means whatever the user actually managed to obtain will be picked up.
     */
    private const val MODEL_EXTENSION = ".task"

    /**
     * Floor for treating a `.task` file as a real model rather than a truncated or aborted download.
     * Set below the smallest usable bundle (~500 MB for a 0.5B q8) with headroom, so a future
     * smaller model still qualifies while a half-written file does not.
     */
    private const val MIN_MODEL_BYTES = 100_000_000L

    // Directories to scan for a .task bundle.
    //
    // Note on shared storage: on Android 13+ an app can't read a .task file from /sdcard/Download
    // without broad storage permission, because scoped storage only grants access to media types.
    // The app's own external files directory is checked first (see findModel) since it needs no
    // permission at all and can still be written by `adb push` — which is how we avoid bundling a
    // half-gigabyte asset into the APK.
    private val SEARCH_DIRS = listOf(
        "/data/local/tmp/llm",        // ADB push location
        "/data/local/tmp",            // Alternative ADB location
        "/sdcard/Download",           // Phone Download folder
        "/sdcard/Downloads",          // Phone Downloads folder
        "/storage/emulated/0/Download" // Android standard Download folder
    )

    /**
     * Human-readable name of whatever model was actually found, derived from its filename.
     * Null until [findModel] locates something. The UI reads this so the badge reflects reality
     * instead of claiming Gemma when a substitute is loaded.
     */
    @Volatile
    var detectedModelName: String? = null
        private set
    
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
        
        // 1. App-private dirs first: no permissions needed, and adb-pushable.
        val appDirs = mutableListOf<File>()
        appDirs += context.filesDir
        try {
            context.getExternalFilesDir(null)?.let { appDirs += it }
        } catch (e: Exception) {
            Log.w(TAG, "Could not resolve external files dir: ${e.message}")
        }

        for (dir in appDirs) {
            resolveModelIn(dir)?.let { return@withContext accept(it, "app storage") }
        }

        // 2. Check bundled APK assets
        try {
            val assetList = context.assets.list("") ?: emptyArray()
            if (assetList.contains(MODEL_FILENAME)) {
                Log.i(TAG, "Bundled model found in APK assets! Extracting to internal storage...")
                return@withContext extractBundledAsset(context, File(context.filesDir, MODEL_FILENAME))
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error checking assets: ${e.message}")
        }
        
        // 3. Check external ADB / Download locations
        for (path in SEARCH_DIRS) {
            try {
                resolveModelIn(File(path))?.let { return@withContext accept(it, "external path") }
            } catch (e: SecurityException) {
                Log.w(TAG, "Cannot access $path: ${e.message}")
            }
        }

        Log.w(TAG, "No .task model found in storage or bundled assets")
        detectedModelName = null
        _modelState.value = ModelState.NotFound
        null
    }

    /**
     * Find a usable model inside [dir]: the preferred Gemma filename if present, otherwise the
     * largest `.task` bundle. Largest wins because a full model beats a partial download when both
     * happen to be sitting in the same folder.
     */
    private fun resolveModelIn(dir: File?): File? {
        if (dir == null || !dir.isDirectory) return null

        val preferred = File(dir, MODEL_FILENAME)
        if (preferred.isFile && preferred.length() >= MIN_MODEL_BYTES) return preferred

        return dir.listFiles()
            ?.filter {
                it.isFile &&
                    it.name.endsWith(MODEL_EXTENSION, ignoreCase = true) &&
                    it.length() >= MIN_MODEL_BYTES
            }
            ?.maxByOrNull { it.length() }
    }

    /** Record a located model as the active one and publish it. */
    private fun accept(file: File, origin: String): String {
        detectedModelName = prettyModelName(file.name)
        Log.i(
            TAG,
            "Model found in $origin: ${file.absolutePath} " +
                "(${file.length() / 1_000_000} MB) -> $detectedModelName",
        )
        _modelState.value = ModelState.Found(file.absolutePath)
        return file.absolutePath
    }

    /**
     * Turn a bundle filename into something presentable, e.g.
     * `Qwen2.5-0.5B-Instruct_multi-prefill-seq_q8_ekv1280.task` -> `Qwen2.5-0.5B-Instruct`.
     */
    private fun prettyModelName(filename: String): String {
        val base = filename.removeSuffix(MODEL_EXTENSION).substringBefore('_')
        return base.ifBlank { filename }
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
            accept(destinationFile, "bundled assets")
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
