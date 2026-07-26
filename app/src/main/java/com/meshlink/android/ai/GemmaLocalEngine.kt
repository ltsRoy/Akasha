package com.MeshLink.android.ai

import android.content.Context
import android.util.Log
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mediapipe.tasks.genai.llminference.LlmInferenceSession
import com.google.mediapipe.tasks.genai.llminference.PromptTemplates
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Fully offline AI engine backed by MediaPipe LLM Inference.
 *
 * Runs entirely on-device with no internet or cloud dependency, which is the point — the mesh is
 * used precisely when there is no network.
 *
 * Three things here matter for answer quality, and all three were wrong before:
 *
 *  1. **Sampling lives on the session, not the engine.** `LlmInferenceOptions` has no temperature
 *     or topK setter; those are on `LlmInferenceSessionOptions`. Calling `generateResponse()`
 *     directly on `LlmInference` therefore runs at MediaPipe's defaults (temperature 0.8), which on
 *     a 1B model reads as rambling and unreliable. Emergency answers want near-greedy decoding.
 *
 *  2. **Role markers are the library's job.** `PromptTemplates` applies the chat template, so
 *     hand-writing `<start_of_turn>` into the prompt string double-templates it and confuses the
 *     model. The markers are declared once as templates instead.
 *
 *  3. **Conversation state belongs to the session.** Reusing one session preserves the KV cache, so
 *     follow-up questions ("and then?") actually resolve, instead of each turn being answered in
 *     isolation with no referent.
 */
class GemmaLocalEngine(private val context: Context) : AriaEngine {

    companion object {
        private const val TAG = "GemmaLocalEngine"
        private const val FALLBACK_ENGINE_NAME = "Local LLM (Offline AI)"

        /**
         * Total token budget — prompt *and* completion share this in MediaPipe; it is not an
         * output-only cap. Sized to sit under the 1280-entry KV cache the bundles are exported with.
         */
        private const val MAX_TOKENS = 1024

        /**
         * Recycle the session once the running conversation approaches the budget, so a long chat
         * degrades into "forgets older turns" rather than failing outright.
         */
        private const val CONTEXT_RECYCLE_TOKENS = 700

        /**
         * Low enough to stay factual, high enough to escape repetition loops.
         *
         * The default 0.8 is tuned for creative writing and produced rambling. But 0.25 turned out to
         * be too far the other way: near-greedy decoding on a 1B model gets stuck restating the same
         * sentence, because the highest-probability continuation after a sentence is often that
         * sentence again. 0.45 keeps answers grounded while giving the sampler enough room to move on.
         */
        private const val TEMPERATURE = 0.45f
        private const val TOP_K = 40
        private const val TOP_P = 0.95f

        /** Fixed seed so the same question gives the same answer — important when demoing. */
        private const val RANDOM_SEED = 42

        private const val INFERENCE_TIMEOUT_MS = 45_000L
        private const val SOS_TIMEOUT_MS = 12_000L

        /**
         * Consecutive streaming updates with no new text before generation is abandoned.
         *
         * Tolerant enough to survive a few duplicate tokens mid-sentence, tight enough that a real
         * loop is cut off in about a second instead of running to the full token budget.
         */
        private const val STALL_UPDATES_BEFORE_CANCEL = 12

        /**
         * Instruction prepended to the first turn.
         *
         * Gemma has no separate system role — its chat template folds system text into the opening
         * user turn — so this is injected as part of the first message rather than via a system
         * template.
         */
        private const val SYSTEM_PROMPT =
            "You are Akasha, an offline emergency assistant running on a phone connected to a " +
                "Bluetooth mesh network, used by people with no internet or cell signal. " +
                "Answer in plain English. Be concrete and practical: give numbered steps when " +
                "explaining a procedure. Keep answers under 80 words. Never invent phone numbers " +
                "or claim you can contact emergency services. If you do not know, say so plainly. " +
                "Start immediately with the first action — no greeting, no restating the question, " +
                "and no markdown formatting."

        private const val MAX_WORDS = 110

        /**
         * Reference facts injected when a question matches a topic.
         *
         * A 1B model knows first aid only approximately, and approximate first aid is worse than
         * none. Rather than trusting it to recall specifics, the correct numbers are supplied in the
         * prompt and the model is left to do what small models are actually good at: phrasing them
         * clearly for the situation asked about. This is retrieval, not training, and it needs no
         * network — the facts ship with the app.
         *
         * Sources are standard first-aid/WHO guidance; deliberately kept to figures that are stable
         * and non-controversial.
         */
        private val GROUNDING: List<Pair<List<String>, String>> = listOf(
            listOf(
                "cpr", "breathing", "no pulse", "cardiac", "heart stopped",
                "collapsed", "unconscious", "unresponsive", "passed out",
            ) to
                "CPR facts: compress the centre of the chest 5-6 cm deep, 100-120 compressions per " +
                "minute, 30 compressions to 2 rescue breaths. Do not stop until help arrives or the " +
                "person breathes. Hands-only compressions are acceptable if untrained in breaths.",

            listOf("bleed", "cut", "wound", "blood", "laceration", "gash") to
                "Bleeding facts: apply firm direct pressure with a clean cloth for a full 10 minutes " +
                "without lifting to check. Elevate the limb above heart level. A tourniquet is a last " +
                "resort for life-threatening limb bleeding only, placed 5-7 cm above the wound, never " +
                "on a joint, and the time applied must be written down.",

            listOf("burn", "scald", "fire injury") to
                "Burn facts: cool with clean running water for 20 minutes. Never use ice, butter or " +
                "toothpaste. Do not pop blisters. Remove rings and tight items before swelling. Cover " +
                "loosely with cling film or a clean non-fluffy cloth.",

            listOf("water", "purify", "drink", "thirsty", "dehydrat") to
                "Water facts: a rolling boil for 1 minute makes water microbiologically safe (3 " +
                "minutes above 2000 m altitude). Boiling does not remove chemical contamination. " +
                "Household bleach at 2 drops per litre, left 30 minutes, is an alternative. Cloth " +
                "filtering removes sediment but not pathogens.",

            listOf("hypothermia", "cold", "freezing", "shiver") to
                "Hypothermia facts: get the person dry, insulate from the ground, cover the head, and " +
                "warm the core before the limbs. Give warm sweet drinks only if fully conscious. Never " +
                "give alcohol, and do not rub frostbitten skin.",

            listOf("snake", "snakebite", "bitten") to
                "Snakebite facts: keep the person still and the bitten limb below heart level. Do not " +
                "cut, suck, apply a tourniquet, or use ice. Remove rings and watches. Note the time of " +
                "the bite. Antivenom at a hospital is the only real treatment.",

            listOf("fracture", "broken", "splint", "sprain") to
                "Fracture facts: immobilise the joint above and below the injury. Do not attempt to " +
                "straighten a deformed limb. Pad a rigid splint against the limb. Check fingers or " +
                "toes stay warm and pink — if they go pale or numb, the splint is too tight.",

            listOf("shelter", "exposure", "shade", "night") to
                "Shelter facts: insulation from the ground matters more than cover overhead, since " +
                "conduction drains heat fastest. Keep the shelter small enough for body heat to warm " +
                "it. Avoid dry riverbeds, ridgelines and the base of loose slopes.",

            listOf("signal", "rescue", "found", "attract attention", "whistle") to
                "Signalling facts: three of anything means distress — three whistle blasts, three " +
                "fires, three flashes. A whistle carries far further than a shout and costs almost no " +
                "energy. Ground signals should be large, geometric, and high-contrast against terrain.",
        )

        /**
         * Match a question to reference facts, if any topic applies.
         *
         * Apostrophes are stripped before matching so "isn't breathing", "isn’t breathing" and
         * "isnt breathing" all resolve to the same topic — people under stress don't punctuate.
         */
        private fun groundingFor(question: String): String? {
            val q = question.lowercase().replace(Regex("""['’]"""), "")
            return GROUNDING.firstOrNull { (keys, _) -> keys.any { q.contains(it) } }?.second
        }

        /** Template markers that must never reach the UI if a model echoes them. */
        private val TEMPLATE_ARTIFACTS = listOf(
            "<|im_end|>", "<|im_start|>", "<|endoftext|>",
            "<end_of_turn>", "<start_of_turn>", "<eos>", "<bos>",
        )

        /** Below this length a repeated sentence is likely legitimate emphasis, not a decode loop. */
        private const val MIN_DEDUP_LENGTH = 25

        /**
         * Remove sentences the model has already produced.
         *
         * Small models fall into degenerate loops, restating the same sentence until the token budget
         * runs out — a real answer here repeated one address five times. MediaPipe's session options
         * expose no repetition penalty (only temperature, topK, topP and seed), so this cannot be
         * fixed at the sampler and has to be caught in the output.
         *
         * Matching is on a normalised form so trivial punctuation or spacing differences don't let a
         * duplicate through, and only longer sentences are considered, since a short phrase may
         * legitimately recur.
         */
        private fun dropRepeatedSentences(text: String): String {
            // Split after ., ! or ? while keeping the delimiter attached to the sentence.
            val parts = Regex("(?<=[.!?])\\s+").split(text)
            if (parts.size < 2) return text

            val seen = HashSet<String>()
            val kept = ArrayList<String>(parts.size)
            var dropped = 0

            for (part in parts) {
                val sentence = part.trim()
                if (sentence.isEmpty()) continue

                if (sentence.length < MIN_DEDUP_LENGTH) {
                    kept += sentence
                    continue
                }

                val key = sentence.lowercase().replace(Regex("[^a-z0-9 ]"), "").replace(Regex("\\s+"), " ")
                if (seen.add(key)) kept += sentence else dropped++
            }

            // Intentionally not logged: this runs on every streaming update, and logging here
            // produced hundreds of lines per answer that drowned out the rest of the app's output.
            return kept.joinToString(" ")
        }

        private fun trimToLimit(text: String): String {
            val trimmed = text.trim()
            val words = trimmed.split(Regex("\\s+"))
            if (words.size <= MAX_WORDS) return trimmed

            val clipped = words.take(MAX_WORDS).joinToString(" ")
            val lastSentenceEnd = clipped.lastIndexOfAny(charArrayOf('.', '!', '?'))
            return if (lastSentenceEnd > clipped.length / 2) {
                clipped.substring(0, lastSentenceEnd + 1)
            } else {
                "$clipped..."
            }
        }

        /**
         * Conversational filler the model opens with despite being told not to.
         *
         * Instruction-tuned models are trained to be chatty, and a "no preamble" instruction only
         * partly suppresses it. Stripping it after the fact is more reliable than prompting harder,
         * and it matters here: the first line should be the first action, not pleasantries.
         */
        private val PREAMBLE_PATTERN = Regex(
            // Optional greeting, then an optional "let's ..." / "here's what you need" opener.
            // Each part is independent so a bare "Let's focus on your arm." is stripped too.
            // Apostrophes are matched as a class: the model emits the typographic ’, not ASCII ',
            // so "let'?s" silently failed to match "let’s".
            """^\s*((okay|ok|alright|sure|right|got it|understood|hi|hello)\b[^.!?\n]*[.!?]\s*)?""" +
                """(let['’]?s\s+\w+[^.!?\n]*[.!?]\s*)?""" +
                """(here['’]?s\s+what\s+(you\s+need|to\s+do)[^:.!?\n]*[:.]\s*)?""",
            RegexOption.IGNORE_CASE,
        )

        /**
         * Strip template tokens, leaked role labels, markdown emphasis and opening filler.
         *
         * Markdown is removed rather than rendered because the bubble is plain Text — leaving it in
         * showed literal `**` around every step.
         */
        private fun clean(raw: String): String {
            var out = raw
            for (artifact in TEMPLATE_ARTIFACTS) out = out.replace(artifact, "")

            out = out
                .removePrefix("assistant")
                .removePrefix("model")
                .trim()
                .trim(':')
                .trim()

            // Markdown emphasis and headings: the UI renders plain text.
            out = out
                .replace(Regex("""\*\*\*(.+?)\*\*\*""", RegexOption.DOT_MATCHES_ALL), "$1")
                .replace(Regex("""\*\*(.+?)\*\*""", RegexOption.DOT_MATCHES_ALL), "$1")
                .replace(Regex("""(?<!\*)\*(?!\s)(.+?)(?<!\s)\*(?!\*)""", RegexOption.DOT_MATCHES_ALL), "$1")
                .replace(Regex("""^#{1,6}\s*""", RegexOption.MULTILINE), "")

            out = PREAMBLE_PATTERN.replace(out, "")

            out = dropRepeatedSentences(out)

            // Put each numbered step on its own line so steps are scannable under stress.
            out = out.replace(Regex("""\s+(\d{1,2})\.\s+"""), "\n$1. ")

            return out.trim()
        }
    }

    /** Chat templates differ per model family; the wrong one degrades output badly. */
    private enum class Family { GEMMA, QWEN, GENERIC }

    private var llmInference: LlmInference? = null
    private var session: LlmInferenceSession? = null
    private var isInitialized = false
    private var modelFamily: Family = Family.GENERIC

    /** Approximate tokens consumed by the live session, used to decide when to recycle it. */
    private var sessionTokens = 0

    /** Whether the system instruction still needs injecting into the current session. */
    private var needsSystemPrompt = true

    private val lock = Any()

    suspend fun initialize(modelPath: String): Boolean = withContext(Dispatchers.IO) {
        try {
            modelFamily = detectFamily(modelPath)
            Log.i(TAG, "Initializing offline model (family=$modelFamily) from: $modelPath")

            val options = LlmInference.LlmInferenceOptions.builder()
                .setModelPath(modelPath)
                .setMaxTokens(MAX_TOKENS)
                // Must be >= the session's topK or session creation is rejected.
                .setMaxTopK(TOP_K)
                .build()

            llmInference = LlmInference.createFromOptions(context, options)
            isInitialized = true
            Log.i(TAG, "Offline model ready (maxTokens=$MAX_TOKENS, temp=$TEMPERATURE, topK=$TOP_K)")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize offline model: ${e.message}", e)
            isInitialized = false
            false
        } catch (e: Error) {
            // OutOfMemoryError is realistic when loading a large model on a loaded device. Report
            // failure so the tiered stack drops to keywords instead of taking the app down.
            Log.e(TAG, "Fatal error initializing offline model: ${e.message}", e)
            isInitialized = false
            false
        }
    }

    private fun detectFamily(modelPath: String): Family {
        val name = modelPath.substringAfterLast('/').lowercase()
        return when {
            name.contains("gemma") -> Family.GEMMA
            name.contains("qwen") -> Family.QWEN
            else -> Family.GENERIC
        }
    }

    /**
     * Role markers for the loaded model, declared as templates so MediaPipe applies them exactly
     * once. Instruct models are trained with these specific delimiters; without them the model
     * continues the transcript instead of answering it.
     */
    private fun promptTemplates(): PromptTemplates? = when (modelFamily) {
        Family.GEMMA -> PromptTemplates.builder()
            .setUserPrefix("<start_of_turn>user\n")
            .setUserSuffix("<end_of_turn>\n")
            .setModelPrefix("<start_of_turn>model\n")
            .setModelSuffix("<end_of_turn>\n")
            .build()

        Family.QWEN -> PromptTemplates.builder()
            .setUserPrefix("<|im_start|>user\n")
            .setUserSuffix("<|im_end|>\n")
            .setModelPrefix("<|im_start|>assistant\n")
            .setModelSuffix("<|im_end|>\n")
            .build()

        // Unknown bundle: let the model's own baked-in template handle it.
        Family.GENERIC -> null
    }

    /** Build a fresh session, discarding any conversation history. */
    private fun newSession(): LlmInferenceSession {
        val engine = llmInference ?: error("Model not initialized")
        val builder = LlmInferenceSession.LlmInferenceSessionOptions.builder()
            .setTopK(TOP_K)
            .setTopP(TOP_P)
            .setTemperature(TEMPERATURE)
            .setRandomSeed(RANDOM_SEED)

        promptTemplates()?.let { builder.setPromptTemplates(it) }

        val created = LlmInferenceSession.createFromOptions(engine, builder.build())
        sessionTokens = 0
        needsSystemPrompt = true
        return created
    }

    /** Current session, recreated when the conversation has outgrown the token budget. */
    private fun activeSession(): LlmInferenceSession {
        val existing = session
        if (existing != null && sessionTokens < CONTEXT_RECYCLE_TOKENS) return existing

        if (existing != null) {
            Log.i(TAG, "Recycling session at ~$sessionTokens tokens to free context")
            runCatching { existing.close() }
        }
        return newSession().also { session = it }
    }

    /** Drop conversation history — wired to the UI's clear-chat action. */
    fun resetConversation() {
        synchronized(lock) {
            runCatching { session?.close() }
            session = null
            sessionTokens = 0
            needsSystemPrompt = true
        }
    }

    override suspend fun chat(userMessage: String): String = chatWithContext(userMessage, "")

    override suspend fun chatWithContext(userMessage: String, chatContext: String): String {
        return try {
            withContext(Dispatchers.IO) {
                if (!isInitialized || llmInference == null) {
                    return@withContext "Offline model is still loading. Give it a moment."
                }

                val reply = withTimeoutOrNull(INFERENCE_TIMEOUT_MS) {
                    synchronized(lock) { generate(userMessage, chatContext) }
                }

                if (reply == null) {
                    Log.w(TAG, "Inference timed out after ${INFERENCE_TIMEOUT_MS}ms")
                    return@withContext "That took too long to answer. Try a shorter question."
                }

                val cleaned = clean(reply)
                if (cleaned.isBlank()) {
                    "I couldn't answer that. Try rephrasing."
                } else {
                    trimToLimit(cleaned)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Chat inference failed: ${e.message}", e)
            // A failed turn can leave the session's KV cache inconsistent; start clean next time.
            resetConversation()
            "The AI had trouble answering. Try a simpler question."
        } catch (e: Error) {
            Log.e(TAG, "Fatal inference error: ${e.message}", e)
            resetConversation()
            "The AI ran out of memory. Clear the chat and try again."
        }
    }

    /**
     * Assemble the text submitted for one turn: system instruction (first turn only), any reference
     * facts for the topic, optional mesh context, then the question itself.
     */
    private fun buildChunk(userMessage: String, chatContext: String): String = buildString {
        if (needsSystemPrompt) {
            append(SYSTEM_PROMPT)
            append("\n\n")
        }
        groundingFor(userMessage)?.let { facts ->
            // Given as authoritative reference so the model rephrases rather than recalls.
            append("Use these verified reference facts in your answer:\n")
            append(facts)
            append("\n\n")
        }
        if (chatContext.isNotBlank()) {
            append("Context from nearby mesh messages:\n")
            append(chatContext.trim())
            append("\n\n")
        }
        append(userMessage.trim())
    }

    /**
     * Run one turn on the live session.
     *
     * The session keeps prior turns in its KV cache, so only the new message is submitted — no
     * re-sending of the transcript, and follow-up questions resolve against what was already said.
     */
    private fun generate(userMessage: String, chatContext: String): String {
        val active = activeSession()

        val chunk = buildChunk(userMessage, chatContext)
        needsSystemPrompt = false

        sessionTokens += runCatching { active.sizeInTokens(chunk) }.getOrDefault(chunk.length / 4)
        Log.d(TAG, "Submitting turn (~$sessionTokens session tokens): ${userMessage.take(60)}")

        active.addQueryChunk(chunk)
        val raw = active.generateResponse().orEmpty()

        sessionTokens += runCatching { active.sizeInTokens(raw) }.getOrDefault(raw.length / 4)
        Log.d(TAG, "Raw model output: ${raw.take(200)}")
        return raw
    }

    /**
     * Stream a reply, emitting the answer so far as tokens arrive.
     *
     * On-device decode of a 1B model takes tens of seconds for a full answer. Waiting in silence
     * reads as "broken"; watching words appear reads as "working", and the first line is usually the
     * actionable one anyway. [onPartial] receives the cumulative text, always on the caller's
     * coroutine context via the suspending wrapper below.
     *
     * Returns the final cleaned answer.
     */
    suspend fun chatStreaming(
        userMessage: String,
        chatContext: String = "",
        onPartial: (String) -> Unit,
    ): String = withContext(Dispatchers.IO) {
        if (!isInitialized || llmInference == null) {
            return@withContext "Offline model is still loading. Give it a moment."
        }

        try {
            val result = withTimeoutOrNull(INFERENCE_TIMEOUT_MS) {
                suspendCancellableCoroutine { cont ->
                    val accumulated = StringBuilder()
                    // Repetition watchdog. Once cleaning removes everything new, the model is only
                    // restating itself, so there is nothing left to wait for.
                    var lastCleanedLength = 0
                    var stalledUpdates = 0
                    try {
                        val active: LlmInferenceSession
                        val chunk: String
                        synchronized(lock) {
                            active = activeSession()
                            chunk = buildChunk(userMessage, chatContext)
                            needsSystemPrompt = false
                            sessionTokens += runCatching { active.sizeInTokens(chunk) }
                                .getOrDefault(chunk.length / 4)
                            active.addQueryChunk(chunk)
                        }

                        active.generateResponseAsync { partial, done ->
                            // MediaPipe delivers deltas, not the whole string; accumulate them.
                            if (partial != null) {
                                accumulated.append(partial)
                                val cleaned = clean(accumulated.toString())
                                onPartial(cleaned)

                                // Growing output means progress; a flat length across many updates
                                // means every new token is duplicate text being stripped again.
                                if (cleaned.length > lastCleanedLength) {
                                    lastCleanedLength = cleaned.length
                                    stalledUpdates = 0
                                } else if (++stalledUpdates >= STALL_UPDATES_BEFORE_CANCEL) {
                                    Log.d(TAG, "Output stalled on repetition; cancelling generation")
                                    runCatching { active.cancelGenerateResponseAsync() }
                                    if (cont.isActive) cont.resume(accumulated.toString())
                                    return@generateResponseAsync
                                }
                            }
                            if (done && cont.isActive) {
                                val raw = accumulated.toString()
                                synchronized(lock) {
                                    sessionTokens += runCatching { active.sizeInTokens(raw) }
                                        .getOrDefault(raw.length / 4)
                                }
                                cont.resume(raw)
                            }
                        }

                        cont.invokeOnCancellation {
                            runCatching { active.cancelGenerateResponseAsync() }
                        }
                    } catch (e: Throwable) {
                        if (cont.isActive) cont.resumeWithException(e)
                    }
                }
            }

            if (result == null) {
                Log.w(TAG, "Streaming inference timed out after ${INFERENCE_TIMEOUT_MS}ms")
                return@withContext "That took too long to answer. Try a shorter question."
            }

            val cleaned = clean(result)
            if (cleaned.isBlank()) "I couldn't answer that. Try rephrasing." else trimToLimit(cleaned)
        } catch (e: Exception) {
            Log.e(TAG, "Streaming inference failed: ${e.message}", e)
            resetConversation()
            "The AI had trouble answering. Try a simpler question."
        } catch (e: Error) {
            Log.e(TAG, "Fatal streaming error: ${e.message}", e)
            resetConversation()
            "The AI ran out of memory. Clear the chat and try again."
        }
    }

    override suspend fun detectSOS(message: String): Boolean = withContext(Dispatchers.IO) {
        val keywordMatch = message.lowercase().let { msg ->
            msg.contains("help") || msg.contains("sos") || msg.contains("emergency")
        }

        if (!isInitialized || llmInference == null) return@withContext keywordMatch

        try {
            // Classification runs on a throwaway session so it can't pollute the chat's history
            // with these internal prompts.
            val raw = withTimeoutOrNull(SOS_TIMEOUT_MS) {
                synchronized(lock) {
                    val probe = newSession()
                    try {
                        probe.addQueryChunk(
                            "Does this message describe a person in danger or needing urgent help? " +
                                "Reply with only the word true or false.\nMessage: \"$message\""
                        )
                        probe.generateResponse().orEmpty()
                    } finally {
                        runCatching { probe.close() }
                        // The probe replaced nothing, but reset bookkeeping for the chat session.
                        sessionTokens = 0
                        needsSystemPrompt = true
                    }
                }
            }

            val response = clean(raw.orEmpty()).lowercase()
            when {
                response.contains("true") -> true
                response.contains("false") -> false
                // Ambiguous or timed out — fall back to keywords rather than miss a real SOS.
                else -> keywordMatch
            }
        } catch (e: Exception) {
            Log.w(TAG, "SOS classification failed, using keywords: ${e.message}")
            keywordMatch
        } catch (e: Error) {
            Log.e(TAG, "Fatal SOS classification error, using keywords: ${e.message}")
            keywordMatch
        }
    }

    // Named after whatever bundle actually loaded, so the badge can't claim Gemma when an ungated
    // substitute is running.
    override fun engineName(): String =
        ModelManager.detectedModelName?.let { "$it (Offline AI)" } ?: FALLBACK_ENGINE_NAME

    override suspend fun isAvailable(): Boolean = isInitialized && llmInference != null

    fun release() {
        try {
            synchronized(lock) {
                session?.close()
                session = null
            }
            llmInference?.close()
            llmInference = null
            isInitialized = false
        } catch (e: Exception) {
            // Ignore
        }
    }
}
