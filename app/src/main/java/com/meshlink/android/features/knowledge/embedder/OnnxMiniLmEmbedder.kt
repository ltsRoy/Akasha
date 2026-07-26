package com.MeshLink.android.features.knowledge.embedder

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.LongBuffer

/**
 * all-MiniLM-L6-v2 running on-device through ONNX Runtime.
 *
 * This is required rather than optional: the Ground Station's `/search` accepts a 384-dim vector and
 * never text, so the phone embeds its own queries. Both sides must land in the same vector space, so
 * the pipeline here — WordPiece → transformer → mean-pool over the attention mask → L2 normalise —
 * has to match the reference implementation step for step.
 *
 * The model file is **not bundled**. At 87 MB (fp32) it would nearly triple the APK, and it has to
 * coexist in RAM with a 554 MB LLM, BLE scanning and a foreground service. It is loaded from the
 * app's own storage instead, the same pattern the LLM weights use:
 *
 *     adb push minilm.onnx /sdcard/Android/data/com.akasha.app/files/minilm.onnx
 *
 * When absent, callers fall back to [KeywordFallbackEmbedder] and lose semantic search rather than
 * failing outright.
 */
class OnnxMiniLmEmbedder private constructor(
    private val session: OrtSession,
    private val tokenizer: WordPieceTokenizer,
    private val env: OrtEnvironment,
) : TextEmbedder {

    override val name = "all-MiniLM-L6-v2-ONNX"
    override val dimensions = DIMENSIONS
    override val isSemantic = true

    companion object {
        private const val TAG = "OnnxMiniLmEmbedder"
        private const val DIMENSIONS = 384

        /** Filenames accepted when hunting for the model, in preference order. */
        private val MODEL_NAMES = listOf("minilm.onnx", "all-MiniLM-L6-v2.onnx", "embedder.onnx")

        /** Guards against a truncated or aborted push being loaded as a valid model. */
        private const val MIN_MODEL_BYTES = 10_000_000L

        /** Bundled copy, used when nothing has been pushed to the device. */
        private const val BUNDLED_ASSET_PATH = "akasha/minilm.onnx"
        private const val BUNDLED_ASSET_NAME = "minilm.onnx"

        /**
         * Locate and open the model. Returns null when it isn't present, which is an expected
         * state rather than an error.
         */
        suspend fun create(context: Context): OnnxMiniLmEmbedder? = withContext(Dispatchers.IO) {
            val modelFile = findModel(context) ?: run {
                Log.i(TAG, "No ONNX embedder on device — semantic search unavailable")
                return@withContext null
            }

            val tokenizer = WordPieceTokenizer.load(context) ?: run {
                Log.e(TAG, "Vocab missing; cannot tokenize")
                return@withContext null
            }

            try {
                val env = OrtEnvironment.getEnvironment()
                val options = OrtSession.SessionOptions().apply {
                    // Two threads: enough for a 6-layer model, and leaves headroom for the LLM and
                    // the mesh service, which are running at the same time.
                    setIntraOpNumThreads(2)
                    setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                }
                val session = env.createSession(modelFile.absolutePath, options)
                Log.i(TAG, "Embedder ready: ${modelFile.name} (${modelFile.length() / 1_000_000} MB), inputs=${session.inputNames}")
                OnnxMiniLmEmbedder(session, tokenizer, env)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to open ONNX session: ${e.message}")
                null
            } catch (e: Error) {
                // A large fp32 graph can exhaust memory on a loaded device; degrade instead of dying.
                Log.e(TAG, "Fatal error opening ONNX session: ${e.message}")
                null
            }
        }

        private fun findModel(context: Context): File? {
            val dirs = mutableListOf<File>()
            dirs += context.filesDir
            runCatching { context.getExternalFilesDir(null) }.getOrNull()?.let { dirs += it }
            dirs += File("/data/local/tmp/llm")
            dirs += File("/data/local/tmp")

            for (dir in dirs) {
                if (!dir.isDirectory) continue
                for (candidate in MODEL_NAMES) {
                    val f = File(dir, candidate)
                    if (f.isFile && f.length() >= MIN_MODEL_BYTES) return f
                }
            }

            // Nothing on disk: fall back to the copy bundled in the APK.
            return extractBundledModel(context)
        }

        /**
         * Copy the bundled model out of assets into app storage, once.
         *
         * ONNX Runtime needs a real filesystem path — it cannot open an APK asset stream — so a
         * bundled model has to be materialised before it can be used. The copy is skipped when a
         * complete one already exists, and a partial copy is deleted rather than left behind to be
         * mistaken for a good model on the next launch.
         */
        private fun extractBundledModel(context: Context): File? {
            val destination = File(context.filesDir, BUNDLED_ASSET_NAME)
            if (destination.isFile && destination.length() >= MIN_MODEL_BYTES) return destination

            return try {
                Log.i(TAG, "Extracting bundled embedder from assets (first launch)")
                context.assets.open(BUNDLED_ASSET_PATH).use { input ->
                    destination.outputStream().use { output -> input.copyTo(output, 1 shl 16) }
                }
                if (destination.length() < MIN_MODEL_BYTES) {
                    Log.e(TAG, "Extracted embedder is too small (${destination.length()} bytes); discarding")
                    destination.delete()
                    null
                } else {
                    Log.i(TAG, "Embedder extracted: ${destination.length() / 1_000_000} MB")
                    destination
                }
            } catch (e: Exception) {
                Log.w(TAG, "No bundled embedder available: ${e.message}")
                runCatching { destination.delete() }
                null
            } catch (e: Error) {
                Log.e(TAG, "Fatal error extracting bundled embedder: ${e.message}")
                runCatching { destination.delete() }
                null
            }
        }
    }

    override suspend fun embed(text: String): FloatArray? = withContext(Dispatchers.Default) {
        if (text.isBlank()) return@withContext null

        try {
            val encoded = tokenizer.encode(text)
            val seqLen = encoded.inputIds.size.toLong()
            val shape = longArrayOf(1, seqLen)

            val inputs = HashMap<String, OnnxTensor>()
            try {
                // Graphs vary in which inputs they declare, so only feed what this one wants.
                val declared = session.inputNames
                if (declared.contains("input_ids")) {
                    inputs["input_ids"] = OnnxTensor.createTensor(env, LongBuffer.wrap(encoded.inputIds), shape)
                }
                if (declared.contains("attention_mask")) {
                    inputs["attention_mask"] = OnnxTensor.createTensor(env, LongBuffer.wrap(encoded.attentionMask), shape)
                }
                if (declared.contains("token_type_ids")) {
                    inputs["token_type_ids"] = OnnxTensor.createTensor(env, LongBuffer.wrap(encoded.tokenTypeIds), shape)
                }

                session.run(inputs).use { output ->
                    @Suppress("UNCHECKED_CAST")
                    val hidden = output[0].value as Array<Array<FloatArray>>
                    meanPoolAndNormalize(hidden[0], encoded.attentionMask)
                }
            } finally {
                inputs.values.forEach { runCatching { it.close() } }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Embedding failed: ${e.message}")
            null
        } catch (e: Error) {
            Log.e(TAG, "Fatal embedding error: ${e.message}")
            null
        }
    }

    /**
     * Mean-pool token embeddings over real tokens only, then L2 normalise.
     *
     * Masking matters: averaging across padding drags every vector toward the padding embedding, and
     * because the result is still a plausible-looking unit vector the damage is invisible except as
     * degraded scores. Normalising lets cosine similarity be a plain dot product, which is what the
     * pack's vectors and Actian's Cosine distance both assume.
     */
    private fun meanPoolAndNormalize(tokens: Array<FloatArray>, mask: LongArray): FloatArray {
        val out = FloatArray(DIMENSIONS)
        var counted = 0

        for (i in tokens.indices) {
            if (i >= mask.size || mask[i] == 0L) continue
            val token = tokens[i]
            for (d in 0 until minOf(DIMENSIONS, token.size)) out[d] += token[d]
            counted++
        }

        if (counted == 0) return out
        for (d in out.indices) out[d] /= counted

        var norm = 0f
        for (v in out) norm += v * v
        norm = kotlin.math.sqrt(norm)
        if (norm > 0f) for (d in out.indices) out[d] /= norm

        return out
    }

    fun close() {
        runCatching { session.close() }
    }
}
