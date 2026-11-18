package com.antigravity.copilot.core.logic.fuel

import com.antigravity.copilot.core.logic.Config
import kotlin.math.abs

/**
 * Phase 4 — automatic unlogged fuel, no user confirmation.
 *
 * 1. The raw tank estimate may go below 0 ("debt"). After every trip that ends with raw ≤ 0 a
 *    PROVISIONAL "unlogged consumption" entry is created. Only the part of the trip below zero
 *    is booked, so the provisional total always equals −raw (= total debt).
 * 2. At the next refill: true_before = gauge_after_litres − refill_litres,
 *    unexplained = true_before − raw_before (= true_before + total_debt while in debt).
 *    `min(unexplained, debt)` is allocated back to the provisional trips proportional to km
 *    (RESOLVED, source "unlogged refill, auto"); the remainder is real fuel that was in the tank.
 * 3. Sanity: unexplained > actual capacity is impossible → nothing is logged; mileage is nudged
 *    (≤ 15 %) and its confidence lowered instead. Small surpluses that a ≤ 15 % mileage change
 *    explains are treated as mileage error, not as a phantom refill (smaller correction wins).
 * Only quiet notices are produced, never a blocking prompt.
 */
object UnloggedFuel {

    const val SOURCE_PROVISIONAL = "unlogged consumption, auto"
    const val SOURCE_RESOLVED = "unlogged refill, auto"
    const val SOURCE_CLEARED = "unlogged consumption, cleared (implausible refill data)"

    fun onTripEnded(
        s: FuelModelState,
        tripId: Long,
        tripKm: Double,
        odoKmAtEnd: Double,
        nowMs: Long
    ): Pair<FuelModelState, UnloggedEntry?> {
        if (tripKm <= 0.0) return s to null
        // A placeholder anchor (tank never set) cannot prove the tank ran dry.
        if (s.tankUnknown) return s to null
        val raw = FuelModel.rawLitres(s, odoKmAtEnd, nowMs)
        if (raw > 0.0) return s to null
        val kmpl = FuelModel.mileage(s, nowMs).kmpl
        val uncovered = (-raw - s.totalDebtL).coerceAtLeast(0.0)
        val litres = minOf(tripKm / kmpl, uncovered)
        if (litres < 0.01) return s to null
        val entry = UnloggedEntry(
            tripId = tripId,
            timeMs = nowMs,
            km = litres * kmpl,
            litres = litres,
            status = UnloggedStatus.PROVISIONAL,
            source = SOURCE_PROVISIONAL
        )
        return s.copy(unlogged = (s.unlogged + entry).takeLast(FuelModelState.MAX_UNLOGGED)) to entry
    }

    fun onRefill(s: FuelModelState, input: RefillInput, gaugeAfterLitres: Double?): RefillOutcome {
        val now = input.timeMs
        val litres = input.litres
        val debt = s.totalDebtL
        val truthAfter = when {
            input.fullTank -> s.actualCapacityL
            gaugeAfterLitres != null -> gaugeAfterLitres
            else -> null
        }
        if (truthAfter == null || s.tankUnknown) {
            // No gauge reading and not full, or an unknown start level (tank never set): nothing to
            // reconcile against; keep provisional entries and never invent unlogged fuel.
            return RefillOutcome(FuelModel.applyRefill(s, input, if (s.tankUnknown) gaugeAfterLitres else null), litres, null, null)
        }

        val rawBefore = FuelModel.rawLitres(s, input.odoKm, now)
        val trueBefore = (truthAfter - litres).coerceAtLeast(0.0)
        val unexplained = trueBefore - rawBefore
        val provisional = s.unlogged.filter { it.status == UnloggedStatus.PROVISIONAL }

        // --- Impossible: more unexplained fuel than the tank can hold -> do not log it.
        if (unexplained > s.actualCapacityL) {
            val nudged = nudgeMileage(s, input.odoKm, unexplained, now)
                .copy(confidencePenalty = (s.confidencePenalty + Config.MILEAGE_CONFIDENCE_PENALTY).coerceAtMost(0.9))
            val cleared = provisional.map { it.copy(status = UnloggedStatus.RESOLVED, resolvedLitres = 0.0, source = SOURCE_CLEARED) }
            val withCleared = nudged.copy(unlogged = replace(nudged.unlogged, cleared))
            val next = FuelModel.applyRefill(withCleared, input, gaugeAfterLitres)
            return RefillOutcome(
                state = next,
                litres = litres,
                trueBeforeL = trueBefore,
                unexplainedL = unexplained,
                resolved = cleared,
                notice = FuelNotice(
                    "Refill did not match the tank model (≈${unexplained.toInt()} L unexplained > " +
                        "${s.actualCapacityL.toInt()} L tank). Nothing was logged; mileage confidence lowered."
                )
            )
        }

        if (unexplained > Config.UNLOGGED_EPSILON_L) {
            val mileageExplains = debt <= Config.UNLOGGED_EPSILON_L &&
                relativeMileageChange(s, input.odoKm, unexplained, now) <= Config.UNLOGGED_MAX_MILEAGE_CORRECTION
            if (mileageExplains) {
                // Smaller correction: the model was slightly pessimistic; segments will absorb it.
                return RefillOutcome(FuelModel.applyRefill(s, input, gaugeAfterLitres), litres, trueBefore, unexplained)
            }
            // Unlogged refill explanation.
            val allocate = minOf(unexplained, debt)
            val totalKm = provisional.sumOf { it.km }.takeIf { it > 0 } ?: 1.0
            val resolved = provisional.map {
                it.copy(
                    status = UnloggedStatus.RESOLVED,
                    resolvedLitres = allocate * it.km / totalKm,
                    source = SOURCE_RESOLVED
                )
            }
            val withResolved = s.copy(unlogged = replace(s.unlogged, resolved))
            val next = FuelModel.applyRefill(withResolved, input, gaugeAfterLitres, extraUnloggedL = unexplained)
            val n = resolved.size
            return RefillOutcome(
                state = next,
                litres = litres,
                trueBeforeL = trueBefore,
                unexplainedL = unexplained,
                resolved = resolved,
                autoUnloggedRefillL = unexplained,
                notice = FuelNotice(
                    "≈${"%.1f".format(unexplained)} L of unlogged fuel detected" +
                        (if (n > 0) " and assigned to $n trip${if (n == 1) "" else "s"}" else "") + " automatically."
                )
            )
        }

        // Less (or as much) fuel than expected: real consumption; mileage segments adapt.
        return RefillOutcome(FuelModel.applyRefill(s, input, gaugeAfterLitres), litres, trueBefore, unexplained)
    }

    /** |implied − current| / current mileage if [surplusL] were explained by mileage error alone. */
    fun relativeMileageChange(s: FuelModelState, odoKm: Double, surplusL: Double, nowMs: Long): Double {
        val km = (odoKm - s.anchorOdoKm).coerceAtLeast(0.0)
        val kmpl = FuelModel.mileage(s, nowMs).kmpl
        val usedModel = km / kmpl
        val usedImplied = usedModel - surplusL
        if (km <= 0.0 || usedImplied <= 0.0) return Double.POSITIVE_INFINITY
        return abs(km / usedImplied - kmpl) / kmpl
    }

    /** Move mileage towards what the data implies, by at most [Config.UNLOGGED_MAX_MILEAGE_CORRECTION]. */
    private fun nudgeMileage(s: FuelModelState, odoKm: Double, surplusL: Double, nowMs: Long): FuelModelState {
        val km = (odoKm - s.anchorOdoKm).coerceAtLeast(0.0)
        val kmpl = FuelModel.mileage(s, nowMs).kmpl
        val usedImplied = km / kmpl - surplusL
        val maxC = Config.UNLOGGED_MAX_MILEAGE_CORRECTION
        val factor = if (km > 0 && usedImplied > 0) ((km / usedImplied) / kmpl).coerceIn(1 - maxC, 1 + maxC) else 1 + maxC
        return s.copy(mileageBias = (s.mileageBias * factor).coerceIn(0.5, 2.0))
    }

    private fun replace(all: List<UnloggedEntry>, updated: List<UnloggedEntry>): List<UnloggedEntry> {
        val byKey = updated.associateBy { it.tripId to it.timeMs }
        return all.map { byKey[it.tripId to it.timeMs] ?: it }
    }
}
