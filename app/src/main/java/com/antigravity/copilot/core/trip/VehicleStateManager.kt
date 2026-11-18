package com.antigravity.copilot.core.trip

import android.content.Context
import android.content.SharedPreferences
import com.antigravity.copilot.core.database.AppDatabase
import com.antigravity.copilot.core.database.VehicleStateTransitionEntity
import com.antigravity.copilot.core.model.StateConfidence
import com.antigravity.copilot.core.model.VehicleState
import com.antigravity.copilot.core.model.VehicleStateMetrics
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.math.max

/**
 * Robust automotive vehicle operational state manager.
 *
 * Distinguishes three core vehicle states:
 * 1. MOVING: Vehicle is actively in motion.
 * 2. IDLE: Engine is running but vehicle is stationary.
 * 3. ENGINE OFF: Vehicle engine is switched off and power is severed.
 * 4. UNKNOWN: Unobserved tracking gap where engine state cannot be reliably confirmed.
 *
 * Strictly follows principles:
 * - Engine state is NEVER fabricated from GPS alone.
 * - Engine Off is only confirmed on direct hardware/system evidence (12V ACC / USB power cut, OS shutdown).
 * - Startup recovery identifies session gaps and separates confirmed engine-off time from unknown intervals.
 */
class VehicleStateManager private constructor(context: Context) {

    private val appContext = context.applicationContext
    private val database = AppDatabase.getInstance(appContext)
    private val vehicleStateDao = database.vehicleStateDao()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val prefs: SharedPreferences = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private var lastStateChangeTimestamp: Long = System.currentTimeMillis()
    /** Monotonic (elapsedRealtime) reference for duration buckets: the RTC may jump or be wrong. */
    private var lastTickMono: Long = mono()
    private var lastPersistMono: Long = Long.MIN_VALUE
    /** Hysteresis + 10 s idle debounce so stop-and-go traffic does not flood the transition log. */
    private val motionDebouncer = com.antigravity.copilot.core.logic.VehicleMotionDebouncer()

    private fun mono(): Long = android.os.SystemClock.elapsedRealtime()

    private val _metrics = MutableStateFlow(
        VehicleStateMetrics(
            currentState = VehicleState.IDLE,
            stateSinceTimestamp = System.currentTimeMillis()
        )
    )
    val metrics: StateFlow<VehicleStateMetrics> = _metrics.asStateFlow()

    init {
        recoverPreviousSession()
    }

    /**
     * Recover previous session across app restarts or system power cycles.
     * Accurately determines whether the unobserved gap was confirmed engine-off or an unknown gap.
     */
    private fun recoverPreviousSession() {
        val now = System.currentTimeMillis()
        val savedStateName = prefs.getString(KEY_CURRENT_STATE, null)
        val savedTimestamp = prefs.getLong(KEY_LAST_STATE_TIMESTAMP, 0L)
        val isConfirmedOff = prefs.getBoolean(KEY_IS_CONFIRMED_ENGINE_OFF, false)
        val lastOffTimestamp = prefs.getLong(KEY_LAST_ENGINE_OFF_TIMESTAMP, 0L)
        val lastOffReason = prefs.getString(KEY_LAST_ENGINE_OFF_REASON, null)

        var accMoving = prefs.getLong(KEY_MOVING_DURATION_MS, 0L)
        var accIdle = prefs.getLong(KEY_IDLE_DURATION_MS, 0L)
        var accEngineOff = prefs.getLong(KEY_ENGINE_OFF_DURATION_MS, 0L)
        var accUnknown = prefs.getLong(KEY_UNKNOWN_DURATION_MS, 0L)
        var lastOffDuration = prefs.getLong(KEY_LAST_CONFIRMED_OFF_DURATION, 0L)

        val previousState = try {
            if (savedStateName != null) VehicleState.valueOf(savedStateName) else VehicleState.IDLE
        } catch (_: Exception) {
            VehicleState.IDLE
        }

        if (savedTimestamp > 0L && now > savedTimestamp) {
            val gapMs = now - savedTimestamp

            if (isConfirmedOff || previousState == VehicleState.ENGINE_OFF) {
                // Confirmed Engine Off evidence (ACC power cut or OS shutdown recorded)
                accEngineOff += gapMs
                lastOffDuration = gapMs
                logTransitionAsync(
                    from = VehicleState.ENGINE_OFF,
                    to = VehicleState.IDLE,
                    confidence = StateConfidence.CONFIRMED,
                    reason = "STARTUP_POWER_RESTORED",
                    metadata = "Engine off gap: ${gapMs / 1000}s"
                )
            } else {
                // Unknown/unobserved gap (app terminated without shutdown broadcast or power event)
                accUnknown += gapMs
                logTransitionAsync(
                    from = previousState,
                    to = VehicleState.IDLE,
                    confidence = StateConfidence.INFERRED,
                    reason = "STARTUP_RECOVERY_UNKNOWN_GAP",
                    metadata = "Unobserved gap: ${gapMs / 1000}s"
                )
            }
        }

        lastStateChangeTimestamp = now
        lastTickMono = mono()
        motionDebouncer.reset(false)
        _metrics.value = VehicleStateMetrics(
            currentState = VehicleState.IDLE,
            stateSinceTimestamp = now,
            movingDurationMs = accMoving,
            idleDurationMs = accIdle,
            engineOffDurationMs = accEngineOff,
            unknownDurationMs = accUnknown,
            lastEngineOffTimestamp = if (lastOffTimestamp > 0L) lastOffTimestamp else null,
            lastEngineOffReason = lastOffReason,
            lastConfirmedEngineOffDurationMs = lastOffDuration
        )

        persistState(isSynchronous = true)
        pruneOldTransitionsAsync()
    }

    /** Transition log retention: rows older than [TRANSITION_RETENTION_MS] are deleted at startup. */
    private fun pruneOldTransitionsAsync() {
        scope.launch {
            try {
                vehicleStateDao.deleteTransitionsBefore(System.currentTimeMillis() - TRANSITION_RETENTION_MS)
            } catch (e: Exception) {
                android.util.Log.w("VehicleStateManager", "Transition prune failed: ${e.message}")
            }
        }
    }

    /**
     * Transition vehicle to a new operational state.
     * [timestamp] is wall-clock and only stamps the state; durations use the monotonic clock.
     */
    @Synchronized
    fun transitionTo(
        newState: VehicleState,
        confidence: StateConfidence,
        triggerReason: String,
        tripId: Long? = null,
        timestamp: Long = System.currentTimeMillis()
    ) {
        val currentState = _metrics.value.currentState

        // Accumulate elapsed time into current state bucket
        val nowMono = mono()
        val deltaMs = max(0L, nowMono - lastTickMono)
        accumulateTime(currentState, deltaMs)
        lastTickMono = nowMono

        if (newState == currentState) {
            persistState(isSynchronous = false)
            return
        }

        val previousState = currentState
        lastStateChangeTimestamp = timestamp

        val isConfirmedEngineOff = (newState == VehicleState.ENGINE_OFF && confidence == StateConfidence.CONFIRMED)

        _metrics.update { current ->
            current.copy(
                currentState = newState,
                stateSinceTimestamp = timestamp,
                lastEngineOffTimestamp = if (isConfirmedEngineOff) timestamp else current.lastEngineOffTimestamp,
                lastEngineOffReason = if (isConfirmedEngineOff) triggerReason else current.lastEngineOffReason
            )
        }

        logTransitionAsync(
            from = previousState,
            to = newState,
            confidence = confidence,
            reason = triggerReason,
            tripId = tripId
        )

        persistState(isSynchronous = isConfirmedEngineOff)
    }

    /**
     * Called when GPS location or Doppler speed updates.
     * Decoupled state classification: strictly MOVING vs IDLE while engine/service is running.
     * Never fabricates ENGINE_OFF from GPS alone.
     */
    fun onSpeedOrLocationUpdated(speedKmh: Float, timestamp: Long = System.currentTimeMillis(), tripId: Long? = null) {
        val currentState = _metrics.value.currentState
        val speedThreshold = com.antigravity.copilot.core.settings.AppSettingsManager.settingsState.value.stationarySpeedThresholdKmh
        val moving = motionDebouncer.onSample(mono(), speedKmh.toDouble(), speedThreshold.toDouble())
        if (currentState == VehicleState.ENGINE_OFF) {
            // Only transition away from ENGINE_OFF if actual motion above threshold is detected!
            if (moving) {
                transitionTo(
                    newState = VehicleState.MOVING,
                    confidence = StateConfidence.INFERRED,
                    triggerReason = "MOTION_AFTER_ENGINE_OFF",
                    tripId = tripId,
                    timestamp = timestamp
                )
            }
            // If speed is below threshold (e.g. 0 km/h jitter), vehicle remains safely in ENGINE_OFF
            return
        }

        if (moving) {
            transitionTo(
                newState = VehicleState.MOVING,
                confidence = StateConfidence.INFERRED,
                triggerReason = "SPEED_ABOVE_THRESHOLD",
                tripId = tripId,
                timestamp = timestamp
            )
        } else {
            transitionTo(
                newState = VehicleState.IDLE,
                confidence = StateConfidence.INFERRED,
                triggerReason = "VEHICLE_STOPPED",
                tripId = tripId,
                timestamp = timestamp
            )
        }
    }

    /**
     * Called when hardware 12V ACC / USB power is cut by vehicle ignition switch.
     * Reliable hardware evidence for ENGINE_OFF.
     */
    fun onHardwarePowerDisconnected(timestamp: Long = System.currentTimeMillis(), tripId: Long? = null) {
        motionDebouncer.reset(false)
        transitionTo(
            newState = VehicleState.ENGINE_OFF,
            confidence = StateConfidence.CONFIRMED,
            triggerReason = "POWER_DISCONNECTED",
            tripId = tripId,
            timestamp = timestamp
        )
    }

    /**
     * Called when hardware 12V ACC / USB power is re-engaged.
     */
    fun onHardwarePowerConnected(timestamp: Long = System.currentTimeMillis(), tripId: Long? = null) {
        val currentState = _metrics.value.currentState
        if (currentState == VehicleState.ENGINE_OFF) {
            transitionTo(
                newState = VehicleState.IDLE,
                confidence = StateConfidence.CONFIRMED,
                triggerReason = "POWER_CONNECTED",
                tripId = tripId,
                timestamp = timestamp
            )
        }
    }

    /**
     * Called when infotainment head unit shuts down with the engine (ACTION_SHUTDOWN / QUICKBOOT).
     * Synchronously persists tracking state before system power cuts completely.
     */
    fun onSystemShutdown(reason: String = "SYSTEM_SHUTDOWN", timestamp: Long = System.currentTimeMillis(), tripId: Long? = null) {
        transitionTo(
            newState = VehicleState.ENGINE_OFF,
            confidence = StateConfidence.CONFIRMED,
            triggerReason = reason,
            tripId = tripId,
            timestamp = timestamp
        )
        persistState(isSynchronous = true)
    }

    /**
     * Periodic watchdog tick to accumulate duration and periodically flush to disk.
     */
    fun onPeriodicTick(@Suppress("UNUSED_PARAMETER") now: Long = System.currentTimeMillis()) {
        val nowMono = mono()
        val deltaMs = max(0L, nowMono - lastTickMono)
        if (deltaMs >= 1000L) {
            accumulateTime(_metrics.value.currentState, deltaMs)
            lastTickMono = nowMono
            persistState(isSynchronous = false)
        }
    }

    private fun accumulateTime(state: VehicleState, deltaMs: Long) {
        if (deltaMs <= 0L) return
        _metrics.update { m ->
            when (state) {
                VehicleState.MOVING -> m.copy(movingDurationMs = m.movingDurationMs + deltaMs)
                VehicleState.IDLE -> m.copy(idleDurationMs = m.idleDurationMs + deltaMs)
                VehicleState.ENGINE_OFF -> m.copy(engineOffDurationMs = m.engineOffDurationMs + deltaMs)
                VehicleState.UNKNOWN -> m.copy(unknownDurationMs = m.unknownDurationMs + deltaMs)
            }
        }
    }

    fun reloadFromDatabase() {
        recoverPreviousSession()
    }

    internal fun persistState(isSynchronous: Boolean) {
        val now = System.currentTimeMillis()
        val nowMono = mono()
        if (!isSynchronous && lastPersistMono != Long.MIN_VALUE && (nowMono - lastPersistMono < PERSIST_DEBOUNCE_MS)) {
            return
        }
        lastPersistMono = nowMono

        val m = _metrics.value
        val editor = prefs.edit()
            .putString(KEY_CURRENT_STATE, m.currentState.name)
            .putLong(KEY_LAST_STATE_TIMESTAMP, now)
            .putBoolean(KEY_IS_CONFIRMED_ENGINE_OFF, m.currentState == VehicleState.ENGINE_OFF)
            .putLong(KEY_MOVING_DURATION_MS, m.movingDurationMs)
            .putLong(KEY_IDLE_DURATION_MS, m.idleDurationMs)
            .putLong(KEY_ENGINE_OFF_DURATION_MS, m.engineOffDurationMs)
            .putLong(KEY_UNKNOWN_DURATION_MS, m.unknownDurationMs)
            .putLong(KEY_LAST_CONFIRMED_OFF_DURATION, m.lastConfirmedEngineOffDurationMs)

        m.lastEngineOffTimestamp?.let { editor.putLong(KEY_LAST_ENGINE_OFF_TIMESTAMP, it) }
        m.lastEngineOffReason?.let { editor.putString(KEY_LAST_ENGINE_OFF_REASON, it) }

        if (isSynchronous) {
            editor.commit()
        } else {
            editor.apply()
        }
    }

    private fun logTransitionAsync(
        from: VehicleState,
        to: VehicleState,
        confidence: StateConfidence,
        reason: String,
        tripId: Long? = null,
        metadata: String? = null
    ) {
        scope.launch {
            try {
                vehicleStateDao.insertTransition(
                    VehicleStateTransitionEntity(
                        fromState = from.name,
                        toState = to.name,
                        timestamp = System.currentTimeMillis(),
                        confidence = confidence.name,
                        triggerReason = reason,
                        tripId = tripId,
                        extraMetadata = metadata
                    )
                )
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    companion object {
        const val PREFS_NAME = "vehicle_state_prefs"
        private const val KEY_CURRENT_STATE = "current_state"
        private const val KEY_LAST_STATE_TIMESTAMP = "last_state_timestamp"
        private const val KEY_IS_CONFIRMED_ENGINE_OFF = "is_confirmed_engine_off"
        private const val KEY_LAST_ENGINE_OFF_TIMESTAMP = "last_engine_off_timestamp"
        private const val KEY_LAST_ENGINE_OFF_REASON = "last_engine_off_reason"
        private const val KEY_LAST_CONFIRMED_OFF_DURATION = "last_confirmed_off_duration"
        private const val KEY_MOVING_DURATION_MS = "moving_duration_ms"
        private const val KEY_IDLE_DURATION_MS = "idle_duration_ms"
        private const val KEY_ENGINE_OFF_DURATION_MS = "engine_off_duration_ms"
        private const val KEY_UNKNOWN_DURATION_MS = "unknown_duration_ms"

        const val MOVEMENT_SPEED_THRESHOLD_KMH = 3.0f
        const val PERSIST_DEBOUNCE_MS = 1000L
        const val TRANSITION_RETENTION_MS = 90L * 24 * 60 * 60 * 1000

        @Volatile
        private var INSTANCE: VehicleStateManager? = null

        fun getInstance(context: Context): VehicleStateManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: VehicleStateManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    fun resetAllData() {
        prefs.edit().clear().apply()
        motionDebouncer.reset(false)
        lastTickMono = mono()
        _metrics.value = VehicleStateMetrics(
            currentState = VehicleState.IDLE,
            stateSinceTimestamp = System.currentTimeMillis()
        )
    }
}
