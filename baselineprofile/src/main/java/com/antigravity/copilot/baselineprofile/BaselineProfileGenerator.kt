package com.antigravity.copilot.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Generates the app's baseline profile by walking every critical journey in [Journey]:
 * home map pan/zoom, Trips, Fuel, full-screen Maps, Apps, Log Fuel, odometer and gauge
 * calibration, and every Settings tab with its sub-dialogs.
 *
 * Run (rooted device on API 28-32, or any device on API 33+):
 *   ./gradlew :app:generateReleaseBaselineProfile
 */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {

    @get:Rule
    val rule = BaselineProfileRule()

    @Test
    fun generate() = rule.collect(
        packageName = PACKAGE,
        maxIterations = 4,
        stableIterations = 2,
    ) {
        grantPermissions()
        pressHome()
        startActivityAndWait()
        device.waitForIdle()
        Journey.values().forEach { device.runJourney(it) }
        device.goDrive()
    }
}
