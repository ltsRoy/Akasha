package com.MeshLink.android.features.voice

import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.TreeMap

/**
 * Receives ADPCM frames from the mesh, reorders them, and plays them in real time.
 *
 * ## Why this is more than a queue
 *
 * A plain FIFO works on a direct link but breaks as soon as frames are relayed. Multi-hop delivery
 * introduces three problems at once, and all three are audible as crackle:
 *
 *  1. **Reordering** — relayed copies take paths with different delays, so frames arrive out of
 *     order. Playing them in arrival order scrambles the waveform. Fixed by ordering on the sequence
 *     number the streamer stamps into each frame.
 *  2. **Duplication** — mesh flooding delivers the same frame via several neighbours. Playing it
 *     twice is a stutter. Fixed by tracking a play watermark and discarding anything at or below it.
 *  3. **Loss** — BLE drops frames under load. A gap starves AudioTrack, which clicks audibly. Fixed
 *     by concealment: the previous frame is repeated at decaying volume, which the ear reads as a
 *     brief dulling rather than a click.
 *
 * The jitter target adapts: it starts small for low latency and grows when reordering is observed,
 * because a relayed call genuinely needs a deeper buffer than a direct one.
 */
object LiveAudioPlayer {
    private const val TAG = "LiveAudioPlayer"
    const val SAMPLE_RATE = 8000
    private const val CHANNELS = AudioFormat.CHANNEL_OUT_MONO
    private const val AUDIO_ENCODING = AudioFormat.ENCODING_PCM_16BIT

    /** Frames buffered before playback starts on a direct (single-hop) call. */
    private const val MIN_JITTER_FRAMES = 3

    /** Ceiling on the adaptive jitter target. 10 frames at 40ms = 400ms, the limit of comfort. */
    private const val MAX_JITTER_FRAMES = 10

    /** Hard cap on buffered frames; beyond this we're accumulating latency, so drop the oldest. */
    private const val MAX_BUFFER_FRAMES = 16

    /** Consecutive concealed frames before we accept the talker has stopped and go quiet. */
    private const val MAX_CONSECUTIVE_CONCEALED = 5

    /** Stop playback entirely after this long with no frames at all. */
    private const val INACTIVITY_TIMEOUT_MS = 2000L

    private var audioTrack: AudioTrack? = null
    private var playingJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /**
     * Frames awaiting playback, ordered by sequence number rather than arrival.
     *
     * A TreeMap rather than a queue is the core of the fix: insertion order is irrelevant, so a frame
     * that arrives late still plays in the right place as long as it beats the playback cursor.
     */
    private val jitterBuffer = TreeMap<Int, ByteArray>()
    private val bufferLock = Any()

    /** Highest sequence already played. Anything at or below this is a duplicate or too late. */
    private var playedWatermark = -1

    /** Last successfully decoded PCM, reused to conceal gaps. */
    private var lastFrame: ShortArray? = null

    private var consecutiveConcealed = 0

    /** Grows when reordering is detected, so relayed calls get a deeper buffer automatically. */
    private var jitterTargetFrames = MIN_JITTER_FRAMES

    @Volatile private var isPlaying = false
    @Volatile private var isBuffering = true
    @Volatile private var lastPacketTime = 0L
    @Volatile var isCallActive = false

    fun startPlaying() {
        if (isPlaying) return

        try {
            val minBufferSize = AudioTrack.getMinBufferSize(SAMPLE_RATE, CHANNELS, AUDIO_ENCODING)
            // Roomier than the source material needs: an undersized track buffer underruns on any
            // scheduling hiccup, which sounds identical to packet loss.
            val bufferSize = maxOf(minBufferSize, LiveAudioStreamer.PACKET_SIZE_SAMPLES * 2 * 8)

            audioTrack = AudioTrack(
                AudioManager.STREAM_VOICE_CALL,
                SAMPLE_RATE,
                CHANNELS,
                AUDIO_ENCODING,
                bufferSize,
                AudioTrack.MODE_STREAM
            )

            if (audioTrack?.state != AudioTrack.STATE_INITIALIZED) {
                Log.e(TAG, "AudioTrack initialization failed")
                return
            }

            audioTrack?.play()
            isPlaying = true
            resetStreamState()

            playingJob = scope.launch {
                while (isActive && isPlaying) {
                    val frame = nextFrameToPlay()

                    if (frame != null) {
                        audioTrack?.write(frame, 0, frame.size)
                        continue
                    }

                    // Nothing playable right now.
                    if (System.currentTimeMillis() - lastPacketTime > INACTIVITY_TIMEOUT_MS) {
                        Log.d(TAG, "Auto-stopping playback due to inactivity")
                        stopPlaying()
                        break
                    }
                    delay(5)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start live playback: ${e.message}")
            stopPlaying()
        }
    }

    /**
     * Decide what to play next: the in-order frame if present, a concealment frame if one is missing
     * but the stream is still live, or null when there's genuinely nothing to do.
     */
    private fun nextFrameToPlay(): ShortArray? {
        synchronized(bufferLock) {
            // Wait for the jitter target to fill before starting, so early reordering has a chance
            // to resolve itself rather than being played wrong.
            if (isBuffering) {
                if (jitterBuffer.size < jitterTargetFrames) return null
                isBuffering = false
            }

            if (jitterBuffer.isEmpty()) {
                // Conceal briefly — the frame may still be in flight. Beyond a few frames, treat it
                // as the talker having stopped and fall silent rather than smearing audio.
                return if (consecutiveConcealed < MAX_CONSECUTIVE_CONCEALED) concealFrame() else {
                    isBuffering = true
                    null
                }
            }

            val nextSeq = jitterBuffer.firstKey()
            val expected = playedWatermark + 1

            // In order, or the first frame of a stream.
            if (playedWatermark < 0 || nextSeq == expected) {
                val frame = jitterBuffer.remove(nextSeq)!!
                playedWatermark = nextSeq
                consecutiveConcealed = 0
                return decodeAndRemember(frame)
            }

            if (nextSeq > expected) {
                // A gap. Conceal for a short while to give the missing frame time to arrive; if it
                // doesn't, skip ahead rather than stalling the call.
                if (consecutiveConcealed < MAX_CONSECUTIVE_CONCEALED) {
                    return concealFrame()
                }
                Log.d(TAG, "Skipping lost frames $expected..${nextSeq - 1}")
                val frame = jitterBuffer.remove(nextSeq)!!
                playedWatermark = nextSeq
                consecutiveConcealed = 0
                return decodeAndRemember(frame)
            }

            // nextSeq <= watermark: stale duplicate that outlived its slot.
            jitterBuffer.remove(nextSeq)
            return null
        }
    }

    /** Decode a frame and keep the PCM around for concealment. */
    private fun decodeAndRemember(frame: ByteArray): ShortArray {
        val pcm = decodeADPCM(frame)
        if (pcm.isNotEmpty()) lastFrame = pcm
        return pcm
    }

    /**
     * Synthesise a replacement for a missing frame by repeating the last one at reduced gain.
     *
     * Repetition with decay is the standard cheap concealment: pitch and timbre continue plausibly,
     * and the fade stops a long gap turning into an obvious buzz. Silence would be worse — an abrupt
     * amplitude step is exactly what produces the click the user is hearing.
     */
    private fun concealFrame(): ShortArray? {
        val previous = lastFrame ?: return null
        consecutiveConcealed++

        // 0.6^n decay: audible as the sound softening out, not cutting out.
        val gain = Math.pow(0.6, consecutiveConcealed.toDouble()).toFloat()
        val out = ShortArray(previous.size)
        for (i in previous.indices) {
            out[i] = (previous[i] * gain).toInt().toShort()
        }
        return out
    }

    /**
     * Accept a frame from the mesh.
     *
     * Duplicates and frames that have already missed their slot are dropped here rather than being
     * queued, which is what stops mesh flooding from turning into stutter.
     */
    fun onPacketReceived(compressedPacket: ByteArray) {
        if (!isCallActive) return // Drop packets unless we joined the call
        if (compressedPacket.size <= LiveAudioStreamer.HEADER_BYTES) return

        val seq = readSequence(compressedPacket)
        lastPacketTime = System.currentTimeMillis()

        synchronized(bufferLock) {
            if (playedWatermark >= 0) {
                // Already played, or a duplicate still sitting in the buffer.
                if (seq <= playedWatermark && !isWrapAround(seq)) return
                if (jitterBuffer.containsKey(seq)) return

                // Arriving behind a frame we've already queued means the path reordered — deepen the
                // buffer so the next reorder resolves instead of being heard.
                if (jitterBuffer.isNotEmpty() && seq < jitterBuffer.lastKey()) {
                    if (jitterTargetFrames < MAX_JITTER_FRAMES) {
                        jitterTargetFrames++
                        Log.d(TAG, "Reordering seen, jitter target now $jitterTargetFrames frames")
                    }
                }
            }

            jitterBuffer[seq] = compressedPacket

            // Latency guard: if we're holding too much, discard the oldest rather than fall behind.
            while (jitterBuffer.size > MAX_BUFFER_FRAMES) {
                val oldest = jitterBuffer.firstKey()
                jitterBuffer.remove(oldest)
                playedWatermark = maxOf(playedWatermark, oldest)
            }
        }

        if (!isPlaying) {
            startPlaying()
        }
    }

    /**
     * Detect the 16-bit sequence counter wrapping, so a call doesn't stall for ~44 minutes when it
     * rolls over from 65535 back to 0.
     */
    private fun isWrapAround(seq: Int): Boolean =
        playedWatermark > 0xF000 && seq < 0x0FFF

    private fun readSequence(packet: ByteArray): Int =
        (packet[4].toInt() and 0xFF) or ((packet[5].toInt() and 0xFF) shl 8)

    private fun resetStreamState() {
        synchronized(bufferLock) {
            jitterBuffer.clear()
            playedWatermark = -1
            lastFrame = null
            consecutiveConcealed = 0
            jitterTargetFrames = MIN_JITTER_FRAMES
            isBuffering = true
        }
    }

    /**
     * Release the output but stay in the call.
     *
     * Crucially this does NOT clear [isCallActive]. It used to, and that silently killed every call:
     * the caller opens playback immediately, no audio arrives during the seconds before the other side
     * joins, the inactivity timer fires, and clearing the flag made [onPacketReceived] discard
     * everything from then on. The call looked connected and was permanently deaf.
     *
     * Idle is a normal state — someone not talking is not someone hanging up. Use [endCall] for that.
     */
    fun stopPlaying() {
        if (!isPlaying) return

        isPlaying = false
        playingJob?.cancel()
        playingJob = null

        try {
            audioTrack?.apply {
                if (state == AudioTrack.STATE_INITIALIZED) {
                    stop()
                }
                release()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping AudioTrack: ${e.message}")
        } finally {
            audioTrack = null
            resetStreamState()
        }
    }

    /** Leave the call: stop output and refuse further audio until someone joins again. */
    fun endCall() {
        isCallActive = false
        stopPlaying()
    }

    // --- IMA ADPCM Codec (Decoder) ---
    private val indexTable = intArrayOf(
        -1, -1, -1, -1, 2, 4, 6, 8,
        -1, -1, -1, -1, 2, 4, 6, 8
    )

    private val stepsizeTable = intArrayOf(
        7, 8, 9, 10, 11, 12, 13, 14, 16, 17,
        19, 21, 23, 25, 28, 31, 34, 37, 41, 45,
        50, 55, 60, 66, 73, 80, 88, 97, 107, 118,
        130, 143, 157, 173, 190, 209, 230, 253, 279, 307,
        337, 371, 408, 449, 494, 544, 598, 658, 724, 796,
        876, 963, 1060, 1166, 1282, 1411, 1552, 1707, 1878, 2066,
        2272, 2499, 2749, 3024, 3327, 3660, 4026, 4428, 4871, 5358,
        5894, 6484, 7132, 7845, 8630, 9493, 10442, 11487, 12635, 13899,
        15289, 16818, 18500, 20350, 22385, 24623, 27086, 29794, 32767
    )

    private fun decodeADPCM(adpcm: ByteArray): ShortArray {
        val header = LiveAudioStreamer.HEADER_BYTES
        if (adpcm.size <= header) return ShortArray(0)

        // Read state header
        var valprev = (adpcm[0].toInt() and 0xFF) or ((adpcm[1].toInt() and 0xFF) shl 8)
        if (valprev > 32767) valprev -= 65536
        var index = adpcm[2].toInt() and 0xFF
        if (index < 0) index = 0
        if (index > 88) index = 88

        val length = (adpcm.size - header) * 2
        val out = ShortArray(length)
        var outPtr = 0

        var bufferStep = true
        var inPtr = header
        var delta = 0

        for (i in 0 until length) {
            // Low nibble first, matching the encoder, which writes ((second shl 4) or first).
            // Reading the high nibble first swaps every sample pair — still intelligible, but it
            // injects audible high-frequency grit into the whole stream.
            if (bufferStep) {
                delta = adpcm[inPtr].toInt() and 0x0F
            } else {
                delta = (adpcm[inPtr].toInt() shr 4) and 0x0F
                inPtr++
            }
            bufferStep = !bufferStep

            var step = stepsizeTable[index]
            var vpdiff = step shr 3
            if ((delta and 4) != 0) vpdiff += step
            if ((delta and 2) != 0) vpdiff += (step shr 1)
            if ((delta and 1) != 0) vpdiff += (step shr 2)

            if ((delta and 8) != 0) {
                valprev -= vpdiff
            } else {
                valprev += vpdiff
            }

            if (valprev > 32767) valprev = 32767
            else if (valprev < -32768) valprev = -32768

            index += indexTable[delta]
            if (index < 0) index = 0
            if (index > 88) index = 88

            out[outPtr++] = valprev.toShort()
        }

        return out
    }
}
