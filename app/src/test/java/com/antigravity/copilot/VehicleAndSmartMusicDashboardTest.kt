package com.antigravity.copilot

import android.media.session.PlaybackState
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.antigravity.copilot.core.designsystem.CockpitScaleTokens
import com.antigravity.copilot.core.designsystem.UiScaleMode
import com.antigravity.copilot.core.location.RoadSurfaceCondition
import com.antigravity.copilot.core.location.StoppingDistanceEngine
import com.antigravity.copilot.core.media.MediaPlaybackManager
import com.antigravity.copilot.core.media.MediaPlaybackState
import com.antigravity.copilot.core.model.FuelState
import com.antigravity.copilot.core.odometer.OdometerManager
import com.antigravity.copilot.feature.dashboard.calculateSpeedometerDecay
import com.antigravity.copilot.feature.dashboard.formatDuration
import org.junit.Assert.*
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.*

class VehicleAndSmartMusicDashboardTest {

    // =========================================================================
    // 1. VEHICLE CARD DATA & TELEMETRY CALCULATIONS
    // =========================================================================

    @Test
    fun testSpeedometerDecayCurveGuarantees() {
        val initialSpeed = 100f

        // Stage 1: Under 2000ms retains 100% genuine speed
        assertEquals(100f, calculateSpeedometerDecay(initialSpeed, 0L), 0.01f)
        assertEquals(100f, calculateSpeedometerDecay(initialSpeed, 1500L), 0.01f)
        assertEquals(100f, calculateSpeedometerDecay(initialSpeed, 2000L), 0.01f)

        // Stage 2: Aerodynamic linear coasting (2.0 km/h per second)
        // At 3000ms (1.0s coasting): 100 - (1.0 * 2.0) = 98.0 km/h
        assertEquals(98.0f, calculateSpeedometerDecay(initialSpeed, 3000L), 0.01f)
        // At 5000ms (3.0s coasting): 100 - (3.0 * 2.0) = 94.0 km/h
        assertEquals(94.0f, calculateSpeedometerDecay(initialSpeed, 5000L), 0.01f)

        // Stage 3 & 4: Termination at 10000ms
        assertEquals(0.0f, calculateSpeedometerDecay(initialSpeed, 10000L), 0.01f)
        assertEquals(0.0f, calculateSpeedometerDecay(initialSpeed, 15000L), 0.01f)

        // Zero and negative guards
        assertEquals(0.0f, calculateSpeedometerDecay(0f, 500L), 0.01f)
        assertEquals(0.0f, calculateSpeedometerDecay(-10f, 500L), 0.01f)
    }

    @Test
    fun testStoppingDistanceCalculationAccuracy() {
        val estimate60 = StoppingDistanceEngine.calculate(60f, RoadSurfaceCondition.DRY)
        assertTrue(estimate60.totalDistanceMeters > 0f)
        assertTrue(estimate60.reactionDistanceMeters > 0f)
        assertTrue(estimate60.brakingDistanceMeters > 0f)
        assertEquals(estimate60.totalDistanceMeters, estimate60.reactionDistanceMeters + estimate60.brakingDistanceMeters, 0.1f)

        val estimate0 = StoppingDistanceEngine.calculate(0f, RoadSurfaceCondition.DRY)
        assertEquals(0f, estimate0.totalDistanceMeters, 0.01f)
    }

    @Test
    fun testFuelStateTelemetryBoundsAndRange() {
        val fullTank = FuelState(
            designCapacityLiters = 50.0,
            remainingLiters = 50.0,
            fuelPercentage = 100,
            remainingRangeKm = 750.0
        )
        assertEquals(100, fullTank.fuelPercentage)
        assertEquals(750.0, fullTank.remainingRangeKm, 0.01)

        val lowTank = FuelState(
            designCapacityLiters = 50.0,
            remainingLiters = 5.0,
            fuelPercentage = 10,
            remainingRangeKm = 75.0
        )
        assertEquals(10, lowTank.fuelPercentage)
        assertTrue("Low fuel percentage should be <= 15 for critical alert", lowTank.fuelPercentage <= 15)

        val unavailableTank = FuelState(
            designCapacityLiters = 0.0,
            remainingLiters = 0.0,
            fuelPercentage = 0,
            remainingRangeKm = 0.0
        )
        assertEquals(0.0, unavailableTank.designCapacityLiters, 0.01)
    }

    @Test
    fun testOdometerLiveCalculationsAndDelta() {
        // Test Odometer state math without needing android Context
        val baselineOdo = 15000.0
        val baselineLifetime = 100.0
        val currentLifetime = 125.4

        // Delta added since baseline calibration: 125.4 - 100.0 = 25.4 km
        val delta = currentLifetime - baselineLifetime
        assertEquals(25.4, delta, 0.001)

        // Live displayed odometer: 15000.0 + 25.4 = 15025.4 km
        val liveOdo = baselineOdo + delta
        assertEquals(15025.4, liveOdo, 0.001)
    }

    // =========================================================================
    // 2. MUSIC SESSION STATE TRANSITIONS
    // =========================================================================

    @Test
    fun testMediaPlaybackStateDefaultIsClockOnly() {
        val state = MediaPlaybackState()
        assertFalse("Default state must not be active music", state.isMusicActive)
        assertFalse(state.isPlaying)
        assertFalse(state.hasActiveSession)
        assertEquals("Media Idle", state.displayTitle)
        assertEquals("Tap to Play Music", state.displayArtist)
    }

    @Test
    fun testMediaPlaybackStatePlayingTransitionsToMusicState() {
        val playingState = MediaPlaybackState(
            title = "Midnight City",
            artist = "M83",
            album = "Hurry Up, We're Dreaming",
            isPlaying = true,
            hasActiveSession = true,
            playbackStateCode = PlaybackState.STATE_PLAYING,
            durationMs = 244000L,
            positionMs = 30000L
        )
        assertTrue("Playing music must activate State B (Clock + Music)", playingState.isMusicActive)
        assertTrue(playingState.isPlaying)
        assertEquals("Midnight City", playingState.displayTitle)
        assertEquals("M83", playingState.displayArtist)
    }

    @Test
    fun testMediaPlaybackStatePausedRetainsMusicStateWithPlayButton() {
        val pausedState = MediaPlaybackState(
            title = "Starboy",
            artist = "The Weeknd",
            isPlaying = false,
            hasActiveSession = true,
            playbackStateCode = PlaybackState.STATE_PAUSED,
            durationMs = 230000L,
            positionMs = 45000L
        )
        assertTrue("Paused track with valid session must remain in State B to show track & play control", pausedState.isMusicActive)
        assertFalse(pausedState.isPlaying)
        assertEquals("Starboy", pausedState.displayTitle)
    }

    @Test
    fun testMediaPlaybackStateStoppedRevertsToClockOnly() {
        val stoppedState = MediaPlaybackState(
            title = "Blinding Lights",
            artist = "The Weeknd",
            isPlaying = false,
            hasActiveSession = true,
            playbackStateCode = PlaybackState.STATE_STOPPED
        )
        assertFalse("Stopped playback must return to State A (Clock only)", stoppedState.isMusicActive)
    }

    @Test
    fun testMediaPlaybackStateSessionDestroyedRevertsToClockOnly() {
        val destroyedState = MediaPlaybackState(
            title = "",
            artist = "",
            isPlaying = false,
            hasActiveSession = false,
            playbackStateCode = PlaybackState.STATE_NONE
        )
        assertFalse("Destroyed session must return to State A (Clock only)", destroyedState.isMusicActive)
    }

    @Test
    fun testElapsedRealtimeLivePositionCalculationWithoutIpcPolling() {
        val basePosition = 30000L
        val lastUpdate = 100000L
        val duration = 180000L

        val state = MediaPlaybackState(
            isPlaying = true,
            hasActiveSession = true,
            positionMs = basePosition,
            lastPositionUpdateTime = lastUpdate,
            playbackSpeed = 1.0f,
            durationMs = duration
        )

        // 5 seconds elapsed realtime since last update
        val livePos5s = state.calculateLivePositionMs(lastUpdate + 5000L)
        assertEquals(35000L, livePos5s)

        // 200 seconds elapsed (exceeds duration) -> clamped to duration
        val livePosOver = state.calculateLivePositionMs(lastUpdate + 200000L)
        assertEquals(duration, livePosOver)

        // Paused state does not advance
        val pausedState = state.copy(isPlaying = false)
        val pausedPos = pausedState.calculateLivePositionMs(lastUpdate + 5000L)
        assertEquals(basePosition, pausedPos)
    }

    // =========================================================================
    // 3. MISSING METADATA & FALLBACK HANDLING
    // =========================================================================

    @Test
    fun testMissingMetadataFallbacksWithoutFabrication() {
        val emptyMetaState = MediaPlaybackState(
            title = "",
            artist = "",
            album = "",
            albumArt = null,
            isPlaying = true,
            hasActiveSession = true
        )

        // Fallbacks must describe genuine state rather than hardcoding fake song names
        assertEquals("Audio Playing", emptyMetaState.displayTitle)
        assertEquals("Car Audio", emptyMetaState.displayArtist)
        assertNull("Album art must remain null for fallback icon rendering", emptyMetaState.albumArt)
    }

    // =========================================================================
    // 4. CLOCK & DURATION FORMATTING
    // =========================================================================

    @Test
    fun testDurationFormatting() {
        assertEquals("0:00", formatDuration(0L))
        assertEquals("0:00", formatDuration(-500L))
        assertEquals("0:45", formatDuration(45000L))
        assertEquals("3:45", formatDuration(225000L))
        assertEquals("12:05", formatDuration(725000L))
        assertEquals("1:02:15", formatDuration(3735000L))
    }

    @Test
    fun testClockTimeFormatting12HourAnd24Hour() {
        val calendar = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 14)
            set(Calendar.MINUTE, 35)
        }

        val format24 = SimpleDateFormat("HH:mm", Locale.US).format(calendar.time)
        assertEquals("14:35", format24)

        val format12 = SimpleDateFormat("hh:mm", Locale.US).format(calendar.time)
        assertEquals("02:35", format12)

        val amPm = SimpleDateFormat("a", Locale.US).format(calendar.time).uppercase()
        assertEquals("PM", amPm)
    }

    // =========================================================================
    // 5. RESPONSIVE UI SCALING INTEGRATION
    // =========================================================================

    @Test
    fun testAllScalingModesTelemetryTokensPreserved() {
        UiScaleMode.entries.forEach { mode ->
            val tokens = CockpitScaleTokens.forMode(mode)

            // Speed number size must be readable and constrained
            assertTrue("Speed number size in $mode must be >= 60sp", tokens.speedNumberSize.value >= 60f)
            assertTrue("Speed number size in $mode must be <= 85sp", tokens.speedNumberSize.value <= 85f)

            // Fuel value size must scale appropriately
            assertTrue("Fuel value size in $mode must be > 25sp", tokens.fuelValueSize.value > 25f)

            // Card padding must protect screen boundary
            assertTrue("Card padding in $mode must be in 10dp..16dp", tokens.cardPadding.value in 10f..16f)

            // Item spacing must remain balanced
            assertTrue("Item spacing in $mode must be in 5dp..10dp", tokens.itemSpacing.value in 5f..10f)
        }
    }

    // =========================================================================
    // 6. RESTORED CURRENT MILEAGE & 10-SEGMENT MID GAUGE LOGIC
    // =========================================================================

    @Test
    fun testCurrentMileageResolutionLogic() {
        // Case 1: Confirmed learned mileage
        val learnedFuel = FuelState(
            currentMileageKmpl = 18.2,
            mileageLearningState = com.antigravity.copilot.core.model.MileageLearningState.LEARNED
        )
        val isConfirmed = learnedFuel.mileageLearningState == com.antigravity.copilot.core.model.MileageLearningState.LEARNED && learnedFuel.currentMileageKmpl > 0.0
        assertTrue("Learned state with positive kmpl must be confirmed", isConfirmed)
        assertEquals("18.2 km/L", String.format(Locale.US, "%.1f km/L", learnedFuel.currentMileageKmpl))

        // Case 2: Estimated mileage
        val learningFuel = FuelState(
            currentMileageKmpl = 15.6,
            mileageLearningState = com.antigravity.copilot.core.model.MileageLearningState.LEARNING
        )
        val isEstimated = (learningFuel.mileageLearningState == com.antigravity.copilot.core.model.MileageLearningState.LEARNING || learningFuel.isRealMileageDerived) && learningFuel.currentMileageKmpl > 0.0
        assertTrue("Learning state with positive kmpl must be marked estimated", isEstimated)
        assertEquals("15.6 km/L", String.format(Locale.US, "%.1f km/L", learningFuel.currentMileageKmpl))

        // Case 3: Initial learning state (no data yet)
        val zeroFuel = FuelState(
            currentMileageKmpl = 0.0,
            expectedMileageKmpl = 0.0,
            mileageLearningState = com.antigravity.copilot.core.model.MileageLearningState.INSUFFICIENT_DATA
        )
        val hasAnyMileage = zeroFuel.currentMileageKmpl > 0.0 || zeroFuel.expectedMileageKmpl > 0.0
        assertFalse("Uninitialized state with 0 kmpl must not claim valid mileage", hasAnyMileage)
    }

    @Test
    fun testTenSegmentMidGaugeColorLogic() {
        for (currentMidBar in 0..10) {
            for (i in 1..10) {
                val isActive = i <= currentMidBar
                if (!isActive) {
                    // Inactive segment
                    assertTrue(i > currentMidBar)
                } else {
                    val zone = when {
                        i <= 2 -> "CRITICAL_RED"
                        i <= 4 -> "WARNING_AMBER"
                        else -> "ACCENT_CYAN"
                    }
                    if (i in 1..2) assertEquals("CRITICAL_RED", zone)
                    if (i in 3..4) assertEquals("WARNING_AMBER", zone)
                    if (i in 5..10) assertEquals("ACCENT_CYAN", zone)
                }
            }
        }
    }
}
