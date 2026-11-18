package com.antigravity.copilot.core.fuel.station

/**
 * Hybrid delayed mileage engine (dual-anchor state machine). Pure and deterministic: it replays
 * the whole refuel history every time, so edits, restores and late entries can never leave a
 * half-applied incremental state behind.
 *
 * - Rule A (FULL_TANK → FULL_TANK): fuel = L_B + ΣTopUp, distance = Odo_B − Odo_A → station A.
 * - Rule B (AT_RESERVE → AT_RESERVE): fuel = L_A + ΣTopUp, distance = Res_B − Res_A → station A,
 *   where Res is the "Reserve light ON" marker odometer when one was tapped before that refuel,
 *   else the pump odometer.
 * - Rule C (pattern switch, e.g. AT_RESERVE → FULL_TANK): the open anchor is closed without a
 *   mileage (no station rating touched) and the new log becomes the anchor. If no cycle closed in
 *   the last 3 refuels, a 3-log rolling trend is reported for display only.
 *
 * Statuses: an open anchor is PENDING_EVALUATION, top-ups inside an open cycle are ACCUMULATING,
 * everything else is COMPLETED (anchors carry their km/L once the cycle closes cleanly).
 */
object HybridMileageEngine {
    const val BASELINE_KMPL = 16.5
    const val MIN_VALID_KMPL = 3.0
    const val MAX_VALID_KMPL = 60.0
    /** Station average = mean of its last N completed cycles. */
    const val STATION_WINDOW = 5
    const val TREND_WINDOW = 3

    data class Entry(
        val id: Long,
        val stationId: Long?,
        val mode: RefuelMode,
        val liters: Double,
        val odometer: Double
    )

    data class Outcome(val status: RefuelLogStatus, val mileage: Double?)

    data class Cycle(val anchorLogId: Long, val stationId: Long?, val mileage: Double, val rule: Char)

    data class Result(
        val outcomes: Map<Long, Outcome>,
        val cycles: List<Cycle>,
        /** Rule C 3-log rolling trend (km/L), only when no cycle closed in the last 3 refuels. */
        val trendKmpl: Double?,
        /** Mode of the open anchor, if any (UI hint). */
        val openAnchorMode: RefuelMode?,
        /** Odometer of a "Reserve light ON" tap not yet consumed by a refuel. */
        val pendingReserveOdometer: Double?
    )

    private class Anchor(val entry: Entry, val refOdometer: Double)

    fun evaluate(entries: List<Entry>): Result {
        val outcomes = LinkedHashMap<Long, Outcome>()
        val cycles = ArrayList<Cycle>()
        var anchor: Anchor? = null
        val topUps = ArrayList<Entry>()
        var pendingReserve: Double? = null
        var refuelsSinceClosure = 0
        val recent = ArrayDeque<Entry>()

        fun closeWithout(a: Anchor?) {
            if (a != null) outcomes[a.entry.id] = Outcome(RefuelLogStatus.COMPLETED, null)
            topUps.forEach { outcomes[it.id] = Outcome(RefuelLogStatus.COMPLETED, null) }
            topUps.clear()
        }

        fun close(a: Anchor, distance: Double, fuel: Double, rule: Char): Boolean {
            val kmpl = if (fuel > 0.0 && distance > 0.0) distance / fuel else 0.0
            val valid = kmpl in MIN_VALID_KMPL..MAX_VALID_KMPL
            outcomes[a.entry.id] = Outcome(RefuelLogStatus.COMPLETED, if (valid) kmpl else null)
            topUps.forEach { outcomes[it.id] = Outcome(RefuelLogStatus.COMPLETED, null) }
            topUps.clear()
            if (valid) cycles += Cycle(a.entry.id, a.entry.stationId, kmpl, rule)
            return valid
        }

        for (e in entries) {
            if (e.mode == RefuelMode.RESERVE_MARKER) {
                pendingReserve = e.odometer
                outcomes[e.id] = Outcome(RefuelLogStatus.COMPLETED, null)
                continue
            }
            // A marker locks the odometer at the moment the light came on (before the pump).
            val reserveOdo = pendingReserve?.takeIf { it <= e.odometer + 0.5 } ?: e.odometer
            pendingReserve = null
            var closed = false

            when (e.mode) {
                RefuelMode.TOP_UP -> {
                    if (anchor != null) {
                        topUps += e
                        outcomes[e.id] = Outcome(RefuelLogStatus.ACCUMULATING, null)
                    } else {
                        outcomes[e.id] = Outcome(RefuelLogStatus.COMPLETED, null)
                    }
                }
                RefuelMode.FULL_TANK -> {
                    val a = anchor
                    if (a != null && a.entry.mode == RefuelMode.FULL_TANK) {
                        closed = close(a, e.odometer - a.refOdometer, e.liters + topUps.sumOf { it.liters }, 'A')
                    } else {
                        closeWithout(a)
                    }
                    anchor = Anchor(e, e.odometer)
                    outcomes[e.id] = Outcome(RefuelLogStatus.PENDING_EVALUATION, null)
                }
                RefuelMode.AT_RESERVE -> {
                    val a = anchor
                    if (a != null && a.entry.mode == RefuelMode.AT_RESERVE) {
                        closed = close(a, reserveOdo - a.refOdometer, a.entry.liters + topUps.sumOf { it.liters }, 'B')
                    } else {
                        closeWithout(a)
                    }
                    anchor = Anchor(e, reserveOdo)
                    outcomes[e.id] = Outcome(RefuelLogStatus.PENDING_EVALUATION, null)
                }
                RefuelMode.RESERVE_MARKER -> Unit
            }
            refuelsSinceClosure = if (closed) 0 else refuelsSinceClosure + 1
            recent.addLast(e)
            if (recent.size > TREND_WINDOW) recent.removeFirst()
        }

        val trend = if (refuelsSinceClosure >= TREND_WINDOW && recent.size == TREND_WINDOW) {
            val distance = recent.last().odometer - recent.first().odometer
            val fuel = recent.take(TREND_WINDOW - 1).sumOf { it.liters }
            (if (fuel > 0.0) distance / fuel else 0.0).takeIf { it in MIN_VALID_KMPL..MAX_VALID_KMPL }
        } else null

        return Result(outcomes, cycles, trend, anchor?.entry?.mode, pendingReserve)
    }

    /** Rolling station average (mean of the last [STATION_WINDOW] cycles) and cycle count. */
    fun stationAverages(cycles: List<Cycle>): Map<Long, Pair<Double, Int>> =
        cycles.filter { it.stationId != null }
            .groupBy { it.stationId!! }
            .mapValues { (_, list) -> list.takeLast(STATION_WINDOW).map { it.mileage }.average() to list.size }

    /**
     * ≥ +5 % over baseline → EXCELLENT, below −8 % → POOR, otherwise AVERAGE (the −8 %…−5 % band
     * is treated as AVERAGE so one slightly weak cycle doesn't brand a pump as poor).
     */
    fun rate(averageKmpl: Double, baselineKmpl: Double = BASELINE_KMPL): StationQuality {
        if (averageKmpl <= 0.0 || baselineKmpl <= 0.0) return StationQuality.NOT_AVAILABLE
        val delta = averageKmpl / baselineKmpl - 1.0
        return when {
            delta >= 0.05 -> StationQuality.EXCELLENT
            delta < -0.08 -> StationQuality.POOR
            else -> StationQuality.AVERAGE
        }
    }
}
