package com.MeshLink.android.india

import android.util.Log
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader

/**
 * A disaster alert in the Common Alerting Protocol format — the standard India's national alert
 * system is built on.
 *
 * ## Why CAP, and why relay it over the mesh
 *
 * NDMA runs a CAP-based Integrated Alert System (the SACHET portal and app) that pushes cyclone,
 * flood and extreme-weather warnings nationwide, in 19+ Indian languages. It reaches people by SMS
 * and cell broadcast — both of which need a working cellular network.
 *
 * That's the gap. The moment towers are destroyed or the network is cut, the official warning
 * pipeline stops exactly when the warning matters most. In Wayanad in July 2024 the telecom layer
 * was gone and ham radio operators improvised the rescue network.
 *
 * So: pull official CAP alerts whenever any device has connectivity, then flood them peer-to-peer
 * across the BLE mesh. One phone that caught the warning before the network died can carry it to
 * everyone else, hop by hop, with no infrastructure at all. Alerts are signed like every other
 * packet, so a relayed warning stays attributable and can't be forged mid-flight.
 *
 * CAP is an open OASIS standard, which is what makes this possible without proprietary integration.
 *
 * Reference: https://sachet.ndma.gov.in/
 */
data class CapAlert(
    /** Unique alert identifier from the issuing agency. */
    val identifier: String,
    /** Issuing authority, e.g. an IMD or state disaster-management office. */
    val sender: String,
    /** ISO-8601 issue time as sent. */
    val sent: String,
    /** Actual / Exercise / System / Test / Draft. */
    val status: String,
    /** Alert / Update / Cancel / Ack / Error. */
    val msgType: String,
    /** Met / Geo / Fire / Health / Safety / Rescue / etc. */
    val category: String,
    /** Short headline — what shows in the alert banner. */
    val event: String,
    /** Extreme / Severe / Moderate / Minor / Unknown. */
    val severity: String,
    /** Immediate / Expected / Future / Past / Unknown. */
    val urgency: String,
    /** Observed / Likely / Possible / Unlikely / Unknown. */
    val certainty: String,
    /** Human-readable description of the hazard. */
    val description: String,
    /** What people are being told to do. */
    val instruction: String,
    /** Plain-text area description, e.g. district names. */
    val areaDesc: String,
    /** Language of this alert block, e.g. "en-IN", "hi-IN". */
    val language: String,
) {

    /** Highest-priority alerts — these justify overriding do-not-disturb and interrupting the user. */
    val isCritical: Boolean
        get() = severity.equals("Extreme", true) || severity.equals("Severe", true)

    /** Needs action now rather than later. */
    val isImmediate: Boolean get() = urgency.equals("Immediate", true)

    /**
     * Compact wire form for mesh relay. The full CAP XML is far too heavy for BLE — a single
     * fragment budget is a few hundred bytes — so only the fields a person actually needs to act on
     * are carried, pipe-delimited. The identifier is preserved so a device that later regains
     * connectivity can fetch the authoritative original.
     */
    fun toMeshPayload(): String = listOf(
        identifier, sender, sent, severity, urgency, event, areaDesc, instruction, language,
    ).joinToString(FIELD_SEP) { it.replace(FIELD_SEP, " ") }

    companion object {
        private const val TAG = "CapAlert"
        private const val FIELD_SEP = "\u001F" // unit separator: won't appear in alert prose

        /** Marker prefix so mesh receivers can recognise a relayed alert. */
        const val MESH_PREFIX = "CAP:"

        fun fromMeshPayload(payload: String): CapAlert? {
            val body = payload.removePrefix(MESH_PREFIX)
            val f = body.split(FIELD_SEP)
            if (f.size < 9) return null
            return CapAlert(
                identifier = f[0],
                sender = f[1],
                sent = f[2],
                status = "Actual",
                msgType = "Alert",
                category = "Met",
                event = f[5],
                severity = f[3],
                urgency = f[4],
                certainty = "Unknown",
                description = "",
                instruction = f[7],
                areaDesc = f[6],
                language = f[8],
            )
        }

        /**
         * Parse a CAP 1.2 XML document. Deliberately lenient: real feeds vary in namespace usage
         * and optional elements, and a partially-understood warning is far better than none.
         */
        fun parseXml(xml: String): CapAlert? {
            return try {
                val parser = XmlPullParserFactory.newInstance().apply {
                    isNamespaceAware = true
                }.newPullParser()
                parser.setInput(StringReader(xml))

                val v = mutableMapOf<String, String>()
                var current: String? = null

                while (parser.eventType != XmlPullParser.END_DOCUMENT) {
                    when (parser.eventType) {
                        XmlPullParser.START_TAG -> current = parser.name
                        XmlPullParser.TEXT -> {
                            val text = parser.text?.trim()
                            if (!text.isNullOrEmpty() && current != null && !v.containsKey(current)) {
                                v[current!!] = text
                            }
                        }
                        XmlPullParser.END_TAG -> current = null
                    }
                    parser.next()
                }

                val identifier = v["identifier"] ?: return null
                CapAlert(
                    identifier = identifier,
                    sender = v["sender"] ?: "unknown",
                    sent = v["sent"] ?: "",
                    status = v["status"] ?: "Actual",
                    msgType = v["msgType"] ?: "Alert",
                    category = v["category"] ?: "Met",
                    event = v["event"] ?: "Alert",
                    severity = v["severity"] ?: "Unknown",
                    urgency = v["urgency"] ?: "Unknown",
                    certainty = v["certainty"] ?: "Unknown",
                    description = v["description"] ?: "",
                    instruction = v["instruction"] ?: "",
                    areaDesc = v["areaDesc"] ?: "",
                    language = v["language"] ?: "en-IN",
                )
            } catch (e: Exception) {
                Log.w(TAG, "CAP parse failed: ${e.message}")
                null
            }
        }
    }
}
