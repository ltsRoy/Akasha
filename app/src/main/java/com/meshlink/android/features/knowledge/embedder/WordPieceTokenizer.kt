package com.MeshLink.android.features.knowledge.embedder

import android.content.Context
import android.util.Log

/**
 * BERT WordPiece tokenizer, reading `assets/akasha/vocab.txt`.
 *
 * Hand-written rather than pulled from a library because the only requirement is matching what
 * all-MiniLM-L6-v2 was trained with, and that is a small, fully specified algorithm: lowercase,
 * strip accents, split on punctuation, then greedily match the longest vocabulary prefix, marking
 * continuations with `##`.
 *
 * Tokenisation must agree with the server exactly. A mismatch here doesn't throw — it silently
 * shifts every embedding, which shows up as retrieval quietly getting worse.
 */
class WordPieceTokenizer private constructor(
    private val vocab: Map<String, Int>,
) {

    companion object {
        private const val TAG = "WordPieceTokenizer"
        private const val VOCAB_ASSET = "akasha/vocab.txt"

        private const val UNK = "[UNK]"
        private const val CLS = "[CLS]"
        private const val SEP = "[SEP]"
        private const val PAD = "[PAD]"

        /** MiniLM's trained maximum; longer inputs are truncated rather than rejected. */
        const val MAX_TOKENS = 256

        /** Longest wordpiece we'll attempt to match, matching reference implementations. */
        private const val MAX_CHARS_PER_WORD = 100

        fun load(context: Context): WordPieceTokenizer? {
            return try {
                val map = HashMap<String, Int>(31_000)
                context.assets.open(VOCAB_ASSET).bufferedReader().useLines { lines ->
                    lines.forEachIndexed { index, token -> map[token.trim()] = index }
                }
                Log.i(TAG, "Loaded vocab: ${map.size} tokens")
                WordPieceTokenizer(map)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load vocab: ${e.message}")
                null
            }
        }
    }

    /** Token ids, attention mask and token-type ids for one input string. */
    data class Encoded(
        val inputIds: LongArray,
        val attentionMask: LongArray,
        val tokenTypeIds: LongArray,
    )

    fun encode(text: String, maxTokens: Int = MAX_TOKENS): Encoded {
        val pieces = ArrayList<String>()
        pieces += CLS

        for (word in basicTokenize(text)) {
            // Reserve room for the closing [SEP].
            if (pieces.size >= maxTokens - 1) break
            pieces += wordPiece(word)
        }

        if (pieces.size > maxTokens - 1) {
            while (pieces.size > maxTokens - 1) pieces.removeAt(pieces.size - 1)
        }
        pieces += SEP

        val padId = vocab[PAD] ?: 0
        val ids = LongArray(maxTokens) { padId.toLong() }
        val mask = LongArray(maxTokens)

        for (i in pieces.indices) {
            ids[i] = (vocab[pieces[i]] ?: vocab[UNK] ?: 100).toLong()
            mask[i] = 1L
        }

        return Encoded(ids, mask, LongArray(maxTokens))
    }

    /**
     * Lowercase, strip accents, and separate punctuation into its own tokens — the "basic"
     * pre-tokenisation step that runs before wordpiece splitting.
     */
    private fun basicTokenize(text: String): List<String> {
        val normalized = java.text.Normalizer.normalize(text.lowercase(), java.text.Normalizer.Form.NFD)
        val out = ArrayList<String>()
        val current = StringBuilder()

        for (ch in normalized) {
            when {
                // Combining marks are dropped, which is what strip_accents does.
                Character.getType(ch) == Character.NON_SPACING_MARK.toInt() -> Unit

                ch.isWhitespace() -> {
                    if (current.isNotEmpty()) { out += current.toString(); current.clear() }
                }

                !ch.isLetterOrDigit() -> {
                    if (current.isNotEmpty()) { out += current.toString(); current.clear() }
                    out += ch.toString()
                }

                else -> current.append(ch)
            }
        }
        if (current.isNotEmpty()) out += current.toString()
        return out
    }

    /** Greedy longest-prefix match; continuations are prefixed with `##`. */
    private fun wordPiece(word: String): List<String> {
        if (word.length > MAX_CHARS_PER_WORD) return listOf(UNK)

        val out = ArrayList<String>()
        var start = 0
        while (start < word.length) {
            var end = word.length
            var match: String? = null

            while (start < end) {
                val candidate = if (start == 0) word.substring(start, end) else "##" + word.substring(start, end)
                if (vocab.containsKey(candidate)) { match = candidate; break }
                end--
            }

            if (match == null) return listOf(UNK) // Unmatchable: whole word becomes [UNK].
            out += match
            start = end
        }
        return out
    }
}
