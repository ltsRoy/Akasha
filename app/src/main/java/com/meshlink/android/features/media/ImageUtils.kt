package com.MeshLink.android.features.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream

object ImageUtils {

    private const val TAG = "ImageUtils"

    /**
     * Longest edge allowed on an image that goes out over the mesh.
     *
     * Sized for the transport, not for the screen. A frame is split into 469-byte fragments and
     * there is no retransmission, so the odds of a transfer completing fall off a cliff as the
     * fragment count grows. 640px is still legible for a wound, a landmark, or a road sign.
     */
    const val MESH_MAX_DIM = 640

    /**
     * Byte budget for an outgoing mesh image: ~100 fragments.
     *
     * Also keeps a private image inside the 65,535-byte ceiling of a v1 packet, so the transfer
     * does not depend on the v2 length field being negotiated correctly on both ends.
     */
    const val MESH_BUDGET_BYTES = 48 * 1024

    /** Quality steps tried before giving up resolution. */
    private val QUALITY_LADDER = intArrayOf(70, 60, 50, 40, 30)

    /** Edge lengths tried, largest first, once the quality ladder is exhausted. */
    private val DIMENSION_LADDER = intArrayOf(MESH_MAX_DIM, 512, 400, 320)

    /**
     * Re-encode [source] so it fits [MESH_BUDGET_BYTES], trading quality first and resolution only
     * when quality alone is not enough.
     *
     * Returns [source] unchanged when it is already within budget or cannot be decoded, so a
     * failure here degrades to the previous behaviour rather than dropping the send.
     */
    fun compressForMesh(source: ByteArray): ByteArray {
        if (source.size <= MESH_BUDGET_BYTES) return source

        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(source, 0, source.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return source

            var best: ByteArray? = null

            for (maxDim in DIMENSION_LADDER) {
                val decoded = decodeScaled(source, bounds, maxDim) ?: continue
                val scaled = scaleToMaxDim(decoded, maxDim)

                try {
                    for (quality in QUALITY_LADDER) {
                        val encoded = java.io.ByteArrayOutputStream().use { out ->
                            scaled.compress(Bitmap.CompressFormat.JPEG, quality, out)
                            out.toByteArray()
                        }
                        // Keep the smallest candidate seen, so an exhausted ladder still improves.
                        if (best == null || encoded.size < best!!.size) best = encoded
                        if (encoded.size <= MESH_BUDGET_BYTES) {
                            android.util.Log.d(
                                TAG,
                                "Mesh image: ${source.size} -> ${encoded.size} bytes " +
                                    "(${scaled.width}x${scaled.height}, q$quality)",
                            )
                            return encoded
                        }
                    }
                } finally {
                    try { if (scaled !== decoded) scaled.recycle() } catch (_: Exception) {}
                    try { decoded.recycle() } catch (_: Exception) {}
                }
            }

            val result = best ?: source
            android.util.Log.w(
                TAG,
                "Mesh image still ${result.size} bytes, above the " +
                    "$MESH_BUDGET_BYTES budget; sending anyway",
            )
            result
        } catch (e: OutOfMemoryError) {
            android.util.Log.w(TAG, "Mesh image compression ran out of memory; sending original")
            source
        } catch (e: Exception) {
            android.util.Log.w(TAG, "Mesh image compression failed; sending original", e)
            source
        }
    }

    /** Decode at the smallest power-of-two sample size that still covers [maxDim]. */
    private fun decodeScaled(source: ByteArray, bounds: BitmapFactory.Options, maxDim: Int): Bitmap? {
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxDim) {
            sample *= 2
        }
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return BitmapFactory.decodeByteArray(source, 0, source.size, opts)
    }

    /** Scale [bitmap] so its longest edge is at most [maxDim]; returns the input if already smaller. */
    private fun scaleToMaxDim(bitmap: Bitmap, maxDim: Int): Bitmap {
        val longest = maxOf(bitmap.width, bitmap.height)
        if (longest <= maxDim) return bitmap
        val ratio = maxDim.toFloat() / longest.toFloat()
        val w = (bitmap.width * ratio).toInt().coerceAtLeast(1)
        val h = (bitmap.height * ratio).toInt().coerceAtLeast(1)
        return try {
            Bitmap.createScaledBitmap(bitmap, w, h, true)
        } catch (_: Exception) {
            bitmap
        }
    }

    fun downscaleAndSaveToAppFiles(context: Context, uri: Uri, maxDim: Int = MESH_MAX_DIM, quality: Int = 80): String? {
        return try {
            val resolver = context.contentResolver
            val exifRotation = resolver.openInputStream(uri)?.use { getRotationDegreesFromExif(it) } ?: 0

            // Reopen for decode as the previous stream is consumed
            val input = resolver.openInputStream(uri) ?: return null
            val original = BitmapFactory.decodeStream(input)
            input.close()
            original ?: return null

            val oriented = if (exifRotation != 0) rotateBitmap(original, exifRotation) else original

            val w = oriented.width
            val h = oriented.height
            val scale = (maxOf(w, h).toFloat() / maxDim.toFloat()).coerceAtLeast(1f)
            val newW = (w / scale).toInt().coerceAtLeast(1)
            val newH = (h / scale).toInt().coerceAtLeast(1)
            val scaled = if (scale > 1f) Bitmap.createScaledBitmap(oriented, newW, newH, true) else oriented
            val dir = File(context.filesDir, "images/outgoing").apply { mkdirs() }
            val outFile = File(dir, "img_${System.currentTimeMillis()}.jpg")
            FileOutputStream(outFile).use { fos ->
                scaled.compress(Bitmap.CompressFormat.JPEG, quality, fos)
            }
            try { if (oriented !== original) original.recycle() } catch (_: Exception) {}
            try { if (scaled !== oriented) oriented.recycle() } catch (_: Exception) {}
            outFile.absolutePath
        } catch (e: Exception) {
            null
        }
    }

    fun downscalePathAndSaveToAppFiles(context: Context, path: String, maxDim: Int = MESH_MAX_DIM, quality: Int = 80): String? {
        return try {
            val original = BitmapFactory.decodeFile(path) ?: return null
            val exifRotation = getRotationDegreesFromExif(path)
            val oriented = if (exifRotation != 0) rotateBitmap(original, exifRotation) else original

            val w = oriented.width
            val h = oriented.height
            val scale = (maxOf(w, h).toFloat() / maxDim.toFloat()).coerceAtLeast(1f)
            val newW = (w / scale).toInt().coerceAtLeast(1)
            val newH = (h / scale).toInt().coerceAtLeast(1)
            val scaled = if (scale > 1f) Bitmap.createScaledBitmap(oriented, newW, newH, true) else oriented
            val dir = File(context.filesDir, "images/outgoing").apply { mkdirs() }
            val outFile = File(dir, "img_${System.currentTimeMillis()}.jpg")
            FileOutputStream(outFile).use { fos ->
                scaled.compress(Bitmap.CompressFormat.JPEG, quality, fos)
            }
            try { if (oriented !== original) original.recycle() } catch (_: Exception) {}
            try { if (scaled !== oriented) oriented.recycle() } catch (_: Exception) {}
            outFile.absolutePath
        } catch (e: Exception) {
            null
        }
    }

    fun loadBitmapWithExifOrientation(path: String): Bitmap? {
        return try {
            val base = BitmapFactory.decodeFile(path) ?: return null
            val rotation = getRotationDegreesFromExif(path)
            if (rotation != 0) rotateBitmap(base, rotation) else base
        } catch (_: Exception) {
            null
        }
    }

    private fun rotateBitmap(src: Bitmap, degrees: Int): Bitmap {
        return try {
            val m = Matrix()
            m.postRotate(degrees.toFloat())
            Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true).also {
                try { src.recycle() } catch (_: Exception) {}
            }
        } catch (_: Exception) {
            src
        }
    }

    private fun getRotationDegreesFromExif(path: String): Int = try {
        val exif = ExifInterface(path)
        orientationToDegrees(exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL))
    } catch (_: Exception) { 0 }

    private fun getRotationDegreesFromExif(stream: InputStream): Int = try {
        val exif = ExifInterface(stream)
        orientationToDegrees(exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL))
    } catch (_: Exception) { 0 }

    private fun orientationToDegrees(orientation: Int): Int = when (orientation) {
        ExifInterface.ORIENTATION_ROTATE_90 -> 90
        ExifInterface.ORIENTATION_ROTATE_180 -> 180
        ExifInterface.ORIENTATION_ROTATE_270 -> 270
        ExifInterface.ORIENTATION_TRANSPOSE -> 90
        ExifInterface.ORIENTATION_TRANSVERSE -> 270
        else -> 0
    }
}
