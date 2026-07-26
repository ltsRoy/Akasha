package com.MeshLink.android.features.knowledge.embedder

/**
 * Turns text into a vector comparable with the vectors in the knowledge pack.
 *
 * Two implementations exist for a reason: [OnnxMiniLmEmbedder] is the real thing and the only one
 * whose vectors are comparable with the Ground Station's, while [KeywordFallbackEmbedder] keeps the
 * app useful when the 87 MB model isn't on the device.
 */
interface TextEmbedder {
    /** Human-readable name, surfaced in diagnostics so it's obvious which one is live. */
    val name: String

    /** Dimensionality of the produced vectors. Must be 384 to match the pack. */
    val dimensions: Int

    /**
     * True when this embedder's vectors are semantically meaningful and safe to send to the Ground
     * Station. False for the fallback, which must never be used for remote search — the scores would
     * be nonsense compared against real MiniLM vectors.
     */
    val isSemantic: Boolean

    suspend fun embed(text: String): FloatArray?
}

/**
 * Deterministic non-semantic stand-in used when the ONNX model is absent.
 *
 * It exists so the retrieval path stays exercisable and the UI has something to show, but it is
 * deliberately marked non-semantic: [QueryHandler] falls back to keyword matching over the local
 * pack instead of trusting these vectors, and never sends them over the network.
 */
class KeywordFallbackEmbedder(override val dimensions: Int = 384) : TextEmbedder {
    override val name = "keyword-fallback"
    override val isSemantic = false

    override suspend fun embed(text: String): FloatArray {
        // Hashed bag-of-words. Cheap, stable, and good enough to be a placeholder — but not
        // comparable with MiniLM's space, which is why isSemantic is false.
        val vec = FloatArray(dimensions)
        for (token in text.lowercase().split(Regex("\\W+"))) {
            if (token.isBlank()) continue
            val bucket = (token.hashCode().toLong() and 0x7FFFFFFF).toInt() % dimensions
            vec[bucket] += 1f
        }
        var norm = 0f
        for (v in vec) norm += v * v
        norm = kotlin.math.sqrt(norm)
        if (norm > 0f) for (i in vec.indices) vec[i] /= norm
        return vec
    }
}
