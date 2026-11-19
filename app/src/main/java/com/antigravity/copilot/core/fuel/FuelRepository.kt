package com.antigravity.copilot.core.fuel

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.antigravity.copilot.core.database.AppDatabase
import com.antigravity.copilot.core.database.FuelEventEntity
import com.antigravity.copilot.core.database.FuelLedgerEntity
import com.antigravity.copilot.core.database.PersistentDataManager
import com.antigravity.copilot.core.fuel.intelligence.*
import com.antigravity.copilot.core.database.FuelModelStateEntity
import com.antigravity.copilot.core.logic.fuel.FuelModel
import com.antigravity.copilot.core.logic.fuel.FuelModelState
import com.antigravity.copilot.core.logic.fuel.FuelReplay
import com.antigravity.copilot.core.logic.fuel.RefillInput
import com.antigravity.copilot.core.logic.fuel.UnloggedEntry
import com.antigravity.copilot.core.logic.fuel.UnloggedFuel
import com.antigravity.copilot.core.logic.fuel.UnloggedStatus
import com.antigravity.copilot.core.model.FuelState
import com.antigravity.copilot.core.odometer.OdometerManager
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Enterprise Automotive Adaptive Fuel Intelligence Repository.
 * Powered by 4 interconnected engines:
 * 1. FuelLedger: Immutable, timestamped, non-destructive audit log.
 * 2. FuelStateEstimator: Decimal-safe continuous balance, 3-point range bounds, physical tank limits.
 * 3. AdaptiveMileageEngine: Statistical learning supporting partial refills, IQR outlier rejection, recency weighting, and City/Highway separation.
 * 4. FuelGaugeCalibrationEngine & MissedEntryIntelligence: Empirical 0-10 gauge curve learning, slosh filtering, and unrecorded refuel detection.
 */
data class FuelTelemetrySnapshot(
    val mileageKmpl: Double,
    val fuelPricePerLiter: Double,
    val tripConsumedLiters: Double,
    /** Per-car trip-estimate calibration against refill truth (TripFuelCalibration); 1.0 = none yet. */
    val tripFuelCalibration: Double = 1.0
)

class FuelRepository private constructor(context: Context) {
    private val appContext = context.applicationContext
    private val database = AppDatabase.getInstance(context)
    private val fuelDao = database.fuelDao()
    private val fuelLedgerDao = database.fuelLedgerDao()
    private val coroutineExceptionHandler = CoroutineExceptionHandler { _, throwable ->
        Log.e(TAG, "Unhandled exception in FuelRepository coroutine scope: ${throwable.message}", throwable)
    }
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob() + coroutineExceptionHandler)
    private val prefs: SharedPreferences = context.getSharedPreferences("driving_report_fuel_prefs", Context.MODE_PRIVATE)

    private val stateMutex = Mutex()

    // Core Engines
    var designCapacity: Double = prefs.getFloat("design_capacity", 48.0f).toDouble().coerceIn(10.0, 200.0)
        private set
    var maxCapacity: Double = prefs.getFloat("max_capacity", 48.0f).toDouble().coerceAtLeast(designCapacity)
        private set
    var baselineVehicleMileage: Double = prefs.getFloat("baseline_vehicle_mileage", 18.0f).toDouble()
        private set
    private var fuelPrice: Double = prefs.getFloat("fuel_price", 96.5f).toDouble()
    /** Service-owned lifetime distance (LifetimeOdometer); persisted, so it is correct at boot too. */
    private var latestAppLifetimeKm: Double = run {
        com.antigravity.copilot.core.odometer.LifetimeOdometer.init(context.applicationContext)
        com.antigravity.copilot.core.odometer.LifetimeOdometer.totalKm.value
    }
    /** Trip-estimate calibration (actual refill litres / predicted) and its full-to-full accumulators. */
    @Volatile private var tripFuelCalibration: Double = prefs.getFloat("trip_fuel_calibration", 1.0f).toDouble()
        .takeIf { it.isFinite() && it in com.antigravity.copilot.core.logic.fuel.TripFuelCalibration.MIN..com.antigravity.copilot.core.logic.fuel.TripFuelCalibration.MAX } ?: 1.0
    private var predictedSinceFullL: Double = prefs.getFloat("predicted_since_full_l", 0.0f).toDouble()
    private var predictedSinceFullKm: Double = prefs.getFloat("predicted_since_full_km", 0.0f).toDouble()

    // Multi-Tank Interval History Consolidation State
    private var pendingAnchorOdometerKm: Double = prefs.getFloat("pending_anchor_odo", -1.0f).toDouble()
    private var pendingAnchorFuelLiters: Double = prefs.getFloat("pending_anchor_fuel", -1.0f).toDouble()
    private var pendingAccumulatedAddedLiters: Double = prefs.getFloat("pending_accumulated_added", 0.0f).toDouble()

    val gaugeCalibration = FuelGaugeCalibrationEngine(designCapacityLiters = designCapacity)
    val estimator = FuelStateEstimator(
        designCapacityLiters = designCapacity,
        maxCapacityLiters = maxCapacity,
        gaugeCalibration = gaugeCalibration
    )
    val mileageEngine = AdaptiveMileageEngine(baselineVehicleMileageKmpl = baselineVehicleMileage)
    val missedEntryIntel = MissedEntryIntelligence(calibrationEngine = gaugeCalibration, thresholdLiters = 4.0)

    // Legacy AI Engine for thermal/kinematic live consumption calculation
    val aiEngine = FuelAIEngine(context)

    private var cumulativeTripConsumedLiters = 0.0
    private var liveInstantMileage: Double = baselineVehicleMileage
    private var liveDrivingStyle: String = "OPTIMAL DRIVE"

    // I/O debounce: one pending job at most (a burst of calls = one write after the window).
    @Volatile private var persistJob: kotlinx.coroutines.Job? = null
    private val persistDebounceMs: Long = PERSIST_DEBOUNCE_MS

    // ---------------- Pure fuel model (source of truth for tank level, gauge, mileage) ----------------
    private val fuelModelDao = database.fuelModelDao()
    private val modelGson = com.google.gson.Gson()
    @Volatile
    private var model: FuelModelState = FuelModelState(
        designCapacityL = designCapacity,
        actualCapacityL = maxCapacity,
        defaultKmpl = baselineVehicleMileage,
        anchorLitres = prefs.getFloat("remaining_liters", (designCapacity * 0.5).toFloat()).toDouble(),
        // Anchor at the live odometer: an anchor at 0 km made the pre-load seed estimate
        // "litres − 8,800 km / kmpl" and flash (or stick at) 0 L on the dashboard.
        anchorOdoKm = seedOdometerKm(),
        anchorTimeMs = System.currentTimeMillis(),
        // Fresh install (no legacy tank level): unknown until the first refill / gauge reading.
        tankUnknown = !prefs.contains("remaining_liters")
    )
        set(value) {
            field = value
            // Curve / mileage caches are keyed on the model instance; drop them on every change.
            curveCache = null
            mileageCache = null
        }

    // ---- B6 / E6: per-model caches (the curve fit and mileage estimate are not free at 1 Hz) ----
    @Volatile private var curveCache: Pair<FuelModelState, com.antigravity.copilot.core.logic.fuel.GaugeCurve>? = null
    @Volatile private var mileageCache: Triple<FuelModelState, Long, com.antigravity.copilot.core.logic.fuel.MileageEstimate>? = null

    private fun cachedCurve(m: FuelModelState): com.antigravity.copilot.core.logic.fuel.GaugeCurve =
        curveCache?.takeIf { it.first === m }?.second ?: FuelModel.curve(m).also { curveCache = m to it }

    /** Mileage depends on time only through segment recency: one-minute buckets are exact enough. */
    private fun cachedMileage(m: FuelModelState, nowMs: Long): com.antigravity.copilot.core.logic.fuel.MileageEstimate {
        val bucket = nowMs / 60_000L
        return mileageCache?.takeIf { it.first === m && it.second == bucket }?.third
            ?: FuelModel.mileage(m, nowMs).also { mileageCache = Triple(m, bucket, it) }
    }

    private fun currentEstimate(nowMs: Long = System.currentTimeMillis()): com.antigravity.copilot.core.logic.fuel.FuelEstimate {
        val m = model
        return FuelModel.estimate(m, getEffectiveOdometer(), nowMs, cachedMileage(m, nowMs), cachedCurve(m))
    }
    @Volatile
    private var modelLoaded = false
    /** Latest hybrid lifetime (km/L, km); re-applied after the persisted model loads. */
    @Volatile private var hybridLifetime: Pair<Double, Double> = 0.0 to 0.0

    private fun seedOdometerKm(): Double = try { getEffectiveOdometer() } catch (_: Exception) { 0.0 }

    /** Read-only view of the current fuel model (for tests / diagnostics / UI). */
    val fuelModelState: FuelModelState get() = model

    /** Quiet, non-blocking fuel notices (e.g. auto-resolved unlogged fuel). */
    private val _fuelNotice = MutableStateFlow<String?>(null)
    val fuelNotice: StateFlow<String?> = _fuelNotice.asStateFlow()
    fun dismissFuelNotice() {
        _fuelNotice.value = null
        _fuelState.value = _fuelState.value.copy(fuelNotice = null)
    }

    private suspend fun loadModel() {
        try {
            val row = fuelModelDao.get()
            if (row != null && row.json.isNotBlank()) {
                model = modelGson.fromJson(row.json, FuelModelState::class.java).normalized()
                    .let { m -> if (hybridLifetime.second > 0.0) m.copy(hybridKmpl = hybridLifetime.first, hybridKm = hybridLifetime.second) else m }
            } else {
                // First run on this version: seed from legacy state (learned mileage, odometer).
                val learned = mileageEngine.overallEstimatedMileage.takeIf { mileageEngine.sampleCount >= 1 && it > 0 }
                val hasHistory = fuelDao.getAllFuelEvents().any { it.eventType == "REFUEL" || it.eventType == "MID_OBSERVED" }
                model = model.copy(
                    defaultKmpl = learned ?: baselineVehicleMileage,
                    anchorOdoKm = getEffectiveOdometer(),
                    tankUnknown = model.tankUnknown && !hasHistory
                )
                if (!prefs.contains("model_seed_litres")) {
                    prefs.edit().putFloat("model_seed_litres", model.anchorLitres.toFloat()).apply()
                }
                persistModelNow()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Fuel model load failed; keeping seed: ${e.message}", e)
        }
        modelLoaded = true
        notifyRoadSegmentCalibration()
        _fuelState.update { buildFuelState() }
    }

    private suspend fun persistModelNow() {
        try {
            fuelModelDao.upsert(FuelModelStateEntity(json = modelGson.toJson(model)))
        } catch (e: Exception) {
            Log.e(TAG, "Fuel model persist failed: ${e.message}", e)
        }
        notifyRoadSegmentCalibration()
    }

    /**
     * Road-segment mileage calibration hook: a new tank-to-tank / gauge-to-gauge segment (or a
     * baseline change) re-scales the calibrated km/L of every stored road segment. Cheap no-op
     * when nothing relevant changed.
     */
    private fun notifyRoadSegmentCalibration() {
        try {
            com.antigravity.copilot.core.map.intel.RoadSegmentRepository.getInstance(appContext).onFuelModelUpdated(model)
        } catch (e: Exception) {
            Log.w(TAG, "Road segment calibration skipped: ${e.message}")
        }
    }

    private fun persistModelAsync() {
        scope.launch { persistModelNow() }
    }

    /** Calibration popup: user picked [point] (0..10, half points) shown by the car's gauge. */
    fun calibrateGaugePoint(point: Double) {
        scope.launch {
            stateMutex.withLock {
                model = FuelModel.onGaugeReading(model, point, getEffectiveOdometer(), System.currentTimeMillis())
                persistModelNow()
                _fuelState.update { buildFuelState() }
            }
        }
    }

    private val _fuelState = MutableStateFlow(buildFuelState())
    val fuelState: StateFlow<FuelState> = _fuelState.asStateFlow()

    val fuelHistory: Flow<List<FuelEventEntity>> = fuelDao.getFuelEvents(1)
    val fuelLedgerHistory: Flow<List<FuelLedgerEntity>> = fuelLedgerDao.getAllEventsFlow()
    val provisionalInferredEvents: Flow<List<FuelLedgerEntity>> = fuelLedgerDao.getProvisionalInferredEventsFlow()

    data class RefuelPromptData(
        val timestamp: Long = System.currentTimeMillis(),
        val locationName: String,
        val latitude: Double,
        val longitude: Double,
        val currentOdometerKm: Double
    )

    private val _refuelPromptEvent = MutableStateFlow<RefuelPromptData?>(null)
    val refuelPromptEvent: StateFlow<RefuelPromptData?> = _refuelPromptEvent.asStateFlow()

    private val _missedRefuelAlert = MutableStateFlow<MissedRefuelDetectionResult?>(null)
    val missedRefuelAlert: StateFlow<MissedRefuelDetectionResult?> = _missedRefuelAlert.asStateFlow()

    init {
        // Ensure OdometerManager is initialized prior to computing initial odometer reading
        com.antigravity.copilot.core.odometer.OdometerManager.init(appContext)

        // Hydrate baseline from persisted preferences and past ledger
        val initialLiters = if (prefs.contains("remaining_liters")) {
            prefs.getFloat("remaining_liters", (designCapacity * 0.5f).toFloat()).toDouble()
        } else {
            designCapacity * 0.5
        }
        val lastBar = gaugeCalibration.getBarForLiters(initialLiters)
        val initialOdo = getEffectiveOdometer()

        estimator.initializeBaseline(
            initialLiters = initialLiters,
            gaugePoints = lastBar,
            odometerKm = initialOdo
        )

        // Asynchronously rehydrate from ledger
        rehydrateLedgerAsync()
        scope.launch { stateMutex.withLock { loadModel() } }
        // The lifetime odometer is service-owned; the fuel model only follows it (G1/G2).
        scope.launch {
            com.antigravity.copilot.core.odometer.LifetimeOdometer.totalKm.collect { km -> syncOdometerDistance(km) }
        }
        // Hybrid refuel-cycle lifetime mileage drives the tank / range / Fuel-card km/L.
        com.antigravity.copilot.core.fuel.station.FuelStationRepository.getInstance(appContext).let { repo ->
            repo.onLifetimeMileage = { kmpl, km -> setHybridLifetime(kmpl, km) }
            repo.engineState.value.lifetimeKmpl?.let { setHybridLifetime(it, repo.engineState.value.lifetimeKm) }
        }
    }

    private fun setHybridLifetime(kmpl: Double, km: Double) {
        hybridLifetime = kmpl to km
        scope.launch {
            stateMutex.withLock {
                if (model.hybridKmpl == kmpl && model.hybridKm == km) return@withLock
                model = model.copy(hybridKmpl = kmpl, hybridKm = km)
                if (modelLoaded) persistModelNow()
                _fuelState.update { buildFuelState() }
            }
        }
    }

    fun reloadFromDatabase() {
        designCapacity = prefs.getFloat("design_capacity", 48.0f).toDouble().coerceIn(10.0, 200.0)
        maxCapacity = prefs.getFloat("max_capacity", 48.0f).toDouble().coerceAtLeast(designCapacity)
        baselineVehicleMileage = prefs.getFloat("baseline_vehicle_mileage", 18.0f).toDouble()
        fuelPrice = prefs.getFloat("fuel_price", 96.5f).toDouble()
        rehydrateLedgerAsync()
    }

    /**
     * After a backup restore (audit N1): the restored prefs and fuel model row replace the
     * in-memory state, which would otherwise be persisted back over them on the next write.
     * Without a model row in the archive (older backups) the model is replayed from fuel_events.
     */
    fun reloadAfterRestore(hasModelRow: Boolean) {
        persistJob?.cancel()
        persistJob = null
        designCapacity = prefs.getFloat("design_capacity", 48.0f).toDouble().coerceIn(10.0, 200.0)
        maxCapacity = prefs.getFloat("max_capacity", 48.0f).toDouble().coerceAtLeast(designCapacity)
        baselineVehicleMileage = prefs.getFloat("baseline_vehicle_mileage", 18.0f).toDouble()
        fuelPrice = prefs.getFloat("fuel_price", 96.5f).toDouble()
        tripFuelCalibration = prefs.getFloat("trip_fuel_calibration", 1.0f).toDouble()
            .takeIf { it.isFinite() && it in com.antigravity.copilot.core.logic.fuel.TripFuelCalibration.MIN..com.antigravity.copilot.core.logic.fuel.TripFuelCalibration.MAX } ?: 1.0
        predictedSinceFullL = prefs.getFloat("predicted_since_full_l", 0.0f).toDouble()
        predictedSinceFullKm = prefs.getFloat("predicted_since_full_km", 0.0f).toDouble()
        cumulativeTripConsumedLiters = 0.0
        rehydrateLedgerAsync()
        scope.launch {
            stateMutex.withLock {
                // loadModel re-applies the latest hybrid lifetime on top of the restored row.
                if (hasModelRow) loadModel() else rebuildModelFromHistoryLocked()
                lastDisplayKey = null
                _fuelState.update { buildFuelState() }
            }
        }
    }

    fun rehydrateLedgerAsync() {
        scope.launch {
            stateMutex.withLock {
                try {
                    val events = fuelLedgerDao.getAllEventsAsc()
                    if (events.isNotEmpty()) {
                        // Replay past refuels into mileageEngine to calculate true historical mileage
                        var lastAnchorOdo = -1.0
                        var lastAnchorFuel = -1.0
                        var accumulatedAdded = 0.0

                        for (e in events) {
                            when (e.eventType) {
                                FuelEventType.INITIAL_FUEL_BASELINE.name -> {
                                    lastAnchorOdo = e.odometerKm
                                    lastAnchorFuel = e.nominalFuelLiters
                                    accumulatedAdded = 0.0
                                    gaugeCalibration.learnCalibrationPoint(e.fuelGaugePoints, e.nominalFuelLiters, 0.4)
                                }
                                FuelEventType.REFUEL.name -> {
                                    if (e.confidence == EventConfidence.CONFIRMED.name) {
                                        accumulatedAdded += e.fuelQuantityLiters
                                        val isFullTank = e.fuelGaugePoints >= 10 || e.nominalFuelLiters >= designCapacity
                                        var intervalProcessed = false

                                        if (lastAnchorOdo > 0 && e.odometerKm > lastAnchorOdo && lastAnchorFuel > 0) {
                                            val learned = mileageEngine.processAnchorInterval(
                                                startOdometerKm = lastAnchorOdo,
                                                endOdometerKm = e.odometerKm,
                                                startFuelLiters = lastAnchorFuel,
                                                addedFuelLitersDuringInterval = accumulatedAdded,
                                                endFuelLiters = e.nominalFuelLiters
                                            )
                                            if (learned != null) {
                                                intervalProcessed = true
                                            }
                                        }

                                        if (intervalProcessed || isFullTank) {
                                            lastAnchorOdo = e.odometerKm
                                            lastAnchorFuel = e.nominalFuelLiters
                                            accumulatedAdded = 0.0
                                        }

                                        gaugeCalibration.learnCalibrationPoint(e.fuelGaugePoints, e.nominalFuelLiters, 0.25)
                                    }
                                }
                                FuelEventType.FUEL_GAUGE_UPDATE.name -> {
                                    gaugeCalibration.learnCalibrationPoint(e.fuelGaugePoints, e.nominalFuelLiters, 0.15)
                                }
                            }
                        }

                        // Restore latest event snapshot
                        val latest = events.last()
                        estimator.restoreFromSnapshot(latest.toDomainEvent().resultingState ?: estimator.toSnapshot(), latest.odometerKm)
                    }

                    // Feature 18: Audit active trip in Room SQLite on reboot / startup
                    val activeTrip = database.tripDao().getActiveTrip()
                    if (activeTrip != null && activeTrip.endTime <= 0L && activeTrip.estimatedFuelLiters > 0.0) {
                        if (cumulativeTripConsumedLiters == 0.0) {
                            cumulativeTripConsumedLiters = activeTrip.estimatedFuelLiters
                        }
                        // Re-apply mid-trip consumption to continuous estimator so tank level reflects ongoing burn
                        estimator.applyConsumption(
                            consumedLiters = activeTrip.estimatedFuelLiters,
                            odometerKm = getEffectiveOdometer()
                        )
                    } else if (events.isEmpty()) {
                        // Fallback: Rehydrate from legacy fuel_events if fuel_ledger_events was empty
                        val legacyEvents = fuelDao.getAllFuelEvents()
                        if (legacyEvents.isNotEmpty()) {
                            val sortedRefuels = legacyEvents.filter {
                                (it.eventType == "REFUEL" || it.eventType == "INITIAL_FUEL_BASELINE") && it.odometerKm > 0.0
                            }.sortedBy { it.timestamp }

                            var prevOdo = -1.0
                            var prevLiters = -1.0
                            var accumulated = 0.0
                            for (le in sortedRefuels) {
                                accumulated += le.liters
                                val isFullTank = le.midLevelObserved >= 10 || le.calculatedBalanceLiters >= designCapacity
                                var intervalProcessed = false

                                if (prevOdo > 0.0 && le.odometerKm > prevOdo && prevLiters > 0.0) {
                                    val learned = mileageEngine.processAnchorInterval(
                                        startOdometerKm = prevOdo,
                                        endOdometerKm = le.odometerKm,
                                        startFuelLiters = prevLiters,
                                        addedFuelLitersDuringInterval = accumulated,
                                        endFuelLiters = le.calculatedBalanceLiters
                                    )
                                    if (learned != null) {
                                        intervalProcessed = true
                                    }
                                }

                                if (intervalProcessed || isFullTank) {
                                    prevOdo = le.odometerKm
                                    prevLiters = le.calculatedBalanceLiters
                                    accumulated = 0.0
                                }

                                if (le.midLevelObserved in 0..10) {
                                    gaugeCalibration.learnCalibrationPoint(le.midLevelObserved, le.calculatedBalanceLiters, 0.25)
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.w("FuelRepository", "Error rehydrating fuel ledger", e)
                }

                _fuelState.update { buildFuelState() }
            }
        }
    }

    fun getEffectiveOdometer(): Double {
        // Ensure OdometerManager is initialized to avoid returning 0 on first access
        if (!OdometerManager.isInitialized) {
            OdometerManager.init(appContext)
        }
        val odoMgr = OdometerManager.calculateLiveOdometer(latestAppLifetimeKm)
        return FuelMath.roundDistance(odoMgr)
    }

    private fun buildFuelState(): FuelState {
        // Tank level = last anchor litres − km since anchor / learned mileage (pure FuelModel).
        val est = currentEstimate()
        val tankUnknown = model.tankUnknown
        val balance = est.displayLitres
        val spread = (1.0 - est.mileageConfidence) * 0.15 * balance
        val conservativeBalance = (balance - spread).coerceAtLeast(0.0)
        val upperBalance = (balance + spread).coerceAtMost(est.actualCapacityL)
        val effectiveMax = est.actualCapacityL
        val safeDesign = est.designCapacityL.coerceAtLeast(10.0)

        val fraction = if (effectiveMax > 0.0) (balance / effectiveMax) else 0.0
        val percentage = (fraction * 100.0).roundToInt().coerceIn(0, 100)

        val hasConfirmedEmpirical = mileageEngine.hasSufficientEvidence && mileageEngine.measuredMileage != null
        val hasEstimatedEmpirical = mileageEngine.sampleCount >= 1 && (mileageEngine.measuredMileage != null || mileageEngine.overallEstimatedMileage > 0.0)

        // Empirical mileage derived from actual observations:
        // - Multiple samples (>= 2): overallEstimatedMileage (IQR-filtered recency-weighted adaptive average)
        // - Single sample (== 1): measuredMileage (exact verified interval calculation)
        // - Fallback to vehicle baseline so the system never remains stuck in perpetual "Learning" state
        val validEmpiricalMileage = when {
            hasConfirmedEmpirical -> mileageEngine.overallEstimatedMileage
            hasEstimatedEmpirical -> mileageEngine.measuredMileage ?: mileageEngine.overallEstimatedMileage
            baselineVehicleMileage > 0.0 -> baselineVehicleMileage
            else -> 18.0
        }

        // Range uses the model's learned mileage (never contaminated by live throttle oscillation)
        val rangeMileage = est.kmpl
        val range = est.rangeKm
        val minRange = conservativeBalance * rangeMileage
        val maxRange = upperBalance * rangeMileage

        val midBar = est.gaugePoints.roundToInt().coerceIn(0, 10)
        // Relative to this tank and this car's range (a fixed 7 L was 11 % of a 60 L tank).
        val isLow = com.antigravity.copilot.core.logic.fuel.FuelDisplayPolicy.isLowFuel(balance, effectiveMax, range, tankUnknown)
        val tripCost = FuelMath.roundCurrency(cumulativeTripConsumedLiters * fuelPrice)

        // Instantaneous metrics for live HUD efficiency display
        val displayInstantMileage = if (liveInstantMileage > 0.0) liveInstantMileage else rangeMileage
        val instantL100km = if (displayInstantMileage > 0.0) {
            FuelMath.roundMileage(100.0 / displayInstantMileage)
        } else {
            6.7
        }

        val hasOverfill = balance > safeDesign
        val overfillLiters = max(0.0, balance - safeDesign)

        // Relative to the learned mileage: a 10 km/L SUV at its best is ECO too.
        val tier = com.antigravity.copilot.core.logic.fuel.FuelDisplayPolicy.efficiencyTier(displayInstantMileage, rangeMileage)

        val confidencePct = (est.mileageConfidence * 100).roundToInt()
        val confidenceLabel = when {
            confidencePct >= 80 -> "CONFIRMED"
            confidencePct >= 50 -> "HIGH"
            else -> "PROVISIONAL"
        }

        return FuelState(
            designCapacityLiters = FuelMath.roundFuel(safeDesign),
            maxCapacityLiters = FuelMath.roundFuel(effectiveMax),
            tankCapacityLiters = FuelMath.roundFuel(effectiveMax),
            remainingLiters = FuelMath.roundFuel(balance),
            conservativeFuelLiters = FuelMath.roundFuel(conservativeBalance),
            upperFuelLiters = FuelMath.roundFuel(upperBalance),
            currentMileageKmpl = FuelMath.roundMileage(est.kmpl),
            expectedMileageKmpl = FuelMath.roundMileage(baselineVehicleMileage),
            cityMileageKmpl = FuelMath.roundMileage(mileageEngine.cityMileageEstimate),
            highwayMileageKmpl = FuelMath.roundMileage(mileageEngine.highwayMileageEstimate),
            isRealMileageDerived = model.segments.isNotEmpty() || hasConfirmedEmpirical || hasEstimatedEmpirical,
            mileageLearningState = mileageEngine.learningState,
            isTankCalibrated = true,
            hasOverfill = hasOverfill,
            overfillLiters = FuelMath.roundFuel(overfillLiters),
            remainingRangeKm = FuelMath.roundDistance(range),
            rangeMinKm = FuelMath.roundDistance(minRange),
            rangeMaxKm = FuelMath.roundDistance(maxRange),
            fuelPercentage = percentage,
            tripFuelConsumedLiters = FuelMath.roundFuel(cumulativeTripConsumedLiters),
            tripFuelCost = tripCost,
            fuelPricePerLiter = fuelPrice,
            instantConsumptionRateL100Km = instantL100km,
            midBarsCount = 10,
            currentMidBar = midBar,
            isLowFuelWarning = isLow,
            fuelEfficiencyTier = tier,
            odometerKm = getEffectiveOdometer(),
            liveInstantMileageKmpl = FuelMath.roundMileage(displayInstantMileage),
            aiConfidencePercent = confidencePct,
            aiDrivingStyle = liveDrivingStyle,
            confidenceLabel = confidenceLabel,
            explanation = estimator.currentExplanation,
            actualCapacityLiters = FuelMath.roundFuel(est.actualCapacityL),
            gaugeFillPoints = est.gaugePoints,
            gaugeCurve = cachedCurve(model),
            mileageConfidence = est.mileageConfidence,
            rawFuelLiters = est.rawLitres,
            topBufferLiters = est.topBufferL,
            bottomBufferLiters = est.bottomBufferL,
            unloggedDebtLiters = model.totalDebtL,
            fuelNotice = _fuelNotice.value,
            isTankUnknown = tankUnknown
        )
    }

    /** Last displayed values; a new FuelState is only built / emitted when one of them changes (E6). */
    @Volatile private var lastDisplayKey: List<Any>? = null

    private fun refreshFuelStateIfChanged() {
        val est = currentEstimate()
        val key = listOf(
            Math.round(est.displayLitres * 10.0),
            Math.round(est.rangeKm),
            Math.round(cumulativeTripConsumedLiters * 100.0),
            Math.round(liveInstantMileage * 10.0),
            Math.round(getEffectiveOdometer() * 10.0),
            liveDrivingStyle,
            model.tankUnknown
        )
        if (key == lastDisplayKey) return
        lastDisplayKey = key
        _fuelState.update { buildFuelState() }
    }

    fun triggerRefuelPrompt(locationName: String, lat: Double, lon: Double, odoKm: Double) {
        _refuelPromptEvent.value = RefuelPromptData(
            locationName = locationName,
            latitude = lat,
            longitude = lon,
            currentOdometerKm = odoKm
        )
    }

    fun dismissRefuelPrompt() {
        _refuelPromptEvent.value = null
    }

    fun dismissMissedRefuelAlert() {
        _missedRefuelAlert.value = null
    }

    fun updateVehicleConfiguration(tankCapacityLiters: Double, expectedMileageKmpl: Double) {
        val safeCapacity = tankCapacityLiters.coerceIn(10.0, 200.0)
        val safeMileage = expectedMileageKmpl.coerceIn(4.0, 60.0)
        // Every model write happens under stateMutex on the IO scope (a caller-thread write could
        // be lost under a concurrent refill / gauge update).
        scope.launch {
            stateMutex.withLock {
                designCapacity = safeCapacity
                maxCapacity = safeCapacity
                baselineVehicleMileage = safeMileage

                prefs.edit()
                    .putFloat("design_capacity", safeCapacity.toFloat())
                    .putFloat("max_capacity", safeCapacity.toFloat())
                    .putFloat("baseline_vehicle_mileage", safeMileage.toFloat())
                    .apply()

                estimator.updateDesignCapacity(safeCapacity)
                gaugeCalibration.updateDesignCapacity(safeCapacity)
                mileageEngine.updateBaseline(safeMileage)
                model = FuelModel.withConfig(model, safeCapacity, safeMileage)
                persistModelNow()
                _fuelState.update { buildFuelState() }
            }
        }
    }

    fun calibrateOdometer(newOdometerReadingKm: Double, currentRecordedKm: Double) {
        scope.launch {
            stateMutex.withLock {
                latestAppLifetimeKm = currentRecordedKm
                applyRealOdometerLocked(newOdometerReadingKm, currentRecordedKm)
                _fuelState.update { buildFuelState() }
            }
        }
    }

    /**
     * Re-bases the app odometer on a real dashboard reading (caller holds [stateMutex]).
     *
     * The *first* real reading only sets the scale: the jump from the uncalibrated (near-zero)
     * odometer to e.g. 45,000 km is not driven distance. The fuel model's odometer references move
     * with it, so no litres are "burned" for it (no negative tank, no unlogged-fuel debt). Later
     * readings are real corrections and keep the existing behaviour.
     */
    private suspend fun applyRealOdometerLocked(readingKm: Double, lifetimeKm: Double) {
        if (!OdometerManager.isInitialized) OdometerManager.init(appContext)
        val firstCalibration = !OdometerManager.odometerConfig.value.isManuallyVerified
        latestAppLifetimeKm = lifetimeKm
        val before = getEffectiveOdometer()
        OdometerManager.setBaselineOdometer(appContext, readingKm, lifetimeKm)
        val after = getEffectiveOdometer()
        // The verified flag alone is not reliable (backup restore / legacy migration set it), so
        // an impossible jump (tank would go below 0 L) is also treated as a calibration.
        if (!FuelModel.isCalibrationJump(model, before, after, firstCalibration, System.currentTimeMillis())) return
        val delta = after - before
        model = FuelModel.rebaseOdometer(model, delta)
        if (pendingAnchorOdometerKm > 0.0) {
            pendingAnchorOdometerKm += delta
            prefs.edit().putFloat("pending_anchor_odo", pendingAnchorOdometerKm.toFloat()).apply()
        }
        persistModelNow()
        Log.i(TAG, "Odometer calibration jump: fuel model re-based by ${"%.1f".format(delta)} km (first=$firstCalibration)")
    }

    fun syncOdometerDistance(currentRecordedKm: Double) {
        if (latestAppLifetimeKm != currentRecordedKm) {
            latestAppLifetimeKm = currentRecordedKm
            OdometerManager.syncLifetimeKm(currentRecordedKm)
            refreshFuelStateIfChanged()
        }
    }

    fun calibrateMidBar(selectedBar: Int) = calibrateMidBar(selectedBar.toDouble())

    /** Gauge tap calibration: feeds the pure model (half points) and the legacy ledger. */
    fun calibrateMidBar(point: Double, note: String? = null) {
        calibrateGaugePoint(point)
        val selectedBar = point.roundToInt().coerceIn(0, 10)
        val currentOdo = getEffectiveOdometer()
        // Feature 16: Reset slosh buffer on explicit user calibration so user's manual action applies immediately
        gaugeCalibration.resetSloshBuffer(selectedBar)
        syncMidAndOdometer(currentOdo, selectedBar, latestAppLifetimeKm, exactPoint = FuelModel.snap(point), note = note)
    }

    /**
     * Dashboard "Reserve light ON": the car itself says the tank is at reserve, so the tank level
     * follows it as a gauge reading of 0 (the curve's hidden-reserve litres). Stored as a gauge
     * event so history replay keeps it; tapping again (undo) removes that event.
     */
    fun onReserveLight(on: Boolean) {
        if (on) {
            calibrateMidBar(0.0, note = RESERVE_LIGHT_NOTE)
            return
        }
        scope.launch {
            val last = fuelDao.getAllFuelEvents()
                .filter { it.eventType == "MID_OBSERVED" && it.notes == RESERVE_LIGHT_NOTE }
                .maxByOrNull { it.timestamp } ?: return@launch
            deleteFuelEvent(last.id)
        }
    }

    /**
     * Reconciles observed gauge level.
     * Evaluates against MissedEntryIntelligence to detect unrecorded refuels.
     */
    fun syncMidAndOdometer(
        newOdometerKm: Double,
        newMidBar: Int,
        recordedLifetimeKm: Double = latestAppLifetimeKm,
        /** The exact (half-point) reading the pure model received, stored for history replay. */
        exactPoint: Double? = null,
        /** Marks where the reading came from (e.g. [RESERVE_LIGHT_NOTE]); default = cluster sync. */
        note: String? = null
    ) {
        scope.launch {
            stateMutex.withLock {
                try {
                    latestAppLifetimeKm = recordedLifetimeKm
                    val effectiveOdo = if (newOdometerKm > 0.0) newOdometerKm else getEffectiveOdometer()

                    // Feature 16: Filter transient pitch, roll, acceleration, and slosh anomalies with 60s rolling median filter
                    val filteredMidBar = estimator.filterSloshAndSlope(newMidBar)

                    // Missed refuels are no longer prompted for: unlogged fuel is reconciled
                    // automatically at the next refill (UnloggedFuel), with only a quiet notice.
                    // Normal gauge update
                    val litersForBar = gaugeCalibration.getLitersForBar(filteredMidBar)
                    val prevState = estimator.toSnapshot()
                    estimator.applyManualAdjustment(
                        adjustedLiters = litersForBar,
                        observedGaugePoints = filteredMidBar,
                        odometerKm = effectiveOdo,
                        reason = "Gauge updated to $filteredMidBar/10 bars",
                        confidence = EventConfidence.HIGH
                    )
                    gaugeCalibration.learnCalibrationPoint(filteredMidBar, litersForBar, 0.15)

                    // fuel_events first: its id links the ledger row (edits / deletes follow it).
                    val legacyEvent = FuelEventEntity(
                        vehicleId = 1,
                        timestamp = System.currentTimeMillis(),
                        eventType = "MID_OBSERVED",
                        liters = 0.0,
                        midLevelObserved = filteredMidBar,
                        pricePerLiter = fuelPrice,
                        totalCost = 0.0,
                        calculatedBalanceLiters = estimator.nominalFuelLiters,
                        notes = note ?: "Manual MID cluster sync: $filteredMidBar/10 bars at $effectiveOdo km",
                        odometerKm = effectiveOdo,
                        calculatedMileageKmpl = mileageEngine.overallEstimatedMileage,
                        locationName = if (note == RESERVE_LIGHT_NOTE) "Reserve light" else "Cluster Calibration",
                        gaugePoint = exactPoint ?: filteredMidBar.toDouble()
                    )
                    val legacyId = fuelDao.insertFuelEvent(legacyEvent)

                    val ledgerEvent = FuelLedgerEvent(
                        correlationId = "gauge_${System.currentTimeMillis()}",
                        eventType = FuelEventType.FUEL_GAUGE_UPDATE,
                        timestamp = System.currentTimeMillis(),
                        odometerKm = effectiveOdo,
                        fuelQuantityLiters = 0.0,
                        fuelGaugePoints = filteredMidBar,
                        source = EventSource.MANUAL,
                        confidence = EventConfidence.HIGH,
                        previousState = prevState,
                        resultingState = estimator.toSnapshot(),
                        reasonNote = "Cluster gauge synchronized to $filteredMidBar/10 bars"
                    )
                    fuelLedgerDao.insertEvent(FuelLedgerEntity.fromDomainEvent(ledgerEvent).copy(sourceEventId = legacyId))

                    persistValuesImmediate()
                    _fuelState.update { buildFuelState() }
                } catch (e: Exception) {
                    Log.e(TAG, "Error in syncMidAndOdometer Room DB writes: ${e.message}", e)
                }
            }
        }
    }

    /**
     * Confirms and resolves a provisional inferred refuel into an immutable confirmed refuel.
     */
    fun resolveInferredRefuel(
        inferredEventId: Long,
        confirmedLiters: Double,
        pricePerLiter: Double,
        totalAmount: Double,
        fuelStation: String = ""
    ) {
        scope.launch {
            stateMutex.withLock {
                try {
                    val inferredEntity = fuelLedgerDao.getEventById(inferredEventId) ?: return@withLock
                    val inferredDomain = inferredEntity.toDomainEvent()
                    val currentOdo = getEffectiveOdometer()

                    val resolvedEvent = missedEntryIntel.createConfirmedResolutionEvent(
                        inferredEvent = inferredDomain,
                        confirmedLiters = confirmedLiters,
                        pricePerLiter = pricePerLiter,
                        totalAmount = totalAmount,
                        odometerKm = currentOdo,
                        fuelStation = fuelStation
                    )

                    // Apply refuel to state
                    estimator.applyRefuel(
                        addedLiters = confirmedLiters,
                        observedGaugePoints = resolvedEvent.fuelGaugePoints,
                        odometerKm = currentOdo,
                        confidence = EventConfidence.CONFIRMED,
                        explanation = "Confirmed inferred refill: $confirmedLiters L"
                    )

                    fuelPrice = pricePerLiter
                    fuelLedgerDao.insertEvent(FuelLedgerEntity.fromDomainEvent(resolvedEvent))
                    _missedRefuelAlert.value = null

                    persistValuesImmediate()
                    _fuelState.update { buildFuelState() }
                } catch (e: Exception) {
                    Log.e(TAG, "Error in resolveInferredRefuel Room DB writes: ${e.message}", e)
                }
            }
        }
    }

    /**
     * Records a confirmed refuel event.
     * Accurately calculates partial refill mileage:
     * Fuel Consumed = Starting + Added - Ending.
     */
    fun recordRefuel(
        litersAdded: Double,
        midBarObserved: Int,
        pricePerLiter: Double = 96.5,
        totalCost: Double = 0.0,
        odometerKm: Double = 0.0,
        locationName: String = "",
        latitude: Double = 0.0,
        longitude: Double = 0.0,
        recordedLifetimeKm: Double = latestAppLifetimeKm,
        fullTank: Boolean = midBarObserved >= 10,
        gaugeAfterPoint: Double? = midBarObserved.takeIf { it in 0..10 }?.toDouble(),
        /** When set, the hybrid station log is written here, linked to this refill's fuel_events id. */
        refuelMode: com.antigravity.copilot.core.fuel.station.RefuelMode? = null,
        confirmedStation: Boolean = false
    ) {
        if (litersAdded <= 0.0 || !litersAdded.isFinite()) return

        scope.launch {
            stateMutex.withLock {
                try {
                    latestAppLifetimeKm = recordedLifetimeKm
                    // The real dashboard odometer entered at every refill re-bases the app odometer
                    // and teaches the correction factor k (OdometerCorrection).
                    if (odometerKm > 0.0) {
                        applyRealOdometerLocked(odometerKm, recordedLifetimeKm)
                    }
                    val effectiveOdo = if (odometerKm > 0.0) odometerKm else getEffectiveOdometer()
                    val safeTotalCost = if (totalCost > 0.0) totalCost else FuelMath.roundCurrency(litersAdded * pricePerLiter)

                    // Pure fuel model: litres = amount / price, anchor reset, segments, gauge curve.
                    val refillTimeMs = System.currentTimeMillis()
                    val refill = RefillInput(
                        timeMs = refillTimeMs,
                        amountRs = safeTotalCost,
                        pricePerL = if (pricePerLiter > 0.0) pricePerLiter else safeTotalCost / litersAdded,
                        odoKm = effectiveOdo,
                        fullTank = fullTank,
                        gaugeAfter = gaugeAfterPoint
                    )
                    val lastSegmentBefore = model.segments.lastOrNull()
                    // Tank level just before this refill. Only the FuelModel tracks live consumption,
                    // so the legacy estimator is re-synced to it here; otherwise its balance never
                    // drains (stored balances climbed past the tank size and faked "full tank").
                    val levelBeforeL = FuelModel.rawLitres(model, effectiveOdo, refillTimeMs).coerceAtLeast(0.0)
                    applyRefillToModel(refill)
                    if (fullTank) calibrateTripEstimateLocked(lastSegmentBefore)

                    estimator.syncNominal(levelBeforeL)
                    val prevState = estimator.toSnapshot()

                    // Initialize pending anchor if uninitialized
                    if (pendingAnchorOdometerKm <= 0.0) {
                        pendingAnchorOdometerKm = if (estimator.lastReliableOdometerAnchorKm > 0.0) {
                            estimator.lastReliableOdometerAnchorKm
                        } else {
                            effectiveOdo
                        }
                        pendingAnchorFuelLiters = if (estimator.lastReliableFuelAnchorLiters > 0.0) {
                            estimator.lastReliableFuelAnchorLiters
                        } else {
                            estimator.nominalFuelLiters
                        }
                        pendingAccumulatedAddedLiters = 0.0
                    }

                    // Accumulate fuel added across partial fills
                    pendingAccumulatedAddedLiters += litersAdded

                    // Apply refuel to continuous estimator
                    estimator.applyRefuel(
                        addedLiters = litersAdded,
                        observedGaugePoints = midBarObserved,
                        odometerKm = effectiveOdo,
                        confidence = EventConfidence.CONFIRMED,
                        explanation = "Refueled ${FuelMath.roundFuel(litersAdded)} L"
                    )

                    // Teach calibration engine this ground truth point
                    if (midBarObserved in 0..10) {
                        gaugeCalibration.learnCalibrationPoint(midBarObserved, estimator.nominalFuelLiters, 0.30)
                    }

                    // Full-tank fill-up: the user's explicit "Full tank" choice wins; otherwise infer it
                    // from a full gauge or the estimator reaching design capacity.
                    val isFullTank = fullTank || midBarObserved >= 10 || estimator.nominalFuelLiters >= designCapacity
                    val deltaDistance = effectiveOdo - pendingAnchorOdometerKm

                    var computedMileage = 0.0
                    // Only a measured end level (full tank or a gauge reading) makes the interval real
                    // evidence; with a plain partial fill the end level is the model's own estimate and
                    // the "learned" km/L would just echo the model's prior.
                    val endLevelObserved = fullTank || midBarObserved in 0..10
                    if (endLevelObserved && deltaDistance >= com.antigravity.copilot.core.fuel.intelligence.AdaptiveMileageEngine.MIN_INTERVAL_KM && pendingAnchorFuelLiters > 0.0) {
                        val learned = mileageEngine.processAnchorInterval(
                            startOdometerKm = pendingAnchorOdometerKm,
                            endOdometerKm = effectiveOdo,
                            startFuelLiters = pendingAnchorFuelLiters,
                            addedFuelLitersDuringInterval = pendingAccumulatedAddedLiters,
                            endFuelLiters = estimator.nominalFuelLiters
                        )
                        if (learned != null) {
                            computedMileage = learned
                            // Interval validated; advance anchor to current point and reset accumulator
                            pendingAnchorOdometerKm = effectiveOdo
                            pendingAnchorFuelLiters = estimator.nominalFuelLiters
                            pendingAccumulatedAddedLiters = 0.0
                        }
                    }

                    if (isFullTank && computedMileage <= 0.0) {
                        // Full tank top-up establishes a clean physical anchor regardless of interval distance
                        pendingAnchorOdometerKm = effectiveOdo
                        pendingAnchorFuelLiters = estimator.nominalFuelLiters
                        pendingAccumulatedAddedLiters = 0.0
                    }

                    if (computedMileage <= 0.0 && mileageEngine.sampleCount >= 1) {
                        computedMileage = mileageEngine.measuredMileage ?: mileageEngine.overallEstimatedMileage
                    }

                    if (pricePerLiter > 0.0 && pricePerLiter.isFinite()) fuelPrice = pricePerLiter
                    cumulativeTripConsumedLiters = 0.0

                    // fuel_events first (same timestamp as the model input): its id links the
                    // ledger row and the station log, so later edits / deletes reach all three.
                    val legacyEvent = FuelEventEntity(
                        vehicleId = 1,
                        timestamp = refillTimeMs,
                        eventType = "REFUEL",
                        liters = litersAdded,
                        midLevelObserved = midBarObserved,
                        pricePerLiter = pricePerLiter,
                        totalCost = safeTotalCost,
                        calculatedBalanceLiters = estimator.nominalFuelLiters,
                        notes = "Refueled $litersAdded L",
                        odometerKm = effectiveOdo,
                        calculatedMileageKmpl = computedMileage,
                        locationName = locationName,
                        latitude = latitude,
                        longitude = longitude,
                        fullTank = if (fullTank) 1 else 0,
                        gaugePoint = gaugeAfterPoint ?: -1.0
                    )
                    val legacyId = fuelDao.insertFuelEvent(legacyEvent)

                    // Create immutable ledger event
                    val ledgerEvent = FuelLedgerEvent(
                        correlationId = "refuel_${System.currentTimeMillis()}",
                        eventType = FuelEventType.REFUEL,
                        timestamp = System.currentTimeMillis(),
                        odometerKm = effectiveOdo,
                        fuelQuantityLiters = litersAdded,
                        fuelGaugePoints = midBarObserved,
                        pricePerLiter = pricePerLiter,
                        totalAmount = safeTotalCost,
                        source = EventSource.MANUAL,
                        confidence = EventConfidence.CONFIRMED,
                        locationName = locationName,
                        latitude = latitude,
                        longitude = longitude,
                        previousState = prevState,
                        resultingState = estimator.toSnapshot(),
                        reasonNote = "Refueled $litersAdded L at ₹$pricePerLiter/L",
                        calculationMetadataJson = """{"computedIntervalMileage":$computedMileage}"""
                    )
                    fuelLedgerDao.insertEvent(FuelLedgerEntity.fromDomainEvent(ledgerEvent).copy(sourceEventId = legacyId))

                    if (refuelMode != null) {
                        com.antigravity.copilot.core.fuel.station.FuelStationRepository.getInstance(appContext).recordRefuel(
                            stationName = locationName,
                            lat = latitude,
                            lon = longitude,
                            liters = litersAdded,
                            rate = pricePerLiter,
                            mode = refuelMode,
                            odometerKm = effectiveOdo,
                            confirmedStation = confirmedStation,
                            fuelEventId = legacyId
                        )
                    }

                    if (pricePerLiter > 0.0) {
                        StationPriceManager.rememberStationPrice(
                            context = appContext,
                            stationName = locationName,
                            lat = latitude,
                            lon = longitude,
                            pricePerLiter = pricePerLiter
                        )
                    }

                    persistValuesImmediate()
                    _fuelState.update { buildFuelState() }
                } catch (e: Exception) {
                    Log.e(TAG, "Error in recordRefuel Room DB writes: ${e.message}", e)
                }
            }
        }
    }

    /**
     * Refill handling with automatic unlogged-fuel reconciliation (Phase 4, no user confirmation).
     * Gauge-after litres come from the learned curve; a full tank means actual capacity.
     */
    private suspend fun applyRefillToModel(refill: RefillInput) {
        val gaugeLitres = refill.gaugeAfter?.takeIf { it < 10.0 }?.let { FuelModel.curve(model).litresAt(it) }
        val outcome = UnloggedFuel.onRefill(model, refill, gaugeLitres)
        model = outcome.state
        persistModelNow()
        for (e in outcome.resolved) markUnloggedResolved(e)
        outcome.autoUnloggedRefillL?.let { litres ->
            fuelDao.insertFuelEvent(
                FuelEventEntity(
                    vehicleId = 1,
                    timestamp = refill.timeMs - 1,
                    eventType = "UNLOGGED_REFILL_AUTO",
                    liters = FuelMath.roundFuel(litres),
                    calculatedBalanceLiters = model.anchorLitres,
                    notes = UnloggedFuel.SOURCE_RESOLVED,
                    odometerKm = refill.odoKm,
                    locationName = "Unlogged refill (auto)"
                )
            )
        }
        _fuelNotice.value = outcome.notice?.message
    }

    private fun unloggedMarker(e: UnloggedEntry) = "[unlogged:${e.tripId}:${e.timeMs}]"

    private suspend fun recordUnloggedEntry(e: UnloggedEntry, odoKm: Double) {
        fuelDao.insertFuelEvent(
            FuelEventEntity(
                vehicleId = 1,
                timestamp = e.timeMs,
                eventType = "UNLOGGED_CONSUMPTION",
                liters = -FuelMath.roundFuel(e.litres),
                calculatedBalanceLiters = 0.0,
                notes = "PROVISIONAL • ${e.source} • ${unloggedMarker(e)}",
                odometerKm = odoKm,
                calculatedMileageKmpl = if (e.litres > 0) e.km / e.litres else 0.0,
                locationName = "Unlogged consumption"
            )
        )
    }

    private suspend fun markUnloggedResolved(e: UnloggedEntry) {
        val row = fuelDao.findUnloggedByMarker("%${unloggedMarker(e)}%") ?: return
        fuelDao.updateFuelEvent(
            row.copy(
                liters = -FuelMath.roundFuel(e.resolvedLitres),
                notes = "${if (e.status == UnloggedStatus.RESOLVED) "RESOLVED" else "PROVISIONAL"} • ${e.source} • ${unloggedMarker(e)}"
            )
        )
    }

    /**
     * Real-time kinematic live consumption deduction.
     * Deducts continuous fuel while preserving decimal accuracy.
     */
    /**
     * Live HUD only: instantaneous km/L and driving-style tag for this movement slice. Trip litres
     * come from the trip engine's estimator ([onTripFuelEstimate]) so the dashboard and the trip
     * card show one number; the tank level comes from the pure FuelModel.
     */
    fun consumeLiveFuel(
        deltaDistanceMeters: Double,
        currentSpeedKmh: Double = 0.0,
        deltaDurationMs: Long = 1000L
    ) {
        val effectiveMax = max(designCapacity, estimator.maxCapacityLiters)
        val now = System.currentTimeMillis()
        aiEngine.updateLearnedBase(cachedMileage(model, now).kmpl, tripFuelCalibration)
        val result = aiEngine.calculateLiveConsumption(
            deltaDistanceMeters = deltaDistanceMeters,
            currentSpeedKmh = currentSpeedKmh,
            deltaDurationMs = deltaDurationMs,
            currentRemainingLiters = estimator.nominalFuelLiters.coerceAtLeast(1.0),
            designCapacityLiters = effectiveMax
        )

        liveInstantMileage = result.liveInstantMileageKmpl
        liveDrivingStyle = result.drivingStyleTag
        refreshFuelStateIfChanged()
    }

    /**
     * Single source of the live trip fuel (audit F1 / F3): the trip engine's estimator total,
     * already including bridged distance at the learned mileage. Kept raw (no per-step rounding).
     */
    fun onTripFuelEstimate(litres: Double) {
        if (!litres.isFinite() || litres < 0.0) return
        cumulativeTripConsumedLiters = litres
        refreshFuelStateIfChanged()
    }

    fun getFuelTelemetrySnapshot(): FuelTelemetrySnapshot {
        // Learned mileage from the pure fuel model drives the trip estimate (cached per model / minute).
        val effMileage = cachedMileage(model, System.currentTimeMillis()).kmpl
        return FuelTelemetrySnapshot(
            mileageKmpl = effMileage,
            fuelPricePerLiter = fuelPrice,
            tripConsumedLiters = cumulativeTripConsumedLiters,
            tripFuelCalibration = tripFuelCalibration
        )
    }

    /**
     * F2 / F4: after a full refill closed a full-to-full segment, compare the litres actually used
     * with what the trip estimator predicted (uncalibrated) per km over the same interval.
     */
    private fun calibrateTripEstimateLocked(lastSegmentBefore: com.antigravity.copilot.core.logic.fuel.MileageSegment?) {
        val seg = model.segments.lastOrNull()
        // Identity, not list size: the segment list is capped, so its size stops growing.
        val closedSegment = seg != null && seg !== lastSegmentBefore &&
            seg.type == com.antigravity.copilot.core.logic.fuel.SegmentType.FULL_TO_FULL
        if (closedSegment && seg != null && predictedSinceFullKm > 0.0) {
            val predictedForSegment = predictedSinceFullL / predictedSinceFullKm * seg.km
            tripFuelCalibration = com.antigravity.copilot.core.logic.fuel.TripFuelCalibration.update(
                tripFuelCalibration, seg.litres, predictedForSegment
            )
        }
        predictedSinceFullL = 0.0
        predictedSinceFullKm = 0.0
        prefs.edit()
            .putFloat("trip_fuel_calibration", tripFuelCalibration.toFloat())
            .putFloat("predicted_since_full_l", 0f)
            .putFloat("predicted_since_full_km", 0f)
            .apply()
    }

    fun resetTripConsumption() {
        cumulativeTripConsumedLiters = 0.0
        _fuelState.update { buildFuelState() }
    }

    /**
     * Feature 18: Restores cumulative trip fuel consumed from Room SQLite on reboot or app recreation.
     * Prevents active trip fuel consumption and trip cost from resetting to 0.0.
     */
    fun restoreActiveTripFuel(consumedLiters: Double) {
        if (consumedLiters <= 0.0 || !consumedLiters.isFinite()) return
        scope.launch {
            stateMutex.withLock {
                if (cumulativeTripConsumedLiters == 0.0) {
                    cumulativeTripConsumedLiters = consumedLiters
                    _fuelState.update { buildFuelState() }
                }
            }
        }
    }

    fun onTripCompleted(tripId: Long = 0L, tripKm: Double = 0.0) {
        flushPendingPersist()
        // Calibration accumulator: uncalibrated predicted litres over tracked km since the last full refill.
        val tripLitres = cumulativeTripConsumedLiters
        if (tripKm > 0.0 && tripLitres > 0.0 && tripFuelCalibration > 0.0) {
            predictedSinceFullL += tripLitres / tripFuelCalibration
            predictedSinceFullKm += tripKm
            prefs.edit()
                .putFloat("predicted_since_full_l", predictedSinceFullL.toFloat())
                .putFloat("predicted_since_full_km", predictedSinceFullKm.toFloat())
                .apply()
        }
        // Phase 4: trips ending with the raw estimate at/below 0 create provisional unlogged entries.
        if (tripKm > 0.0) {
            scope.launch {
                stateMutex.withLock {
                    try {
                        val odo = getEffectiveOdometer()
                        val (next, entry) = UnloggedFuel.onTripEnded(model, tripId, tripKm, odo, System.currentTimeMillis())
                        model = next
                        if (entry != null) {
                            persistModelNow()
                            recordUnloggedEntry(entry, odo)
                        }
                        _fuelState.update { buildFuelState() }
                    } catch (e: Exception) {
                        Log.e(TAG, "Unlogged-consumption bookkeeping failed: ${e.message}", e)
                    }
                }
            }
        }
        // Record trip consumption slice in ledger
        scope.launch {
            stateMutex.withLock {
                try {
                    if (cumulativeTripConsumedLiters > 0.0) {
                        val event = FuelLedgerEvent(
                            correlationId = "trip_${System.currentTimeMillis()}",
                            eventType = FuelEventType.TRIP_FUEL_CONSUMPTION,
                            timestamp = System.currentTimeMillis(),
                            odometerKm = getEffectiveOdometer(),
                            fuelQuantityLiters = cumulativeTripConsumedLiters,
                            source = EventSource.TRIP,
                            confidence = EventConfidence.HIGH,
                            resultingState = estimator.toSnapshot(),
                            reasonNote = "Trip completed; consumed ${FuelMath.roundFuel(cumulativeTripConsumedLiters)} L"
                        )
                        fuelLedgerDao.insertEvent(FuelLedgerEntity.fromDomainEvent(event))
                    }
                    persistValuesImmediate()
                    _fuelState.update { buildFuelState() }
                } catch (e: Exception) {
                    Log.e(TAG, "Error in onTripCompleted Room DB writes: ${e.message}", e)
                }
            }
        }
    }

    private fun persistValuesImmediate() {
        prefs.edit()
            .putFloat("design_capacity", designCapacity.toFloat())
            .putFloat("max_capacity", estimator.maxCapacityLiters.toFloat())
            .putFloat("remaining_liters", estimator.nominalFuelLiters.toFloat())
            .putFloat("current_mileage", mileageEngine.overallEstimatedMileage.toFloat())
            .putFloat("baseline_vehicle_mileage", baselineVehicleMileage.toFloat())
            .putFloat("fuel_price", fuelPrice.toFloat())
            .putFloat("pending_anchor_odo", pendingAnchorOdometerKm.toFloat())
            .putFloat("pending_anchor_fuel", pendingAnchorFuelLiters.toFloat())
            .putFloat("pending_accumulated_added", pendingAccumulatedAddedLiters.toFloat())
            .putBoolean("is_tank_calibrated", true)
            .apply()

        PersistentDataManager.backupAsync(appContext)
    }

    /**
     * At most one pending write: further calls inside the window are absorbed by it (the old
     * version launched one delayed write per call, so a 1 Hz caller wrote every second).
     */
    @Suppress("unused")
    private fun persistValuesDebounced() {
        if (persistJob?.isActive == true) return
        persistJob = scope.launch {
            kotlinx.coroutines.delay(persistDebounceMs)
            persistValuesImmediate()
        }
    }

    fun flushPendingPersist() {
        val pending = persistJob
        if (pending != null && pending.isActive) {
            pending.cancel()
            persistJob = null
            persistValuesImmediate()
        }
    }

    fun updateVehicleConfig(newDesignCapacity: Double, newDefaultMileage: Double, @Suppress("UNUSED_PARAMETER") midLevels: Int = 10) {
        if (newDesignCapacity <= 0.0 || newDefaultMileage <= 0.0) return
        scope.launch {
            stateMutex.withLock {
                designCapacity = newDesignCapacity.coerceIn(10.0, 200.0)
                maxCapacity = max(designCapacity, estimator.maxCapacityLiters)
                estimator.designCapacityLiters = designCapacity
                estimator.maxCapacityLiters = maxCapacity
                estimator.ensureCapacityConsistency()
                gaugeCalibration.updateDesignCapacity(designCapacity)

                baselineVehicleMileage = newDefaultMileage.coerceIn(4.0, 60.0)
                mileageEngine.updateBaseline(baselineVehicleMileage)
                model = FuelModel.withConfig(model, designCapacity, baselineVehicleMileage)
                persistModelNow()

                persistValuesImmediate()
                _fuelState.update { buildFuelState() }
            }
        }
    }

    // ---------------- E1 / E2 / E10: history edits rebuild the model from fuel_events ----------------

    /** fuel_events row → replay input (legacy rows without the exact flags fall back to the gauge). */
    private fun FuelEventEntity.toReplayInput(): FuelReplay.Input? = when (eventType) {
        "REFUEL" -> {
            val price = if (pricePerLiter > 0.0) pricePerLiter else if (liters > 0.0 && totalCost > 0.0) totalCost / liters else 0.0
            val amount = if (totalCost > 0.0) totalCost else liters * price
            if (liters <= 0.0 || price <= 0.0) null else FuelReplay.Input.Refill(
                timeMs = timestamp,
                odoKm = odometerKm,
                amountRs = amount,
                pricePerL = price,
                fullTank = if (fullTank >= 0) fullTank == 1 else midLevelObserved >= 10,
                gaugeAfter = gaugePoint.takeIf { it >= 0.0 } ?: midLevelObserved.takeIf { it in 0..10 }?.toDouble()
            )
        }
        "MID_OBSERVED" -> (gaugePoint.takeIf { it >= 0.0 } ?: midLevelObserved.takeIf { it in 0..10 }?.toDouble())
            ?.let { FuelReplay.Input.Gauge(timestamp, odometerKm, it) }
        "UNLOGGED_CONSUMPTION" -> FuelReplay.parseUnloggedMarker(notes)?.let { (tripId, _) ->
            val litres = kotlin.math.abs(liters)
            val km = if (calculatedMileageKmpl > 0.0) litres * calculatedMileageKmpl else 0.0
            if (km > 0.0) FuelReplay.Input.TripEnd(timestamp, odometerKm, tripId, km) else null
        }
        else -> null
    }

    /**
     * Replays every fuel_events input into a fresh model (caller holds [stateMutex]). The seed
     * carries only configuration and the hybrid lifetime prior; everything learned (anchor,
     * capacity, segments, gauge curve, unlogged debt) is recomputed from the corrected history.
     */
    private suspend fun rebuildModelFromHistoryLocked() {
        val inputs = fuelDao.getAllFuelEvents().mapNotNull { it.toReplayInput() }
        val seedLitres = prefs.getFloat("model_seed_litres", (designCapacity * 0.5).toFloat()).toDouble()
        val (hKmpl, hKm) = hybridLifetime
        val seed = FuelModel.withConfig(
            FuelModelState(
                designCapacityL = designCapacity,
                actualCapacityL = designCapacity,
                defaultKmpl = baselineVehicleMileage,
                anchorLitres = seedLitres.coerceIn(0.0, designCapacity),
                anchorOdoKm = getEffectiveOdometer(),
                anchorTimeMs = System.currentTimeMillis(),
                hybridKmpl = hKmpl,
                hybridKm = hKm,
                tankUnknown = inputs.none { it is FuelReplay.Input.Refill || it is FuelReplay.Input.Gauge }
            ),
            designCapacity, baselineVehicleMileage
        )
        model = FuelReplay.replay(seed, inputs).let { if (inputs.isEmpty()) seed else it }
        persistModelNow()
        for (e in model.unlogged) markUnloggedResolved(e)
        lastDisplayKey = null
        _fuelState.update { buildFuelState() }
    }

    /** Public entry (backup restore): rebuild the pure model from the restored history. */
    fun rebuildModelFromHistory() {
        scope.launch { stateMutex.withLock { rebuildModelFromHistoryLocked() } }
    }

    /** Ledger rows mirroring [event]: linked by sourceEventId, else (legacy) same type, ±5 s, same litres. */
    private suspend fun ledgerRowsFor(event: FuelEventEntity): List<FuelLedgerEntity> {
        val linked = fuelLedgerDao.getBySourceEventId(event.id)
        if (linked.isNotEmpty()) return linked
        val type = when (event.eventType) {
            "REFUEL" -> FuelEventType.REFUEL.name
            "MID_OBSERVED" -> FuelEventType.FUEL_GAUGE_UPDATE.name
            else -> return emptyList()
        }
        return fuelLedgerDao.getEventsBetween(event.timestamp - 5_000L, event.timestamp + 5_000L)
            .filter { it.eventType == type && it.sourceEventId == null && kotlin.math.abs(it.fuelQuantityLiters - event.liters) < 0.01 }
            .take(1)
    }

    private suspend fun isLatestRefuel(event: FuelEventEntity): Boolean =
        fuelDao.getAllFuelEvents().filter { it.eventType == "REFUEL" }.maxByOrNull { it.timestamp }?.id == event.id

    fun deleteFuelEvent(eventId: Long) {
        scope.launch {
            stateMutex.withLock {
                try {
                    val target = fuelDao.getFuelEventById(eventId)
                    if (target != null && target.eventType == "REFUEL" && target.liters > 0.0) {
                        // Record immutable reversal event
                        val reversalEvent = FuelLedgerEvent(
                            correlationId = "delete_${System.currentTimeMillis()}",
                            parentId = eventId,
                            eventType = FuelEventType.MANUAL_FUEL_ADJUSTMENT,
                            odometerKm = target.odometerKm,
                            fuelQuantityLiters = -target.liters,
                            reasonNote = "Reversal of deleted refuel #${target.id}"
                        )
                        fuelLedgerDao.insertEvent(FuelLedgerEntity.fromDomainEvent(reversalEvent))
                        estimator.applyConsumption(target.liters, getEffectiveOdometer())
                    }
                    // Ledger rows are removed by their link to this fuel_events row, never by
                    // reusing the fuel_events id against the ledger table (different id spaces).
                    if (target != null) {
                        ledgerRowsFor(target).forEach { fuelLedgerDao.deleteEventById(it.id) }
                        if (target.eventType == "REFUEL") {
                            com.antigravity.copilot.core.fuel.station.FuelStationRepository.getInstance(appContext)
                                .deleteLogForFuelEvent(target.id, target.liters, target.odometerKm)
                        }
                    }
                    fuelDao.deleteFuelEvent(eventId)
                    rebuildModelFromHistoryLocked()

                    persistValuesImmediate()
                    _fuelState.update { buildFuelState() }
                } catch (e: Exception) {
                    Log.e(TAG, "Error in deleteFuelEvent Room DB writes: ${e.message}", e)
                }
            }
        }
    }

    fun updateFuelEvent(updatedEvent: FuelEventEntity) {
        scope.launch {
            stateMutex.withLock {
                try {
                    val old = fuelDao.getFuelEventById(updatedEvent.id)
                    // Only the most recent refill defines the current pump price (editing an old
                    // entry must not overwrite today's price).
                    val wasLatestRefuel = old != null && old.eventType == "REFUEL" && isLatestRefuel(old)
                    if (old != null && old.eventType == "REFUEL" && updatedEvent.eventType == "REFUEL") {
                        val deltaLiters = updatedEvent.liters - old.liters
                        if (deltaLiters > 0.0) {
                            estimator.applyRefuel(deltaLiters, updatedEvent.midLevelObserved, updatedEvent.odometerKm)
                        } else if (deltaLiters < 0.0) {
                            estimator.applyConsumption(-deltaLiters, updatedEvent.odometerKm)
                        }

                        // Record immutable correction
                        val correctionEvent = FuelLedgerEvent(
                            correlationId = "correction_${System.currentTimeMillis()}",
                            parentId = updatedEvent.id,
                            eventType = FuelEventType.FUEL_CORRECTION,
                            odometerKm = updatedEvent.odometerKm,
                            fuelQuantityLiters = updatedEvent.liters,
                            fuelGaugePoints = updatedEvent.midLevelObserved,
                            pricePerLiter = updatedEvent.pricePerLiter,
                            totalAmount = updatedEvent.totalCost,
                            reasonNote = "Corrected refuel #${updatedEvent.id} from ${old.liters} L to ${updatedEvent.liters} L"
                        )
                        fuelLedgerDao.insertEvent(FuelLedgerEntity.fromDomainEvent(correctionEvent))
                        // Mirrored ledger row + station log follow the edit.
                        ledgerRowsFor(old).forEach { row ->
                            fuelLedgerDao.updateEvent(
                                row.copy(
                                    odometerKm = updatedEvent.odometerKm,
                                    fuelQuantityLiters = updatedEvent.liters,
                                    pricePerLiter = updatedEvent.pricePerLiter,
                                    totalAmount = updatedEvent.totalCost,
                                    fuelGaugePoints = updatedEvent.midLevelObserved,
                                    sourceEventId = old.id
                                )
                            )
                        }
                        com.antigravity.copilot.core.fuel.station.FuelStationRepository.getInstance(appContext)
                            .updateLogForFuelEvent(old.id, old.liters, old.odometerKm, updatedEvent.liters, updatedEvent.pricePerLiter, updatedEvent.odometerKm)
                    }

                    fuelDao.updateFuelEvent(updatedEvent)
                    if (wasLatestRefuel && updatedEvent.pricePerLiter > 0.0 && updatedEvent.pricePerLiter.isFinite()) {
                        fuelPrice = updatedEvent.pricePerLiter
                    }
                    rebuildModelFromHistoryLocked()

                    if (updatedEvent.pricePerLiter > 0.0) {
                        StationPriceManager.rememberStationPrice(
                            context = appContext,
                            stationName = updatedEvent.locationName,
                            lat = updatedEvent.latitude,
                            lon = updatedEvent.longitude,
                            pricePerLiter = updatedEvent.pricePerLiter
                        )
                    }

                    persistValuesImmediate()
                    _fuelState.update { buildFuelState() }
                } catch (e: Exception) {
                    Log.e(TAG, "Error in updateFuelEvent Room DB writes: ${e.message}", e)
                }
            }
        }
    }

    fun resetAllData() {
        cumulativeTripConsumedLiters = 0.0
        persistJob?.cancel()
        persistJob = null
        tripFuelCalibration = 1.0
        predictedSinceFullL = 0.0
        predictedSinceFullKm = 0.0
        lastDisplayKey = null
        designCapacity = 48.0
        maxCapacity = 48.0
        baselineVehicleMileage = 18.0
        liveInstantMileage = 18.0
        fuelPrice = 96.5
        pendingAnchorOdometerKm = -1.0
        pendingAnchorFuelLiters = -1.0
        pendingAccumulatedAddedLiters = 0.0
        latestAppLifetimeKm = 0.0

        estimator.initializeBaseline(48.0, 10, 0.0)
        model = FuelModelState(anchorLitres = 24.0, anchorTimeMs = System.currentTimeMillis(), tankUnknown = true)
        prefs.edit().clear().apply()
        aiEngine.resetLearnedModel()
        mileageEngine.resetLearnedModel(18.0)
        StationPriceManager.clearAll(appContext)

        persistValuesImmediate()
        _fuelState.update { buildFuelState() }

        scope.launch {
            try {
                fuelDao.clearAllFuelEvents()
                fuelLedgerDao.clearAll()
                fuelModelDao.clear()
                PersistentDataManager.clearBackup(appContext)
            } catch (e: Exception) {
                Log.e(TAG, "Error in resetAllData Room DB clear: ${e.message}", e)
            }
        }
    }

    suspend fun getFuelEventsBetween(startTime: Long, endTime: Long): List<FuelLedgerEntity> {
        return fuelLedgerDao.getEventsBetween(startTime, endTime)
    }

    companion object {
        private const val TAG = "FuelRepository"
        /** fuel_events.notes of the gauge reading written by the dashboard reserve-light tap. */
        const val RESERVE_LIGHT_NOTE = "Reserve light ON"
        const val PERSIST_DEBOUNCE_MS: Long = 30000L

        @Volatile
        private var INSTANCE: FuelRepository? = null

        fun getInstance(context: Context): FuelRepository {
            // Double-checked: without the inner check two racing callers each built their own
            // repository (two fuel states, two tank estimates).
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: FuelRepository(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
}
