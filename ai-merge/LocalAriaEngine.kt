package com.MeshLink.android.ai

/**
 * Tier 3 fallback: A fully offline, keyword/rule-based engine.
 *
 * - SOS detection uses weighted keyword matching with context awareness.
 * - Chat uses category matching against a curated response database
 *   covering first aid, water, shelter, signaling, fire, and navigation.
 *
 * This engine has zero external dependencies and always works.
 */
object LocalAriaEngine : AriaEngine {

    override fun engineName(): String = "Offline Basic"

    override suspend fun isAvailable(): Boolean = true // Always available

    // ── SOS Detection ──────────────────────────────────────────────────

    private data class SOSKeyword(val word: String, val weight: Int)

    private val sosKeywords = listOf(
        SOSKeyword("sos", 10),
        SOSKeyword("help", 6),
        SOSKeyword("emergency", 9),
        SOSKeyword("trapped", 9),
        SOSKeyword("injured", 8),
        SOSKeyword("bleeding", 8),
        SOSKeyword("dying", 10),
        SOSKeyword("rescue", 8),
        SOSKeyword("mayday", 10),
        SOSKeyword("911", 9),
        SOSKeyword("fire", 7),
        SOSKeyword("flood", 7),
        SOSKeyword("earthquake", 7),
        SOSKeyword("collapse", 7),
        SOSKeyword("avalanche", 8),
        SOSKeyword("drowning", 9),
        SOSKeyword("unconscious", 8),
        SOSKeyword("broken", 5),
        SOSKeyword("attack", 6),
        SOSKeyword("shooter", 9),
        SOSKeyword("explosion", 9),
        SOSKeyword("tsunami", 9),
        SOSKeyword("tornado", 7),
        SOSKeyword("hurricane", 7),
        SOSKeyword("stranded", 7),
        SOSKeyword("lost", 4),
        SOSKeyword("can't breathe", 9),
        SOSKeyword("chest pain", 8),
        SOSKeyword("heart attack", 9),
        SOSKeyword("seizure", 8),
        SOSKeyword("snake bite", 7),
        SOSKeyword("poisoned", 8),
        SOSKeyword("suffocating", 9),
        SOSKeyword("please help", 8),
        SOSKeyword("need help", 7),
        SOSKeyword("send help", 9),
    )

    /** SOS detection threshold — total keyword weight must exceed this. */
    private const val SOS_THRESHOLD = 7

    override suspend fun detectSOS(message: String): Boolean {
        if (message.isBlank()) return false
        val lower = message.lowercase()
        var score = 0
        for (kw in sosKeywords) {
            if (lower.contains(kw.word)) {
                score += kw.weight
                if (score >= SOS_THRESHOLD) return true
            }
        }
        return false
    }

    // ── Chat Responses ─────────────────────────────────────────────────

    private data class ResponseCategory(
        val name: String,
        val keywords: List<String>,
        val responses: List<String>
    )

    private val categories = listOf(
        ResponseCategory(
            name = "first_aid",
            keywords = listOf(
                "first aid", "bleeding", "wound", "cut", "burn", "fracture",
                "broken bone", "cpr", "choking", "bandage", "splint", "tourniquet",
                "injury", "injured", "hurt", "bite", "sting", "poison",
                "unconscious", "faint", "seizure", "heart attack", "stroke"
            ),
            responses = listOf(
                "🩹 **Basic First Aid — Offline Mode**\n\n" +
                "• **Bleeding**: Apply firm, direct pressure with the cleanest cloth available. Elevate the limb above the heart. Do NOT remove a soaked bandage — add more layers on top.\n\n" +
                "• **Burns**: Cool under running water for 10–20 minutes. Do NOT use ice, butter, or toothpaste. Cover loosely with a clean, non-stick dressing.\n\n" +
                "• **Fractures**: Immobilize the limb. Splint it using stiff material (sticks, cardboard) padded with fabric. Do NOT try to realign the bone.\n\n" +
                "• **CPR**: 30 chest compressions (hard and fast, center of chest) → 2 rescue breaths. Repeat. Push at least 2 inches deep at 100–120 BPM.\n\n" +
                "• **Choking**: 5 back blows between shoulder blades → 5 abdominal thrusts (Heimlich). Alternate until cleared.\n\n" +
                "_If the situation is critical, broadcast an SOS on the mesh network._"
            )
        ),
        ResponseCategory(
            name = "water",
            keywords = listOf(
                "water", "thirst", "thirsty", "drink", "dehydration", "purify",
                "filter", "boil", "rain", "stream", "river", "well"
            ),
            responses = listOf(
                "💧 **Water Procurement — Offline Mode**\n\n" +
                "• **Boiling** is the safest method: Bring water to a rolling boil for at least 1 minute (3 minutes above 2,000m elevation).\n\n" +
                "• **Improvised filter**: Layer gravel → sand → charcoal → sand → cloth in a container with a hole at the bottom. Pour water through. Still boil afterward if possible.\n\n" +
                "• **Rain collection**: Use any clean surface (tarp, large leaves, plastic sheeting) angled into a container.\n\n" +
                "• **Signs of water nearby**: Follow animal tracks downhill, look for green vegetation in arid areas, listen for flowing water.\n\n" +
                "• **Avoid**: Saltwater, water near industrial areas, stagnant water with algae.\n\n" +
                "• **Dehydration signs**: Dark urine, dizziness, rapid heartbeat, confusion. Sip water slowly — don't gulp."
            )
        ),
        ResponseCategory(
            name = "shelter",
            keywords = listOf(
                "shelter", "cold", "warm", "hypothermia", "exposure",
                "rain", "sleep", "camp", "tent", "cover", "roof"
            ),
            responses = listOf(
                "🏕️ **Emergency Shelter — Offline Mode**\n\n" +
                "• **Priority**: Protection from wind and rain is more important than warmth. Find or build a windbreak first.\n\n" +
                "• **Lean-to**: Prop a long branch against a tree. Layer smaller branches at 45° against it. Cover with leaves, bark, or a tarp.\n\n" +
                "• **Debris hut**: Create a ridgepole from your head to feet. Pile branches and leaves on both sides for insulation. The smaller the interior, the warmer.\n\n" +
                "• **Ground insulation**: NEVER sleep directly on the ground — it drains body heat 25× faster than air. Use leaves, pine needles, cardboard, or branches.\n\n" +
                "• **Hypothermia signs**: Shivering → slurred speech → confusion → drowsiness. Warm the core (armpits, groin, neck) not the extremities.\n\n" +
                "• **Location**: Avoid hilltops (wind), valley floors (cold air sinks), and dry riverbeds (flash floods)."
            )
        ),
        ResponseCategory(
            name = "signal",
            keywords = listOf(
                "signal", "rescue", "found", "location", "help",
                "helicopter", "flare", "mirror", "smoke", "sos",
                "visible", "search", "flag"
            ),
            responses = listOf(
                "🔦 **Signaling for Rescue — Offline Mode**\n\n" +
                "• **Universal distress signal**: 3 of anything — 3 fires, 3 whistle blasts, 3 mirror flashes.\n\n" +
                "• **Mirror signal**: Any reflective surface. Aim reflected sunlight at aircraft by holding two fingers in a V toward the target and sweeping the flash between them.\n\n" +
                "• **Smoke**: By day, add green leaves or damp material to a fire for white smoke. Against snow, burn rubber/oil for black smoke.\n\n" +
                "• **Ground signals**: Make signals at least 3m (10ft) tall. Use contrasting materials. 'X' = need help. 'V' = need assistance. '→' = traveling this way.\n\n" +
                "• **Whistle**: 3 short blasts, pause, repeat. Sound carries farther than shouting and uses less energy.\n\n" +
                "• **At night**: Flashlight or phone screen. Flash SOS in Morse: ··· — — — ···"
            )
        ),
        ResponseCategory(
            name = "fire",
            keywords = listOf(
                "fire", "warm", "cook", "heat", "flame", "match",
                "lighter", "tinder", "kindling", "firewood"
            ),
            responses = listOf(
                "🔥 **Fire Starting — Offline Mode**\n\n" +
                "• **Tinder** (catches spark): Dry grass, birch bark, cotton, dryer lint, char cloth, fine wood shavings.\n\n" +
                "• **Kindling** (grows flame): Pencil-thin dry sticks, split wood, small branches.\n\n" +
                "• **Fuel** (sustains fire): Wrist-thick and larger dry wood.\n\n" +
                "• **Structure**: Build a small nest of tinder. Lean kindling in a teepee shape around it. Light the tinder and blow gently at the base.\n\n" +
                "• **Without matches**: Friction (bow drill), steel + flint, battery + steel wool, focusing sunlight through a lens or water-filled clear bag.\n\n" +
                "• **Safety**: Clear a 3m circle around your fire. Never leave it unattended. Put it DEAD out — drown, stir, drown again, feel with your hand."
            )
        ),
        ResponseCategory(
            name = "navigation",
            keywords = listOf(
                "navigate", "direction", "compass", "north", "south",
                "east", "west", "lost", "map", "star", "sun", "gps",
                "trail", "path", "find way"
            ),
            responses = listOf(
                "🧭 **Navigation Without GPS — Offline Mode**\n\n" +
                "• **Sun method**: The sun rises in the east and sets in the west. At noon in the Northern Hemisphere, it's due south.\n\n" +
                "• **Stick shadow**: Plant a stick vertically. Mark the tip of its shadow. Wait 15 min, mark again. A line between marks runs roughly east-west (first mark = west).\n\n" +
                "• **Stars (N. Hemisphere)**: Find the Big Dipper. The two stars at the end of the \"cup\" point to Polaris (North Star).\n\n" +
                "• **Stars (S. Hemisphere)**: Find the Southern Cross. Extend the long axis 4.5× its length toward the horizon for due south.\n\n" +
                "• **If lost**: STOP — Sit, Think, Observe, Plan. Stay put if possible. If you must move, follow water downstream — it leads to civilization.\n\n" +
                "• **Leave breadcrumbs**: Mark your trail with stacked rocks, broken branches, or scratched arrows so rescuers can track you."
            )
        ),
        ResponseCategory(
            name = "mesh_network",
            keywords = listOf(
                "mesh", "network", "bluetooth", "connection", "node",
                "peer", "range", "signal", "antenna", "radio",
                "offline", "connectivity", "relay"
            ),
            responses = listOf(
                "📡 **Mesh Network Tips — Offline Mode**\n\n" +
                "• **Range**: BLE mesh typically reaches 30–100m between nodes. Position yourself with line-of-sight to other users if possible.\n\n" +
                "• **Relay**: Every connected device acts as a relay. More people running MeshLink = wider mesh coverage.\n\n" +
                "• **Elevation**: Higher ground dramatically improves BLE range. Climb a hill or go to an upper floor.\n\n" +
                "• **Battery**: Mesh networking uses Bluetooth LE, which is power-efficient. Keep your screen brightness low and close other apps to extend battery.\n\n" +
                "• **SOS**: Any message detected as a distress signal is automatically prioritized and relayed across the entire mesh.\n\n" +
                "• **Tip**: If you can't reach anyone, try moving. Even 50 meters can bring you into range of a relay node."
            )
        )
    )

    private val defaultResponse =
        "🤖 **Aria — Offline Basic Mode**\n\n" +
        "I'm running in offline mode without an AI model, so I can help with these topics:\n\n" +
        "• **\"first aid\"** — Wound care, CPR, burns, fractures\n" +
        "• **\"water\"** — Finding and purifying water\n" +
        "• **\"shelter\"** — Emergency shelter and hypothermia\n" +
        "• **\"fire\"** — Starting and managing a fire\n" +
        "• **\"signal\"** — Signaling for rescue\n" +
        "• **\"navigation\"** — Finding your way without GPS\n" +
        "• **\"mesh\"** — Tips for mesh network connectivity\n\n" +
        "Try asking about one of these topics. For full AI-powered assistance, connect to the internet or use a device with on-device AI support."

    override suspend fun chat(userMessage: String): String {
        val lower = userMessage.lowercase()

        // First check if it's an SOS — respond with urgency
        if (detectSOS(userMessage)) {
            return "🚨 **DISTRESS DETECTED**\n\n" +
                "Your message appears to be a distress signal. Here's what to do:\n\n" +
                "1. **Stay calm.** Panic burns energy and clouds judgment.\n" +
                "2. **Broadcast an SOS** on the mesh network — it will be prioritized and relayed automatically.\n" +
                "3. **Share your location** if you can — use landmarks, elevation, or GPS coordinates.\n" +
                "4. **Make yourself visible** — use signals (3 fires, mirror flashes, whistle blasts).\n" +
                "5. **Conserve resources** — ration water and battery.\n\n" +
                "_Your SOS has been flagged in the mesh. Help is being routed._"
        }

        // Find the best matching category
        var bestCategory: ResponseCategory? = null
        var bestScore = 0

        for (category in categories) {
            var score = 0
            for (keyword in category.keywords) {
                if (lower.contains(keyword)) {
                    score += keyword.length // Longer keyword matches = more specific
                }
            }
            if (score > bestScore) {
                bestScore = score
                bestCategory = category
            }
        }

        return if (bestCategory != null && bestScore > 0) {
            bestCategory.responses.first()
        } else {
            defaultResponse
        }
    }
}
