package com.MeshLink.android.features.voice

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Captures live microphone audio, compresses it using ADPCM (4-bit), and emits tiny packets
 * suitable for Bluetooth Low Energy streaming.
 */
object LiveAudioStreamer {
    private const val TAG = "LiveAudioStreamer"
    const val SAMPLE_RATE = 8000 // Revert to telephone quality to prevent BLE flooding
    private const val CHANNELS = AudioFormat.CHANNEL_IN_MONO
    private const val AUDIO_ENCODING = AudioFormat.ENCODING_PCM_16BIT
    
    // Target 320 samples (640 bytes) per chunk -> 160 bytes ADPCM (40ms of audio)
    const val PACKET_SIZE_SAMPLES = 320

    /** Duration of one frame in milliseconds, derived so the two can't drift apart. */
    const val FRAME_MS = PACKET_SIZE_SAMPLES * 1000 / SAMPLE_RATE

    /**
     * Frame header: predictor state (2) + step index (1) + reserved (1) + sequence (2).
     *
     * The sequence number is what makes multi-hop calls usable. Relayed copies of the same frame
     * take different paths with different delays, so they arrive out of order and duplicated. Without
     * a sequence the receiver plays them in arrival order, which is audible as crackle.
     *
     * The predictor state travels in every frame, so a lost frame degrades only itself rather than
     * corrupting everything after it.
     */
    const val HEADER_BYTES = 6

    private var audioRecord: AudioRecord? = null
    private var streamingJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val _isStreaming = MutableStateFlow(false)
    val isStreaming: StateFlow<Boolean> = _isStreaming.asStateFlow()
    
    private val _amplitude = MutableStateFlow(0)
    val amplitude: StateFlow<Int> = _amplitude.asStateFlow()

    // Callback that fires whenever a tiny packet of compressed audio is ready to broadcast
    var onPacketReady: ((ByteArray) -> Unit)? = null

    // Simple IMA ADPCM encoder state
    private var valprev = 0
    private var index = 0

    /** Monotonic frame counter stamped into every packet, wrapping at 16 bits. */
    private var sequence = 0

    @SuppressLint("MissingPermission")
    fun startStreaming() {
        if (_isStreaming.value) return

        try {
            val minBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNELS, AUDIO_ENCODING)
            val bufferSize = maxOf(minBufferSize, PACKET_SIZE_SAMPLES * 2 * 4) // Keep buffer small for low latency

            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                SAMPLE_RATE,
                CHANNELS,
                AUDIO_ENCODING,
                bufferSize
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord initialization failed")
                return
            }

            audioRecord?.startRecording()
            _isStreaming.value = true
            
            // Reset ADPCM and sequence state so a new call starts from a known point.
            valprev = 0
            index = 0
            sequence = 0

            streamingJob = scope.launch {
                val rawBuffer = ShortArray(PACKET_SIZE_SAMPLES)
                
                while (isActive && _isStreaming.value) {
                    val readResult = audioRecord?.read(rawBuffer, 0, PACKET_SIZE_SAMPLES) ?: -1
                    
                    if (readResult > 0) {
                        // Calculate amplitude for UI
                        var maxAmp = 0
                        for (i in 0 until readResult) {
                            val absVal = Math.abs(rawBuffer[i].toInt())
                            if (absVal > maxAmp) maxAmp = absVal
                        }
                        _amplitude.value = maxAmp

                        // Compress to ADPCM
                        val compressedPacket = encodeADPCM(rawBuffer, readResult)
                        
                        // Fire callback to broadcast over Mesh
                        onPacketReady?.invoke(compressedPacket)
                    } else {
                        Log.w(TAG, "AudioRecord read failed: $readResult")
                        delay(10) // Small delay on error
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start live stream: ${e.message}")
            stopStreaming()
        }
    }

    fun stopStreaming() {
        if (!_isStreaming.value) return
        
        _isStreaming.value = false
        streamingJob?.cancel()
        streamingJob = null
        
        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping AudioRecord: ${e.message}")
        } finally {
            audioRecord = null
            _amplitude.value = 0
        }
    }

    // --- IMA ADPCM Codec ---
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

    private fun encodeADPCM(samples: ShortArray, length: Int): ByteArray {
        val out = ByteArray((length / 2) + HEADER_BYTES)
        out[0] = (valprev and 0xFF).toByte()
        out[1] = ((valprev shr 8) and 0xFF).toByte()
        out[2] = index.toByte()
        out[3] = 0 // Reserved

        // 16-bit sequence, wrapping. At 25 frames/second that's ~44 minutes before reuse, far longer
        // than any jitter window, so the receiver can order and de-duplicate safely.
        out[4] = (sequence and 0xFF).toByte()
        out[5] = ((sequence shr 8) and 0xFF).toByte()
        sequence = (sequence + 1) and 0xFFFF

        var outPtr = HEADER_BYTES
        var buffer = 0
        var bufferStep = true
        
        for (i in 0 until length) {
            val sample = samples[i].toInt()
            var diff = sample - valprev
            var step = stepsizeTable[index]
            var sign = 0
            
            if (diff < 0) {
                sign = 8
                diff = -diff
            }
            
            var delta = 0
            var vpdiff = (step shr 3)
            
            if (diff >= step) {
                delta = delta or 4
                diff -= step
                vpdiff += step
            }
            step = step shr 1
            if (diff >= step) {
                delta = delta or 2
                diff -= step
                vpdiff += step
            }
            step = step shr 1
            if (diff >= step) {
                delta = delta or 1
                vpdiff += step
            }
            
            if (sign != 0) {
                valprev -= vpdiff
            } else {
                valprev += vpdiff
            }
            
            if (valprev > 32767) valprev = 32767
            else if (valprev < -32768) valprev = -32768
            
            delta = delta or sign
            index += indexTable[delta]
            if (index < 0) index = 0
            if (index > 88) index = 88
            
            if (bufferStep) {
                buffer = delta and 0x0F
            } else {
                out[outPtr++] = ((delta shl 4) or buffer).toByte()
            }
            bufferStep = !bufferStep
        }
        return out
    }
}
