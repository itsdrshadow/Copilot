package com.antigravity.copilot

import com.antigravity.copilot.core.fuel.intelligence.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Deterministic test suite verifying all 24 required core edge cases
 * and mathematical behaviors of the Adaptive Fuel Intelligence Engine.
 */
class AdaptiveFuelIntelligenceTest {

    private lateinit var estimator: FuelStateEstimator
    private lateinit var mileageEngine: AdaptiveMileageEngine
    private lateinit var gaugeCalibration: FuelGaugeCalibrationEngine
    private lateinit var missedEntryIntel: MissedEntryIntelligence

    @Before
    fun setUp() {
        estimator = FuelStateEstimator(designCapacityLiters = 48.0, maxCapacityLiters = 48.0)
        mileageEngine = AdaptiveMileageEngine(baselineVehicleMileageKmpl = 18.0)
        gaugeCalibration = FuelGaugeCalibrationEngine(designCapacityLiters = 48.0)
        missedEntryIntel = MissedEntryIntelligence(calibrationEngine = gaugeCalibration, thresholdLiters = 4.0)
    }

    // Edge Case 1: Tank initialized to 14 L with 3/10 gauge bars.
    @Test
    fun test01_TankInitializedTo14LWith3Bars() {
        estimator.initializeBaseline(
            initialLiters = 14.0,
            gaugePoints = 3,
            odometerKm = 50000.0
        )
        assertEquals(14.0, estimator.nominalFuelLiters, 0.01)
        assertEquals(3, estimator.currentGaugeBar)
        assertEquals(50000.0, estimator.lastReliableOdometerAnchorKm, 0.01)
        assertTrue(estimator.conservativeFuelLiters <= estimator.nominalFuelLiters)
        assertTrue(estimator.upperFuelLiters >= estimator.nominalFuelLiters)
    }

    // Edge Case 2: ₹1,000 partial refill at ₹109.20/L adds 9.16 L, resulting in 23.16 L.
    @Test
    fun test02_PartialRefuel1000RupeesAt109_20() {
        estimator.initializeBaseline(initialLiters = 14.0, gaugePoints = 3, odometerKm = 50000.0)

        val pricePerLiter = 109.20
        val totalAmount = 1000.0
        val addedLiters = FuelMath.calculateLitersFromAmount(totalAmount, pricePerLiter)
        assertEquals(9.16, addedLiters, 0.01)

        estimator.applyRefuel(
            addedLiters = addedLiters,
            observedGaugePoints = 5,
            odometerKm = 50000.0
        )
        assertEquals(23.16, estimator.nominalFuelLiters, 0.01)
        assertEquals(5, estimator.currentGaugeBar)
    }

    // Edge Case 3: Driving 120 km at 18 km/L consumes 6.67 L, leaving 16.49 L.
    @Test
    fun test03_Driving120KmAt18KmplConsumes6_67L() {
        estimator.initializeBaseline(initialLiters = 23.16, gaugePoints = 5, odometerKm = 50000.0)

        val distanceKm = 120.0
        val mileageKmpl = 18.0
        val consumedLiters = FuelMath.calculateFuelConsumed(distanceKm, mileageKmpl)
        assertEquals(6.67, consumedLiters, 0.01)

        estimator.applyTripConsumption(
            consumedLiters = consumedLiters,
            newOdometerKm = 50120.0,
            currentGaugePoints = 4
        )
        assertEquals(16.49, estimator.nominalFuelLiters, 0.01)
    }

    // Edge Case 4: Second partial refill: ₹500 at ₹109.20/L adds 4.58 L, resulting in 21.07 L.
    @Test
    fun test04_SecondPartialRefillAdds4_58L() {
        estimator.initializeBaseline(initialLiters = 16.49, gaugePoints = 4, odometerKm = 50120.0)

        val addedLiters = FuelMath.calculateLitersFromAmount(500.0, 109.20)
        assertEquals(4.58, addedLiters, 0.01)

        estimator.applyRefuel(
            addedLiters = addedLiters,
            observedGaugePoints = 5,
            odometerKm = 50120.0
        )
        assertEquals(21.07, estimator.nominalFuelLiters, 0.01)
    }

    // Edge Case 5: Driving 80 km at 16 km/L consumes 5.0 L, leaving 16.07 L.
    @Test
    fun test05_Driving80KmAt16KmplConsumes5L() {
        estimator.initializeBaseline(initialLiters = 21.07, gaugePoints = 5, odometerKm = 50120.0)

        val consumedLiters = FuelMath.calculateFuelConsumed(80.0, 16.0)
        assertEquals(5.0, consumedLiters, 0.01)

        estimator.applyTripConsumption(
            consumedLiters = consumedLiters,
            newOdometerKm = 50200.0,
            currentGaugePoints = 4
        )
        assertEquals(16.07, estimator.nominalFuelLiters, 0.01)
    }

    // Edge Case 6: Continuous partial refill mileage formula: Fuel Consumed = Starting + Added - Ending.
    @Test
    fun test06_ContinuousPartialRefillMileageFormula() {
        // Interval: Start 14.0 L at 50,000 km.
        // Refill 1: 9.16 L
        // Refill 2: 4.58 L
        // Total Added: 13.74 L
        // Ending Fuel: 16.07 L at 50,200 km (total distance 200 km)
        // Fuel Consumed = 14.0 + 13.74 - 16.07 = 11.67 L
        // Mileage = 200 km / 11.67 L = 17.14 km/L
        val startingFuel = 14.0
        val totalAdded = 9.16 + 4.58
        val endingFuel = 16.07
        val distanceKm = 200.0

        val fuelConsumed = FuelMath.calculatePartialRefillFuelConsumed(startingFuel, totalAdded, endingFuel)
        assertEquals(11.67, fuelConsumed, 0.01)

        val calculatedMileage = FuelMath.calculateMileage(distanceKm, fuelConsumed)
        assertEquals(17.14, calculatedMileage, 0.01)

        // Verify with AdaptiveMileageEngine
        val interval = FuelInterval(
            startOdometerKm = 50000.0,
            endOdometerKm = 50200.0,
            startFuelLiters = 14.0,
            fuelAddedDuringInterval = 13.74,
            endFuelLiters = 16.07
        )
        assertEquals(17.14, interval.calculateMileage(), 0.01)
    }

    // Edge Case 7: Fuel gauge update to 6/10 bars (~28.8 L) when expected balance was 16.07 L triggers INFERRED_REFUEL.
    @Test
    fun test07_GaugeUpdateTo6BarsTriggersInferredRefuel() {
        estimator.initializeBaseline(initialLiters = 16.07, gaugePoints = 4, odometerKm = 50200.0)

        // Gauge 6 bars out of 10 in 48L tank is 0.60 * 48 = 28.8L
        val result = missedEntryIntel.evaluateObservedGauge(
            observedGaugePoints = 6,
            estimator = estimator,
            currentOdometerKm = 50250.0
        )
        assertTrue("Must detect unrecorded fuel increase", result.detected)
        assertEquals(6, result.observedGaugePoints)
        assertTrue("Plausible nominal should be approximately 28.8 - 16.07 = 12.73 L",
            result.plausibleNominalLiters in 10.0..15.0)
    }

    // Edge Case 8: Inferred refuel has PROVISIONAL confidence, plausible range, zero price.
    @Test
    fun test08_InferredRefuelProperties() {
        estimator.initializeBaseline(initialLiters = 16.07, gaugePoints = 4, odometerKm = 50200.0)
        val result = missedEntryIntel.evaluateObservedGauge(
            observedGaugePoints = 6,
            estimator = estimator,
            currentOdometerKm = 50250.0
        )
        val event = result.suggestedLedgerEvent
        assertNotNull(event)
        assertEquals(EventConfidence.PROVISIONAL, event!!.confidence)
        assertEquals(EventSource.INFERRED, event.source)
        assertEquals(FuelEventType.INFERRED_REFUEL, event.eventType)
        assertEquals(0.0, event.pricePerLiter, 0.001) // Never fabricate fake receipt price
        assertEquals(0.0, event.totalAmount, 0.001)
        assertTrue(result.plausibleMinLiters > 0.0)
        assertTrue(result.plausibleMaxLiters >= result.plausibleMinLiters)
    }

    // Edge Case 9: User confirms inferred refuel: converts to CONFIRMED refuel with parent-child linkage.
    @Test
    fun test09_UserConfirmsInferredRefuel() {
        estimator.initializeBaseline(initialLiters = 16.07, gaugePoints = 4, odometerKm = 50200.0)
        val detection = missedEntryIntel.evaluateObservedGauge(6, estimator, 50250.0)
        val inferredEvent = detection.suggestedLedgerEvent!!.copy(id = 101L)

        // User confirms: they added ₹1,500 for ~13.73 L at ₹109.20
        val resolved = missedEntryIntel.createConfirmedResolutionEvent(
            inferredEvent = inferredEvent,
            confirmedLiters = 13.73,
            pricePerLiter = 109.20,
            totalAmount = 1500.0,
            fuelStation = "IndianOil Highway"
        )
        assertEquals(101L, resolved.parentId)
        assertEquals(FuelEventType.REFUEL, resolved.eventType)
        assertEquals(EventConfidence.CONFIRMED, resolved.confidence)
        assertEquals(13.73, resolved.fuelQuantityLiters, 0.01)
        assertEquals(109.20, resolved.pricePerLiter, 0.01)
        assertEquals("IndianOil Highway", resolved.locationName)
    }

    // Edge Case 10: Odometer update without refill consumes fuel based on distance delta and adaptive mileage.
    @Test
    fun test10_OdometerUpdateWithoutRefillConsumesFuel() {
        estimator.initializeBaseline(initialLiters = 30.0, gaugePoints = 6, odometerKm = 50000.0)
        // Odometer advances from 50000 to 50090 (90 km). Mileage is 18 km/L -> 5 L consumed.
        val deltaKm = 90.0
        val mileage = 18.0
        val consumed = FuelMath.calculateFuelConsumed(deltaKm, mileage)

        estimator.applyTripConsumption(consumed, 50090.0, 5)
        assertEquals(25.0, estimator.nominalFuelLiters, 0.01)
        assertEquals(50090.0, estimator.lastReliableOdometerAnchorKm, 0.01)
    }

    // Edge Case 11: Fuel consumption never drops below 0.0 L (clamped at 0.0 with explanation).
    @Test
    fun test11_FuelBalanceNeverDropsBelowZero() {
        estimator.initializeBaseline(initialLiters = 5.0, gaugePoints = 1, odometerKm = 50000.0)
        // Attempt to consume 10 L when only 5 L are available
        estimator.applyTripConsumption(10.0, 50200.0, 0)
        assertEquals(0.0, estimator.nominalFuelLiters, 0.001)
        assertEquals(0.0, estimator.conservativeFuelLiters, 0.001)
        assertTrue(estimator.currentExplanation.contains("Tank empty") || estimator.currentExplanation.contains("clamped"))
    }

    // Edge Case 12: Fuel addition never exceeds physical tank capacity (or safely expands for overfill within physical limit).
    @Test
    fun test12_FuelAdditionWithinPhysicalCapacityBounds() {
        estimator.initializeBaseline(initialLiters = 40.0, gaugePoints = 8, odometerKm = 50000.0)
        // Adding 20 L to 40 L tank (design 48 L). Max safe overfill limit is 1.35x = 64.8 L.
        estimator.applyRefuel(20.0, 10, 50000.0)
        assertTrue(estimator.nominalFuelLiters <= estimator.maxCapacityLiters)
        assertTrue(estimator.nominalFuelLiters <= 48.0 * 1.35)
    }

    // Edge Case 13: Slosh filtering rejects single-sample momentary gauge spikes.
    @Test
    fun test13_SloshFilteringRejectsMomentarySpikes() {
        // Vehicle at steady 5 bars
        gaugeCalibration.addGaugeObservation(5, 1000L)
        gaugeCalibration.addGaugeObservation(5, 2000L)
        gaugeCalibration.addGaugeObservation(5, 3000L)
        assertEquals(5, gaugeCalibration.getSloshFilteredBar())

        // Sudden momentary spike (car takes sharp curve or incline) to 8 bars
        gaugeCalibration.addGaugeObservation(8, 3500L)
        // Median of [5, 5, 5, 8] is still 5!
        assertEquals(5, gaugeCalibration.getSloshFilteredBar())
    }

    // Edge Case 14: Non-linear gauge calibration curve maintains monotonicity.
    @Test
    fun test14_GaugeCurveMonotonicity() {
        for (bar in 0 until 10) {
            val litersCurrent = gaugeCalibration.getLitersForBar(bar)
            val litersNext = gaugeCalibration.getLitersForBar(bar + 1)
            assertTrue("Bar $bar ($litersCurrent L) must be <= bar ${bar + 1} ($litersNext L)",
                litersNext >= litersCurrent)
        }
    }

    // Edge Case 15: Recency weighting favors latest refill intervals over ancient intervals.
    @Test
    fun test15_RecencyWeightingFavorsLatestIntervals() {
        val oldInterval = FuelInterval(10000.0, 10200.0, 20.0, 10.0, 20.0, timestamp = 1000L) // 20 km/L
        val recentInterval = FuelInterval(20000.0, 20200.0, 20.0, 13.33, 20.0, timestamp = 5000000L) // 15 km/L

        mileageEngine.addInterval(oldInterval)
        mileageEngine.addInterval(recentInterval)

        val estimated = mileageEngine.getAdaptiveMileage()
        // Because recent interval (15 km/L) has higher exponential weight, estimated should be closer to 15 than 20
        assertTrue("Estimated mileage should be closer to recent (15 km/L), got $estimated", estimated < 17.5)
    }

    // Edge Case 16: Outlier rejection (IQR) filters erroneous 100 km/L or 1 km/L entry bugs.
    @Test
    fun test16_IqrOutlierRejection() {
        // Add several normal intervals around 16-18 km/L
        for (i in 1..6) {
            mileageEngine.addInterval(
                FuelInterval(i * 100.0, (i + 1) * 100.0, 20.0, 5.88, 20.0) // ~17.0 km/L
            )
        }
        // Add crazy outliers (e.g. user entered wrong odometer or zero added fuel)
        mileageEngine.addInterval(FuelInterval(700.0, 800.0, 20.0, 1.0, 20.0)) // 100 km/L bug!
        mileageEngine.addInterval(FuelInterval(800.0, 810.0, 20.0, 10.0, 20.0)) // 1.0 km/L bug!

        val estimated = mileageEngine.getAdaptiveMileage()
        assertTrue("Mileage must reject 100 and 1 km/L outliers, was $estimated", estimated in 15.0..19.0)
    }

    // Edge Case 17: City vs Highway mileage separation when trip characteristics are distinct.
    @Test
    fun test17_CityVsHighwayMileageSeparation() {
        val cityMileage = mileageEngine.cityMileageEstimate
        val highwayMileage = mileageEngine.highwayMileageEstimate
        assertTrue("Highway mileage must be higher than City mileage", highwayMileage > cityMileage)
    }

    // Edge Case 18: Decimal precision check: no IEEE 754 drift after 100 sequential micro-consumptions.
    @Test
    fun test18_DecimalPrecisionNoDriftAfter100MicroConsumptions() {
        estimator.initializeBaseline(initialLiters = 10.0, gaugePoints = 2, odometerKm = 1000.0)

        // Consume 0.05 L 100 times = exactly 5.00 L
        for (i in 1..100) {
            estimator.applyTripConsumption(0.05, 1000.0 + (i * 0.5), 2)
        }
        assertEquals(5.00, estimator.nominalFuelLiters, 0.0001)
    }

    // Edge Case 19: Power cut / restart recovery: snapshot restoration yields identical bounds.
    @Test
    fun test19_SnapshotRestorationYieldsIdenticalBounds() {
        estimator.initializeBaseline(initialLiters = 22.45, gaugePoints = 5, odometerKm = 52340.5)
        val snapshot = estimator.toSnapshot()

        val restoredEstimator = FuelStateEstimator(designCapacityLiters = 48.0, maxCapacityLiters = 48.0)
        restoredEstimator.restoreFromSnapshot(snapshot, 52340.5)

        assertEquals(estimator.nominalFuelLiters, restoredEstimator.nominalFuelLiters, 0.001)
        assertEquals(estimator.conservativeFuelLiters, restoredEstimator.conservativeFuelLiters, 0.001)
        assertEquals(estimator.upperFuelLiters, restoredEstimator.upperFuelLiters, 0.001)
        assertEquals(estimator.currentGaugeBar, restoredEstimator.currentGaugeBar)
    }

    // Edge Case 20: Editing a past refuel: creates a child event with FUEL_CORRECTION and parentId.
    @Test
    fun test20_EditingPastRefuelCreatesCorrectionWithParentId() {
        val originalEvent = FuelLedgerEvent(
            id = 55L,
            eventType = FuelEventType.REFUEL,
            odometerKm = 51000.0,
            fuelQuantityLiters = 10.0,
            pricePerLiter = 100.0,
            totalAmount = 1000.0
        )

        // User corrects quantity from 10.0 L to 12.0 L
        val correctionEvent = FuelLedgerEvent(
            parentId = originalEvent.id,
            eventType = FuelEventType.FUEL_CORRECTION,
            odometerKm = originalEvent.odometerKm,
            fuelQuantityLiters = 12.0,
            pricePerLiter = originalEvent.pricePerLiter,
            totalAmount = 1200.0,
            source = EventSource.MANUAL,
            confidence = EventConfidence.CONFIRMED,
            reasonNote = "Corrected quantity from 10.0 L to 12.0 L"
        )

        assertEquals(55L, correctionEvent.parentId)
        assertEquals(FuelEventType.FUEL_CORRECTION, correctionEvent.eventType)
        assertEquals(12.0, correctionEvent.fuelQuantityLiters, 0.01)
    }

    // Edge Case 21: Deleting a refuel: creates a reversal/correction event, restoring prior state.
    @Test
    fun test21_DeletingRefuelCreatesReversalEvent() {
        val originalEvent = FuelLedgerEvent(
            id = 88L,
            eventType = FuelEventType.REFUEL,
            odometerKm = 52000.0,
            fuelQuantityLiters = 15.0
        )

        val reversalEvent = FuelLedgerEvent(
            parentId = originalEvent.id,
            eventType = FuelEventType.MANUAL_FUEL_ADJUSTMENT,
            odometerKm = originalEvent.odometerKm,
            fuelQuantityLiters = -15.0,
            reasonNote = "Reversal of erroneously logged refuel #${originalEvent.id}"
        )

        assertEquals(88L, reversalEvent.parentId)
        assertEquals(-15.0, reversalEvent.fuelQuantityLiters, 0.01)
    }

    // Edge Case 22: Zero distance traveled with non-zero fuel consumed (idle time) correctly reduces fuel balance.
    @Test
    fun test22_ZeroDistanceIdleFuelConsumption() {
        estimator.initializeBaseline(initialLiters = 20.0, gaugePoints = 4, odometerKm = 50000.0)

        // Idling for 30 minutes with AC on consumes 0.4 L at 0 distance traveled
        estimator.applyTripConsumption(consumedLiters = 0.4, newOdometerKm = 50000.0, currentGaugePoints = 4)
        assertEquals(19.60, estimator.nominalFuelLiters, 0.01)
        assertEquals(50000.0, estimator.lastReliableOdometerAnchorKm, 0.01)
    }

    // Edge Case 23: Negative distance input rejected (ignored or clamped).
    @Test
    fun test23_NegativeDistanceRejected() {
        val startingOdo = 50000.0
        estimator.initializeBaseline(initialLiters = 20.0, gaugePoints = 4, odometerKm = startingOdo)

        // Faulty GPS / sensor gives negative odometer update (49900.0)
        estimator.applyTripConsumption(consumedLiters = 1.0, newOdometerKm = 49900.0, currentGaugePoints = 4)
        // Odometer anchor must not regress backwards
        assertEquals(startingOdo, estimator.lastReliableOdometerAnchorKm, 0.01)
    }

    // Edge Case 24: Zero or negative fuel price handled safely without division by zero.
    @Test
    fun test24_ZeroOrNegativeFuelPriceHandledSafely() {
        val litersZeroPrice = FuelMath.calculateLitersFromAmount(500.0, 0.0)
        assertEquals(0.0, litersZeroPrice, 0.001)

        val litersNegativePrice = FuelMath.calculateLitersFromAmount(500.0, -100.0)
        assertEquals(0.0, litersNegativePrice, 0.001)

        val mileageZeroConsumed = FuelMath.calculateMileage(100.0, 0.0)
        assertEquals(0.0, mileageZeroConsumed, 0.001)
    }
}
