package com.MeshLink.android.moderation

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/**
 * On-device explicit-image detector, used to blur (never block) sexual content on the mesh.
 *
 * Runs entirely offline from a bundled 5.7 MB model, which matters because the mesh is used when
 * there is no network — a cloud moderation API would be dead weight here.
 *
 * ## Deliberately permissive
 *
 * This is tuned to let almost everything through. In an emergency app the costly mistake is a false
 * positive, not a false negative: the images people most need to send — a deep laceration, a burn,
 * a shirtless heat-stroke casualty, a birth — are skin-heavy, and skin-heavy is exactly what NSFW
 * classifiers over-trigger on. Hiding a wound photo from someone asking for triage help is worse
 * than letting an explicit image through to an adult who can report it.
 *
 * Three choices follow from that:
 *  - The threshold sits at [NSFW_THRESHOLD], far above the model author's suggested 0.8, so only
 *    confident detections count.
 *  - A flag never deletes or rejects. It blurs, with tap-to-reveal, so the user stays in control.
 *  - Any failure — model missing, decode error, unreadable file — resolves to "not flagged".
 *    A broken classifier must not start hiding legitimate images.
 *
 * ## Model
 *
 * Yahoo's open_nsfw, converted to TensorFlow Lite.
 * Original: https://github.com/yahoo/open_nsfw — BSD 2-Clause.
 * TFLite conversion via https://github.com/xiongshanxi/open_nsfw_android.
 *
 * Input contract, which must be matched exactly or the scores are meaningless:
 * float32 [1,224,224,3], **BGR** channel order, ImageNet mean subtracted (B-104, G-117, R-123),
 * no rescaling to 0..1. The source image is scaled to 256x256 then centre-cropped to 224x224.
 * Output is [SFW, NSFW].
 */
object NsfwClassifier {

    private const val TAG = "NsfwClassifier"

    private const val MODEL_ASSET = "nsfw.tflite"

    /** Model's native input size, after the 256 -> centre-crop step. */
    private const val INPUT_SIZE = 224
    private const val SCALE_SIZE = 256

    /** Per-channel means the model was trained with (BGR order). */
    private const val MEAN_B = 104f
    private const val MEAN_G = 117f
    private const val MEAN_R = 123f

    /**
     * Confidence required to flag an image.
     *
     * The model's own guidance treats >0.8 as explicit. This sits higher on purpose: at 0.8 the
     * medical and rescue photos this app exists to carry get caught. Raising it trades some missed
     * explicit images for near-zero interference with legitimate ones, which is the right trade when
     * the fallback is "an adult sees it and reports the sender".
     */
    private const val NSFW_THRESHOLD = 0.95f

    /** Decode ceiling — the model only ever sees 224px, so decoding full-resolution is wasted work. */
    private const val MAX_DECODE_DIMENSION = 512

    private var interpreter: Interpreter? = null

    @Volatile
    private var initFailed = false

    private val lock = Any()

    /** Reusable input buffer: 1 * 224 * 224 * 3 floats. */
    private val inputBuffer: ByteBuffer =
        ByteBuffer.allocateDirect(INPUT_SIZE * INPUT_SIZE * 3 * 4).order(ByteOrder.nativeOrder())

    private val pixels = IntArray(INPUT_SIZE * INPUT_SIZE)

    /** Verdict for one image. */
    data class Result(
        /** Model's probability that the image is explicit, 0..1. */
        val nsfwScore: Float,
        /** True only when [nsfwScore] clears [NSFW_THRESHOLD]. */
        val isExplicit: Boolean,
    )

    /**
     * Score an image file.
     *
     * Returns null when no opinion could be formed (model unavailable, not an image, unreadable).
     * Callers must treat null as "allow" — see the class note on failing open.
     */
    suspend fun scoreFile(context: Context, path: String): Result? = withContext(Dispatchers.Default) {
        val bitmap = decodeDownsampled(path) ?: return@withContext null
        try {
            score(context, bitmap)
        } finally {
            bitmap.recycle()
        }
    }

    /** Score an already-decoded bitmap, e.g. the one the send path decodes for compression. */
    suspend fun score(context: Context, bitmap: Bitmap): Result? = withContext(Dispatchers.Default) {
        val model = ensureInterpreter(context) ?: return@withContext null

        try {
            val output = Array(1) { FloatArray(2) }
            synchronized(lock) {
                writeInput(bitmap)
                model.run(inputBuffer, output)
            }

            val nsfw = output[0][1]
            val result = Result(nsfwScore = nsfw, isExplicit = nsfw >= NSFW_THRESHOLD)
            Log.d(TAG, "sfw=${output[0][0]} nsfw=$nsfw -> explicit=${result.isExplicit}")
            result
        } catch (e: Exception) {
            Log.w(TAG, "Classification failed, treating image as allowed: ${e.message}")
            null
        } catch (e: Error) {
            Log.e(TAG, "Fatal classification error, treating image as allowed: ${e.message}")
            null
        }
    }

    /** Lazily create the interpreter. A single failure disables the feature rather than retrying forever. */
    private fun ensureInterpreter(context: Context): Interpreter? {
        interpreter?.let { return it }
        if (initFailed) return null

        synchronized(lock) {
            interpreter?.let { return it }
            if (initFailed) return null

            return try {
                val created = Interpreter(
                    loadModel(context),
                    // Two threads is plenty for a 5.7 MB model and leaves headroom for the mesh
                    // service and any LLM inference running at the same time.
                    Interpreter.Options().setNumThreads(2),
                )
                interpreter = created
                Log.i(TAG, "NSFW classifier ready (threshold=$NSFW_THRESHOLD)")
                created
            } catch (e: Exception) {
                Log.w(TAG, "NSFW model unavailable, moderation disabled: ${e.message}")
                initFailed = true
                null
            } catch (e: Error) {
                Log.e(TAG, "Fatal error loading NSFW model, moderation disabled: ${e.message}")
                initFailed = true
                null
            }
        }
    }

    /** Memory-map the model from assets — requires the `noCompress += "tflite"` build setting. */
    private fun loadModel(context: Context): ByteBuffer {
        context.assets.openFd(MODEL_ASSET).use { fd ->
            FileInputStream(fd.fileDescriptor).use { stream ->
                return stream.channel.map(
                    FileChannel.MapMode.READ_ONLY,
                    fd.startOffset,
                    fd.declaredLength,
                )
            }
        }
    }

    /**
     * Fill [inputBuffer] following the model's exact preprocessing.
     *
     * Note the BGR ordering and the absence of any /255 normalisation: this model came from Caffe,
     * where that was the convention. Feeding it RGB or 0..1 values produces confident nonsense
     * rather than an obvious error, which would show up as random images being flagged.
     */
    private fun writeInput(source: Bitmap) {
        val scaled = Bitmap.createScaledBitmap(source, SCALE_SIZE, SCALE_SIZE, true)
        try {
            inputBuffer.rewind()

            // Centre crop from 256 to 224.
            val offset = (SCALE_SIZE - INPUT_SIZE) / 2
            scaled.getPixels(pixels, 0, INPUT_SIZE, offset, offset, INPUT_SIZE, INPUT_SIZE)

            for (color in pixels) {
                val r = (color shr 16) and 0xFF
                val g = (color shr 8) and 0xFF
                val b = color and 0xFF

                inputBuffer.putFloat(b - MEAN_B)
                inputBuffer.putFloat(g - MEAN_G)
                inputBuffer.putFloat(r - MEAN_R)
            }
        } finally {
            if (scaled != source) scaled.recycle()
        }
    }

    /** Decode at reduced resolution: the model sees 224px, so full-size decoding only risks OOM. */
    private fun decodeDownsampled(path: String): Bitmap? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

            var sample = 1
            while (
                bounds.outWidth / (sample * 2) >= MAX_DECODE_DIMENSION &&
                bounds.outHeight / (sample * 2) >= MAX_DECODE_DIMENSION
            ) {
                sample *= 2
            }

            BitmapFactory.decodeFile(
                path,
                BitmapFactory.Options().apply {
                    inSampleSize = sample
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                },
            )
        } catch (e: Exception) {
            Log.w(TAG, "Could not decode $path for classification: ${e.message}")
            null
        } catch (e: Error) {
            Log.e(TAG, "Fatal decode error for $path: ${e.message}")
            null
        }
    }
}
