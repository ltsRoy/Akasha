package com.MeshLink.android.features.knowledge

import java.io.ByteArrayOutputStream

/**
 * Byte layout for knowledge queries carried over BLE.
 *
 * **Query text crosses the mesh, not vectors.** A 384-dim fp32 embedding is 1536 bytes, which would
 * fragment on every hop; the question itself is typically under 100. The responder re-embeds the text
 * on its own side, which is safe precisely because both devices run the same MiniLM model — the
 * reason embedding parity is enforced at build time.
 *
 * QUERY payload:
 *   [0]      version
 *   [1..16]  request id (16 bytes, random)
 *   [17]     topK
 *   [18..19] question length (big-endian u16)
 *   [20..]   question, UTF-8
 *
 * QUERY_RESULT payload:
 *   [0]      version
 *   [1..16]  request id, echoed
 *   [17]     confidence ordinal
 *   [18]     backend ordinal
 *   [19]     result count
 *   then per result:
 *     score (4 bytes, float bits BE)
 *     text length (u16) + text
 *     category length (u8) + category
 *     sourceDoc length (u8) + sourceDoc
 *     packVersion length (u8) + packVersion
 */
object MeshQueryCodec {

    /**
     * Version 2 adds a query kind and the requester's coordinates, so facility lookups can hop too.
     *
     * Version 1 carried only a question string, which limited relaying to the safety corpus. A
     * facility search is useless without a location, and the requester must supply its own — the
     * responder has no idea where the asker is.
     */
    private const val VERSION: Byte = 2
    const val REQUEST_ID_BYTES = 16

    /** What the requester wants back. */
    enum class Kind { SAFETY, FACILITY }

    /**
     * Ceiling on an encoded result set.
     *
     * Kept under the 512-byte fragmentation threshold so a reply crosses in a single packet.
     * Fragmented replies would need reassembly on a link that is already the weakest part of the
     * system, so passages are truncated instead.
     */
    private const val MAX_RESULT_BYTES = 460

    /** Longest single passage carried over the mesh; longer ones are cut with an ellipsis. */
    private const val MAX_TEXT_BYTES = 320

    data class Query(
        val requestId: ByteArray,
        val question: String,
        val topK: Int,
        val kind: Kind = Kind.SAFETY,
        /** Requester's position, present only for [Kind.FACILITY]. */
        val latitude: Double? = null,
        val longitude: Double? = null,
    )

    /** Facility records relayed back to an isolated requester. */
    data class FacilityResult(
        val requestId: ByteArray,
        val facilities: List<SearchableFacility>,
    )

    /**
     * Compact facility record for the air.
     *
     * A trimmed subset of [Facility]: the fields a person needs to decide where to go, and nothing
     * else. Phone numbers are omitted because the database doesn't vouch for them, and specialty
     * lists are dropped because they don't fit alongside three records in one BLE packet.
     */
    data class SearchableFacility(
        val name: String,
        val category: String,
        val address: String,
        val latitude: Double,
        val longitude: Double,
        val distanceKm: Double,
        val emergency24h: Boolean,
    )

    data class Result(
        val requestId: ByteArray,
        val confidence: Confidence,
        val backend: Backend,
        val results: List<SearchResult>,
    )

    fun newRequestId(): ByteArray = ByteArray(REQUEST_ID_BYTES).also { java.security.SecureRandom().nextBytes(it) }

    /**
     * QUERY payload (v2):
     *   [0] version, [1..16] request id, [17] topK, [18] kind,
     *   [19..26] latitude, [27..34] longitude (IEEE-754 doubles, NaN when absent),
     *   [35..36] question length, [37..] question
     */
    fun encodeQuery(
        requestId: ByteArray,
        question: String,
        topK: Int,
        kind: Kind = Kind.SAFETY,
        latitude: Double? = null,
        longitude: Double? = null,
    ): ByteArray {
        val text = question.toByteArray(Charsets.UTF_8).let {
            if (it.size > MAX_TEXT_BYTES) it.copyOf(MAX_TEXT_BYTES) else it
        }
        val out = ByteArrayOutputStream()
        out.write(VERSION.toInt())
        out.write(requestId, 0, REQUEST_ID_BYTES)
        out.write(topK.coerceIn(1, 5))
        out.write(kind.ordinal)
        // NaN encodes "no fix", so the responder can tell a missing location from the equator.
        writeDouble(out, latitude ?: Double.NaN)
        writeDouble(out, longitude ?: Double.NaN)
        out.write((text.size shr 8) and 0xFF)
        out.write(text.size and 0xFF)
        out.write(text)
        return out.toByteArray()
    }

    fun decodeQuery(payload: ByteArray): Query? {
        try {
            if (payload.size < 37 || payload[0] != VERSION) return null
            val requestId = payload.copyOfRange(1, 17)
            val topK = (payload[17].toInt() and 0xFF).coerceIn(1, 5)
            val kind = Kind.entries.getOrNull(payload[18].toInt() and 0xFF) ?: Kind.SAFETY
            val lat = readDouble(payload, 19)
            val lon = readDouble(payload, 27)
            val len = ((payload[35].toInt() and 0xFF) shl 8) or (payload[36].toInt() and 0xFF)
            if (37 + len > payload.size) return null
            val question = String(payload, 37, len, Charsets.UTF_8)
            return Query(
                requestId = requestId,
                question = question,
                topK = topK,
                kind = kind,
                latitude = lat.takeIf { !it.isNaN() },
                longitude = lon.takeIf { !it.isNaN() },
            )
        } catch (e: Exception) {
            return null
        }
    }

    /**
     * QUERY_RESULT payload for facilities:
     *   [0] version, [1..16] request id, [17] kind marker, [18] count, then per record:
     *   lat/lon/distance as float32, flags byte, then name/category/address each u8-length-prefixed.
     *
     * Floats rather than doubles for coordinates: ~1 m precision at these magnitudes, and it halves
     * the numeric cost so three records still fit in a single unfragmented packet.
     */
    fun encodeFacilityResult(requestId: ByteArray, facilities: List<SearchableFacility>): ByteArray {
        val body = ByteArrayOutputStream()
        var written = 0
        var count = 0

        for (f in facilities) {
            val encoded = encodeFacility(f) ?: continue
            if (written + encoded.size > MAX_RESULT_BYTES) break
            body.write(encoded)
            written += encoded.size
            count++
        }

        val out = ByteArrayOutputStream()
        out.write(VERSION.toInt())
        out.write(requestId, 0, REQUEST_ID_BYTES)
        out.write(FACILITY_MARKER)
        out.write(count)
        out.write(body.toByteArray())
        return out.toByteArray()
    }

    fun decodeFacilityResult(payload: ByteArray): FacilityResult? {
        try {
            if (payload.size < 19 || payload[0] != VERSION) return null
            if ((payload[17].toInt() and 0xFF) != FACILITY_MARKER) return null

            val requestId = payload.copyOfRange(1, 17)
            val count = payload[18].toInt() and 0xFF
            var p = 19
            val out = ArrayList<SearchableFacility>(count)

            repeat(count) {
                if (p + 13 > payload.size) return@repeat
                val lat = readFloat(payload, p); p += 4
                val lon = readFloat(payload, p); p += 4
                val dist = readFloat(payload, p); p += 4
                val flags = payload[p].toInt() and 0xFF; p += 1

                val name = readShortString(payload, p) ?: return@repeat; p += 1 + name.second
                val category = readShortString(payload, p) ?: return@repeat; p += 1 + category.second
                val address = readShortString(payload, p) ?: return@repeat; p += 1 + address.second

                out += SearchableFacility(
                    name = name.first,
                    category = category.first,
                    address = address.first,
                    latitude = lat.toDouble(),
                    longitude = lon.toDouble(),
                    distanceKm = dist.toDouble(),
                    emergency24h = (flags and 0x01) != 0,
                )
            }
            return FacilityResult(requestId, out)
        } catch (e: Exception) {
            return null
        }
    }

    /** Distinguishes a facility reply from a passage reply sharing the QUERY_RESULT packet type. */
    private const val FACILITY_MARKER = 0xF1

    private fun encodeFacility(f: SearchableFacility): ByteArray? {
        try {
            val name = f.name.take(60).toByteArray(Charsets.UTF_8)
            val category = f.category.take(24).toByteArray(Charsets.UTF_8)
            val address = f.address.take(80).toByteArray(Charsets.UTF_8)

            val out = ByteArrayOutputStream()
            writeFloat(out, f.latitude.toFloat())
            writeFloat(out, f.longitude.toFloat())
            writeFloat(out, f.distanceKm.toFloat())
            out.write(if (f.emergency24h) 0x01 else 0x00)
            out.write(name.size); out.write(name)
            out.write(category.size); out.write(category)
            out.write(address.size); out.write(address)
            return out.toByteArray()
        } catch (e: Exception) {
            return null
        }
    }

    private fun writeDouble(out: ByteArrayOutputStream, value: Double) {
        val bits = java.lang.Double.doubleToRawLongBits(value)
        for (shift in 56 downTo 0 step 8) out.write(((bits shr shift) and 0xFF).toInt())
    }

    private fun readDouble(buf: ByteArray, at: Int): Double {
        var bits = 0L
        for (i in 0 until 8) bits = (bits shl 8) or (buf[at + i].toLong() and 0xFF)
        return java.lang.Double.longBitsToDouble(bits)
    }

    private fun writeFloat(out: ByteArrayOutputStream, value: Float) {
        val bits = java.lang.Float.floatToRawIntBits(value)
        for (shift in 24 downTo 0 step 8) out.write((bits shr shift) and 0xFF)
    }

    private fun readFloat(buf: ByteArray, at: Int): Float {
        var bits = 0
        for (i in 0 until 4) bits = (bits shl 8) or (buf[at + i].toInt() and 0xFF)
        return java.lang.Float.intBitsToFloat(bits)
    }

    fun encodeResult(
        requestId: ByteArray,
        confidence: Confidence,
        backend: Backend,
        results: List<SearchResult>,
    ): ByteArray {
        val body = ByteArrayOutputStream()
        var written = 0

        for (r in results) {
            val encoded = encodeOne(r) ?: continue
            // Stop before exceeding one BLE packet rather than emitting something that fragments.
            if (written + encoded.size > MAX_RESULT_BYTES) break
            body.write(encoded)
            written += encoded.size
            }

        val countWritten = countResults(body.toByteArray())

        val out = ByteArrayOutputStream()
        out.write(VERSION.toInt())
        out.write(requestId, 0, REQUEST_ID_BYTES)
        out.write(confidence.ordinal)
        out.write(backend.ordinal)
        out.write(countWritten)
        out.write(body.toByteArray())
        return out.toByteArray()
    }

    private fun encodeOne(r: SearchResult): ByteArray? {
        try {
            var text = r.text.toByteArray(Charsets.UTF_8)
            if (text.size > MAX_TEXT_BYTES) {
                text = String(text.copyOf(MAX_TEXT_BYTES), Charsets.UTF_8)
                    .dropLast(1).plus("…").toByteArray(Charsets.UTF_8)
            }
            val category = r.category.take(40).toByteArray(Charsets.UTF_8)
            val source = r.sourceDoc.take(60).toByteArray(Charsets.UTF_8)
            val version = r.packVersion.take(16).toByteArray(Charsets.UTF_8)

            val out = ByteArrayOutputStream()
            val bits = java.lang.Float.floatToIntBits(r.score)
            out.write((bits shr 24) and 0xFF)
            out.write((bits shr 16) and 0xFF)
            out.write((bits shr 8) and 0xFF)
            out.write(bits and 0xFF)
            out.write((text.size shr 8) and 0xFF)
            out.write(text.size and 0xFF)
            out.write(text)
            out.write(category.size); out.write(category)
            out.write(source.size); out.write(source)
            out.write(version.size); out.write(version)
            return out.toByteArray()
        } catch (e: Exception) {
            return null
        }
    }

    /** Count entries in an encoded body by walking it, so the header count can never disagree. */
    private fun countResults(body: ByteArray): Int {
        var p = 0
        var n = 0
        while (p < body.size) {
            val next = skipOne(body, p) ?: break
            p = next
            n++
        }
        return n
    }

    private fun skipOne(body: ByteArray, start: Int): Int? {
        try {
            var p = start + 4 // score
            if (p + 2 > body.size) return null
            val textLen = ((body[p].toInt() and 0xFF) shl 8) or (body[p + 1].toInt() and 0xFF)
            p += 2 + textLen
            for (i in 0 until 3) {
                if (p >= body.size) return null
                p += 1 + (body[p].toInt() and 0xFF)
            }
            return if (p <= body.size) p else null
        } catch (e: Exception) {
            return null
        }
    }

    fun decodeResult(payload: ByteArray): Result? {
        try {
            if (payload.size < 20 || payload[0] != VERSION) return null
            val requestId = payload.copyOfRange(1, 17)
            val confidence = Confidence.entries.getOrNull(payload[17].toInt() and 0xFF) ?: return null
            val backend = Backend.entries.getOrNull(payload[18].toInt() and 0xFF) ?: Backend.MESH_GATEWAY
            val count = payload[19].toInt() and 0xFF

            var p = 20
            val results = ArrayList<SearchResult>(count)
            repeat(count) {
                if (p + 6 > payload.size) return@repeat
                val bits = ((payload[p].toInt() and 0xFF) shl 24) or
                    ((payload[p + 1].toInt() and 0xFF) shl 16) or
                    ((payload[p + 2].toInt() and 0xFF) shl 8) or
                    (payload[p + 3].toInt() and 0xFF)
                val score = java.lang.Float.intBitsToFloat(bits)
                p += 4

                val textLen = ((payload[p].toInt() and 0xFF) shl 8) or (payload[p + 1].toInt() and 0xFF)
                p += 2
                if (p + textLen > payload.size) return@repeat
                val text = String(payload, p, textLen, Charsets.UTF_8); p += textLen

                val category = readShortString(payload, p) ?: return@repeat
                p += 1 + category.second
                val source = readShortString(payload, p) ?: return@repeat
                p += 1 + source.second
                val version = readShortString(payload, p) ?: return@repeat
                p += 1 + version.second

                results += SearchResult(text, score, category.first, source.first, version.first)
            }
            return Result(requestId, confidence, backend, results)
        } catch (e: Exception) {
            return null
        }
    }

    private fun readShortString(buf: ByteArray, at: Int): Pair<String, Int>? {
        if (at >= buf.size) return null
        val len = buf[at].toInt() and 0xFF
        if (at + 1 + len > buf.size) return null
        return String(buf, at + 1, len, Charsets.UTF_8) to len
    }
}
