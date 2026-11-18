package com.antigravity.copilot.core.logic

/**
 * Trip auto-tag (audit B8): CITY / HIGHWAY / MIXED from how the moving time was split across
 * speed bands, and COMMUTE when the same origin → destination pair already appears in at least
 * [COMMUTE_MIN_REPEATS] earlier trips.
 */
object TripTagger {
    const val CITY = "City"
    const val HIGHWAY = "Highway"
    const val MIXED = "Mixed"
    const val COMMUTE = "Commute"
    const val COMMUTE_MIN_REPEATS = 3
    const val HIGHWAY_KMH = 60.0
    const val CITY_KMH = 35.0

    /** Moving-time accumulator by speed band. */
    class SpeedHistogram {
        var slowMs = 0L; private set
        var midMs = 0L; private set
        var fastMs = 0L; private set

        fun add(speedKmh: Double, dtMs: Long) {
            if (dtMs <= 0 || speedKmh < Config.IDLE_SPEED_KMH) return
            when {
                speedKmh >= HIGHWAY_KMH -> fastMs += dtMs
                speedKmh < CITY_KMH -> slowMs += dtMs
                else -> midMs += dtMs
            }
        }

        fun reset() { slowMs = 0; midMs = 0; fastMs = 0 }
        val totalMs: Long get() = slowMs + midMs + fastMs
    }

    fun speedTag(h: SpeedHistogram): String {
        val total = h.totalMs
        if (total <= 0L) return ""
        val fast = h.fastMs.toDouble() / total
        val slow = h.slowMs.toDouble() / total
        return when {
            fast >= 0.5 -> HIGHWAY
            slow + h.midMs.toDouble() / total * 0.5 >= 0.7 -> CITY
            else -> MIXED
        }
    }

    fun tag(speedTag: String, startName: String, endName: String, sameRouteEarlierTrips: Int): String {
        val commute = startName.isNotBlank() && endName.isNotBlank() && startName != endName &&
            sameRouteEarlierTrips >= COMMUTE_MIN_REPEATS
        return when {
            commute && speedTag.isNotBlank() -> "$COMMUTE · $speedTag"
            commute -> COMMUTE
            else -> speedTag
        }
    }
}
